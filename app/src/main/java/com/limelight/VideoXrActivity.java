package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.limelight.binding.video.XrRenderer;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.smb.SmbClientManager;
import com.limelight.smb.SmbDataSource;
import com.limelight.smb.SmbStorage;

import java.util.ArrayList;
import java.util.List;

/** Plays a local video through the existing OpenXR depth and stereo renderer. */
@OptIn(markerClass = UnstableApi.class)
public final class VideoXrActivity extends Activity implements XrRenderer.InputListener {
    public static final String EXTRA_VIDEO_URIS = "videoUris";
    public static final String EXTRA_SMB_VIDEO_URIS = "smbVideoUris";
    public static final String EXTRA_START_INDEX = "startIndex";
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    private static final long SEEK_STEP_MS = 10_000;
    // Keep network buffering well below Quest's 256 MiB Java heap. A time-only
    // target can otherwise consume the entire heap on high-bitrate 4K60 files.
    private static final int SMB_TARGET_BUFFER_BYTES = 48 * 1024 * 1024;

    private volatile boolean stopped;
    private Thread sourceThread;
    private XrRenderer renderer;
    private ExoPlayer player;
    private Surface videoSurface;
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable controlsTicker = new Runnable() {
        @Override public void run() {
            updateControlBar();
            if (!stopped) controlsHandler.postDelayed(this, 250);
        }
    };

    private static final class VideoGeometry {
        final int width;
        final int height;

