package com.limelight;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
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
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList locales = manager.getApplicationLocales();
            if (locales.isEmpty()) locales = manager.getSystemLocales();
            return supportedLanguage(locales.get(0));
        }
        String saved = PreferenceManager.getDefaultSharedPreferences(context).getString(LANGUAGE_KEY, null);
        if (saved != null) return saved;
        return supportedLanguage(Resources.getSystem().getConfiguration().locale);
    }

    static String supportedLanguage(Locale locale) {
        return locale != null && "zh".equals(locale.getLanguage()) ? "zh-CN" : "en";
    }

    static void migrateLanguagePreference(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String saved = prefs.getString(LANGUAGE_KEY, null);
        if (saved == null) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        if (manager.getApplicationLocales().isEmpty()) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(saved));
        }
        prefs.edit().remove(LANGUAGE_KEY).apply();
    }

    public void selectLanguage(String tag) {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString(LANGUAGE_KEY, tag).apply();
        }
        if (!tag.equals(attachedLanguage)) recreate();
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
