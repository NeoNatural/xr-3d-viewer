package com.limelight.media;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared ordering and filename lookup for a browsed directory. */
public final class MediaDirectoryOrder {
    public enum SortOrder {
        NAME_ASC, NAME_DESC, MODIFIED_ASC, MODIFIED_DESC,
        SIZE_ASC, SIZE_DESC, TYPE_ASC, TYPE_DESC;

        public static SortOrder fromPreference(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return MODIFIED_DESC;
            }
        }
    }

    private MediaDirectoryOrder() { }

    public static void newestFirst(List<MediaEntry> entries) {
        sort(entries, SortOrder.MODIFIED_DESC);
    }

    public static void sort(List<MediaEntry> entries, SortOrder order) {
        Comparator<MediaEntry> byName = Comparator
                .comparing((MediaEntry entry) -> entry.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name)
                .thenComparing(entry -> entry.uri);
        Comparator<MediaEntry> selected;
        switch (order) {
            case NAME_ASC: selected = byName; break;
            case NAME_DESC: selected = byName.reversed(); break;
            case MODIFIED_ASC:
                selected = Comparator.comparingLong(entry -> entry.modifiedTime); break;
            case MODIFIED_DESC:
                selected = Comparator.comparingLong((MediaEntry entry) -> entry.modifiedTime)
                        .reversed(); break;
            case SIZE_ASC: selected = Comparator.comparingLong(entry -> entry.size); break;
            case SIZE_DESC:
                selected = Comparator.comparingLong((MediaEntry entry) -> entry.size)
                        .reversed(); break;
            case TYPE_ASC: selected = Comparator.comparing(MediaDirectoryOrder::extension); break;
            case TYPE_DESC:
                selected = Comparator.comparing(MediaDirectoryOrder::extension).reversed(); break;
            default: throw new IllegalArgumentException("Unknown sort order: " + order);
        }
        // Keep the existing files-before-folders grouping in both directions.
        entries.sort(Comparator.comparing((MediaEntry entry) -> entry.directory)
                .thenComparing(selected).thenComparing(byName));
    }

    private static String extension(MediaEntry entry) {
        if (entry.directory) return "";
        int dot = entry.name.lastIndexOf('.');
        return dot > 0 ? entry.name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static MediaEntry fileNamed(List<MediaEntry> entries, String filename) {
        if (filename == null) return null;
        for (MediaEntry entry : entries) {
            if (!entry.directory && entry.name.equals(filename)) return entry;
        }
        return null;
    }
}
