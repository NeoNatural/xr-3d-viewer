package com.limelight.media;

import java.util.Locale;

/** Playback timestamps; negative durations represent unavailable metadata. */
public final class MediaPlaybackTime {
    private MediaPlaybackTime() { }

    public static String format(long milliseconds) {
        if (milliseconds < 0) return "--:--";
        long seconds = milliseconds / 1000;
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public static String label(long positionMs, long durationMs) {
        return format(Math.max(0, positionMs)) + " / " + format(durationMs);
    }
}
