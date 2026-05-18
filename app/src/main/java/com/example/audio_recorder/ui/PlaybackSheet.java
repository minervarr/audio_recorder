package com.example.audio_recorder.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.audio_recorder.MainActivity;
import com.example.audio_recorder.R;
import com.example.audio_recorder.engine.PlaybackEngine;
import com.example.audio_recorder.service.RecorderService;
import com.example.audio_recorder.settings.AppSettings;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.nerio.audioengine.EqProfile;
import com.nerio.audioengine.SignalPathInfo;

import java.util.List;

/**
 * Bottom sheet for playing back one recording. Drives a {@link PlaybackEngine}
 * owned by {@link RecorderService}, polls position while playing, and exposes
 * the EQ on/off + profile picker. Dismissing the sheet stops playback so we
 * don't leave the service holding the USB DAC unexpectedly.
 */
public class PlaybackSheet extends BottomSheetDialogFragment {

    private static final String ARG_URI = "uri";
    private static final String ARG_NAME = "name";
    private static final String ARG_DAC_KEY = "dac_key";
    private static final long POSITION_POLL_MS = 250;

    // Matches MainActivity's monitor slider taper so the cube-root mapping in
    // UsbAudioOutput produces the same on-DAC dB at the same slider position.
    private static final double DB_MIN = -45.0;
    private static final double DB_MAX = 0.0;
    private static final float DEFAULT_VOLUME_LINEAR = 0.857375f; // ≈ -3 dB after cube-root taper

