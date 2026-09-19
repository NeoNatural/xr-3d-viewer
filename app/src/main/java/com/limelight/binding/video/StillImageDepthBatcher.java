package com.limelight.binding.video;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import com.limelight.LimeLog;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide still-image depth worker; the GPU delegate remains on one thread. */
public final class StillImageDepthBatcher {
    public static final int SIZE = 256;
    public static final int V2_SIZE = 518;
    private static final String V2_MODEL = "depth_anything_v2_small_518.tflite";
    private static final String FALLBACK_MODEL = "midas_v21_small_256_fp16.tflite";
    private static StillImageDepthBatcher shared;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "Still Image Depth GPU"));
    private Interpreter interpreter;
    private GpuDelegate delegate;
    private boolean fallback;
    // The worker is single threaded, so the interpreter output can be reused.
    // Previously every image allocated another ~1 MiB direct buffer and waited
    // for a later GC/finalizer pass to return that native memory.
    private final ByteBuffer inferenceOutput = ByteBuffer
            .allocateDirect(V2_SIZE * V2_SIZE * Float.BYTES)
            .order(ByteOrder.nativeOrder());

    private StillImageDepthBatcher(Context context) { this.context = context.getApplicationContext(); }

    public static synchronized StillImageDepthBatcher shared(Context context) {
        if (shared == null) shared = new StillImageDepthBatcher(context);
        return shared;
    }

    public ExecutorService worker() { return worker; }

    /** Begin the four-second delegate compile while the user browses images. */
    public void prewarm() {
        worker.execute(() -> {
            try { ensureInterpreter(); }
            catch (IOException e) { LimeLog.warning("Still depth prewarm failed: " + e); }
        });
    }

    private static MappedByteBuffer mapAsset(Context context, String name) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(name);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
            return stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private void ensureInterpreter() throws IOException {
        if (interpreter != null) return;
        long start = System.nanoTime();
        try {
            GpuDelegateFactory.Options gpu = new GpuDelegateFactory.Options();
            gpu.setPrecisionLossAllowed(true);
            gpu.setInferencePreference(
                    GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
            delegate = new GpuDelegate(gpu);
            Interpreter.Options options = new Interpreter.Options();
            options.addDelegate(delegate);
            interpreter = new Interpreter(mapAsset(context, V2_MODEL), options);
            LimeLog.info("Still V2 LiteRT GPU ready in "
                    + (System.nanoTime() - start) / 1000000 + " ms");
        } catch (Exception e) {
            LimeLog.warning("Still V2 LiteRT GPU unavailable: " + e);
            openFallback();
        }
    }

    private void openFallback() throws IOException {
        if (interpreter != null) interpreter.close();
        interpreter = null;
        if (delegate != null) delegate.close();
        delegate = null;
        try {
            Interpreter.Options cpu = new Interpreter.Options();
            cpu.setNumThreads(2);
            interpreter = new Interpreter(mapAsset(context, FALLBACK_MODEL), cpu);
            fallback = true;
            LimeLog.warning("Still depth using MiDaS CPU fallback");
        } catch (RuntimeException e) {
            throw new IOException("No usable still depth model", e);
        }
    }

    /** Must run on worker(); nativeRgb is the 256-square image used by XR upload. */
    public ByteBuffer inferOne(ByteBuffer modelRgb, ByteBuffer nativeRgb) throws IOException {
        ensureInterpreter();
        try { return run(modelRgb, nativeRgb); }
        catch (RuntimeException e) {
            if (fallback) throw new IOException("MiDaS fallback inference failed", e);
            LimeLog.warning("Still V2 inference failed, switching to MiDaS: " + e);
            openFallback();
            return run(modelRgb, nativeRgb);
        }
    }

    private ByteBuffer run(ByteBuffer modelRgb, ByteBuffer nativeRgb) {
        int n = fallback ? SIZE : V2_SIZE;
        ByteBuffer input = (fallback ? nativeRgb : modelRgb).duplicate()
                .order(ByteOrder.nativeOrder());
        input.rewind();
        ByteBuffer raw = inferenceOutput;
        raw.clear();
        long start = System.nanoTime();
        interpreter.run(input, raw);
        LimeLog.info("Still depth " + (fallback ? "MiDaS CPU" : "V2 LiteRT GPU")
                + " inference " + (System.nanoTime() - start) / 1000000 + " ms");
        raw.rewind();
        if (fallback) return copyDepth(raw.asFloatBuffer(), SIZE);
        return resizeDepth(raw.asFloatBuffer());
    }

    private static ByteBuffer copyDepth(FloatBuffer source, int n) {
        ByteBuffer result = ByteBuffer.allocateDirect(n * n * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer out = result.asFloatBuffer();
        source.position(0);
        source.limit(n * n);
        out.put(source);
        result.rewind();
        return result;
    }

    private static ByteBuffer resizeDepth(FloatBuffer source) {
        ByteBuffer result = ByteBuffer.allocateDirect(SIZE * SIZE * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer out = result.asFloatBuffer();
        for (int y = 0; y < SIZE; y++) {
            float sy = (y + 0.5f) * V2_SIZE / SIZE - 0.5f;
            int y0 = Math.max(0, (int)Math.floor(sy));
            int y1 = Math.min(V2_SIZE - 1, y0 + 1);
            float fy = Math.max(0f, sy - y0);
            for (int x = 0; x < SIZE; x++) {
                float sx = (x + 0.5f) * V2_SIZE / SIZE - 0.5f;
                int x0 = Math.max(0, (int)Math.floor(sx));
                int x1 = Math.min(V2_SIZE - 1, x0 + 1);
                float fx = Math.max(0f, sx - x0);
                float a = source.get(y0 * V2_SIZE + x0);
                float b = source.get(y0 * V2_SIZE + x1);
                float c = source.get(y1 * V2_SIZE + x0);
                float d = source.get(y1 * V2_SIZE + x1);
                out.put((a + (b - a) * fx) * (1f - fy)
                        + (c + (d - c) * fx) * fy);
            }
        }
        result.rewind();
        return result;
    }
}
