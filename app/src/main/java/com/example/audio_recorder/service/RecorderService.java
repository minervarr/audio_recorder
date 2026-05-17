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
import com.example.audio_recorder.engine.RecordingEngine;
import com.example.audio_recorder.engine.WavSink;
import com.nerio.audioengine.UsbAudioDevice;

import java.util.ArrayList;
import java.util.List;

public class RecorderService extends Service {

    private static final String TAG = "RecorderService";
    private static final String CHANNEL_ID = "recorder";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.example.audio_recorder.action.STOP_RECORDING";

    public enum State { IDLE, DEVICE_READY, RECORDING, ERROR }

    public interface StateListener {
        void onState(State state, @Nullable String error);
    }

    public class LocalBinder extends Binder {
        public RecorderService get() { return RecorderService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final List<StateListener> listeners = new ArrayList<>();

    private UsbAudioDevice device;
    private UsbDeviceConnection deviceConnection;
    private UsbDevice usbDevice;
    private RecordingEngine engine;

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
        if (device == null) return;
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

    private void doStart(int rate, int channels, int bits, boolean monitor,
                         float monitorVolume) {
        try {
            engine = new RecordingEngine(this, device, new WavSink(device.getInput()));
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
        if (e != null) {
            try { e.stop(); } catch (Throwable t) { Log.w(TAG, "engine.stop threw", t); }
            lastRecordingUri = e.getOutputUri();
        }
        stopForegroundSafely();
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
