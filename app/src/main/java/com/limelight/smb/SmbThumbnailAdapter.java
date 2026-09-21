package com.limelight.smb;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.media.MediaEntry;
import com.limelight.media.RandomAccessSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads only visible image/video rows, on two SMB workers, with an in-memory cache. */
final class SmbThumbnailAdapter extends BaseAdapter {
    private final Activity activity;
    private final SmbStorage storage;
    private final List<MediaEntry> entries;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Set<String> pending = new HashSet<>();
    private final Set<InputStream> activeStreams = new HashSet<>();
    private final Set<RandomAccessSource> activeSources = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private volatile boolean closed;
    private final int thumbPx;

    SmbThumbnailAdapter(Activity activity, SmbStorage storage, List<MediaEntry> entries) {
        this.activity = activity;
        this.storage = storage;
        this.entries = entries;
        thumbPx = (int) (72 * activity.getResources().getDisplayMetrics().density);
    }

    @Override public int getCount() { return entries.size(); }
    @Override public MediaEntry getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        LinearLayout row;
        ImageView icon;
        TextView label;
        if (recycled instanceof LinearLayout) {
            row = (LinearLayout) recycled;
            icon = (ImageView) row.getChildAt(0);
            label = (TextView) row.getChildAt(1);
        } else {
            row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(icon, new LinearLayout.LayoutParams(thumbPx, thumbPx));
            label = new TextView(activity);
            label.setPadding(16, 8, 8, 8);
            label.setTextSize(17);
            row.addView(label, new LinearLayout.LayoutParams(0, thumbPx, 1));
        }
        MediaEntry item = entries.get(position);
        label.setText(item.directory ? "📁 " + item.name
                : item.name + "  (" + item.size / 1024 + " KB)");
        icon.setImageBitmap(null);
        icon.setContentDescription(item.directory ? "Folder" : "Image thumbnail");
        if (!item.directory && isImage(item.name)) {
            Bitmap bitmap = cache.get(item.uri);
            if (bitmap != null) icon.setImageBitmap(bitmap);
            else {
                icon.setImageResource(android.R.drawable.ic_menu_gallery);
                request(item);
            }
        } else if (!item.directory && SmbClientManager.isVideo(item.name)) {
            Bitmap bitmap = cache.get(item.uri);
            if (bitmap != null) icon.setImageBitmap(bitmap);
            else {
                icon.setImageResource(android.R.drawable.ic_media_play);
                request(item);
            }
            icon.setContentDescription("Video thumbnail");
        }
        return row;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private void request(MediaEntry entry) {
        String uri = entry.uri;
        synchronized (pending) {
            if (closed || !pending.add(uri)) return;
        }
        workers.execute(() -> {
            Bitmap bitmap = SmbClientManager.isVideo(entry.name)
                    ? loadVideoThumbnail(uri) : loadImageThumbnail(entry);
            Bitmap result = bitmap;
            activity.runOnUiThread(() -> {
                synchronized (pending) { pending.remove(uri); }
                if (!closed && result != null) {
                    cache.put(uri, result);
                    notifyDataSetChanged();
                } else if (result != null) {
                    result.recycle();
                }
            });
        });
    }

    private Bitmap loadImageThumbnail(MediaEntry entry) {
            Bitmap bitmap = null;
            byte[] original = null;
            if (entry.size > 0 && entry.size <= SmbImageByteCache.MAX_ITEM_BYTES) {
                try (InputStream stream = openTrackedStream(entry.uri)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream((int)entry.size);
                    byte[] block = new byte[64 * 1024];
                    int count;
                    while ((count = stream.read(block)) >= 0) {
                        if (bytes.size() + count > SmbImageByteCache.MAX_ITEM_BYTES) break;
                        bytes.write(block, 0, count);
                    }
                    if (count < 0) original = bytes.toByteArray();
                } catch (Exception ignored) { }
            }
            try (InputStream stream = original != null
                    ? new ByteArrayInputStream(original) : openTrackedStream(entry.uri)) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(stream, null, bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    int sample = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / sample > thumbPx * 2) {
                        sample *= 2;
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    try (InputStream pixels = original != null
                            ? new ByteArrayInputStream(original) : openTrackedStream(entry.uri)) {
                        bitmap = BitmapFactory.decodeStream(pixels, null, options);
                    }
                }
            } catch (Exception ignored) {
                // Keep the filename visible when a thumbnail is unavailable.
            }
            if (original != null && bitmap != null) SmbImageByteCache.put(entry, original);
            return bitmap;
    }

    private Bitmap loadVideoThumbnail(String uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        TrackedMediaDataSource data = null;
        try {
            data = new TrackedMediaDataSource(openTrackedSource(uri));
            retriever.setDataSource(data);
            Bitmap frame = retriever.getFrameAtTime(1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            float scale = Math.min(1.0f, thumbPx * 2.0f
                    / Math.max(frame.getWidth(), frame.getHeight()));
            if (scale >= 1.0f) return frame;
            Bitmap scaled = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
            if (data != null) {
                try { data.close(); } catch (IOException ignored) { }
            }
        }
    }

    private InputStream openTrackedStream(String uri) throws IOException {
        InputStream source = storage.openInputStream(uri);
        synchronized (activeStreams) {
            if (closed) {
                source.close();
                throw new InterruptedIOException("Thumbnail loading stopped");
            }
            activeStreams.add(source);
        }
        return new FilterInputStream(source) {
            private boolean removed;

            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    synchronized (activeStreams) {
                        if (!removed) {
                            activeStreams.remove(source);
                            removed = true;
                        }
                    }
                }
            }
        };
    }

    private RandomAccessSource openTrackedSource(String uri) throws IOException {
        RandomAccessSource source = storage.openVideoRandomAccess(uri);
        synchronized (activeSources) {
            if (closed) {
                source.close();
                throw new InterruptedIOException("Thumbnail loading stopped");
            }
            activeSources.add(source);
        }
        return source;
    }

    private final class TrackedMediaDataSource extends MediaDataSource {
        private final RandomAccessSource source;
        private boolean sourceClosed;

        TrackedMediaDataSource(RandomAccessSource source) { this.source = source; }

        @Override public int readAt(long position, byte[] buffer, int offset, int size)
                throws IOException {
            return source.readAt(position, buffer, offset, size);
        }

        @Override public long getSize() { return source.size(); }

        @Override public void close() throws IOException {
            synchronized (activeSources) {
                if (sourceClosed) return;
                sourceClosed = true;
                activeSources.remove(source);
            }
            source.close();
        }
    }

    void close() {
        closed = true;
        workers.shutdownNow();
        synchronized (activeStreams) {
            for (InputStream stream : new ArrayList<>(activeStreams)) {
                try { stream.close(); } catch (IOException ignored) { }
            }
            activeStreams.clear();
        }
        synchronized (activeSources) {
            for (RandomAccessSource source : new ArrayList<>(activeSources)) {
                try { source.close(); } catch (IOException ignored) { }
            }
            activeSources.clear();
        }
        ArrayList<Bitmap> bitmaps = new ArrayList<>(cache.snapshot().values());
        cache.evictAll();
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
