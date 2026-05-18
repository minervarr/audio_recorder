package com.example.audio_recorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.audio_recorder.databinding.ActivityMainBinding;
import com.example.audio_recorder.databinding.ItemRecordingBinding;
import com.example.audio_recorder.service.RecorderService;
import com.example.audio_recorder.settings.AppSettings;
import com.example.audio_recorder.usb.UsbAudioManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nerio.audioengine.UsbAudioDevice;
import com.nerio.audioengine.UsbAudioInput;
import com.nerio.audioengine.UsbAudioOutput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int[] FALLBACK_RATES = {44100, 48000, 88200, 96000, 176400, 192000};
    private static final int[] FALLBACK_BIT_DEPTHS = {16, 24, 32};
    private static final int[] FALLBACK_CHANNEL_COUNTS = {1, 2};

    private ActivityMainBinding binding;
    private UsbAudioManager usbAudioManager;
    private AppSettings settings;
    private RecorderService service;
    private boolean bound;

    private int selectedRate = 48000;
    private int selectedBits = 24;
    private int selectedChannels = 2;
    private boolean monitorOn;
    private float monitorVolume = 0.5f;
    private boolean deviceAttached;

    // Monitor slider dB taper: slider 0 = mute, 1..1000 maps linearly in dB to
    // [MIN, MAX]. We then invert UsbAudioOutput's cube-root SW curve so the
    // final on-DAC dB tracks the slider's dB. Caps the top so the rightmost
    // position is loud-but-not-painful.
    private static final double MONITOR_DB_MIN = -45.0;
    private static final double MONITOR_DB_MAX = -3.0;

    private final List<RecordingEntry> recordings = new ArrayList<>();
    private RecordingsAdapter adapter;

    private ActivityResultLauncher<String> recordAudioLauncher;
    private ActivityResultLauncher<String> postNotificationsLauncher;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((RecorderService.LocalBinder) b).get();
            service.registerListener(stateListener);
            bound = true;
            applyState(service.getState(), service.getLastError());
            UsbDevice already = usbAudioManager.getConnectedDevice();
            if (already != null && service.getDevice() == null) {
                handleFoundAtStartup(already);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.unregisterListener(stateListener);
            service = null;
            bound = false;
        }
    };

    private final RecorderService.StateListener stateListener =
            (state, err) -> runOnUiThread(() -> applyState(state, err));

    private final UsbAudioManager.UsbAudioListener usbListener =
            new UsbAudioManager.UsbAudioListener() {
        @Override public void onUsbDacConnected(UsbDevice device) {
            handleConnected(device);
        }
        @Override public void onUsbDacFoundAtStartup(UsbDevice device) {
            handleFoundAtStartup(device);
        }
        @Override public void onUsbDacDisconnected() {
            if (service != null) service.detachDevice();
            renderNoDevice();
        }
        @Override public void onUsbPermissionGranted(UsbDevice device) {
            clearTapToGrant();
            openAndAttach(device);
        }
        @Override public void onUsbPermissionDenied(UsbDevice device) {
            Toast.makeText(MainActivity.this, R.string.request_permission_usb,
                    Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settings = new AppSettings(this);
        usbAudioManager = new UsbAudioManager(this);
        usbAudioManager.setListener(usbListener);

        adapter = new RecordingsAdapter();
        binding.recordingsList.setLayoutManager(new LinearLayoutManager(this));
        binding.recordingsList.setAdapter(adapter);

        binding.recordButton.setOnClickListener(v -> onRecordButton());
        binding.sampleRateButton.setOnClickListener(v -> pickSampleRate());
        binding.bitDepthButton.setOnClickListener(v -> pickBitDepth());
        binding.channelsButton.setOnClickListener(v -> pickChannels());
        binding.monitorSwitch.setOnCheckedChangeListener((b, isChecked) -> {
            monitorOn = isChecked;
            settings.setMonitorViaOutput(isChecked);
            // When monitor toggles ON post-attach, the previously-picked format
            // may no longer be in the input∩output intersection — re-clamp
            // against the new effective sets. validateMonitorIntersection()
            // also forces monitorOn=false (with a toast) if any axis has no
            // common values.
            if (isChecked && deviceAttached) {
                validateMonitorIntersection();
            }
            applyMonitorRowVisibility();
        });

        monitorOn = settings.isMonitorViaOutput();
        binding.monitorSwitch.setChecked(monitorOn);
        applyMonitorRowVisibility();

        binding.monitorVolumeSeekbar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                applyMonitorVolumeFromSlider(progress, seekBar.getMax());
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        recordAudioLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (!granted) {
                        Toast.makeText(this, R.string.request_permission_audio,
                                Toast.LENGTH_LONG).show();
                    }
                });
        postNotificationsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {});

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        usbAudioManager.register();
        Intent svc = new Intent(this, RecorderService.class);
        bindService(svc, conn, Context.BIND_AUTO_CREATE);
        loadRecordings();
        consumeUsbAttachIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // With launchMode=singleTask, the manifest's USB_DEVICE_ATTACHED filter
        // redelivers attach intents to the existing instance instead of stacking
        // a new one. Replace the activity's stored intent so a later onStart sees
        // the latest, then act on it now.
        setIntent(intent);
        consumeUsbAttachIntent(intent);
    }

    private void consumeUsbAttachIntent(Intent intent) {
        if (intent == null) return;
        if (!android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(
                intent.getAction())) return;
        UsbDevice dev = intent.getParcelableExtra(
                android.hardware.usb.UsbManager.EXTRA_DEVICE);
        if (dev != null) handleConnected(dev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            try {
                if (service != null) service.unregisterListener(stateListener);
                unbindService(conn);
            } catch (Throwable ignored) {}
            bound = false;
        }
        usbAudioManager.unregister();
    }

    private void handleConnected(UsbDevice device) {
        renderDeviceHeader(device);
        if (usbAudioManager.hasPermission(device)) {
            clearTapToGrant();
            openAndAttach(device);
        } else {
            usbAudioManager.requestPermission(device);
        }
    }

    private void handleFoundAtStartup(UsbDevice device) {
        renderDeviceHeader(device);
        if (usbAudioManager.hasPermission(device)) {
            clearTapToGrant();
            openAndAttach(device);
        } else {
            showTapToGrant(device);
        }
    }

    private void showTapToGrant(UsbDevice device) {
        binding.deviceTapHint.setVisibility(View.VISIBLE);
        binding.deviceCard.setClickable(true);
        binding.deviceCard.setOnClickListener(v -> {
            clearTapToGrant();
            usbAudioManager.requestPermission(device);
        });
    }

    private void clearTapToGrant() {
        binding.deviceTapHint.setVisibility(View.GONE);
        binding.deviceCard.setOnClickListener(null);
        binding.deviceCard.setClickable(false);
    }

    private void openAndAttach(UsbDevice device) {
        if (service == null) return;
        UsbDeviceConnection c = usbAudioManager.openDevice(device);
        if (c == null) {
            Toast.makeText(this, "Could not open USB device", Toast.LENGTH_SHORT).show();
            return;
        }
        service.attachDevice(device, c);
    }

    private void renderNoDevice() {
        binding.deviceName.setText(R.string.no_device);
        binding.deviceFormatSummary.setText("");
        clearTapToGrant();
        binding.sampleRateButton.setEnabled(false);
        binding.bitDepthButton.setEnabled(false);
        binding.channelsButton.setEnabled(false);
        binding.recordButton.setEnabled(false);
        deviceAttached = false;
        applyMonitorRowVisibility();
        binding.formatDiagnosticText.setVisibility(View.GONE);
    }

    private void renderDeviceHeader(UsbDevice device) {
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            name = device.getProductName();
        }
        if (name == null || name.isEmpty()) {
            name = String.format("VID:%04X PID:%04X",
                    device.getVendorId(), device.getProductId());
        }
        binding.deviceName.setText(name);
    }

    private void applyState(RecorderService.State state, @Nullable String err) {
        switch (state) {
            case IDLE:
                renderNoDevice();
                binding.elapsedText.setText("");
                binding.recordButton.setText(R.string.record_start);
                break;
            case DEVICE_READY:
                onDeviceReady();
                binding.elapsedText.setText("");
                binding.recordButton.setText(R.string.record_start);
                binding.recordButton.setEnabled(true);
                binding.monitorSwitch.setEnabled(true);
                binding.sampleRateButton.setEnabled(true);
                binding.bitDepthButton.setEnabled(true);
                binding.channelsButton.setEnabled(true);
                break;
            case RECORDING:
                binding.recordButton.setText(R.string.record_stop);
                binding.recordButton.setEnabled(true);
                binding.monitorSwitch.setEnabled(false);
                binding.sampleRateButton.setEnabled(false);
                binding.bitDepthButton.setEnabled(false);
                binding.channelsButton.setEnabled(false);
                startTick();
                break;
            case ERROR:
                Toast.makeText(this,
                        getString(R.string.error_prefix,
                                err == null ? "unknown" : err),
                        Toast.LENGTH_LONG).show();
                renderNoDevice();
                break;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (service != null && service.getState() == RecorderService.State.RECORDING) {
                long ms = service.getElapsedMs();
                long sec = ms / 1000;
                String elapsed = String.format(java.util.Locale.US,
                        "%02d:%02d", sec / 60, sec % 60);
                binding.elapsedText.setText(getString(R.string.record_elapsed, elapsed));
                renderFormatDiagnostic();
                binding.elapsedText.postDelayed(this, 1000);
            } else {
                binding.elapsedText.setText("");
                binding.formatDiagnosticText.setVisibility(View.GONE);
                loadRecordings();
            }
        }
    };

    private void renderFormatDiagnostic() {
        com.example.audio_recorder.engine.RecordingEngine eng =
                service != null ? service.getEngine() : null;
        if (eng == null) {
            binding.formatDiagnosticText.setVisibility(View.GONE);
            return;
        }
        String in = getString(R.string.format_diagnostic_in_fmt,
                eng.getInputRate(), eng.getInputBits(),
                eng.getInputChannels(), eng.getInputSubslot());
        String out;
        if (eng.isMonitorMismatch()) {
            out = getString(R.string.format_diagnostic_out_mismatch,
                    eng.getOutputRate(), eng.getOutputBits(),
                    eng.getOutputChannels(), eng.getOutputSubslot());
        } else if (eng.isMonitorActive()) {
            out = getString(R.string.format_diagnostic_out_fmt,
                    eng.getOutputRate(), eng.getOutputBits(),
                    eng.getOutputChannels(), eng.getOutputSubslot());
        } else {
            out = getString(R.string.format_diagnostic_out_off);
        }
        String frames = getString(R.string.format_diagnostic_frames, eng.getFramesWritten());
        binding.formatDiagnosticText.setText(in + "\n" + out + "\n" + frames);
        binding.formatDiagnosticText.setVisibility(View.VISIBLE);
    }

    private void startTick() {
        binding.elapsedText.removeCallbacks(tick);
        binding.elapsedText.post(tick);
    }

    private void onDeviceReady() {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) {
            renderNoDevice();
            return;
        }
        UsbAudioOutput output = device.getOutput();
        UsbAudioInput input = device.getInput();
        int uacVersion = output.getUacVersion();

        int[] capRates = captureRates(input);
        int[] capChannels = captureChannels(input);
        int maxCapRate = capRates.length > 0 ? capRates[capRates.length - 1] : 48000;
        int maxCapChannels = 1;
        for (int c : capChannels) if (c > maxCapChannels) maxCapChannels = c;

        binding.deviceFormatSummary.setText(getString(R.string.device_format_summary,
                uacVersion, maxCapChannels, maxCapRate));

        deviceAttached = true;

        // Format axes the recorder is allowed to pick. With monitor on:
        //   - rates must intersect (no resampling on the monitor path);
        //   - bit depths are input's set (engine handles up/down-cast);
        //   - channels are input's set filtered to ≤ output's max (engine
        //     upmixes channel 0 into trailing slots).
        // With monitor off, all three axes are the capture-only sets.
        int[] effRates = effectiveRates(input, output);
        int[] effChannels = effectiveChannels(input, output);
        int[] effBits = effectiveBitDepths(input, output);

        // If monitor is on but any axis is unworkable, disable it with a
        // clear message. In practice this only fires on truly mismatched
        // devices (no shared rate, or input ch > output max).
        if (monitorOn && (effRates.length == 0
                || effChannels.length == 0
                || effBits.length == 0)) {
            Toast.makeText(this, R.string.monitor_no_common_format,
                    Toast.LENGTH_LONG).show();
            monitorOn = false;
            settings.setMonitorViaOutput(false);
            binding.monitorSwitch.setChecked(false);
            effRates = capRates;
            effChannels = capChannels;
            effBits = captureBitDepths(input);
        }

        UsbDevice usb = usbAudioManager.getConnectedDevice();
        if (usb != null) {
            String dacKey = AppSettings.dacKey(usb.getVendorId(), usb.getProductId());
            // Pick the saved format clamped against what's *effective* now
            // (capture-only when monitor is off, intersection when monitor is
            // on). We do NOT call input.configure() here — a second configure()
            // on the service control thread at record time was leaving the IN
            // endpoint in an under-armed state, so the first capture session
            // produced an empty WAV. One configure() per record session is
            // enough; RecordingEngine.start() handles it.
            int effMaxRate = effRates.length > 0 ? effRates[effRates.length - 1] : 48000;
            int effMaxCh = 1;
            for (int c : effChannels) if (c > effMaxCh) effMaxCh = c;
            selectedRate = clampToSupported(
                    settings.getSampleRate(dacKey, Math.min(48000, effMaxRate)),
                    effRates, Math.min(48000, effMaxRate));
            selectedBits = clampToSupported(
                    settings.getBitDepth(dacKey, 24), effBits, 24);
            selectedChannels = clampToSupported(
                    settings.getChannelCount(dacKey, Math.min(2, effMaxCh)),
                    effChannels, Math.min(2, effMaxCh));
            settings.setLastDeviceKey(dacKey);
            // Now that we know which DAC we're on, load its persisted monitor
            // volume and reflect it in the slider. Pre-attach the slider
            // shouldn't show any value, so we waited until here.
            monitorVolume = settings.getMonitorVolume(dacKey, 0.5f);
            binding.monitorVolumeSeekbar.setProgress(
                    linearToSliderProgress(monitorVolume,
                            binding.monitorVolumeSeekbar.getMax()));
            updateMonitorVolumeLabel();
        }
        applyMonitorRowVisibility();
        renderFormatButtons();
    }

    private static int clampToSupported(int desired, int[] supported, int fallback) {
        if (supported != null) {
            for (int v : supported) if (v == desired) return desired;
            if (supported.length > 0) return supported[0];
        }
        return fallback;
    }

    private void persistMonitorVolume() {
        UsbDevice usb = usbAudioManager.getConnectedDevice();
        if (usb == null) return;
        String dacKey = AppSettings.dacKey(usb.getVendorId(), usb.getProductId());
        settings.setMonitorVolume(dacKey, monitorVolume);
    }

    private void updateMonitorVolumeLabel() {
        if (monitorVolume <= 0f) {
            binding.monitorVolumeLabel.setText(R.string.volume_muted);
            return;
        }
        // Software-path dB taper, matches UsbAudioOutput.SOFTWARE branch:
        // db = -60 * (1 - cbrt(linear))
        double db = -60.0 * (1.0 - Math.cbrt(monitorVolume));
        binding.monitorVolumeLabel.setText(getString(R.string.volume_db_fmt, db));
    }

    private void applyMonitorRowVisibility() {
        boolean visible = monitorOn && deviceAttached;
        binding.monitorVolumeRow.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void applyMonitorVolumeFromSlider(int progress, int max) {
        monitorVolume = sliderProgressToLinear(progress, max);
        persistMonitorVolume();
        updateMonitorVolumeLabel();
        if (service != null
                && service.getState() == RecorderService.State.RECORDING) {
            service.setMonitorVolume(monitorVolume);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Repurpose the hardware volume rocker to drive the monitor slider —
        // but only when the monitor is on AND a DAC is attached. Otherwise
        // hand off to the system so media volume still works.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && monitorOn && deviceAttached
                && binding.monitorVolumeRow.getVisibility() == View.VISIBLE) {
            int max = binding.monitorVolumeSeekbar.getMax();
            int step = Math.max(1, max / 40);   // ~1 dB step on the 42 dB taper
            int next = binding.monitorVolumeSeekbar.getProgress()
                    + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? step : -step);
            if (next < 0) next = 0;
            if (next > max) next = max;
            binding.monitorVolumeSeekbar.setProgress(next);
            applyMonitorVolumeFromSlider(next, max);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Slider position (0..max) → linear gain consumed by UsbAudioOutput.
     * Bottom of slider is hard mute; everything above maps linearly in dB to
     * [MONITOR_DB_MIN, MONITOR_DB_MAX]. We then invert UsbAudioOutput's
     * cube-root SW curve (db = -60 * (1 - cbrt(linear))) so the resulting
     * on-DAC dB matches the dB the slider is positioned at.
     */
    private static float sliderProgressToLinear(int progress, int max) {
        if (progress <= 0 || max <= 0) return 0f;
        double frac = progress / (double) max;
        double db = MONITOR_DB_MIN + frac * (MONITOR_DB_MAX - MONITOR_DB_MIN);
        // Invert UsbAudioOutput's SOFTWARE taper db = -60 * (1 - cbrt(linear));
        // i.e. cbrt(linear) = 1 + db/60. The previous /-60 made every above-zero
        // slider position clamp to linear=1.0 (max gain).
        double cbrtL = 1.0 + db / 60.0;
        if (cbrtL < 0) cbrtL = 0;
        if (cbrtL > 1) cbrtL = 1;
        double linear = cbrtL * cbrtL * cbrtL;
        return (float) linear;
    }

    /** Inverse of {@link #sliderProgressToLinear}. */
    private static int linearToSliderProgress(float linear, int max) {
        if (linear <= 0f) return 0;
        if (linear > 1f) linear = 1f;
        double db = -60.0 * (1.0 - Math.cbrt(linear));
        double frac = (db - MONITOR_DB_MIN) / (MONITOR_DB_MAX - MONITOR_DB_MIN);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        return (int) Math.round(frac * max);
    }

    private void validateMonitorIntersection() {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) return;
        UsbAudioInput input = device.getInput();
        UsbAudioOutput output = device.getOutput();
        int[] effRates = effectiveRates(input, output);
        int[] effChannels = effectiveChannels(input, output);
        int[] effBits = effectiveBitDepths(input, output);
        if (effRates.length == 0 || effChannels.length == 0 || effBits.length == 0) {
            Toast.makeText(this, R.string.monitor_no_common_format,
                    Toast.LENGTH_LONG).show();
            monitorOn = false;
            settings.setMonitorViaOutput(false);
            binding.monitorSwitch.setChecked(false);
            return;
        }
        int newRate = clampToSupported(selectedRate, effRates, effRates[effRates.length - 1]);
        int newBits = clampToSupported(selectedBits, effBits, effBits[effBits.length - 1]);
        int newCh   = clampToSupported(selectedChannels, effChannels,
                effChannels[effChannels.length - 1]);
        if (newRate != selectedRate || newBits != selectedBits || newCh != selectedChannels) {
            selectedRate = newRate;
            selectedBits = newBits;
            selectedChannels = newCh;
            renderFormatButtons();
            persistFormat();
            Toast.makeText(this, R.string.monitor_format_adjusted,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int[] effectiveRates(UsbAudioInput input, UsbAudioOutput output) {
        // Rate is the one axis that strictly must intersect — resampling
        // isn't supported on the monitor path.
        int[] cap = captureRates(input);
        if (!monitorOn) return cap;
        int[] out = output != null ? output.getSupportedOutputRates() : null;
        if (out == null || out.length == 0) return cap;
        return intersect(cap, out);
    }

    private int[] effectiveBitDepths(UsbAudioInput input, UsbAudioOutput output) {
        // The C++ writeIntXX path handles both bit-depth up-cast (subslot
        // wider than source) and down-cast (subslot narrower; LSBs dropped).
        // So we let the user pick any input bit depth even when monitor is on.
        return captureBitDepths(input);
    }

    private int[] effectiveChannels(UsbAudioInput input, UsbAudioOutput output) {
        // The record loop upmixes from N channels to M ≥ N by duplicating
        // channel 0 into the trailing slots. So we accept any input channel
        // count that is ≤ the output's largest supported value (typically 2
        // for headphone DACs). 1ch mic → 2ch phones is the common case.
        int[] cap = captureChannels(input);
        if (!monitorOn) return cap;
        int[] outCh = output != null ? output.getSupportedOutputChannelCounts() : null;
        if (outCh == null || outCh.length == 0) return cap;
        int outMax = 0;
        for (int c : outCh) if (c > outMax) outMax = c;
        java.util.ArrayList<Integer> keep = new java.util.ArrayList<>();
        for (int c : cap) if (c <= outMax) keep.add(c);
        int[] result = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) result[i] = keep.get(i);
        return result;
    }

    private static int[] intersect(int[] a, int[] b) {
        if (a == null || b == null) return new int[0];
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        for (int x : a) {
            for (int y : b) {
                if (x == y) { result.add(x); break; }
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < result.size(); i++) out[i] = result.get(i);
        return out;
    }

    private static int[] captureRates(UsbAudioInput input) {
        int[] r = input.getSupportedRates();
        return (r != null && r.length > 0) ? r : FALLBACK_RATES;
    }

    private static int[] captureBitDepths(UsbAudioInput input) {
        int[] b = input.getSupportedBitDepths();
        return (b != null && b.length > 0) ? b : FALLBACK_BIT_DEPTHS;
    }

    private static int[] captureChannels(UsbAudioInput input) {
        int[] c = input.getSupportedChannelCounts();
        return (c != null && c.length > 0) ? c : FALLBACK_CHANNEL_COUNTS;
    }

    private void renderFormatButtons() {
        binding.sampleRateButton.setText(
                getString(R.string.format_rate_hz, selectedRate));
        binding.bitDepthButton.setText(
                getString(R.string.format_bits, selectedBits));
        binding.channelsButton.setText(
                getString(R.string.format_channels_value, selectedChannels));
    }

    private void pickSampleRate() {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) return;
        int[] rates = effectiveRates(device.getInput(), device.getOutput());
        String[] labels = new String[rates.length];
        int currentIdx = 0;
        for (int i = 0; i < rates.length; i++) {
            labels[i] = getString(R.string.format_rate_hz, rates[i]);
            if (rates[i] == selectedRate) currentIdx = i;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_AudioRecorder_Dialog)
                .setTitle(R.string.format_sample_rate)
                .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                    int candidate = rates[which];
                    if (validateFormat(candidate, selectedChannels, selectedBits)) {
                        // validateFormat() already updated selectedRate to the
                        // negotiated value, which may differ from candidate.
                        persistFormat();
                        renderFormatButtons();
                    } else {
                        Toast.makeText(this, R.string.format_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                    d.dismiss();
                })
                .show();
    }

    private void pickBitDepth() {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) return;
        int[] depths = effectiveBitDepths(device.getInput(), device.getOutput());
        String[] labels = new String[depths.length];
        int currentIdx = 0;
        for (int i = 0; i < depths.length; i++) {
            labels[i] = getString(R.string.format_bits, depths[i]);
            if (depths[i] == selectedBits) currentIdx = i;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_AudioRecorder_Dialog)
                .setTitle(R.string.format_bit_depth)
                .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                    int candidate = depths[which];
                    if (validateFormat(selectedRate, selectedChannels, candidate)) {
                        persistFormat();
                        renderFormatButtons();
                    } else {
                        Toast.makeText(this, R.string.format_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                    d.dismiss();
                })
                .show();
    }

    private void pickChannels() {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) return;
        int[] counts = effectiveChannels(device.getInput(), device.getOutput());
        String[] labels = new String[counts.length];
        int currentIdx = 0;
        for (int i = 0; i < counts.length; i++) {
            labels[i] = getString(R.string.format_channels_value, counts[i]);
            if (counts[i] == selectedChannels) currentIdx = i;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_AudioRecorder_Dialog)
                .setTitle(R.string.format_channels)
                .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                    int candidate = counts[which];
                    if (validateFormat(selectedRate, candidate, selectedBits)) {
                        persistFormat();
                        renderFormatButtons();
                    } else {
                        Toast.makeText(this, R.string.format_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                    d.dismiss();
                })
                .show();
    }

    private boolean validateFormat(int rate, int channels, int bits) {
        UsbAudioDevice device = (service != null) ? service.getDevice() : null;
        if (device == null) return false;
        UsbAudioInput input = device.getInput();
        try { input.stop(); } catch (Throwable ignored) {}
        boolean ok = input.configure(rate, channels, bits);
        if (ok) {
            // Adopt whatever the device actually negotiated — the requested
            // value may have been quietly rounded to a supported one.
            selectedRate = input.getConfiguredRate();
            selectedChannels = input.getConfiguredChannels();
            selectedBits = input.getConfiguredBitDepth();
        }
        // Leave the input un-armed either way; RecordingEngine.start() will
        // configure it once at record time. Calling configure() twice across
        // threads was producing an empty WAV on the first record.
        try { input.stop(); } catch (Throwable ignored) {}
        return ok;
    }

    private void persistFormat() {
        UsbDevice usb = usbAudioManager.getConnectedDevice();
        if (usb == null) return;
        String dacKey = AppSettings.dacKey(usb.getVendorId(), usb.getProductId());
        settings.setSampleRate(dacKey, selectedRate);
        settings.setBitDepth(dacKey, selectedBits);
        settings.setChannelCount(dacKey, selectedChannels);
    }

    private void onRecordButton() {
        if (service == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (service.getState() == RecorderService.State.RECORDING) {
            service.stopRecording();
        } else if (service.getState() == RecorderService.State.DEVICE_READY) {
            service.startRecording(selectedRate, selectedChannels, selectedBits,
                    monitorOn, monitorVolume);
        }
    }

    private void loadRecordings() {
        recordings.clear();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore();
        } else {
            scanLegacyDir();
        }
        adapter.notifyDataSetChanged();
        binding.recordingsEmpty.setVisibility(
                recordings.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void queryMediaStore() {
        ContentResolver resolver = getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
        };
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + " = ?";
        String[] args = {Environment.DIRECTORY_MUSIC + "/Recordings/"};
        Uri collection = MediaStore.Audio.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = resolver.query(collection, projection, selection, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int dateIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                Uri uri = ContentUris.withAppendedId(collection, id);
                RecordingEntry e = new RecordingEntry();
                e.uri = uri;
                e.name = c.getString(nameIdx);
                e.durationMs = durIdx >= 0 ? c.getLong(durIdx) : 0;
                e.sizeBytes = sizeIdx >= 0 ? c.getLong(sizeIdx) : 0;
                e.dateAddedSec = dateIdx >= 0 ? c.getLong(dateIdx) : 0;
                recordings.add(e);
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryMediaStore failed", t);
        }
    }

    private void scanLegacyDir() {
        java.io.File dir = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            RecordingEntry e = new RecordingEntry();
            e.uri = Uri.fromFile(f);
            e.name = f.getName();
            e.sizeBytes = f.length();
            e.dateAddedSec = f.lastModified() / 1000;
            recordings.add(e);
        }
    }

    private static class RecordingEntry {
        Uri uri;
        String name;
        long durationMs;
        long sizeBytes;
        long dateAddedSec;
    }

    private class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemRecordingBinding b = ItemRecordingBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            RecordingEntry e = recordings.get(position);
            h.binding.recordingName.setText(e.name);
            String size = Formatter.formatShortFileSize(
                    h.binding.getRoot().getContext(), e.sizeBytes);
            String date = DateUtils.getRelativeTimeSpanString(
                    e.dateAddedSec * 1000, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            h.binding.recordingMeta.setText(size + "  ·  " + date);
            h.binding.getRoot().setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(e.uri, "audio/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this,
                            "No app to play this recording",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public int getItemCount() {
            return recordings.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemRecordingBinding binding;
            VH(ItemRecordingBinding b) {
                super(b.getRoot());
                this.binding = b;
            }
        }
    }
}
