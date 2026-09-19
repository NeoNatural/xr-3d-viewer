package com.limelight.media;

import java.util.Comparator;
import java.util.List;

/** Shared ordering and filename lookup for a browsed directory. */
public final class MediaDirectoryOrder {
    private MediaDirectoryOrder() { }

    public static void newestFirst(List<MediaEntry> entries) {
        entries.sort(Comparator.comparingLong((MediaEntry entry) -> entry.modifiedTime)
                .reversed().thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
    }

    public static MediaEntry fileNamed(List<MediaEntry> entries, String filename) {
        if (filename == null) return null;
        for (MediaEntry entry : entries) {
            if (!entry.directory && entry.name.equals(filename)) return entry;
        }
        return null;
    }
}
