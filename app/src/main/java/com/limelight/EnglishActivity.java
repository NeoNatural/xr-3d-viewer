package com.limelight;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Use English for app screens and the framework widgets they inflate on every supported API. */
public abstract class EnglishActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration override = new Configuration();
        override.setLocale(Locale.ENGLISH);
        applyOverrideConfiguration(override);
    }
}
