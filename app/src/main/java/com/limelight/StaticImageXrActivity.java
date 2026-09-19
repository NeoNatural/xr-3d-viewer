package com.limelight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.StillImageDepthBatcher;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.smb.SmbClientManager;
import com.limelight.smb.SmbImageByteCache;
import com.limelight.smb.SmbBrowseHistory;
import com.limelight.smb.SmbStorage;
import com.limelight.media.MediaEntry;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/** A local image probe for the existing XR depth and stereo pipeline. */
public class StaticImageXrActivity extends Activity implements XrRenderer.InputListener {
    public static final String EXTRA_TEST_IMAGE_NAME = "testImageName";
    public static final String EXTRA_SMB_URI = "smbUri";
    public static final String EXTRA_SMB_DIRECTORY = "smbDirectory";
    public static final String EXTRA_OPEN_STARTED_NS = "openStartedNs";
    private static final String IMAGE_ASSET = "environments/spaichingen_hill.jpg";
    // Image-only render target. The former 1280x720 Surface discarded detail
    // from high-resolution artwork before it reached the per-eye XR swapchain.
    private static final int FRAME_WIDTH = 2048;
    private static final int FRAME_HEIGHT = 1152;
    private static final int INITIAL_PREFETCH = 8;
    private static final int HISTORY_CACHE = 2;

    private volatile boolean stopped;
    private XrRenderer renderer;
    private Thread imageThread;
    private final AtomicInteger requestedStep = new AtomicInteger();
    private final AtomicLong requestedStepNs = new AtomicLong();
    private final AtomicInteger preparedDepthCount = new AtomicInteger();
    private List<MediaEntry> imageQueue;

    private static final class PreparedImage {
        final Bitmap bitmap;
        volatile ByteBuffer rgb;
        volatile ByteBuffer modelRgb;
        final AtomicBoolean depthStarted = new AtomicBoolean();
        volatile ByteBuffer depth;
        volatile boolean depthReady;
        boolean retired;
        boolean recycleAfterDepth;

        PreparedImage(Bitmap bitmap) {
            this.bitmap = bitmap;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        imageThread = new Thread(this::runImage, "Static Image XR Source");
        imageThread.start();
    }

