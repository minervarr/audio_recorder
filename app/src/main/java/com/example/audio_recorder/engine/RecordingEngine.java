package com.example.audio_recorder.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
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
    private long lastSessionFrames;

    // Negotiated format snapshot, populated in start() and exposed to the UI
    // for the on-screen diagnostic + monitor-mismatch handling.
    private volatile int inRate, inCh, inBits, inSubslot;
    private volatile int outRate, outCh, outBits, outSubslot;
    private volatile boolean monitorMismatch;

    public RecordingEngine(Context context, UsbAudioDevice device, EncodingSink sink) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.sink = sink;
    }

    // Stuck-ADC recovery: after start, wait briefly for the first PCM packet to
    // surface in the ring buffer. Some UAC2 devices accept SET_CUR but don't
    // actually arm the ADC; the warm-up in usb_audio.cpp catches most cases,
    // but if it doesn't, we tear down and re-arm once. See the project plan
    // (when-i-press-recording-shimmying-globe).
    private static final long FIRST_PACKET_DEADLINE_MS = 500;
    private static final long FIRST_PACKET_POLL_MS = 10;

    public boolean start(int rate, int channels, int bits, boolean monitor, float monitorVolume)
            throws IOException {
        if (device == null || !device.isValid()) {
            Log.e(TAG, "start: invalid device");
            return false;
        }
        lastSessionFrames = 0;
        if (!attemptStart(rate, channels, bits, monitor, monitorVolume)) {
            return false;
        }
        if (waitForFirstPacket(FIRST_PACKET_DEADLINE_MS)) {
            startMs = System.currentTimeMillis();
            return true;
        }
        Log.w(TAG, "start: first packet never arrived within "
                + FIRST_PACKET_DEADLINE_MS + " ms; tearing down and retrying once");
        teardownAttempt();
        if (!attemptStart(rate, channels, bits, monitor, monitorVolume)) {
            Log.e(TAG, "start: retry setup failed");
            return false;
        }
        if (waitForFirstPacket(FIRST_PACKET_DEADLINE_MS)) {
            Log.i(TAG, "start: retry succeeded — first packet arrived");
            startMs = System.currentTimeMillis();
            return true;
        }
        Log.e(TAG, "start: retry also failed; device is not producing data");
        teardownAttempt();
        return false;
    }

    private boolean attemptStart(int rate, int channels, int bits, boolean monitor,
                                 float monitorVolume) throws IOException {
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
        inRate = actualRate;
        inCh = actualChannels;
        inBits = actualBits;
        inSubslot = input.getConfiguredSubslotSize();
        outRate = outCh = outBits = outSubslot = 0;
        monitorMismatch = false;
        if (monitor) {
            UsbAudioOutput output = device.getOutput();
            int encoding = bitsToEncoding(actualBits);
            // Asymmetric dongles (mic 1ch / headphones 2ch is the common case)
            // need a monitor output configured at *its* native channel count,
            // not the input's — otherwise output.configure fails or the C++
            // relax-match silently picks a wrong alt-setting. The record loop
            // will upmix mono → stereo on the way to the monitor.
            int monitorChannels = pickMonitorChannelCount(output, actualChannels);
            if (monitorChannels <= 0) {
                Log.w(TAG, "monitor output has no channel count >= input ("
                        + actualChannels + "ch); disabling monitor");
                monitoring = false;
            } else if (!output.configure(actualRate, monitorChannels, encoding, actualBits)
                    || !output.start()) {
                Log.w(TAG, "monitor output failed; recording without monitor");
                monitoring = false;
            } else {
                outRate = output.getConfiguredRate();
                outCh = output.getConfiguredChannels();
                outBits = output.getConfiguredBitDepth();
                outSubslot = output.getConfiguredSubslotSize();
                Log.i(TAG, "monitor formats: input="
                        + actualRate + "Hz/" + actualBits + "b/"
                        + actualChannels + "ch subslot=" + inSubslot
                        + " output="
                        + outRate + "Hz/" + outBits + "b/"
                        + outCh + "ch subslot=" + outSubslot
                        + (outCh > actualChannels ? " (upmix " + actualChannels
                                + "→" + outCh + ")" : ""));
                // Rate mismatch is the only truly broken case — we can't
                // resample. Channel asymmetry is handled by the record loop's
                // upmix, and bit-depth/subslot asymmetry is handled by the C++
                // writeIntXX downshift branch.
                if (outRate != actualRate) {
                    Log.w(TAG, "monitor rate mismatch — disabling monitor");
                    monitorMismatch = true;
                    try { output.stop(); } catch (Throwable ignored) {}
                    monitoring = false;
                } else {
                    // UsbAudioOutput defaults currentVolumeLinear to 0f (silence).
                    // Apply the user's chosen monitor volume before opening the tap.
                    output.setVolume(monitorVolume);
                    input.setMonitorOutput(output, outCh);
                    monitoring = true;
                }
            }
        }
        if (tempFile == null) {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            tempFile = new File(context.getCacheDir(), "rec-" + ts + "." + sink.fileExtension());
        }
        if (!sink.beginRecording(tempFile, actualRate, actualChannels, actualBits)) {
            Log.e(TAG, "sink.beginRecording failed");
            input.stop();
            if (monitoring) device.getOutput().stop();
            return false;
        }
        return true;
    }

    private boolean waitForFirstPacket(long deadlineMs) {
        UsbAudioInput input = device.getInput();
        if (input == null) return false;
        long deadline = SystemClock.elapsedRealtime() + deadlineMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.getFramesWritten() > 0) return true;
            try {
                Thread.sleep(FIRST_PACKET_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return input.getFramesWritten() > 0;
    }

    // Unwind a failed attempt so we can re-arm from scratch. Mirrors stop()
    // except we don't promote the empty file to MediaStore and we don't clear
    // tempFile — startRecording() will truncate and rewrite it.
    private void teardownAttempt() {
        UsbAudioInput input = device.getInput();
        if (input != null && monitoring) {
            try { input.setMonitorOutput(null); } catch (Throwable ignored) {}
        }
        try { sink.endRecording(); } catch (Throwable t) {
            Log.w(TAG, "teardownAttempt: sink.endRecording threw", t);
        }
        if (input != null) {
            try { input.stop(); } catch (Throwable t) {
                Log.w(TAG, "teardownAttempt: input.stop threw", t);
            }
        }
        if (monitoring) {
            try { device.getOutput().stop(); } catch (Throwable t) {
                Log.w(TAG, "teardownAttempt: output.stop threw", t);
            }
            monitoring = false;
        }
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
        // Snapshot the frame count after the record thread has joined (sink.endRecording
        // joins UsbAudioInput-WAV) but before input.stop() tears the native side down.
        lastSessionFrames = input != null ? input.getFramesWritten() : 0;
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
        if (lastSessionFrames <= 0) {
            Log.w(TAG, "stop: no PCM frames captured; discarding empty WAV");
            outputUri = null;
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "temp file not deleted: " + tempFile);
            }
            tempFile = null;
            return;
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

    /** Final PCM frame count from the last stop(). Zero means nothing was captured. */
    public long getCapturedFrames() {
        return lastSessionFrames;
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

    // Format introspection: surfaces the negotiated input/output formats for
    // the on-screen diagnostic. Values are populated during start() and are 0
    // outside an active recording session.
    public int getInputRate()       { return inRate; }
    public int getInputChannels()   { return inCh; }
    public int getInputBits()       { return inBits; }
    public int getInputSubslot()    { return inSubslot; }
    public int getOutputRate()      { return outRate; }
    public int getOutputChannels()  { return outCh; }
    public int getOutputBits()      { return outBits; }
    public int getOutputSubslot()   { return outSubslot; }

    /** True iff monitor was requested but input/output couldn't agree on rate/channels. */
    public boolean isMonitorMismatch() { return monitorMismatch; }

    /** True iff monitor is requested AND output is actively receiving the tap. */
    public boolean isMonitorActive() { return monitoring; }

    /** Frame counter from the capture loop. Diagnostic for the Bug 1 empty-WAV case. */
    public long getFramesWritten() {
        if (device == null) return 0;
        UsbAudioInput input = device.getInput();
        return input != null ? input.getFramesWritten() : 0;
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

    /**
     * Choose a channel count for the monitor output that is ≥ {@code inputChannels}
     * and supported by the device's output side. Returns:
     * <ul>
     *   <li>{@code inputChannels} if the output supports it directly (no upmix);</li>
     *   <li>otherwise the smallest supported value strictly greater than
     *       {@code inputChannels} (upmix path — record loop duplicates channel 0);</li>
     *   <li>{@code 0} if the output supports no count ≥ {@code inputChannels} —
     *       monitor stays off.</li>
     * </ul>
     */
    private static int pickMonitorChannelCount(UsbAudioOutput output, int inputChannels) {
        int[] supported = output != null ? output.getSupportedOutputChannelCounts() : null;
        if (supported == null || supported.length == 0) {
            // Engine doesn't know the output capabilities — try the input count
            // and let configure() either succeed or fail.
            return inputChannels;
        }
        int best = 0;
        for (int c : supported) {
            if (c == inputChannels) return c;
            if (c > inputChannels && (best == 0 || c < best)) best = c;
        }
        return best;
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