    public static PlaybackSheet newInstance(Uri uri, String name, String dacKey) {
        PlaybackSheet sheet = new PlaybackSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_URI, uri);
        args.putString(ARG_NAME, name != null ? name : "");
        args.putString(ARG_DAC_KEY, dacKey != null ? dacKey : "");
        sheet.setArguments(args);
        return sheet;
    }

    private Uri uri;
    private String name;
    private String dacKey;
    private float currentVolumeLinear = DEFAULT_VOLUME_LINEAR;

    private TextView nameLabel;
    private TextView signalPathLabel;
    private SeekBar seekBar;
    private TextView positionLabel;
    private TextView durationLabel;
    private MaterialButton playPauseButton;
    private MaterialSwitch eqSwitch;
    private MaterialButton eqPickButton;
    private TextView eqStatusLabel;
    private SeekBar volumeSeekBar;
    private TextView volumeLabel;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking;
    private boolean stopOnDismiss = true;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updatePositionUi();
            tickHandler.postDelayed(this, POSITION_POLL_MS);
        }
    };

    private final PlaybackEngine.Listener playbackListener = new PlaybackEngine.Listener() {
        @Override public void onPrepared() {
            if (!isAdded()) return;
            refreshSignalPath();
            updatePositionUi();
            updatePlayPauseIcon();
        }
        @Override public void onCompletion() {
            if (!isAdded()) return;
            stopOnDismiss = false;
            dismissAllowingStateLoss();
        }
        @Override public void onError(String message) {
            if (!isAdded()) return;
            stopOnDismiss = false;
            dismissAllowingStateLoss();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            uri = args.getParcelable(ARG_URI);
            name = args.getString(ARG_NAME, "");
            dacKey = args.getString(ARG_DAC_KEY, "");
        }
        AppSettings settings = new AppSettings(requireContext());
        if (dacKey != null && !dacKey.isEmpty()) {
            currentVolumeLinear = settings.getPlaybackVolume(dacKey, DEFAULT_VOLUME_LINEAR);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        // Capture hardware volume keys so they drive the playback slider
        // instead of the OS media slider — same UX as the recording-monitor
        // path. Returning true on consume blocks the system handler.
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                bumpVolume(true);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                bumpVolume(false);
                return true;
            }
            return false;
        });
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        nameLabel = view.findViewById(R.id.playback_name);
        signalPathLabel = view.findViewById(R.id.playback_signal_path);
        seekBar = view.findViewById(R.id.playback_seekbar);
        positionLabel = view.findViewById(R.id.playback_position);
        durationLabel = view.findViewById(R.id.playback_duration);
        playPauseButton = view.findViewById(R.id.playback_play_pause);
        eqSwitch = view.findViewById(R.id.playback_eq_switch);
        eqPickButton = view.findViewById(R.id.playback_eq_pick);
        eqStatusLabel = view.findViewById(R.id.playback_eq_status);
        volumeSeekBar = view.findViewById(R.id.playback_volume_seekbar);
        volumeLabel = view.findViewById(R.id.playback_volume_label);

        volumeSeekBar.setProgress(linearToSliderProgress(
                currentVolumeLinear, volumeSeekBar.getMax()));
        updateVolumeLabel();
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                applyVolumeFromSlider(progress, sb.getMax(), true);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        nameLabel.setText(name);
        signalPathLabel.setText(R.string.playback_via_speaker);

        playPauseButton.setOnClickListener(v -> {
            RecorderService svc = service();
            if (svc != null) svc.togglePlaybackPlayPause();
            // Optimistic toggle; the next tick will reconcile if needed.
            tickHandler.postDelayed(this::updatePlayPauseIcon, 60);
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int duration = durationMs();
                if (duration <= 0) return;
                int ms = (int) ((long) progress * duration / sb.getMax());
                positionLabel.setText(formatTime(ms));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                RecorderService svc = service();
                int duration = durationMs();
                if (svc != null && duration > 0) {
                    int ms = (int) ((long) sb.getProgress() * duration / sb.getMax());
                    svc.seekPlayback(ms);
                }
            }
        });

        AppSettings settings = new AppSettings(requireContext());
        eqSwitch.setChecked(settings.isEqEnabled());
        eqSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            settings.setEqEnabled(isChecked);
            RecorderService svc = service();
            if (svc != null) svc.reloadPlaybackEq();
            updateEqStatus();
        });
        eqPickButton.setOnClickListener(v -> pickEqProfile());
        updateEqStatus();

        // Kick off playback if it hasn't started yet for this Uri.
        RecorderService svc = service();
        if (svc != null) {
            Uri current = svc.getCurrentPlaybackUri();
            if (current == null || !current.equals(uri)) {
                if (!svc.startPlayback(uri, currentVolumeLinear, playbackListener)) {
                    stopOnDismiss = false;
                    dismissAllowingStateLoss();
                    return;
                }
            } else {
                // Re-binding to an active session.
                refreshSignalPath();
                updatePositionUi();
                updatePlayPauseIcon();
            }
        }
        tickHandler.postDelayed(tickRunnable, POSITION_POLL_MS);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        tickHandler.removeCallbacks(tickRunnable);
        if (stopOnDismiss) {
            RecorderService svc = service();
            if (svc != null) svc.stopPlayback();
        }
        super.onDismiss(dialog);
    }

    private void pickEqProfile() {
        List<EqProfile> profiles = EqProfile.loadAll(requireContext());
        AppSettings settings = new AppSettings(requireContext());
        if (profiles == null || profiles.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext(), R.style.Theme_AudioRecorder_Dialog)
                    .setTitle(R.string.playback_eq_pick)
                    .setMessage(R.string.playback_eq_no_profiles)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[profiles.size() + 1];
        labels[0] = getString(R.string.playback_eq_clear);
        int currentIdx = 0;
        String currentName = settings.getEqProfileName();
        String currentSource = settings.getEqProfileSource();
        String currentForm = settings.getEqProfileForm();
        for (int i = 0; i < profiles.size(); i++) {
            EqProfile p = profiles.get(i);
            labels[i + 1] = p.name;
            if (p.name.equals(currentName)
                    && p.source.equals(currentSource)
                    && p.form.equals(currentForm)) {
                currentIdx = i + 1;
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext(), R.style.Theme_AudioRecorder_Dialog)
                .setTitle(R.string.playback_eq_pick)
                .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                    if (which == 0) {
                        settings.setEqProfile("", "", "");
                    } else {
                        EqProfile p = profiles.get(which - 1);
                        settings.setEqProfile(p.name, p.source, p.form);
                        // Picking a profile implies "use EQ".
                        if (!settings.isEqEnabled()) {
                            settings.setEqEnabled(true);
                            eqSwitch.setChecked(true);
                        }
                    }
                    RecorderService svc = service();
                    if (svc != null) svc.reloadPlaybackEq();
                    updateEqStatus();
                    d.dismiss();
                })
                .show();
    }

    private void updatePositionUi() {
        RecorderService svc = service();
        int duration = svc != null ? svc.getPlaybackDurationMs() : 0;
        int position = svc != null ? svc.getPlaybackPositionMs() : 0;
        if (duration > 0) {
            durationLabel.setText(formatTime(duration));
            if (!userSeeking) {
                positionLabel.setText(formatTime(position));
                seekBar.setProgress(
                        (int) ((long) position * seekBar.getMax() / duration));
            }
        }
        updatePlayPauseIcon();
    }

    private void updatePlayPauseIcon() {
        RecorderService svc = service();
        boolean playing = svc != null && svc.isPlaybackPlaying();
        playPauseButton.setIconResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(
                getString(playing ? R.string.playback_pause : R.string.playback_play));
    }

    private void refreshSignalPath() {
        RecorderService svc = service();
        if (svc == null) return;
        PlaybackEngine pe = svc.getPlaybackEngine();
        if (pe == null) return;
        SignalPathInfo info = pe.getSignalPathInfo();
        if (pe.isUsbOutputActive()) {
            int src = info != null ? info.sourceBitDepth : 0;
            // SignalPathInfo doesn't expose the negotiated DAC bit depth
            // directly; for bit-perfect we just show the source side here.
            signalPathLabel.setText(getString(R.string.playback_via_dac));
            if (src > 0 && info != null) {
                signalPathLabel.setText(
                        info.sourceRate + "Hz · " + src + "-bit · "
                                + getString(R.string.playback_via_dac));
            }
        } else {
            signalPathLabel.setText(R.string.playback_via_speaker);
        }
    }

    private void updateEqStatus() {
        AppSettings settings = new AppSettings(requireContext());
        if (!settings.isEqEnabled()) {
            eqStatusLabel.setText(R.string.playback_eq_off);
            return;
        }
        String n = settings.getEqProfileName();
        eqStatusLabel.setText(n == null || n.isEmpty()
                ? getString(R.string.playback_eq_off)
                : n);
    }

    private int durationMs() {
        RecorderService svc = service();
        return svc != null ? svc.getPlaybackDurationMs() : 0;
    }

    @Nullable
    private RecorderService service() {
        if (getActivity() instanceof MainActivity) {
            return ((MainActivity) getActivity()).getRecorderService();
        }
        return null;
    }

    private static String formatTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    private void bumpVolume(boolean up) {
        if (volumeSeekBar == null) return;
        int max = volumeSeekBar.getMax();
        int step = Math.max(1, max / 40);
        int next = volumeSeekBar.getProgress() + (up ? step : -step);
        if (next < 0) next = 0;
        if (next > max) next = max;
        volumeSeekBar.setProgress(next);
        applyVolumeFromSlider(next, max, true);
    }

    private void applyVolumeFromSlider(int progress, int max, boolean persist) {
        currentVolumeLinear = sliderProgressToLinear(progress, max);
        updateVolumeLabel();
        RecorderService svc = service();
        if (svc != null) svc.setPlaybackVolume(currentVolumeLinear);
        if (persist && dacKey != null && !dacKey.isEmpty()) {
            new AppSettings(requireContext())
                    .setPlaybackVolume(dacKey, currentVolumeLinear);
        }
    }

    private void updateVolumeLabel() {
        if (volumeLabel == null) return;
        if (currentVolumeLinear <= 0f) {
            volumeLabel.setText(R.string.volume_muted);
            return;
        }
        double db = -60.0 * (1.0 - Math.cbrt(currentVolumeLinear));
        volumeLabel.setText(getString(R.string.volume_db_fmt, db));
    }

    /**
     * Slider position → linear gain for UsbAudioOutput's software cube-root
     * taper. Identical math to MainActivity's monitor slider, so the on-DAC dB
     * matches the slider position the user dragged.
     */
    private static float sliderProgressToLinear(int progress, int max) {
        if (progress <= 0 || max <= 0) return 0f;
        double frac = progress / (double) max;
        double db = DB_MIN + frac * (DB_MAX - DB_MIN);
        double cbrtL = 1.0 + db / 60.0;
        if (cbrtL < 0) cbrtL = 0;
        if (cbrtL > 1) cbrtL = 1;
        double linear = cbrtL * cbrtL * cbrtL;
        return (float) linear;
    }

    private static int linearToSliderProgress(float linear, int max) {
        if (linear <= 0f) return 0;
        if (linear > 1f) linear = 1f;
        double db = -60.0 * (1.0 - Math.cbrt(linear));
        double frac = (db - DB_MIN) / (DB_MAX - DB_MIN);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        return (int) Math.round(frac * max);
    }
}
