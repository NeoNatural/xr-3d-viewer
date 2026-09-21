package com.limelight.smb;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmbClientManagerTest {
    @Test
    public void recognizesSupportedVideoContainersCaseInsensitively() {
        assertTrue(SmbClientManager.isVideo("movie.MP4"));
        assertTrue(SmbClientManager.isVideo("movie.mkv"));
        assertTrue(SmbClientManager.isVideo("movie.webm"));
        assertTrue(SmbClientManager.isVideo("movie.m4v"));
        assertTrue(SmbClientManager.isVideo("movie.mov"));
        assertFalse(SmbClientManager.isVideo("movie.mp4.txt"));
        assertFalse(SmbClientManager.isVideo("image.jpg"));
    }
}
