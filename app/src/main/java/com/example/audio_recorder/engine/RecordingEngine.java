package com.example.audio_recorder.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.nerio.audioengine.UsbAudioDevice;
import com.nerio.audioengine.UsbAudioInput;
import com.nerio.audioengine.UsbAudioOutput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingEngine {

    private static final String TAG = "RecordingEngine";

    private final Context context;
    private final UsbAudioDevice device;
    private final EncodingSink sink;

    private File tempFile;
    private Uri outputUri;
    private boolean monitoring;
    private long startMs;

    public RecordingEngine(Context context, UsbAudioDevice device, EncodingSink sink) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.sink = sink;
    }

    public boolean start(int rate, int channels, int bits, boolean monitor, float monitorVolume)
            throws IOException {
        if (device == null || !device.isValid()) {
            Log.e(TAG, "start: invalid device");
            return false;
        }
        UsbAudioInput input = device.getInput();
        if (!input.configure(rate, channels, bits)) {
            Log.e(TAG, "input.configure(" + rate + ", " + channels + ", " + bits + ") failed");
            return false;
        }
        if (!input.start()) {
            Log.e(TAG, "input.start() failed");
            return false;
        }
        // The device may negotiate a different actual format than requested
        // (e.g. 24-bit instead of 32-bit). Read the negotiated values back and
        // use them for the monitor output and WAV header — otherwise the
        // monitor write path interprets bytes at the wrong sample width.
        int actualRate = input.getConfiguredRate();
        int actualChannels = input.getConfiguredChannels();
        int actualBits = input.getConfiguredBitDepth();
        if (monitor) {
            UsbAudioOutput output = device.getOutput();
            int encoding = bitsToEncoding(actualBits);
            if (!output.configure(actualRate, actualChannels, encoding, actualBits)
                    || !output.start()) {
                Log.w(TAG, "monitor output failed; recording without monitor");
                monitoring = false;
            } else {
                int outRate = output.getConfiguredRate();
                int outChannels = output.getConfiguredChannels();
                int outBits = output.getConfiguredBitDepth();
                int outSubslot = output.getConfiguredSubslotSize();
                int inSubslot = input.getConfiguredSubslotSize();
                Log.i(TAG, "monitor formats: input="
                        + actualRate + "Hz/" + actualBits + "b/"
                        + actualChannels + "ch subslot=" + inSubslot
                        + " output="
                        + outRate + "Hz/" + outBits + "b/"
                        + outChannels + "ch subslot=" + outSubslot);
                if (outRate != actualRate || outChannels != actualChannels) {
                    Log.w(TAG, "monitor rate/channels mismatch — expect audio artifacts");
                }
                // UsbAudioOutput defaults currentVolumeLinear to 0f (silence).
                // Apply the user's chosen monitor volume before opening the tap.
                output.setVolume(monitorVolume);
                input.setMonitorOutput(output);
                monitoring = true;
            }
        }
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        tempFile = new File(context.getCacheDir(), "rec-" + ts + "." + sink.fileExtension());
        if (!sink.beginRecording(tempFile, actualRate, actualChannels, actualBits)) {
            Log.e(TAG, "sink.beginRecording failed");
            input.stop();
            if (monitoring) device.getOutput().stop();
            return false;
        }
        startMs = System.currentTimeMillis();
        return true;
    }

    public void stop() {
        if (tempFile == null) return;
        UsbAudioInput input = device.getInput();
        // Detach the monitor tap before draining the record loop so the drain
        // doesn't keep writing into an output we're about to stop.
        if (monitoring) {
            try { input.setMonitorOutput(null); } catch (Throwable ignored) {}
        }
        try {
            sink.endRecording();
        } catch (Throwable t) {
            Log.w(TAG, "sink.endRecording threw", t);
        }
        try {
            input.stop();
        } catch (Throwable t) {
            Log.w(TAG, "input.stop threw", t);
        }
        if (monitoring) {
            try {
                device.getOutput().stop();
            } catch (Throwable t) {
                Log.w(TAG, "output.stop threw", t);
            }
            monitoring = false;
        }
        try {
            outputUri = promoteToMediaStore(tempFile);
        } catch (IOException e) {
            Log.e(TAG, "promoteToMediaStore failed", e);
            outputUri = null;
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "temp file not deleted: " + tempFile);
            }
            tempFile = null;
        }
    }

    public void setMonitorVolume(float linear01) {
        if (!monitoring) return;
        try {
            device.getOutput().setVolume(linear01);
        } catch (Throwable t) {
            Log.w(TAG, "live monitor setVolume failed", t);
        }
    }

    public Uri getOutputUri() {
        return outputUri;
    }

    public long getStartTimeMs() {
        return startMs;
    }

    private Uri promoteToMediaStore(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return promoteWithMediaStoreInsert(source);
        } else {
            return promoteToPublicMusic(source);
        }
    }

    private Uri promoteWithMediaStoreInsert(File source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, source.getName());
        v.put(MediaStore.Audio.Media.MIME_TYPE, sink.mimeType());
        v.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/Recordings/");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Audio.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, v);
        if (item == null) throw new IOException("MediaStore insert returned null");

        try (OutputStream os = resolver.openOutputStream(item);
             InputStream is = new FileInputStream(source)) {
            if (os == null) throw new IOException("openOutputStream returned null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(item, v, null, null);
        return item;
    }

    private Uri promoteToPublicMusic(File source) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        File dest = new File(dir, source.getName());
        try (InputStream is = new FileInputStream(source);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        Uri[] resultUri = new Uri[1];
        MediaScannerConnection.scanFile(context,
                new String[]{dest.getAbsolutePath()},
                new String[]{sink.mimeType()},
                (path, uri) -> resultUri[0] = uri);
        return Uri.fromFile(dest);
    }

    private static int bitsToEncoding(int bits) {
        switch (bits) {
            case 16: return AudioFormat.ENCODING_PCM_16BIT;
            case 24: return AudioFormat.ENCODING_PCM_24BIT_PACKED;
            case 32: return AudioFormat.ENCODING_PCM_32BIT;
            default: return AudioFormat.ENCODING_PCM_16BIT;
        }
    }
}
