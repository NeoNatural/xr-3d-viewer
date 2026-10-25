package com.limelight.media;

import org.junit.Test;

import java.text.NumberFormat;
import java.util.Locale;

import static org.junit.Assert.*;

public class MediaFileSizeTest {
    @Test
    public void formatsDecimalUnitsAndRoundingBoundaries() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertEquals("0 B", MediaFileSize.format(0));
            assertEquals("999 B", MediaFileSize.format(999));
            assertEquals("1 KB", MediaFileSize.format(1000));
            assertEquals("1.5 KB", MediaFileSize.format(1500));
            assertEquals("999.9 KB", MediaFileSize.format(999949));
            assertEquals("1 MB", MediaFileSize.format(999950));
            assertEquals("1 GB", MediaFileSize.format(1_000_000_000L));
            assertEquals("1 TB", MediaFileSize.format(1_000_000_000_000L));
            assertEquals("—", MediaFileSize.format(-1));
            for (long bytes : new long[]{1, 999999, 999999999, 999999999999L,
                    999999999999999L, Long.MAX_VALUE}) {
                String value = MediaFileSize.format(bytes).split(" ")[0];
                assertTrue(NumberFormat.getInstance().parse(value).doubleValue() < 1000);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void usesTheCurrentLocaleForDecimals() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("1,5 MB", MediaFileSize.format(1_500_000));
        } finally {
            Locale.setDefault(original);
        }
    }
}
