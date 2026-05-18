package com.example.audio_recorder.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.audio_recorder.MainActivity;
import com.example.audio_recorder.R;
import com.example.audio_recorder.engine.PlaybackEngine;
import com.example.audio_recorder.engine.RecordingEngine;
import com.example.audio_recorder.engine.WavSink;
import com.example.audio_recorder.settings.AppSettings;
import com.nerio.audioengine.UsbAudioDevice;

import java.util.ArrayList;
import java.util.List;

public class RecorderService extends Service {

    private static final String TAG = "RecorderService";
    private static final String CHANNEL_ID = "recorder";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.example.audio_recorder.action.STOP_RECORDING";

    public enum State { IDLE, DEVICE_READY, RECORDING, PLAYING, ERROR }

    public interface StateListener {
        void onState(State state, @Nullable String error);
    }

    public class LocalBinder extends Binder {
        public RecorderService get() { return RecorderService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final List<StateListener> listeners = new ArrayList<>();

    private volatile UsbAudioDevice device;
    private volatile UsbDeviceConnection deviceConnection;
    private volatile UsbDevice usbDevice;
    private volatile RecordingEngine engine;
    private PlaybackEngine playbackEngine;
    private Uri currentPlaybackUri;

    private HandlerThread controlThread;
    private Handler controlHandler;
    private Handler mainHandler;
    private Handler tickHandler;
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateNotification(buildNotification(true));
            tickHandler.postDelayed(this, 1000);
        }
    };

    private volatile State state = State.IDLE;
    private long recordingStartElapsed;
    private Uri lastRecordingUri;
    private String lastError;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        controlThread = new HandlerThread("rec-control");
        controlThread.start();
        controlHandler = new Handler(controlThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        tickHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (state == State.RECORDING) {
            stopRecordingInternal();
        }
        if (state == State.PLAYING) {
            stopPlaybackInternal();
        }
        detachDevice();
        if (controlThread != null) {
            controlThread.quitSafely();
            controlThread = null;
        }
    }

    public State getState() {
        return state;
    }

    public long getElapsedMs() {
        if (state != State.RECORDING) return 0;
        return SystemClock.elapsedRealtime() - recordingStartElapsed;
    }

    public Uri getLastRecordingUri() {
        return lastRecordingUri;
    }

    @Nullable
    public String getLastError() {
        return lastError;
    }

    public UsbAudioDevice getDevice() {
        return device;
    }

    @Nullable
    public RecordingEngine getEngine() {
        return engine;
    }