        VideoGeometry(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        ArrayList<String> smbPlaylist = getIntent().getStringArrayListExtra(EXTRA_SMB_VIDEO_URIS);
        if (smbPlaylist != null && !smbPlaylist.isEmpty()) {
            int startIndex = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_START_INDEX, 0),
                    smbPlaylist.size() - 1));
            sourceThread = new Thread(() -> startSmbVideo(smbPlaylist, startIndex),
                    "SMB Video XR Source");
            sourceThread.start();
            return;
        }
        ArrayList<Uri> playlist = getIntent().getParcelableArrayListExtra(EXTRA_VIDEO_URIS);
        Uri uri = playlist == null || playlist.isEmpty() ? getIntent().getData() : playlist.get(0);
        if (uri == null) {
            Toast.makeText(this, "No video was selected.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ArrayList<Uri> selected = playlist == null ? new ArrayList<>() : playlist;
        if (selected.isEmpty()) selected.add(uri);
        int startIndex = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_START_INDEX, 0),
                selected.size() - 1));
        sourceThread = new Thread(() -> startVideo(selected, startIndex),
                "Local Video XR Source");
        sourceThread.start();
    }

    private void startSmbVideo(ArrayList<String> playlist, int startIndex) {
        long startedNs = System.nanoTime();
        SmbStorage storage = SmbClientManager.active();
        if (storage == null) {
            runOnUiThread(() -> {
                Toast.makeText(this, "The SMB session has ended. Reconnect to the NAS.",
                        Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }
        startRenderer(DEFAULT_WIDTH, DEFAULT_HEIGHT, xr -> runOnUiThread(() ->
                createSmbPlayer(playlist, startIndex, storage, xr.getInputSurface(),
                        startedNs)));
    }

    private void startVideo(ArrayList<Uri> playlist, int startIndex) {
        Uri uri = playlist.get(startIndex);
        long startedNs = System.nanoTime();
        VideoGeometry geometry = readGeometry(uri);
        if (stopped) return;

        startRenderer(geometry.width, geometry.height, xr -> runOnUiThread(() ->
                createPlayer(playlist, startIndex, xr.getInputSurface(), startedNs)));
    }

    private interface RendererReady {
        void run(XrRenderer renderer);
    }

    private void startRenderer(int width, int height, RendererReady ready) {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        // Video playback should not colour the screen edge or the room around it.
        prefs.vrAmbilight = false;
        prefs.vrRoomLight = false;
        XrRenderer xr = new XrRenderer();
        renderer = xr;
        xr.setVideoControlsEnabled(true);
        xr.setInputListener(this);
        if (!xr.start(this, width, height, prefs)) {
            LimeLog.severe("Video XR renderer failed to start");
            runOnUiThread(this::finish);
            return;
        }
        if (stopped) {
            xr.prepareForStop();
            xr.cleanup();
            return;
        }

        ready.run(xr);
    }

    private VideoGeometry readGeometry(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            int width = parsePositive(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), DEFAULT_WIDTH);
            int height = parsePositive(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), DEFAULT_HEIGHT);
            int rotation = parsePositive(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rotation == 90 || rotation == 270) {
                int oldWidth = width;
                width = height;
                height = oldWidth;
            }
            LimeLog.info("Local video metadata " + width + "x" + height
                    + ", rotation " + rotation);
            return new VideoGeometry(width, height);
        } catch (RuntimeException error) {
            LimeLog.warning("Local video metadata unavailable, using 1920x1080: " + error);
            return new VideoGeometry(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
    }

    private static int parsePositive(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void createPlayer(List<Uri> playlist, int startIndex, Surface surface, long startedNs) {
        ExoPlayer next = createConfiguredPlayer(surface, startedNs, "Local", false);
        if (next == null) return;
        ArrayList<MediaItem> items = new ArrayList<>(playlist.size());
        for (Uri uri : playlist) items.add(MediaItem.fromUri(uri));
        next.setMediaItems(items, startIndex, 0);
        startPlayer(next);
        LimeLog.info("Local Media3 playback started with " + items.size() + " item(s): "
                + playlist.get(0).getScheme());
    }

    private void createSmbPlayer(List<String> playlist, int startIndex, SmbStorage storage,
                                 Surface surface, long startedNs) {
        ExoPlayer next = createConfiguredPlayer(surface, startedNs, "SMB", true);
        if (next == null) return;
        ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                new SmbDataSource.Factory(storage));
        ArrayList<MediaSource> sources = new ArrayList<>(playlist.size());
        for (String uri : playlist) {
            sources.add(factory.createMediaSource(MediaItem.fromUri(uri)));
        }
        next.setMediaSources(sources, startIndex, 0);
        startPlayer(next);
        LimeLog.info("SMB Media3 streaming started with " + sources.size()
                + " item(s), selected " + startIndex);
    }

    private ExoPlayer createConfiguredPlayer(Surface surface, long startedNs, String sourceName,
                                              boolean networkSource) {
        if (stopped || isFinishing()) return null;
        videoSurface = surface;
        ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
        if (networkSource) {
            builder.setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 3_000, 5_000)
                    .setTargetBufferBytes(SMB_TARGET_BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build());
        }
        ExoPlayer next = builder.build();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        next.setVideoSurface(surface);
        next.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                LimeLog.info(sourceName + " video first frame rendered after "
                        + (System.nanoTime() - startedNs) / 1_000_000 + " ms");
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                LimeLog.info(sourceName + " video decoder output " + videoSize.width + "x"
                        + videoSize.height + ", pixel ratio " + videoSize.pixelWidthHeightRatio);
                XrRenderer xr = renderer;
                if (xr != null && videoSize.width > 0 && videoSize.height > 0
                        && videoSize.pixelWidthHeightRatio > 0.0f) {
                    xr.setVideoDisplayAspect(videoSize.height
                            / (videoSize.width * videoSize.pixelWidthHeightRatio));
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                LimeLog.severe(sourceName + " video playback failed: " + error.getErrorCodeName()
                        + ": " + error.getMessage());
                Toast.makeText(VideoXrActivity.this,
                        "Video playback failed: " + error.getErrorCodeName(), Toast.LENGTH_LONG)
                        .show();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    LimeLog.info(sourceName + " video buffering at "
                            + next.getCurrentPosition() + " ms");
                } else if (playbackState == Player.STATE_READY) {
                    LimeLog.info(sourceName + " video ready at "
                            + next.getCurrentPosition() + " ms");
                }
            }
        });
        return next;
    }

    private void startPlayer(ExoPlayer next) {
        next.prepare();
        next.play();
        controlsHandler.removeCallbacks(controlsTicker);
        controlsHandler.post(controlsTicker);
    }

    private void togglePlayback() {
        ExoPlayer current = player;
        if (current == null) return;
        if (current.isPlaying()) current.pause();
        else current.play();
        LimeLog.info("Local video " + (current.getPlayWhenReady() ? "playing" : "paused")
                + " at " + current.getCurrentPosition() + " ms");
    }

    private void seekBy(long deltaMs) {
        ExoPlayer current = player;
        if (current == null || !current.isCurrentMediaItemSeekable()) return;
        long target = Math.max(0, current.getCurrentPosition() + deltaMs);
        long duration = current.getDuration();
        if (duration != C.TIME_UNSET) target = Math.min(target, duration);
        current.seekTo(target);
    }

    private void seekToFraction(float fraction) {
        ExoPlayer current = player;
        if (current == null || !current.isCurrentMediaItemSeekable()) return;
        long duration = current.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return;
        current.seekTo((long)(Math.max(0.0f, Math.min(1.0f, fraction)) * duration));
    }

    private void updateControlBar() {
        ExoPlayer current = player;
        XrRenderer xr = renderer;
        if (current == null || xr == null) return;
        long duration = current.getDuration();
        float progress = duration == C.TIME_UNSET || duration <= 0 ? 0.0f
                : current.getCurrentPosition() / (float)duration;
        xr.setVideoPlaybackState(current.getPlayWhenReady(), progress);
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        controlsHandler.removeCallbacks(controlsTicker);
        if (sourceThread != null) sourceThread.interrupt();

        XrRenderer xr = renderer;
        if (xr != null) xr.prepareForStop();
        ExoPlayer current = player;
        if (current != null) {
            Surface surface = videoSurface;
            if (surface != null) current.clearVideoSurface(surface);
            current.release();
            player = null;
        }
        if (xr != null) {
            xr.cleanup();
            renderer = null;
        }
        videoSurface = null;
        super.onDestroy();
    }

    @Override public void onVrPointerMove(float u, float v) { }
    @Override public void onVrButton(int button, boolean down) { }
    @Override public void onVrScroll(int clicks) { }
    @Override public void onVrKey(int code) { }
    @Override public void onVrVideoControl(int action, float value) {
        runOnUiThread(() -> {
            if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_BACK) {
                seekBy(-SEEK_STEP_MS);
            } else if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_TOGGLE) {
                togglePlayback();
            } else if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_FORWARD) {
                seekBy(SEEK_STEP_MS);
            } else if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_SEEK) {
                seekToFraction(value);
            } else if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_PREVIOUS) {
                ExoPlayer current = player;
                if (current != null && current.hasPreviousMediaItem()) {
                    current.seekToPreviousMediaItem();
                }
            } else if (action == com.limelight.binding.video.XrShared.VIDEO_CONTROL_NEXT) {
                ExoPlayer current = player;
                if (current != null && current.hasNextMediaItem()) {
                    current.seekToNextMediaItem();
                }
            }
            updateControlBar();
        });
    }
    @Override public void onVrExit() { runOnUiThread(this::finish); }
}
