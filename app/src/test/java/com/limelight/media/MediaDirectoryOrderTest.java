package com.limelight.media;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class MediaDirectoryOrderTest {
    @Test
    public void allSortOrdersPreserveGroupingAndLastFilename() {
        MediaEntry a = new MediaEntry("a.jpg", "a.jpg", false, 30, 100);
        MediaEntry b = new MediaEntry("b.png", "b.png", false, 10, 300);
        MediaEntry c = new MediaEntry("c.mp4", "c.mp4", false, 20, 200);
        MediaEntry folder = new MediaEntry("folder/", "folder", true, 0, 500);
        MediaEntry[][] expected = {
                {a, b, c, folder}, {c, b, a, folder},
                {a, c, b, folder}, {b, c, a, folder},
                {b, c, a, folder}, {a, c, b, folder},
                {a, c, b, folder}, {b, c, a, folder}
        };
        for (MediaDirectoryOrder.SortOrder order : MediaDirectoryOrder.SortOrder.values()) {
            List<MediaEntry> entries = new ArrayList<>(Arrays.asList(folder, c, a, b));
            MediaDirectoryOrder.sort(entries, order);
            assertEquals(order.name(), Arrays.asList(expected[order.ordinal()]), entries);
            assertSame(b, MediaDirectoryOrder.fileNamed(entries, "b.png"));
        }
    }

    @Test
    public void typeSortHandlesMissingExtensionsAndCase() {
        MediaEntry hidden = new MediaEntry("hidden", ".hidden", false, 0, 0);
        MediaEntry plain = new MediaEntry("plain", "README", false, 0, 0);
        MediaEntry a = new MediaEntry("a", "a.JPG", false, 0, 0);
        MediaEntry b = new MediaEntry("b", "b.jpg", false, 0, 0);
        List<MediaEntry> entries = new ArrayList<>(Arrays.asList(b, plain, a, hidden));
        MediaDirectoryOrder.sort(entries, MediaDirectoryOrder.SortOrder.TYPE_ASC);
        assertEquals(Arrays.asList(hidden, plain, a, b), entries);
    }

    @Test
    public void unknownPreferenceKeepsLegacyNewestFirstDefault() {
        assertEquals(MediaDirectoryOrder.SortOrder.MODIFIED_DESC,
                MediaDirectoryOrder.SortOrder.fromPreference(null));
        assertEquals(MediaDirectoryOrder.SortOrder.MODIFIED_DESC,
                MediaDirectoryOrder.SortOrder.fromPreference("future-mode"));
        assertEquals(MediaDirectoryOrder.SortOrder.NAME_DESC,
                MediaDirectoryOrder.SortOrder.fromPreference("NAME_DESC"));
    }

    @Test
    public void sortsNewestFirstWithStableNameTieBreak() {
        MediaEntry old = new MediaEntry("smb://nas/share/old.jpg", "old.jpg", false, 1, 100);
        MediaEntry z = new MediaEntry("smb://nas/share/z.jpg", "z.jpg", false, 1, 300);
        MediaEntry a = new MediaEntry("smb://nas/share/a.jpg", "a.jpg", false, 1, 300);
        MediaEntry newFolder = new MediaEntry("smb://nas/share/new/", "new", true, 0, 400);
        MediaEntry oldFolder = new MediaEntry("smb://nas/share/old/", "old", true, 0, 50);
        List<MediaEntry> entries = new ArrayList<>(
                Arrays.asList(newFolder, old, oldFolder, z, a));

        MediaDirectoryOrder.newestFirst(entries);

        assertEquals(Arrays.asList(a, z, old, newFolder, oldFolder), entries);
    }

    @Test
    public void findsSavedFilenameAfterOtherFilesAreAdded() {
        MediaEntry saved = new MediaEntry("smb://nas/share/favorite.png", "favorite.png",
                false, 1, 200);
        List<MediaEntry> entries = new ArrayList<>(Arrays.asList(
                new MediaEntry("smb://nas/share/new.png", "new.png", false, 1, 300),
                saved,
                new MediaEntry("smb://nas/share/older.png", "older.png", false, 1, 100)));
        MediaDirectoryOrder.newestFirst(entries);

        assertSame(saved, MediaDirectoryOrder.fileNamed(entries, "favorite.png"));
        assertNull(MediaDirectoryOrder.fileNamed(entries, "missing.png"));
    }
}
