package com.limelight.preferences;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.ListPreference;
import com.limelight.MediaPlaybackActivity;

import com.limelight.LocalizedActivity;
import com.limelight.BugReportActivity;
import com.limelight.LegalNoticesActivity;
import com.limelight.R;
import com.limelight.utils.UiHelper;

/** Settings used by standalone image and video playback. */
public final class StreamSettings extends LocalizedActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            LocalizedActivity activity = (LocalizedActivity) getActivity();
            ListPreference language = (ListPreference) findPreference(LocalizedActivity.LANGUAGE_KEY);
            language.setValue(LocalizedActivity.language(activity));
            language.setOnPreferenceChangeListener((pref, value) -> {
                activity.selectLanguage((String) value);
                return true;
            });
            ListPreference mode = (ListPreference) findPreference(MediaPlaybackActivity.MODE_KEY);
            updatePresentationSettings(mode.getValue());
            mode.setOnPreferenceChangeListener((pref, value) -> {
                updatePresentationSettings((String) value);
                return true;
            });
            findPreference("pref_bug_report").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), BugReportActivity.class));
                return true;
            });
            findPreference("pref_legal_notices").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), LegalNoticesActivity.class));
                return true;
            });
        }

        private void updatePresentationSettings(String mode) {
            boolean window = "window".equals(mode);
            findPreference(getString(R.string.window_resolution_key)).setEnabled(window);
            for (String key : new String[]{"checkbox_vr_pointer", "checkbox_vr_gaze",
                    "checkbox_vr_hand_tracking", "checkbox_vr_passthrough", "list_vr_env_res",
                    "checkbox_enable_perf_overlay"}) {
                findPreference(key).setEnabled(!window);
            }
        }
    }
}
