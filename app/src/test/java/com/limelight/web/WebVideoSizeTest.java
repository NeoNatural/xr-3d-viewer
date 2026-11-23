package com.limelight.web;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebVideoSizeTest {
    @Test public void adaptiveStartupKeepsUhdCompositorInBothOutputModes() {
        WebVideoSize balanced = new WebVideoSize(640, 360, false);
        assertEquals(3840, balanced.inputWidth);
        assertEquals(2160, balanced.inputHeight);
        assertEquals(2560, balanced.eyeWidth);
        assertEquals(1440, balanced.eyeHeight);
        WebVideoSize full = new WebVideoSize(0, 0, true);
        assertEquals(3840, full.eyeWidth);
        assertEquals(2160, full.eyeHeight);
    }

    @Test public void portraitAndCinemaKeepTheirAspectAndPixelBudget() {
        WebVideoSize portrait = new WebVideoSize(1080, 1920, true);
        assertEquals(2160, portrait.inputWidth);
        assertEquals(3840, portrait.inputHeight);
        WebVideoSize cinema = new WebVideoSize(1920, 800, true);
        assertEquals(3840, cinema.inputWidth);
        assertEquals(1600, cinema.inputHeight);
    }
}
