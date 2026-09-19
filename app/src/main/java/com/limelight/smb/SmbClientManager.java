package com.limelight.smb;

import com.limelight.LimeLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.limelight.media.MediaEntry;

/** Owns the active SMB context while the browser and XR viewer hand off media. */
public final class SmbClientManager {
    private static SmbStorage active;
    private static String snapshotDirectory;
    private static List<MediaEntry> snapshotImages = Collections.emptyList();

    private SmbClientManager() { }

    public static synchronized SmbStorage active() {
        return active;
    }

    public static synchronized void replace(SmbStorage next) {
        SmbImageByteCache.clear();
        SmbStorage old = active;
        active = next;
        snapshotDirectory = null;
        snapshotImages = Collections.emptyList();
        if (old != null && old != next) {
            try {
                old.close();
            } catch (IOException e) {
                LimeLog.warning("Old SMB context close failed: " + e.getMessage());
            }
        }
    }

    public static synchronized void setDirectorySnapshot(String directory, List<MediaEntry> entries) {
        ArrayList<MediaEntry> images = new ArrayList<>();
        for (MediaEntry entry : entries) {
            String name = entry.name.toLowerCase(java.util.Locale.ROOT);
            if (!entry.directory && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                images.add(entry);
            }
        }
        snapshotDirectory = directory;
        snapshotImages = Collections.unmodifiableList(images);
    }

    public static synchronized List<MediaEntry> imageSnapshot(String directory) {
        return directory != null && directory.equals(snapshotDirectory)
                ? snapshotImages : Collections.emptyList();
    }
}