    public void registerListener(StateListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unregisterListener(StateListener l) {
        listeners.remove(l);
    }

    public void attachDevice(UsbDevice usbDev, UsbDeviceConnection conn) {
        if (state == State.RECORDING) {
            Log.w(TAG, "attachDevice called while recording; ignoring");
            return;
        }
        if (state == State.PLAYING) {
            Log.w(TAG, "attachDevice called while playing; stopping playback first");
            stopPlaybackInternal();
        }
        detachDevice();
        if (conn == null) {
            transition(State.ERROR, "USB open failed");
            return;
        }
        int fd = conn.getFileDescriptor();
        UsbAudioDevice d = new UsbAudioDevice(fd);
        if (!d.isValid()) {
            d.close();
            try { conn.close(); } catch (Throwable ignored) {}
            transition(State.ERROR, "Engine could not open device");
            return;
        }
        if (!d.getInput().isAvailable()) {
            d.close();
            try { conn.close(); } catch (Throwable ignored) {}
            transition(State.ERROR, "Device has no capture-capable formats");
            return;
        }
        this.usbDevice = usbDev;
        this.deviceConnection = conn;
        this.device = d;
        transition(State.DEVICE_READY, null);
    }

    public void detachDevice() {
        if (state == State.RECORDING) {
            stopRecordingInternal();
        }
        if (state == State.PLAYING) {
            stopPlaybackInternal();
        }
        if (device != null) {
            try { device.close(); } catch (Throwable t) { Log.w(TAG, "device.close threw", t); }
            device = null;
        }
        if (deviceConnection != null) {
            try { deviceConnection.close(); } catch (Throwable ignored) {}
            deviceConnection = null;
        }
        usbDevice = null;
        if (state != State.IDLE) {
            transition(State.IDLE, null);
        }
    }

    public void startRecording(int rate, int channels, int bits, boolean monitor,
                               float monitorVolume) {
        if (state != State.DEVICE_READY) {
            Log.w(TAG, "startRecording in state " + state);
            return;
        }
        UsbAudioDevice d = device;
        UsbDeviceConnection conn = deviceConnection;
        if (d == null || !d.isValid() || conn == null) {
            Log.w(TAG, "startRecording: USB device no longer valid (device="
                    + (d != null) + " valid=" + (d != null && d.isValid())
                    + " conn=" + (conn != null) + ")");
            transition(State.ERROR, "USB device disconnected");
            if (d != null && d.isValid()) {
                transition(State.DEVICE_READY, null);
            } else {
                transition(State.IDLE, null);
            }
            return;
        }
        startForegroundSafely();
        controlHandler.post(() -> doStart(rate, channels, bits, monitor, monitorVolume));
    }

    public void stopRecording() {
        if (state != State.RECORDING) return;
        controlHandler.post(this::stopRecordingInternal);
    }

    public void setMonitorVolume(float linear01) {
        RecordingEngine e = engine;
        if (e == null) return;
        controlHandler.post(() -> e.setMonitorVolume(linear01));
    }

    // --- Playback ---

    public boolean startPlayback(Uri uri, float initialVolume,
                                 PlaybackEngine.Listener listener) {
        if (uri == null) return false;
        if (state == State.RECORDING) {
            Log.w(TAG, "startPlayback ignored: currently recording");
            return false;
        }
        if (state == State.PLAYING) {
            // Replace the active source with the new one.
            stopPlaybackInternal();
        }
        currentPlaybackUri = uri;
        AppSettings settings = new AppSettings(this);
        PlaybackEngine pe = new PlaybackEngine(this, device, settings);
        // Set volume BEFORE play() so AudioEngine.currentVolumeLinear is non-zero
        // when switchOutput()'s pushVolumeStateToOutput() runs — otherwise the
        // USB DAC starts at silence on the first track.
        pe.setVolume(initialVolume);
        pe.setListener(new PlaybackEngine.Listener() {
            @Override public void onPrepared() {
                if (listener != null) mainHandler.post(listener::onPrepared);
            }
            @Override public void onCompletion() {
                if (listener != null) mainHandler.post(listener::onCompletion);
                controlHandler.post(() -> {
                    if (state == State.PLAYING) stopPlaybackInternal();
                });
            }
            @Override public void onError(String message) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onError(message));
                }
                controlHandler.post(() -> {
                    if (state == State.PLAYING) stopPlaybackInternal();
                });
            }
        });
        playbackEngine = pe;
        controlHandler.post(() -> {
            try {
                pe.play(uri);
                transition(State.PLAYING, null);
            } catch (Throwable t) {
                Log.e(TAG, "playback start failed", t);
                playbackEngine = null;
                currentPlaybackUri = null;
                transition(device != null && device.isValid()
                        ? State.DEVICE_READY : State.IDLE, null);
            }
        });
        return true;
    }

    public void pausePlayback() {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(pe::pause);
    }

    public void resumePlayback() {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(pe::resume);
    }

    public void togglePlaybackPlayPause() {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(pe::togglePlayPause);
    }

    public void seekPlayback(int positionMs) {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(() -> pe.seekTo(positionMs));
    }

    public void stopPlayback() {
        if (state != State.PLAYING) return;
        controlHandler.post(this::stopPlaybackInternal);
    }

    public void reloadPlaybackEq() {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(pe::applyEqFromSettings);
    }

    public void setPlaybackVolume(float linear01) {
        PlaybackEngine pe = playbackEngine;
        if (pe == null) return;
        controlHandler.post(() -> pe.setVolume(linear01));
    }

    @Nullable
    public PlaybackEngine getPlaybackEngine() {
        return playbackEngine;
    }

    @Nullable
    public Uri getCurrentPlaybackUri() {
        return currentPlaybackUri;
    }

    public boolean isPlaybackPlaying() {
        PlaybackEngine pe = playbackEngine;
        return pe != null && pe.isPlaying();
    }

    public int getPlaybackPositionMs() {
        PlaybackEngine pe = playbackEngine;
        return pe != null ? pe.getCurrentPositionMs() : 0;
    }

    public int getPlaybackDurationMs() {
        PlaybackEngine pe = playbackEngine;
        return pe != null ? pe.getDurationMs() : 0;
    }

    private void stopPlaybackInternal() {
        PlaybackEngine pe = playbackEngine;
        playbackEngine = null;
        currentPlaybackUri = null;
        if (pe != null) {
            try { pe.stop(); } catch (Throwable t) { Log.w(TAG, "playback.stop threw", t); }
        }
        if (state == State.PLAYING) {
            transition(device != null && device.isValid()
                    ? State.DEVICE_READY : State.IDLE, null);
        }
    }

    private void doStart(int rate, int channels, int bits, boolean monitor,
                         float monitorVolume) {
        try {
            UsbAudioDevice d = device;
            if (d == null || !d.isValid()) {
                Log.w(TAG, "doStart: device became invalid between schedule and run");
                stopForegroundSafely();
                transition(State.ERROR, "USB device disconnected");
                transition(State.IDLE, null);
                return;
            }
            engine = new RecordingEngine(this, d, new WavSink(d.getInput()));
            if (!engine.start(rate, channels, bits, monitor, monitorVolume)) {
                engine = null;
                stopForegroundSafely();
                transition(State.ERROR, "Could not start capture");
                return;
            }
            recordingStartElapsed = SystemClock.elapsedRealtime();
            transition(State.RECORDING, null);
            mainHandler.post(() -> tickHandler.post(tickRunnable));
        } catch (Throwable t) {
            Log.e(TAG, "doStart failed", t);
            engine = null;
            stopForegroundSafely();
            transition(State.ERROR, t.getMessage() != null ? t.getMessage() : "start failed");
        }
    }

    private void stopRecordingInternal() {
        mainHandler.post(() -> tickHandler.removeCallbacks(tickRunnable));
        RecordingEngine e = engine;
        engine = null;
        long capturedFrames = 0;
        if (e != null) {
            try { e.stop(); } catch (Throwable t) { Log.w(TAG, "engine.stop threw", t); }
            lastRecordingUri = e.getOutputUri();
            capturedFrames = e.getCapturedFrames();
        }
        stopForegroundSafely();
        // Empty capture: surface the error toast (via ERROR), then immediately
        // restore DEVICE_READY/IDLE so the UI is usable for a retry. The two
        // notifications fire in order on the main thread, so the toast persists
        // while applyState rebuilds the recording UI.
        if (capturedFrames <= 0 && e != null) {
            transition(State.ERROR, "No audio captured — device did not produce data");
        }
        if (device != null && device.isValid()) {
            transition(State.DEVICE_READY, null);
        } else {
            transition(State.IDLE, null);
        }
    }

    private void startForegroundSafely() {
        Notification n = buildNotification(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateNotification(Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private Notification buildNotification(boolean showElapsed) {
        Intent contentIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = showElapsed
                ? getString(R.string.recording_notification_text, formatElapsed(getElapsedMs()))
                : getString(R.string.recording_notification_starting);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(R.drawable.ic_stop,
                        getString(R.string.recording_notification_action_stop), stopPi)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.recording_notification_channel_desc));
        nm.createNotificationChannel(ch);
    }

    private void transition(State next, @Nullable String error) {
        this.state = next;
        this.lastError = error;
        mainHandler.post(() -> {
            for (StateListener l : new ArrayList<>(listeners)) {
                try { l.onState(next, error); }
                catch (Throwable t) { Log.w(TAG, "listener threw", t); }
            }
        });
    }

    private static String formatElapsed(long ms) {
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
}
