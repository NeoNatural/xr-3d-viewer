package com.limelight.media;

import java.text.NumberFormat;

/** Decimal file sizes with a displayed value below 1000, including after rounding. */
public final class MediaFileSize {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};

    private MediaFileSize() { }

    public static String format(long bytes) {
        if (bytes < 0) return "—";
        double value = bytes;
        int unit = 0;
        while (value >= 1000 && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }
        // One decimal can round 999.95 to 1000.0: promote before formatting.
        if (unit > 0 && Math.round(value * 10) >= 10000 && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }
        NumberFormat number = NumberFormat.getNumberInstance();
        number.setGroupingUsed(false);
        number.setMaximumFractionDigits(unit == 0 ? 0 : 1);
        return number.format(value) + " " + UNITS[unit];
    }
}
