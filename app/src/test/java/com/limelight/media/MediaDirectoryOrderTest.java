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
    public void sortsNewestFirstWithStableNameTieBreak() {
        MediaEntry old = new MediaEntry("smb://nas/share/old.jpg", "old.jpg", false, 1, 100);
        MediaEntry z = new MediaEntry("smb://nas/share/z.jpg", "z.jpg", false, 1, 300);
        MediaEntry a = new MediaEntry("smb://nas/share/a.jpg", "a.jpg", false, 1, 300);
        List<MediaEntry> entries = new ArrayList<>(Arrays.asList(old, z, a));

        MediaDirectoryOrder.newestFirst(entries);

        assertEquals(Arrays.asList(a, z, old), entries);
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
