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
    private static List<MediaEntry> snapshotVideos = Collections.emptyList();

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
        snapshotVideos = Collections.emptyList();
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
        ArrayList<MediaEntry> videos = new ArrayList<>();
        for (MediaEntry entry : entries) {
            String name = entry.name.toLowerCase(java.util.Locale.ROOT);
            if (!entry.directory && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                images.add(entry);
            } else if (!entry.directory && isVideo(name)) {
                videos.add(entry);
            }
        }
        snapshotDirectory = directory;
        snapshotImages = Collections.unmodifiableList(images);
        snapshotVideos = Collections.unmodifiableList(videos);
    }

    public static synchronized List<MediaEntry> imageSnapshot(String directory) {
        return directory != null && directory.equals(snapshotDirectory)
                ? snapshotImages : Collections.emptyList();
    }

    public static synchronized List<MediaEntry> videoSnapshot(String directory) {
        return directory != null && directory.equals(snapshotDirectory)
                ? snapshotVideos : Collections.emptyList();
    }

    public static boolean isVideo(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".m4v")
                || lower.endsWith(".mov");
    }
}
