package com.limelight.media;

import java.util.Comparator;
import java.util.List;

/** Shared ordering and filename lookup for a browsed directory. */
public final class MediaDirectoryOrder {
    private MediaDirectoryOrder() { }

    public static void newestFirst(List<MediaEntry> entries) {
        // Media is the primary group because it is what this browser is for.
        // Within each group, keep the newest item at the top and use the name
        // only to make equal timestamps deterministic.
        entries.sort(Comparator.comparing((MediaEntry entry) -> entry.directory)
                .thenComparing(Comparator.comparingLong(
                        (MediaEntry entry) -> entry.modifiedTime).reversed())
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
    }

    public static MediaEntry fileNamed(List<MediaEntry> entries, String filename) {
        if (filename == null) return null;
        for (MediaEntry entry : entries) {
            if (!entry.directory && entry.name.equals(filename)) return entry;
        }
        return null;
    }
}
