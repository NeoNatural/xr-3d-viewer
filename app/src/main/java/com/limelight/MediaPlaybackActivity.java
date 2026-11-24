package com.limelight;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.Window;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;
import com.limelight.preferences.PreferenceConfiguration;

import metavr.view.SurfaceControlExt;
import metavr.view.SurfaceViewExt;

/** Starts an OpenXR session or builds a Quest panel with a stereoscopic media surface. */
public abstract class MediaPlaybackActivity extends LocalizedActivity implements XrRenderer.InputListener {
    protected XrRenderer renderer;
    protected PreferenceConfiguration mediaPrefs;
    protected LinearLayout controls;
    protected LinearLayout layout;
    private FrameLayout stage;
    private SurfaceView video;
    private boolean stereo;
    private float displayAspect = 16f / 9f;

    private int separation, convergence;
    private String separationKey, convergenceKey, edgeKey;

    public static final String MODE_KEY = "playback_presentation";

    public static Intent playbackIntent(Context context, Class<? extends MediaPlaybackActivity> immersive,
                                        Class<? extends MediaPlaybackActivity> window) {
        String mode = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(MODE_KEY, "immersive");
        return new Intent(context, "window".equals(mode) ? window : immersive);
    }

    protected boolean windowed() { return false; }

    @Override public void onVrPointerMove(float u, float v) { }
    @Override public void onVrButton(int button, boolean down) { }
    @Override public void onVrScroll(int clicks) { }
    @Override public void onVrKey(int code) { }
    @Override public void onVrExit() { runOnUiThread(this::finish); }

    protected boolean openPlayback(int eyeWidth, int eyeHeight, String profile, boolean forceModel) {
        mediaPrefs = PreferenceConfiguration.readPreferences(this);
        if (forceModel) mediaPrefs.vrDepthMode = XrShared.DEPTH_MODE_MODEL;
        if (!windowed()) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            renderer = new XrRenderer();
            renderer.setInputListener(this);
            return true;
        }
        stereo = mediaPrefs.vrDepthMode != XrShared.DEPTH_MODE_OFF;
        video = new SurfaceView(this);
        try {
            if (metavr.os.Build.Version.getApiVersion() < 207) {
                showPlaybackError(R.string.window_sdk_required);
                return false;
            }
            SurfaceViewExt.setStereoComposition(video, stereo
                    ? SurfaceControlExt.STEREO_COMPOSITION_SIDE_BY_SIDE
                    : SurfaceControlExt.STEREO_COMPOSITION_MONO);
        } catch (LinkageError | RuntimeException unavailable) {
            LimeLog.warning("Quest stereo surface unavailable: " + unavailable);
            showPlaybackError(R.string.window_sdk_required);
            return false;
        }
        renderer = new XrRenderer(() -> runOnUiThread(() -> showPlaybackError(R.string.window_render_error)));
        separationKey = PreferenceConfiguration.VR_SEPARATION_PREF_STRING + profile;
        convergenceKey = PreferenceConfiguration.VR_CONVERGENCE_PREF_STRING + profile;
        SharedPreferences saved = PreferenceManager.getDefaultSharedPreferences(this);
        edgeKey = XrRenderer.EDGE_CORRECTION_PREF + profile;
        renderer.setPanelEdgeCorrection(saved.getBoolean(edgeKey, XrRenderer.DEFAULT_EDGE_CORRECTION));
        separation = saved.getInt(separationKey, mediaPrefs.vrStereoSeparation);
        convergence = saved.getInt(convergenceKey, mediaPrefs.vrConvergence);
        renderer.setPanelDepth(separation, convergence);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        stage.addView(video, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPanel());
        layout.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        layout.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        if (stereo) addControl(R.string.window_3d, this::showDepthControls);
        SurfaceHolder holder = video.getHolder();
        holder.setFormat(PixelFormat.RGBA_8888);
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                renderer.setPanelSurface(h.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                renderer.setPanelSurface(null);
            }
        });
        setPanelGeometry(eyeWidth, eyeHeight, eyeWidth / (float)eyeHeight);
        setContentView(layout);
        return true;
    }

    protected void showPlaybackError(int message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    protected void setPanelGeometry(int eyeWidth, int eyeHeight, float aspect) {
        if (eyeWidth <= 0 || eyeHeight <= 0 || !Float.isFinite(aspect) || aspect <= 0) return;
        // The packed buffer and the Android layout have different aspect ratios.
        video.getHolder().setFixedSize(stereo ? eyeWidth * 2 : eyeWidth, eyeHeight);
        displayAspect = aspect;
        fitPanel();
    }

    private void fitPanel() {
        int width = Math.min(stage.getWidth(), Math.round(stage.getHeight() * displayAspect));
        int height = Math.round(width / displayAspect);
        if (width <= 0 || height <= 0) return;
        ViewGroup.LayoutParams params = video.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            video.setLayoutParams(params);
        }
    }

    private void showDepthControls() {
        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        sliders.setPadding(pad, pad, pad, 0);
        addDepthSlider(sliders, true);
        addDepthSlider(sliders, false);
        SharedPreferences saved = PreferenceManager.getDefaultSharedPreferences(this);
        if (mediaPrefs.vrDepthMode == XrShared.DEPTH_MODE_MODEL) {
            CheckBox edge = new CheckBox(this);
            edge.setText(R.string.xr_edge_correction);
            edge.setChecked(saved.getBoolean(edgeKey, XrRenderer.DEFAULT_EDGE_CORRECTION));
            edge.setOnCheckedChangeListener((button, enabled) -> {
                renderer.setPanelEdgeCorrection(enabled);
                saved.edit().putBoolean(edgeKey, enabled).apply();
            });
            sliders.addView(edge);
        }
        new AlertDialog.Builder(this).setTitle(R.string.window_3d)
                .setView(sliders).setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.window_reset, (dialog, which) -> {
                    separation = PreferenceConfiguration.DEFAULT_VR_SEPARATION;
                    convergence = PreferenceConfiguration.DEFAULT_VR_CONVERGENCE;
                    renderer.setPanelEdgeCorrection(XrRenderer.DEFAULT_EDGE_CORRECTION);
                    saved.edit().putBoolean(edgeKey, XrRenderer.DEFAULT_EDGE_CORRECTION).apply();
                    renderer.setPanelDepth(separation, convergence);
                })
                .setOnDismissListener(dialog -> PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putInt(separationKey, separation)
                        .putInt(convergenceKey, convergence).apply())
                .show();
    }

    private void addDepthSlider(LinearLayout parent, boolean adjustSeparation) {
        TextView label = new TextView(this);
        SeekBar slider = new SeekBar(this);
        int title = adjustSeparation ? R.string.title_seekbar_vr_separation : R.string.window_convergence;
        slider.setContentDescription(getText(title));
        slider.setMax(adjustSeparation ? XrShared.COG_SEP_STEPS : 100);
        slider.setProgress(adjustSeparation ? separation : convergence);
        parent.addView(label);
        parent.addView(slider);
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (adjustSeparation) separation = value;
                else convergence = value;
                java.text.NumberFormat format = java.text.NumberFormat.getPercentInstance();
                format.setMaximumFractionDigits(1);
                String percent = format.format(adjustSeparation ? value / 1000.0 : value / 100.0);
                label.setText(getString(title) + "  " + percent);
                renderer.setPanelDepth(separation, convergence);
            }
        };
        slider.setOnSeekBarChangeListener(listener);
        listener.onProgressChanged(slider, slider.getProgress(), false);
    }

    protected Button addControl(int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(v -> action.run());
        controls.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }
}
