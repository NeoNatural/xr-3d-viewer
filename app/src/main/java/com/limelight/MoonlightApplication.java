package com.limelight;

import android.app.Application;
import android.preference.PreferenceManager;

import com.limelight.binding.video.DepthSelfTest;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Runs before anything else does, to start the file log and to settle the
 * headset performance profile. The log setting is read straight out of shared
 * preferences rather than through PreferenceConfiguration, since that pulls in
 * display and codec lookups this early in startup.
 */
public class MoonlightApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Gecko starts isolated renderer and media services with the same Application.
        // Those processes must not open the player's log or its preferences.
        if (android.os.Build.VERSION.SDK_INT >= 28 && !getPackageName().equals(getProcessName())) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && android.os.Build.VERSION.SDK_INT < 28
                && !isLegacyMainProcess()) return;

        String setting = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PreferenceConfiguration.FILE_LOG_PREF_STRING,
                           PreferenceConfiguration.DEFAULT_FILE_LOG);
        FileLog.init(this, FileLog.levelFromName(setting));

        // Has to happen before any activity applies the xml defaults
        PreferenceConfiguration.migrateDepthRate(this);
        PreferenceConfiguration.seedGen1PerfProfile(this);
        PreferenceConfiguration.migrateDepthSource(this);

        // Debug builds only, and nothing unless its property is set
        DepthSelfTest.startIfAsked(this);
    }

    private boolean isLegacyMainProcess() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] expected = getPackageName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte b : expected) {
                if (in.read() != (b & 255)) return false;
            }
            return in.read() == 0;
        } catch (java.io.IOException ignored) {
            return false;
        }
    }
}
