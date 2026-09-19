package com.limelight.smb;

import android.util.LruCache;

import com.limelight.media.MediaEntry;

/** Reuses the bytes already read for visible thumbnails at XR image open. */
public final class SmbImageByteCache {
    public static final long MAX_ITEM_BYTES = 20L * 1024 * 1024;
    private static final int MAX_BYTES = 48 * 1024 * 1024;
    private static final class Value {
        final byte[] bytes;
        final long size;
        final long modified;
        Value(MediaEntry entry, byte[] bytes) {
            this.bytes = bytes;
            size = entry.size;
            modified = entry.modifiedTime;
        }
    }
    private static final LruCache<String, Value> CACHE = new LruCache<String, Value>(MAX_BYTES) {
        @Override protected int sizeOf(String key, Value value) { return value.bytes.length; }
    };

    private SmbImageByteCache() { }

    public static synchronized void put(MediaEntry entry, byte[] bytes) {
        if (bytes != null && bytes.length <= MAX_ITEM_BYTES) {
            CACHE.put(entry.uri, new Value(entry, bytes));
        }
    }

    public static synchronized byte[] get(MediaEntry entry) {
        Value value = CACHE.get(entry.uri);
        if (value == null || value.size != entry.size || value.modified != entry.modifiedTime) {
            CACHE.remove(entry.uri);
            return null;
        }
        return value.bytes;
    }

    public static synchronized void clear() { CACHE.evictAll(); }
}
