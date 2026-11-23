package com.limelight;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.preference.PreferenceManager;

import java.util.Locale;

/** The same language follows the user across the library, panel and OpenXR activities. */
public abstract class LocalizedActivity extends Activity {
    public static final String LANGUAGE_KEY = "app_language";
    private String attachedLanguage;

    public static String language(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return !locales.isEmpty() && "zh".equals(locales.get(0).getLanguage()) ? "zh-CN" : "en";
        }
        return PreferenceManager.getDefaultSharedPreferences(context).getString(LANGUAGE_KEY, "en");
    }

    public void selectLanguage(String tag) {
        if (tag.equals(language(this))) return;
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString(LANGUAGE_KEY, tag).apply();
        }
        recreate();
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        attachedLanguage = language(base);
        Configuration override = new Configuration();
        override.setLocale(Locale.forLanguageTag(attachedLanguage));
        applyOverrideConfiguration(override);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!attachedLanguage.equals(language(this))) recreate();
    }
}
