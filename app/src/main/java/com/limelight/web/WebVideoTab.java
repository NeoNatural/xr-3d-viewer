package com.limelight.web;

import android.content.Context;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;

import androidx.annotation.OptIn;

import org.mozilla.geckoview.ExperimentalGeckoViewApi;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaSession;

import java.util.HashMap;
import java.util.UUID;

/** One live tab, including audio and MSE state. All ownership changes occur on the UI thread. */
final class WebVideoTab implements MediaSession.Delegate, GeckoSession.ContentDelegate {
    static final String BILIBILI = "https://www.bilibili.com/";
    static final String YOUTUBE = "https://www.youtube.com/";
    static final String EXTRA_TAB = "web_video_tab";
    private static final HashMap<String, WebVideoTab> presentations = new HashMap<>();

    final GeckoSession session;
    final String id = UUID.randomUUID().toString();
    Runnable stateChanged;
    Runnable leaveXr;
    boolean fullscreen;
    boolean crashed;
    long width;
    long height;
    private MediaSession media;
    private boolean playing;
    private double position, rate = 1, duration;
    private long updatedAt;

    WebVideoTab(Context context) {
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build());
        session.setMediaSessionDelegate(this);
        session.setContentDelegate(this);
        GeckoRuntime runtime = GeckoRuntime.getDefault(context.getApplicationContext());
        runtime.getSettings().setLocales(new String[] { "en-US" });
        session.open(runtime);
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    void present() {
        // releaseSession() normally marks the page hidden, which can pause fullscreen video.
        session.getSessionPictureInPicture().setKeepActive(true);
        presentations.put(id, this);
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    void endPresentation() {
        session.getSessionPictureInPicture().setKeepActive(false);
        presentations.remove(id);
    }

    static WebVideoTab presenting(String id) { return presentations.get(id); }

    @Override public void onFullScreen(GeckoSession session, boolean enabled) {
        fullscreen = enabled;
        if (!enabled) {
            width = height = 0;
            if (leaveXr != null) leaveXr.run();
        }
        if (stateChanged != null) stateChanged.run();
    }

    @Override public void onCrash(GeckoSession session) {
        crashed = true;
        onFullScreen(session, false);
    }

    @Override public void onKill(GeckoSession session) { onCrash(session); }

    @Override public void onFullscreen(GeckoSession session, MediaSession media, boolean enabled,
                                       MediaSession.ElementMetadata meta) {
        if (enabled && meta != null) {
            width = meta.width;
            height = meta.height;
            duration = meta.duration;
            LimeLog.info("Web fullscreen media metadata " + width + "x" + height);
        }
    }

    @Override public void onActivated(GeckoSession session, MediaSession active) { media = active; }

    @Override public void onDeactivated(GeckoSession session, MediaSession inactive) {
        media = null;
        playing = false;
        position = duration = 0;
    }

    @Override public void onPlay(GeckoSession session, MediaSession media) {
        if (!playing) updatedAt = SystemClock.elapsedRealtime();
        playing = true;
    }

    @Override public void onPause(GeckoSession session, MediaSession media) {
        position = positionNow();
        playing = false;
    }

    @Override public void onStop(GeckoSession session, MediaSession media) {
        playing = false;
        position = 0;
    }

    @Override public void onPositionState(GeckoSession session, MediaSession media,
                                          MediaSession.PositionState state) {
        position = state.position;
        duration = state.duration;
        rate = state.playbackRate;
        updatedAt = SystemClock.elapsedRealtime();
    }

    private double positionNow() {
        double elapsed = playing ? (SystemClock.elapsedRealtime() - updatedAt) * rate / 1000 : 0;
        return Math.max(0, position + elapsed);
    }

    void updateControls(XrRenderer xr) {
        boolean seekable = Double.isFinite(duration) && duration > 0;
        double seconds = positionNow();
        xr.setVideoPlaybackState(playing, seekable ? (float)(seconds / duration) : 0,
                (long)(seconds * 1000), seekable ? (long)(duration * 1000) : -1);
    }

    void control(int action, float fraction) {
        if (media == null) return;
        switch (action) {
            case XrShared.VIDEO_CONTROL_TOGGLE:
                if (playing) media.pause(); else media.play();
                break;
            case XrShared.VIDEO_CONTROL_BACK: media.seekBackward(); break;
            case XrShared.VIDEO_CONTROL_FORWARD: media.seekForward(); break;
            case XrShared.VIDEO_CONTROL_NEXT: media.nextTrack(); break;
            case XrShared.VIDEO_CONTROL_PREVIOUS: media.previousTrack(); break;
            case XrShared.VIDEO_CONTROL_SEEK:
                if (Double.isFinite(duration) && duration > 0) {
                    media.seekTo(duration * Math.max(0, Math.min(1, fraction)), false);
                }
                break;
        }
    }
}
