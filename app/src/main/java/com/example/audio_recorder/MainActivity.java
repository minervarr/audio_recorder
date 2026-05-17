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
            binding.monitorVolumeRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        monitorOn = settings.isMonitorViaOutput();
        binding.monitorSwitch.setChecked(monitorOn);
        binding.monitorVolumeRow.setVisibility(monitorOn ? View.VISIBLE : View.GONE);

        binding.monitorVolumeSeekbar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                monitorVolume = progress / (float) seekBar.getMax();
                persistMonitorVolume();
                updateMonitorVolumeLabel();
                if (service != null
                        && service.getState() == RecorderService.State.RECORDING) {
                    service.setMonitorVolume(monitorVolume);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        binding.monitorVolumeSeekbar.setProgress(Math.round(monitorVolume * 1000));
        updateMonitorVolumeLabel();

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
                binding.elapsedText.postDelayed(this, 1000);
            } else {
                binding.elapsedText.setText("");
                loadRecordings();
            }
        }
    };

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
        int[] capBitDepths = captureBitDepths(input);
        int maxCapRate = capRates.length > 0 ? capRates[capRates.length - 1] : 48000;
        int maxCapChannels = 1;
        for (int c : capChannels) if (c > maxCapChannels) maxCapChannels = c;

        binding.deviceFormatSummary.setText(getString(R.string.device_format_summary,
                uacVersion, maxCapChannels, maxCapRate));

        UsbDevice usb = usbAudioManager.getConnectedDevice();
        if (usb != null) {
            String dacKey = AppSettings.dacKey(usb.getVendorId(), usb.getProductId());
            // Pick the saved format clamped against what the device exposes.
            // We do NOT call input.configure() here — a second configure() on
            // the service control thread at record time was leaving the IN
            // endpoint in an under-armed state, so the first capture session
            // produced an empty WAV. One configure() per record session is
            // enough; RecordingEngine.start() handles it.
            selectedRate = clampToSupported(
                    settings.getSampleRate(dacKey, Math.min(48000, maxCapRate)),
                    capRates, Math.min(48000, maxCapRate));
            selectedBits = clampToSupported(
                    settings.getBitDepth(dacKey, 24), capBitDepths, 24);
            selectedChannels = clampToSupported(
                    settings.getChannelCount(dacKey, Math.min(2, maxCapChannels)),
                    capChannels, Math.min(2, maxCapChannels));
            settings.setLastDeviceKey(dacKey);
            monitorVolume = settings.getMonitorVolume(dacKey, 0.5f);
            binding.monitorVolumeSeekbar.setProgress(Math.round(monitorVolume * 1000));
            updateMonitorVolumeLabel();
        }
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
        int[] rates = captureRates(device.getInput());
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
        int[] depths = captureBitDepths(device.getInput());
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
        int[] counts = captureChannels(device.getInput());
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
