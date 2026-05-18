package com.example.audio_recorder.engine;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.example.audio_recorder.settings.AppSettings;
import com.nerio.audioengine.EqProfile;
import com.nerio.audioengine.MatrixPlayer;
import com.nerio.audioengine.SignalPathInfo;
import com.nerio.audioengine.UsbAudioDevice;
import com.nerio.audioengine.UsbAudioOutput;

/**
 * Plays back a single recording through the shared audio engine, mirroring
 * Matrix Player's quality path: parametric EQ optional, bit-perfect output to
 * an attached USB DAC, automatic fall back to AudioTrack (phone speaker) when
 * no DAC is present.
 *
 * <p>Constructed once per playback session by {@link
 * com.example.audio_recorder.service.RecorderService}. Releases its
 * {@link MatrixPlayer} (and any acquired {@link UsbAudioOutput}) on
 * {@link #stop()}. Recording and playback cannot run concurrently because they
 * share the same {@link UsbAudioDevice} handle.
 */
public class PlaybackEngine {

    private static final String TAG = "PlaybackEngine";

    public interface Listener {
        void onPrepared();
        void onCompletion();
        void onError(String message);
    }

    private final Context context;
    private final UsbAudioDevice device;
    private final AppSettings settings;
    private final MatrixPlayer player;
    private Listener listener;
    private boolean usbOutputActive;

    public PlaybackEngine(Context context, UsbAudioDevice device, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.settings = settings;
        this.player = new MatrixPlayer();

        // Apply EQ from saved settings; MatrixPlayer defers coefficient
        // computation until onPrepared fires and the sample rate is known.
        applyEqFromSettings();

        player.setOnPreparedListener(p -> {
            // Swap to the USB DAC if one is attached. Doing this in onPrepared
            // (rather than before play()) matches Matrix Player's tested
            // pattern — the engine will pause the AudioTrack output, release
            // it, configure UsbAudioOutput at the source format, and resume.
            switchToUsbIfAvailable();
            if (listener != null) listener.onPrepared();
        });
        player.setOnCompletionListener(p -> {
            if (listener != null) listener.onCompletion();
        });
        player.setOnErrorListener((p, msg) -> {
            Log.w(TAG, "playback error: " + msg);
            if (listener != null) listener.onError(msg);
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * Sets the software-gain volume (cube-root taper inside UsbAudioOutput).
     * Call this BEFORE {@link #play} so {@link com.nerio.audioengine.AudioEngine}'s
     * stored volume state is non-zero when {@code switchOutput()} fires its
     * {@code pushVolumeStateToOutput()} pass — otherwise the USB DAC sink
     * starts at gain 0 (silence) on the first track.
     */
    public void setVolume(float linear01) {
        player.getEngine().setVolume(linear01);
    }

    public void play(Uri uri) {
        if (uri == null) return;
        player.play(context, uri);
    }

    public void pause() {
        player.pause();
    }

    public void resume() {
        player.resume();
    }

    public void togglePlayPause() {
        player.togglePlayPause();
    }

    public void seekTo(int ms) {
        player.seekTo(ms);
    }

    public void stop() {
        try { player.stop(); } catch (Throwable t) { Log.w(TAG, "player.stop threw", t); }
        try { player.release(); } catch (Throwable t) { Log.w(TAG, "player.release threw", t); }
        // The USB output view is owned by UsbAudioDevice; releasing it here
        // would decrement the device's refcount and may close the device while
        // recording is about to start. Just stop the audio engine — the
        // service still owns the device.
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public int getCurrentPositionMs() {
        return player.getCurrentPosition();
    }

    public int getDurationMs() {
        return player.getDuration();
    }

    public boolean isUsbOutputActive() {
        return usbOutputActive;
    }

    public SignalPathInfo getSignalPathInfo() {
        return player.getSignalPathInfo();
    }

    /** Re-reads EQ settings and applies them to the active player. */
    public void applyEqFromSettings() {
        if (!settings.isEqEnabled()) {
            player.setEqProfile(null);
            return;
        }
        EqProfile profile = EqProfile.find(context,
                settings.getEqProfileName(),
                settings.getEqProfileSource(),
                settings.getEqProfileForm());
        player.setEqProfile(profile);
    }

    private void switchToUsbIfAvailable() {
        if (device == null || !device.isValid()) {
            usbOutputActive = false;
            return;
        }
        UsbAudioOutput usbOutput = device.getOutput();
        if (usbOutput == null) {
            usbOutputActive = false;
            return;
        }
        boolean ok = player.switchOutput(usbOutput);
        usbOutputActive = ok;
        if (!ok) {
            Log.w(TAG, "switchOutput to USB DAC failed; staying on AudioTrack");
        }
    }
}