    private void runImage() {
        long startedNs = System.nanoTime();
        long clickedNs = getIntent().getLongExtra(EXTRA_OPEN_STARTED_NS, startedNs);
        LimeLog.info("Static XR open requested at " + startedNs);
        String smbUri = getIntent().getStringExtra(EXTRA_SMB_URI);
        String directoryUri = getIntent().getStringExtra(EXTRA_SMB_DIRECTORY);
        imageQueue = SmbClientManager.imageSnapshot(directoryUri);
        int currentIndex = -1;
        for (int i = 0; i < imageQueue.size(); i++) {
            if (imageQueue.get(i).uri.equals(smbUri)) {
                currentIndex = i;
                break;
            }
        }
        String requestedName = getIntent().getStringExtra(EXTRA_TEST_IMAGE_NAME);
        String sourceLabel = smbUri != null ? smbUri
                : requestedName == null ? IMAGE_ASSET : requestedName;
        ExecutorService prefetch = Executors.newFixedThreadPool(2);
        StillImageDepthBatcher batcher = StillImageDepthBatcher.shared(this);
        ExecutorService depthWorker = batcher.worker();
        FutureTask<PreparedImage> decode = new FutureTask<>(
                () -> prepareImage(smbUri, requestedName));
        prefetch.execute(decode);
        Map<Integer, FutureTask<PreparedImage>> nearby = new HashMap<>();
        Set<FutureTask<PreparedImage>> depthScheduled =
                Collections.newSetFromMap(new WeakHashMap<>());
        if (currentIndex >= 0) nearby.put(currentIndex, decode);
        scheduleDepthOne(depthWorker, batcher, decode, depthScheduled);
        scheduleDepthBatch(prefetch, depthWorker, batcher, nearby, depthScheduled,
                currentIndex + 1, 1, INITIAL_PREFETCH);
        if (currentIndex > 0) {
            FutureTask<PreparedImage> previous = ensureDecoded(prefetch, nearby, currentIndex - 1);
            scheduleDepthOne(depthWorker, batcher, previous, depthScheduled);
        }
        PreparedImage current = null;
        FutureTask<PreparedImage> navigation = null;
        long navigationRequestedNs = 0;
        boolean depthPublished = false;
        int currentGeneration = -1;
        try {
            if (stopped) {
                return;
            }
            PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
            // Static artwork should remain visually stable around the screen.
            // Disable both the glow quad and image-colour room lighting for this
            // session; this also removes their per-frame colour sample pass.
            prefs.vrAmbilight = false;
            prefs.vrRoomLight = false;
            XrRenderer xr = new XrRenderer();
            renderer = xr;
            xr.setFastStillImageDepth(true);
            xr.setImageNavigationEnabled(currentIndex >= 0 && imageQueue.size() > 1);
            xr.setInputListener(this);
            if (!xr.start(this, FRAME_WIDTH, FRAME_HEIGHT, prefs)) {
                LimeLog.severe("Static XR renderer failed to start");
                runOnUiThread(this::finish);
                return;
            }
            currentGeneration = xr.beginStillImageTransition();

            try {
                current = decode.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LimeLog.severe("Static XR image open failed: " + e.getCause());
                runOnUiThread(this::finish);
                return;
            }
            if (current == null || current.bitmap == null) {
                LimeLog.severe("Static XR image decode failed");
                runOnUiThread(this::finish);
                return;
            }
            // The browser keeps compressed originals so opening the selected item can
            // start without a second SMB read. Once that item is decoded, retaining
            // the whole 48 MiB cache beside the XR swapchains and prepared bitmaps is
            // counterproductive. Upcoming items can use entries already acquired by
            // the decoder threads; later cache misses are read normally from SMB.
            SmbImageByteCache.clear();
            if (stopped) return;
            LimeLog.info("Static XR image decoded after "
                    + (System.nanoTime() - startedNs) / 1000000 + " ms");
            Surface surface = xr.getInputSurface();
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            Bitmap bitmap = current.bitmap;
            Rect source = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            nearby.remove(currentIndex);
            if (currentIndex >= 0) {
                SmbBrowseHistory.record(this, directoryUri, imageQueue.get(currentIndex).name);
            }
            LimeLog.info("Static XR image source running: " + sourceLabel);
            boolean firstFrame = true;
            boolean frameDirty = true;
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                if (navigation != null && navigation.isDone()) {
                    try {
                        PreparedImage next = navigation.get();
                        // Image navigation must never wait for depth inference. At a
                        // refill boundary the four GPU jobs may still be running;
                        // show the decoded RGB immediately and publish its depth when
                        // ready instead of making controller input appear frozen.
                        if (next != null) {
                            PreparedImage previous = current;
                            int previousIndex = currentIndex;
                            current = next;
                            bitmap = next.bitmap;
                            source = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                            currentIndex = navigationIndex;
                            depthPublished = false;
                            frameDirty = true;
                            currentGeneration = xr.beginStillImageTransition();
                            if (previousIndex >= 0 && Math.abs(previousIndex - currentIndex) <= 5) {
                                FutureTask<PreparedImage> previousTask =
                                        new FutureTask<>(() -> previous);
                                previousTask.run();
                                nearby.put(previousIndex, previousTask);
                            } else {
                                retirePrepared(previous);
                            }
                            SmbBrowseHistory.record(this, directoryUri,
                                    imageQueue.get(currentIndex).name);
                            long requestMs = navigationRequestedNs == 0 ? -1
                                    : (System.nanoTime() - navigationRequestedNs) / 1000000;
                            LimeLog.info("Static XR navigated to "
                                    + imageQueue.get(currentIndex).name
                                    + (requestMs >= 0 ? " after request " + requestMs + " ms" : ""));
                            if (!next.depthReady) {
                                LimeLog.info("Static XR navigation displayed before depth was ready");
                            }
                            int direction = Integer.compare(currentIndex, previousIndex);
                            if (direction == 0) direction = 1;
                            evictDistant(nearby, currentIndex, direction);
                            scheduleNavigationWindow(prefetch, depthWorker, batcher, nearby,
                                    depthScheduled, currentIndex, direction);
                            navigation = null;
                            // Presses received while this navigation was pending are
                            // stale; do not replay them in a burst after SMB/depth work.
                            requestedStep.set(0);
                            requestedStepNs.set(0);
                        }
                    } catch (Exception e) {
                        LimeLog.warning("Static XR next image failed: " + e);
                        navigation = null;
                    }
                }
                if (navigation == null && currentIndex >= 0) {
                    int step = requestedStep.getAndSet(0);
                    long requestNs = requestedStepNs.getAndSet(0);
                    int target = currentIndex + step;
                    if (step != 0 && target >= 0 && target < imageQueue.size()) {
                        navigation = nearby.remove(target);
                        boolean cachedTask = navigation != null;
                        boolean displayReady = cachedTask && navigation.isDone()
                                && !navigation.isCancelled();
                        navigationIndex = target;
                        if (navigation == null) {
                            navigation = ensureDecoded(prefetch, nearby, target);
                            nearby.remove(target);
                            scheduleDepthOne(depthWorker, batcher, navigation, depthScheduled);
                        }
                        navigationRequestedNs = requestNs;
                        LimeLog.info("Static XR navigation accepted direction " + step
                                + ", target " + target + ", cached " + cachedTask
                                + ", display ready " + displayReady + ", dispatch delay "
                                + (requestNs == 0 ? -1
                                : (System.nanoTime() - requestNs) / 1000000) + " ms");
                    } else if (step != 0) {
                        LimeLog.info("Static XR navigation ignored at index " + currentIndex
                                + ", direction " + step + ", queue " + imageQueue.size());
                    }
                }
                if (currentIndex >= 0) {
                    xr.setImageNavigationDepthReady(
                            depthReadyAt(nearby, currentIndex - 1),
                            depthReadyAt(nearby, currentIndex + 1));
                }
                if (frameDirty) {
                    Canvas canvas = null;
                    boolean framePosted = false;
                    long lockStartedNs = System.nanoTime();
                    long lockedNs = lockStartedNs;
                    try {
                        canvas = surface.lockCanvas(null);
                        lockedNs = System.nanoTime();
                        if (firstFrame) {
                            LimeLog.info("Static XR Canvas " + canvas.getWidth() + "x"
                                    + canvas.getHeight() + ", bitmap " + bitmap.getWidth()
                                    + "x" + bitmap.getHeight());
                        }
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(bitmap, source,
                                fitRect(bitmap.getWidth(), bitmap.getHeight(),
                                        canvas.getWidth(), canvas.getHeight()), paint);
                        xr.markStillImageFramePending(currentGeneration);
                    } catch (RuntimeException e) {
                        if (!stopped) {
                            LimeLog.severe("Static XR image draw failed: " + e.getMessage());
                            runOnUiThread(this::finish);
                        }
                        break;
                    } finally {
                        if (canvas != null) {
                            surface.unlockCanvasAndPost(canvas);
                            framePosted = true;
                        }
                    }
                    long postedNs = System.nanoTime();
                    long lockMs = (lockedNs - lockStartedNs) / 1000000;
                    long postMs = (postedNs - lockedNs) / 1000000;
                    if (lockMs >= 50 || postMs >= 50) {
                        LimeLog.warning("Static XR Surface submit slow: lock " + lockMs
                                + " ms, draw/post " + postMs + " ms, generation "
                                + currentGeneration);
                    }
                    frameDirty = !framePosted;
                    if (firstFrame) {
                        LimeLog.info("Static XR image first frame posted after "
                                + (System.nanoTime() - startedNs) / 1000000
                                + " ms, click-to-frame "
                                + (System.nanoTime() - clickedNs) / 1000000 + " ms");
                        firstFrame = false;
                    }
                }
                if (!depthPublished && current.depthReady
                        && xr.isStillImageFrameLatched(currentGeneration)) {
                    if (current.depth != null) {
                        xr.publishStillDepth(current.rgb, current.depth, currentGeneration);
                    }
                    else xr.allowStillDepthFallback();
                    depthPublished = true;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            IdentityHashMap<Bitmap, Boolean> recycled = new IdentityHashMap<>();
            recyclePrepared(current, recycled);
            recycleTask(decode, recycled);
            recycleTask(navigation, recycled);
            for (FutureTask<PreparedImage> task : nearby.values()) recycleTask(task, recycled);
            prefetch.shutdownNow();
            XrRenderer xr = renderer;
            if (xr != null) {
                xr.prepareForStop();
                xr.cleanup();
                renderer = null;
            }
        }
    }

    private int navigationIndex;

    private FutureTask<PreparedImage> ensureDecoded(ExecutorService executor,
            Map<Integer, FutureTask<PreparedImage>> nearby, int index) {
        FutureTask<PreparedImage> task = nearby.get(index);
        if (task != null) return task;
        String uri = imageQueue.get(index).uri;
        task = new FutureTask<>(() -> prepareImage(uri, null));
        nearby.put(index, task);
        executor.execute(task);
        return task;
    }

    private boolean scheduleDepthOne(ExecutorService worker, StillImageDepthBatcher batcher,
                                     FutureTask<PreparedImage> task,
                                     Set<FutureTask<PreparedImage>> depthScheduled) {
        if (!depthScheduled.add(task)) return false;
        worker.execute(() -> {
            PreparedImage image = null;
            boolean ownsDepth = false;
            try {
                image = task.get();
                synchronized (image) {
                    if (image.retired) return;
                    ownsDepth = image.depthStarted.compareAndSet(false, true);
                    if (!ownsDepth) return;
                }
                // RGB display readiness must not wait for either model tensor.
                // Build these on the serialized depth worker so the two SMB/decode
                // workers can keep the navigation window full.
                image.rgb = modelInput(image.bitmap, StillImageDepthBatcher.SIZE);
                image.modelRgb = modelInput(image.bitmap, StillImageDepthBatcher.V2_SIZE);
                // The renderer and LiteRT delegate use separate GPU queues. An older
                // 150 ms guard here cut sustained preparation throughput almost in
                // half and let the navigation queue catch the worker after ~10 items.
                image.depth = batcher.inferOne(image.modelRgb, image.rgb);
            } catch (CancellationException ignored) {
                // Direction changes deliberately cancel work outside the live window.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LimeLog.warning("Static depth preparation failed: " + e);
            } finally {
                if (ownsDepth) {
                    synchronized (image) {
                        image.modelRgb = null;
                        image.depthReady = true;
                        if (image.recycleAfterDepth && !image.bitmap.isRecycled()) {
                            image.bitmap.recycle();
                        }
                    }
                    int complete = preparedDepthCount.incrementAndGet();
                    if ((complete & 3) == 0) {
                        Runtime runtime = Runtime.getRuntime();
                        LimeLog.info("Static depth prepared " + complete + " images, Java heap "
                                + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                                + " MiB, process PSS " + Debug.getPss() / 1024 + " MiB");
                    }
                }
            }
        });
        return true;
    }

    private int scheduleDepthBatch(ExecutorService decoder, ExecutorService worker,
            StillImageDepthBatcher batcher, Map<Integer, FutureTask<PreparedImage>> nearby,
            Set<FutureTask<PreparedImage>> depthScheduled,
            int start, int direction, int count) {
        if (imageQueue.isEmpty() || start < 0 || start >= imageQueue.size()) {
            return direction > 0 ? imageQueue.size() - 1 : 0;
        }
        ArrayList<FutureTask<PreparedImage>> tasks = new ArrayList<>(count);
        int last = start;
        for (int i = 0; i < count; i++) {
            int index = start + direction * i;
            if (index < 0 || index >= imageQueue.size()) break;
            tasks.add(ensureDecoded(decoder, nearby, index));
            last = index;
        }
        // The shipped graph has a fixed batch-one reshape, verified on Quest.
        // Submit the jobs separately so the next image becomes ready as
        // soon as its own SMB read and inference finish; later reads cannot
        // hold the first image behind the whole group.
        int newlyScheduled = 0;
        for (FutureTask<PreparedImage> task : tasks) {
            if (scheduleDepthOne(worker, batcher, task, depthScheduled)) newlyScheduled++;
        }
        if (newlyScheduled > 0) {
            LimeLog.info("Static prefetch queued " + newlyScheduled + " new of " + tasks.size()
                    + " window images, cached bitmaps "
                    + cachedBitmapBytes(nearby) / (1024 * 1024) + " MiB");
        }
        return last;
    }

    private void scheduleNavigationWindow(ExecutorService decoder, ExecutorService worker,
            StillImageDepthBatcher batcher, Map<Integer, FutureTask<PreparedImage>> nearby,
            Set<FutureTask<PreparedImage>> depthScheduled, int index, int direction) {
        // Rebuild the look-ahead window from the current position. The former
        // forwardEnd/backwardStart cursors only advanced in their original
        // direction, so reversing after a long run left a permanently red
        // adjacent button until the user clicked it and started an on-demand read.
        scheduleDepthBatch(decoder, worker, batcher, nearby, depthScheduled,
                index + direction, direction, INITIAL_PREFETCH);
        int opposite = index - direction;
        if (opposite >= 0 && opposite < imageQueue.size()) {
            FutureTask<PreparedImage> neighbor = ensureDecoded(decoder, nearby, opposite);
            scheduleDepthOne(worker, batcher, neighbor, depthScheduled);
        }
    }

    private boolean depthReadyAt(Map<Integer, FutureTask<PreparedImage>> nearby, int index) {
        if (index < 0 || index >= imageQueue.size()) return true;
        FutureTask<PreparedImage> task = nearby.get(index);
        if (task == null || !task.isDone() || task.isCancelled()) return false;
        try { return task.get().depthReady; }
        catch (Exception e) { return false; }
    }

    private void evictDistant(Map<Integer, FutureTask<PreparedImage>> nearby, int index,
                              int direction) {
        for (Integer key : new ArrayList<>(nearby.keySet())) {
            int low = direction >= 0 ? index - HISTORY_CACHE : index - INITIAL_PREFETCH;
            int high = direction >= 0 ? index + INITIAL_PREFETCH : index + HISTORY_CACHE;
            if (key >= low && key <= high) continue;
            FutureTask<PreparedImage> task = nearby.remove(key);
            if (task.isDone() && !task.isCancelled()) {
                try { retirePrepared(task.get()); } catch (Exception ignored) { }
            } else {
                task.cancel(true);
            }
        }
    }

    private static long cachedBitmapBytes(Map<Integer, FutureTask<PreparedImage>> nearby) {
        long bytes = 0;
        for (FutureTask<PreparedImage> task : nearby.values()) {
            if (!task.isDone() || task.isCancelled()) continue;
            try {
                Bitmap bitmap = task.get().bitmap;
                if (bitmap != null && !bitmap.isRecycled()) bytes += bitmap.getAllocationByteCount();
            } catch (Exception ignored) { }
        }
        return bytes;
    }

    private static void recyclePrepared(PreparedImage image,
                                        IdentityHashMap<Bitmap, Boolean> recycled) {
        if (image != null && image.bitmap != null && !recycled.containsKey(image.bitmap)) {
            recycled.put(image.bitmap, true);
            retirePrepared(image);
        }
    }

    /**
     * Removes an image from the display cache without racing the depth worker.
     * A queued worker observes {@code retired} and skips it; a worker already
     * converting the bitmap owns it until the conversion/inference finishes.
     */
    private static void retirePrepared(PreparedImage image) {
        if (image == null || image.bitmap == null) return;
        synchronized (image) {
            image.retired = true;
            if (image.depthStarted.get() && !image.depthReady) {
                image.recycleAfterDepth = true;
            } else if (!image.bitmap.isRecycled()) {
                image.bitmap.recycle();
            }
        }
    }

    private static void recycleTask(FutureTask<PreparedImage> task,
                                    IdentityHashMap<Bitmap, Boolean> recycled) {
        if (task == null) return;
        if (task.isDone() && !task.isCancelled()) {
            try { recyclePrepared(task.get(), recycled); } catch (Exception ignored) { }
        } else task.cancel(true);
    }

    private PreparedImage prepareImage(String smbUri, String requestedName) throws IOException {
        long start = System.nanoTime();
        Bitmap bitmap = decodeImage(smbUri, requestedName);
        if (bitmap == null) throw new IOException("Image decode returned null");
        try {
            checkDecodeCancelled();
            LimeLog.info("Static image display decoded in "
                    + (System.nanoTime() - start) / 1000000 + " ms, bitmap "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            return new PreparedImage(bitmap);
        } catch (RuntimeException | IOException e) {
            bitmap.recycle();
            throw e;
        }
    }

    private static void checkDecodeCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Image decode cancelled");
    }

    private static ByteBuffer modelInput(Bitmap bitmap, int n) {
        Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        canvas.drawColor(Color.BLACK);
        Rect fitted = fitRect(bitmap.getWidth(), bitmap.getHeight(), FRAME_WIDTH, FRAME_HEIGHT);
        Rect dst = new Rect(Math.round(fitted.left * n / (float)FRAME_WIDTH),
                Math.round(fitted.top * n / (float)FRAME_HEIGHT),
                Math.round(fitted.right * n / (float)FRAME_WIDTH),
                Math.round(fitted.bottom * n / (float)FRAME_HEIGHT));
        canvas.drawBitmap(bitmap, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels = new int[n * n];
        small.getPixels(pixels, 0, n, 0, 0, n, n);
        small.recycle();
        ByteBuffer rgb = ByteBuffer.allocateDirect(n * n * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0 && Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Image conversion cancelled");
            }
            int pixel = pixels[i];
            rgb.putFloat(((pixel >>> 16) & 255) / 255.0f);
            rgb.putFloat(((pixel >>> 8) & 255) / 255.0f);
            rgb.putFloat((pixel & 255) / 255.0f);
        }
        rgb.rewind();
        return rgb;
    }

    private Bitmap decodeImage(String smbUri, String requestedName) throws IOException {
        byte[] cached = null;
        MediaEntry smbEntry = null;
        if (smbUri != null) {
            for (MediaEntry entry : imageQueue) {
                if (entry.uri.equals(smbUri)) {
                    smbEntry = entry;
                    cached = SmbImageByteCache.get(entry);
                    break;
                }
            }
        }
        if (cached != null) LimeLog.info("Static XR reused " + cached.length + " SMB bytes from thumbnail");
        if (cached == null && smbUri != null) {
            long readStart = System.nanoTime();
            LimeLog.info("Static XR SMB read started "
                    + (smbEntry == null ? "unknown" : smbEntry.name) + ", expected "
                    + (smbEntry == null ? -1 : smbEntry.size) + " bytes");
            try (InputStream source = openImage(smbUri, requestedName);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(4 * 1024 * 1024)) {
                byte[] block = new byte[128 * 1024];
                int count;
                while ((count = source.read(block)) >= 0) {
                    checkDecodeCancelled();
                    if (count > 0) output.write(block, 0, count);
                }
                cached = output.toByteArray();
            }
            LimeLog.info("Static XR read " + cached.length + " SMB bytes in "
                    + (System.nanoTime() - readStart) / 1000000 + " ms");
        }
        try (InputStream stream = cached != null ? new ByteArrayInputStream(cached)
                : openImage(smbUri, requestedName)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(stream, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("Unsupported image format");
            }
            Rect target = fitRect(bounds.outWidth, bounds.outHeight, FRAME_WIDTH, FRAME_HEIGHT);
            int targetWidth = target.width();
            int targetHeight = target.height();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth
                    && bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream pixels = cached != null ? new ByteArrayInputStream(cached)
                    : openImage(smbUri, requestedName)) {
                Bitmap decoded = BitmapFactory.decodeStream(pixels, null, options);
                if (decoded == null) return null;
                if (decoded.getWidth() <= targetWidth && decoded.getHeight() <= targetHeight) {
                    return decoded;
                }
                Bitmap resized = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
                decoded.recycle();
                return resized;
            }
        }
    }

