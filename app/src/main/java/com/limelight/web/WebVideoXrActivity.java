package com.limelight.web;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Toast;

import com.limelight.MediaPlaybackActivity;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.XrShared;

import org.mozilla.geckoview.GeckoDisplay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Moves the existing fullscreen Gecko compositor onto the selected player’s external texture. */
public class WebVideoXrActivity extends MediaPlaybackActivity {
    static final String FULL_RESOLUTION = "fullResolution";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebVideoTab tab;
    private GeckoDisplay display;
    private boolean leaving;
    private Button play;
    private SeekBar seek;
    private boolean dragging;

    private final Runnable controls = new Runnable() {
        @Override public void run() {
            if (windowed()) {
                play.setText(tab.isPlaying() ? R.string.window_pause : R.string.window_play);
                seek.setEnabled(tab.canSeek());
                if (!dragging) seek.setProgress(Math.round(tab.progress() * 1000));
            } else tab.updateControls(renderer);
            main.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        tab = WebVideoTab.presenting(getIntent().getStringExtra(WebVideoTab.EXTRA_TAB));
        if (tab == null || !tab.fullscreen || tab.crashed) {
            fail();
            return;
        }
        tab.leaveXr = this::finish;
        WebVideoSize size = new WebVideoSize(tab.width, tab.height,
                getIntent().getBooleanExtra(FULL_RESOLUTION, false));
        if (!openPlayback(size.eyeWidth, size.eyeHeight, ".video", true)) return;
        if (windowed()) {
            addControl(R.string.window_rewind, () -> tab.control(XrShared.VIDEO_CONTROL_BACK, 0));
            play = addControl(R.string.window_play, () -> tab.control(XrShared.VIDEO_CONTROL_TOGGLE, 0));
            addControl(R.string.window_forward, () -> tab.control(XrShared.VIDEO_CONTROL_FORWARD, 0));
            addControl(R.string.title_settings_back, this::finish);
            seek = new SeekBar(this);
            seek.setContentDescription(getString(R.string.window_position));
            seek.setMax(1000);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
                public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    tab.control(XrShared.VIDEO_CONTROL_SEEK, bar.getProgress() / 1000f);
                }
                public void onProgressChanged(SeekBar bar, int value, boolean user) {
                    if (user && !dragging) tab.control(XrShared.VIDEO_CONTROL_SEEK, value / 1000f);
                }
            });
            layout.addView(seek, 1);
        }
        renderer.setVideoControlsEnabled(true);
        renderer.setInputListener(this);
        worker.execute(() -> {
            boolean ready = renderer.start(this, size.eyeWidth, size.eyeHeight, size.inputWidth,
                    size.inputHeight, mediaPrefs);
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
            display.surfaceChanged(new GeckoDisplay.SurfaceInfo.Builder(renderer.getInputSurface())
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
            if (renderer != null) {
                renderer.prepareForStop();
                renderer.cleanup();
            }
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
