package com.limelight.media;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MediaPlaybackTimeTest {
    @Test
    public void formatsSecondsAndTheHourBoundaryWithoutRoundingAhead() {
        assertEquals("00:00", MediaPlaybackTime.format(0));
        assertEquals("00:59", MediaPlaybackTime.format(59999));
        assertEquals("01:00", MediaPlaybackTime.format(60000));
        assertEquals("59:59", MediaPlaybackTime.format(3599999));
        assertEquals("1:00:00", MediaPlaybackTime.format(3600000));
        assertEquals("1:02:03", MediaPlaybackTime.format(3723000));
        assertEquals("27:02:03", MediaPlaybackTime.format(97323000));
    }

    @Test
    public void unknownDurationAndChangingPlaylistsHaveClearLabels() {
        assertEquals("00:00 / --:--", MediaPlaybackTime.label(-1, -9223372036854775807L));
        assertEquals("02:15 / 45:00", MediaPlaybackTime.label(135000, 2700000));
        assertEquals("02:15 / 1:30:00", MediaPlaybackTime.label(135000, 5400000));
        assertEquals("00:00 / 05:00", MediaPlaybackTime.label(0, 300000));
    }
}
