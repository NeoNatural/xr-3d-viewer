package com.limelight;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.utils.UiHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Offline copy of the notices that accompany every source and binary release. */
public final class LegalNoticesActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiHelper.setLocale(this);

        TextView notice = new TextView(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        notice.setPadding(padding, padding, padding, padding);
        notice.setTextSize(14);
        notice.setTextIsSelectable(true);
        notice.setMovementMethod(LinkMovementMethod.getInstance());
        notice.setText(readNotices());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(notice);
        setContentView(scroll);
        UiHelper.notifyNewRootView(this);
    }

    private String readNotices() {
        try (InputStream in = getAssets().open("legal/THIRD_PARTY_NOTICES.md");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return getString(R.string.legal_notices_unavailable, e.getMessage());
        }
    }
}
