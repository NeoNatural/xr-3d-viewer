package com.limelight.web;

/** Preserve the fullscreen video's aspect even if adaptive playback is still on a lower rung. */
final class WebVideoSize {
    final int inputWidth;
    final int inputHeight;
    final int eyeWidth;
    final int eyeHeight;

    WebVideoSize(long mediaWidth, long mediaHeight, boolean fullResolution) {
        double aspect = mediaWidth > 0 && mediaHeight > 0
                ? (double) mediaWidth / mediaHeight : 16.0 / 9.0;
        // Bound both dimensions so square and portrait clips have a similar pixel budget.
        int maxWidth = aspect >= 1 ? 3840 : 2160;
        int maxHeight = aspect >= 1 ? 2160 : 3840;
        inputWidth = even(Math.min(maxWidth, maxHeight * aspect));
        inputHeight = even(Math.min(maxHeight, maxWidth / aspect));
        eyeWidth = fullResolution ? inputWidth : even(inputWidth * (2.0 / 3.0));
        eyeHeight = fullResolution ? inputHeight : even(inputHeight * (2.0 / 3.0));
    }

    private static int even(double n) {
        return Math.max(2, (int) Math.round(n / 2) * 2);
    }
}
