package com.limelight.smb;

import android.content.Context;

/** Remembers a filename per SMB directory, independent of the current sort order. */
public final class SmbBrowseHistory {
    private SmbBrowseHistory() { }

    public static void record(Context context, String directory, String filename) {
        if (directory == null || filename == null) return;
        context.getSharedPreferences("smb_browse_history", Context.MODE_PRIVATE)
                .edit().putString(directory, filename).apply();
    }

    public static String lastFilename(Context context, String directory) {
        if (directory == null) return null;
        return context.getSharedPreferences("smb_browse_history", Context.MODE_PRIVATE)
                .getString(directory, null);
    }
}
