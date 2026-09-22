package com.limelight.preferences;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceFragment;

import com.limelight.BugReportActivity;
import com.limelight.R;
import com.limelight.utils.UiHelper;

/** Settings used by standalone image and video playback. */
public final class StreamSettings extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_stream_settings);
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());
        getFragmentManager().beginTransaction()
                .replace(R.id.stream_settings, new MediaSettingsFragment())
                .commitAllowingStateLoss();
        UiHelper.notifyNewRootView(this);
    }

    public static final class MediaSettingsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            addPreferencesFromResource(R.xml.preferences);
            findPreference("pref_bug_report").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), BugReportActivity.class));
                return true;
            });
        }
    }
}
