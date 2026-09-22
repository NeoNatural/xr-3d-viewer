package com.limelight.local;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.StaticImageXrActivity;
import com.limelight.VideoXrActivity;
import com.limelight.media.MediaDirectoryOrder;
import com.limelight.media.MediaEntry;
import com.limelight.smb.SmbClientManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Storage Access Framework browser with the same photo/video behaviour as SMB. */
public final class LocalBrowserActivity extends Activity {
    private static final int PICK_TREE = 1;
    private static final String PREFS = "local_media_browser";
    private static final String LAST_TREE = "last_tree";

    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Deque<Uri> parents = new ArrayDeque<>();
    private final List<MediaEntry> entries = new ArrayList<>();
    private final Set<String> videoUris = new HashSet<>();
    private ListView list;
    private TextView status;
    private LocalAdapter adapter;
    private Uri treeUri;
    private Uri currentDirectory;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        Button choose = new Button(this);
        choose.setText("Choose local folder");
        root.addView(choose);
        status = new TextView(this);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(status);
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        choose.setOnClickListener(v -> chooseTree());
        list.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position)));

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_TREE, null);
        if (saved == null) chooseTree();
        else openTree(Uri.parse(saved));
    }

    private void chooseTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (SecurityException ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(LAST_TREE, selected.toString()).apply();
        openTree(selected);
    }

    private void openTree(Uri selected) {
        try {
            treeUri = selected;
            parents.clear();
            String rootId = DocumentsContract.getTreeDocumentId(selected);
            loadDirectory(DocumentsContract.buildDocumentUriUsingTree(selected, rootId), false);
        } catch (RuntimeException error) {
            status.setText("Unable to open folder: " + error.getMessage());
        }
    }

    private void loadDirectory(Uri directory, boolean rememberParent) {
        status.setText("Loading…");
        loader.execute(() -> {
            ArrayList<MediaEntry> found = new ArrayList<>();
            HashSet<String> foundVideos = new HashSet<>();
            try {
                String id = DocumentsContract.getDocumentId(directory);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id);
                String[] columns = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                };
                try (Cursor cursor = getContentResolver().query(children, columns,
                        null, null, null)) {
                    if (cursor != null) while (cursor.moveToNext()) {
                        String childId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                        boolean video = !folder && isVideo(name, mime);
                        if (!folder && !isImage(name, mime) && !video) continue;
                        Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                        found.add(new MediaEntry(child.toString(), name == null ? "" : name,
                                folder, cursor.isNull(3) ? 0 : cursor.getLong(3),
                                cursor.isNull(4) ? 0 : cursor.getLong(4)));
                        if (video) foundVideos.add(child.toString());
                    }
                }
                MediaDirectoryOrder.newestFirst(found);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (rememberParent && currentDirectory != null) parents.push(currentDirectory);
                    currentDirectory = directory;
                    entries.clear();
                    entries.addAll(found);
                    videoUris.clear();
                    videoUris.addAll(foundVideos);
                    if (adapter != null) adapter.close();
                    adapter = new LocalAdapter();
                    list.setAdapter(adapter);
                    status.setText(found.size() + " media items — " + directory);
                    if (found.isEmpty()) Toast.makeText(this,
                            "No supported photos or videos in this folder.",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Unable to read folder: "
                        + error.getMessage()));
            }
        });
    }

    private void open(MediaEntry entry) {
        if (entry.directory) {
            loadDirectory(Uri.parse(entry.uri), true);
            return;
        }
        if (!videoUris.contains(entry.uri)) {
            ArrayList<Uri> images = new ArrayList<>();
            int selected = 0;
            for (MediaEntry item : entries) if (!item.directory
                    && !videoUris.contains(item.uri)) {
                if (item.uri.equals(entry.uri)) selected = images.size();
                images.add(Uri.parse(item.uri));
            }
            Intent intent = new Intent(this, StaticImageXrActivity.class);
            intent.setData(Uri.parse(entry.uri));
            intent.putParcelableArrayListExtra(StaticImageXrActivity.EXTRA_LOCAL_IMAGE_URIS, images);
            intent.putExtra(StaticImageXrActivity.EXTRA_LOCAL_START_INDEX, selected);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } else {
            ArrayList<Uri> videos = new ArrayList<>();
            int selected = 0;
            for (MediaEntry item : entries) if (!item.directory
                    && videoUris.contains(item.uri)) {
                if (item.uri.equals(entry.uri)) selected = videos.size();
                videos.add(Uri.parse(item.uri));
            }
            Intent intent = new Intent(this, VideoXrActivity.class);
            intent.setData(Uri.parse(entry.uri));
            intent.putParcelableArrayListExtra(VideoXrActivity.EXTRA_VIDEO_URIS, videos);
            intent.putExtra(VideoXrActivity.EXTRA_START_INDEX, selected);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }

    private static boolean isImage(String name, String mime) {
        if (mime != null && mime.startsWith("image/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static boolean isVideo(String name, String mime) {
        return mime != null && mime.startsWith("video/")
                || name != null && SmbClientManager.isVideo(name);
    }

    @Override
    public void onBackPressed() {
        if (!parents.isEmpty()) {
            Uri target = parents.pop();
            loadDirectory(target, false);
        } else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (adapter != null) adapter.close();
        loader.shutdownNow();
        super.onDestroy();
    }

    private final class LocalAdapter extends BaseAdapter {
        private final int thumb = Math.round(72 * getResources().getDisplayMetrics().density);
        private final ExecutorService thumbnailWorkers = Executors.newFixedThreadPool(2);
        private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
        private final java.util.Set<String> pending = new java.util.HashSet<>();
        private volatile boolean closed;

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView text;
            if (recycled instanceof LinearLayout) {
                row = (LinearLayout) recycled;
                icon = (ImageView) row.getChildAt(0);
                text = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(LocalBrowserActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                icon = new ImageView(LocalBrowserActivity.this);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(icon, new LinearLayout.LayoutParams(thumb, thumb));
                text = new TextView(LocalBrowserActivity.this);
                text.setTextSize(17);
                text.setPadding(16, 8, 8, 8);
                row.addView(text, new LinearLayout.LayoutParams(0, thumb, 1));
            }
            MediaEntry item = entries.get(position);
            text.setText(item.directory ? "📁 " + item.name : item.name);
            icon.setImageBitmap(null);
            if (item.directory) icon.setImageResource(android.R.drawable.ic_menu_agenda);
            else {
                Bitmap bitmap = cache.get(item.uri);
                if (bitmap != null) icon.setImageBitmap(bitmap);
                else {
                    icon.setImageResource(videoUris.contains(item.uri)
                            ? android.R.drawable.ic_media_play
                            : android.R.drawable.ic_menu_gallery);
                    requestThumbnail(item.uri);
                }
            }
            return row;
        }

        private void requestThumbnail(String value) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            synchronized (pending) { if (closed || !pending.add(value)) return; }
            thumbnailWorkers.execute(() -> {
                Bitmap result = null;
                try {
                    result = getContentResolver().loadThumbnail(
                            Uri.parse(value), new Size(thumb * 2, thumb * 2), null);
                } catch (Exception ignored) { }
                Bitmap finalResult = result;
                runOnUiThread(() -> {
                    synchronized (pending) { pending.remove(value); }
                    if (!closed && finalResult != null) {
                        cache.put(value, finalResult);
                        notifyDataSetChanged();
                    } else if (finalResult != null) finalResult.recycle();
                });
            });
        }

        void close() {
            closed = true;
            thumbnailWorkers.shutdownNow();
            ArrayList<Bitmap> images = new ArrayList<>(cache.snapshot().values());
            cache.evictAll();
            for (Bitmap image : images) if (image != null && !image.isRecycled()) image.recycle();
        }
    }
}