    private InputStream openImage(String smbUri, String requestedName) throws IOException {
        if (smbUri != null) {
            SmbStorage storage = SmbClientManager.active();
            if (storage == null) {
                throw new IOException("SMB session is no longer active");
            }
            return storage.openInputStream(smbUri);
        }
        if (requestedName == null) {
            return getAssets().open(IMAGE_ASSET);
        }
        if (!requestedName.matches("[A-Za-z0-9._-]+") || requestedName.equals(".")
                || requestedName.equals("..")) {
            throw new IOException("Invalid test image name");
        }
        File directory = getExternalFilesDir("test_media");
        if (directory == null) {
            throw new IOException("Test media directory unavailable");
        }
        return new FileInputStream(new File(directory, requestedName));
    }

    private static Rect fitRect(int imageWidth, int imageHeight, int canvasWidth, int canvasHeight) {
        float scale = Math.min(canvasWidth / (float)imageWidth,
                canvasHeight / (float)imageHeight);
        int width = Math.round(imageWidth * scale);
        int height = Math.round(imageHeight * scale);
        int left = (canvasWidth - width) / 2;
        int top = (canvasHeight - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        if (imageThread != null) {
            imageThread.interrupt();
        }
        super.onDestroy();
    }

    @Override public void onVrPointerMove(float u, float v) { }
    @Override public void onVrButton(int button, boolean down) {
    }
    @Override public void onVrImageNavigate(int direction) {
        requestedStep.set(direction);
        requestedStepNs.set(System.nanoTime());
        LimeLog.info("Static XR navigation input direction " + direction);
    }
    @Override public void onVrScroll(int clicks) { }
    @Override public void onVrKey(int code) { }
    @Override public void onVrExit() { runOnUiThread(this::finish); }
}
