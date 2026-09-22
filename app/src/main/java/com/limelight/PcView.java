package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.limelight.local.LocalBrowserActivity;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.smb.SmbBrowserActivity;
import com.limelight.utils.UiHelper;
import com.limelight.utils.WarningDialog;

/** Media-first launcher. The former GameStream host grid no longer participates in startup. */
public final class PcView extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.activity_pc_view);
        UiHelper.notifyNewRootView(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        findViewById(R.id.browseLocal).setOnClickListener(v ->
                startActivity(new Intent(this, LocalBrowserActivity.class)));
        findViewById(R.id.browseSmb).setOnClickListener(v ->
                startActivity(new Intent(this, SmbBrowserActivity.class)));
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, StreamSettings.class)));

        if (PreferenceConfiguration.isXr2Gen1Headset()
                && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                PreferenceConfiguration.GEN1_PROFILE_PREF_STRING, false)) {
            WarningDialog.showIfNeeded(this, "gen1_perf_profile",
                    getString(R.string.gen1_warning_title),
                    getString(R.string.gen1_warning_text));
        }
    }
}
