package com.limelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WindowVideoGeometryTest {
    private void assertEye(int w, int h, int limit, int expectedWidth, int expectedHeight) {
        VideoXrActivity.VideoGeometry eye = new VideoXrActivity.VideoGeometry(w, h).fitWithin(limit);
        assertEquals(expectedWidth, eye.width);
        assertEquals(expectedHeight, eye.height);
    }

    @Test public void outputLimitAppliesToEachEyeIndependentlyOfDecoderSize() {
        assertEye(3840, 2160, 1920, 1920, 1080);
        assertEye(2160, 3840, 2560, 1440, 2560);
        assertEye(3840, 2160, 3840, 3840, 2160);
        assertEye(1280, 720, 1920, 1280, 720);
    }
}
