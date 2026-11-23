package com.limelight.web;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.limelight.EnglishActivity;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;
import com.limelight.preferences.PreferenceConfiguration;

import org.mozilla.geckoview.GeckoDisplay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Moves the existing fullscreen Gecko compositor onto the OpenXR external texture. */
public final class WebVideoXrActivity extends EnglishActivity implements XrRenderer.InputListener {
    static final String FULL_RESOLUTION = "fullResolution";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final XrRenderer xr = new XrRenderer();
    private WebVideoTab tab;
    private GeckoDisplay display;
    private boolean leaving;

    private final Runnable controls = new Runnable() {
        @Override public void run() {
            tab.updateControls(xr);
            main.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        tab = WebVideoTab.presenting(getIntent().getStringExtra(WebVideoTab.EXTRA_TAB));
        if (tab == null || !tab.fullscreen || tab.crashed) {
            fail();
            return;
        }
        tab.leaveXr = this::finish;
        WebVideoSize size = new WebVideoSize(tab.width, tab.height,
                getIntent().getBooleanExtra(FULL_RESOLUTION, false));
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        prefs.vrDepthMode = XrShared.DEPTH_MODE_MODEL;
        xr.setVideoControlsEnabled(true);
        xr.setInputListener(this);
        worker.execute(() -> {
            boolean ready = xr.start(this, size.eyeWidth, size.eyeHeight, size.inputWidth,
                    size.inputHeight, prefs);
            main.post(() -> {
                if (leaving || isDestroyed() || isFinishing()) return;
                if (!ready) fail();
                else attach(size);
            });
        });
    }

    private void attach(WebVideoSize size) {
        try {
            display = tab.session.acquireDisplay();
            display.surfaceChanged(new GeckoDisplay.SurfaceInfo.Builder(xr.getInputSurface())
                    .size(size.inputWidth, size.inputHeight)
                    .newSurfaceProvider(this::fail).build());
            tab.session.setActive(true);
            tab.session.setFocused(true);
            LimeLog.info("Web compositor " + size.inputWidth + "x" + size.inputHeight
                    + ", requested per eye " + size.eyeWidth + "x" + size.eyeHeight);
            main.post(controls);
        } catch (RuntimeException e) {
            LimeLog.warning("Gecko could not attach the XR surface: " + e);
            fail();
        }
    }

    private void fail() {
        if (!leaving) Toast.makeText(this, R.string.web_xr_failed, Toast.LENGTH_LONG).show();
        finish();
    }

    private void detach() {
        if (leaving) return;
        leaving = true;
        main.removeCallbacks(controls);
        if (tab != null) tab.leaveXr = null;
        if (display != null) {
            display.surfaceDestroyed();
            tab.session.releaseDisplay(display);
            display = null;
        }
    }

    private void stopRenderer() {
        if (worker.isShutdown()) return;
        detach();
        // A quick return and re-entry must not overlap two native OpenXR sessions.
        worker.execute(() -> {
            xr.prepareForStop();
            xr.cleanup();
            main.post(() -> {
                if (!isDestroyed()) WebVideoXrActivity.super.finish();
            });
        });
        worker.shutdown();
    }

    @Override public void finish() { stopRenderer(); }

    @Override protected void onDestroy() {
        stopRenderer();
        super.onDestroy();
    }

    @Override public void onVrVideoControl(int action, float value) {
        main.post(() -> {
            if (!leaving) tab.control(action, value);
        });
    }
    @Override public void onVrExit() { main.post(this::finish); }
    @Override public void onVrPointerMove(float u, float v) { }
    @Override public void onVrButton(int button, boolean down) { }
    @Override public void onVrScroll(int clicks) { }
    @Override public void onVrKey(int code) { }
}
