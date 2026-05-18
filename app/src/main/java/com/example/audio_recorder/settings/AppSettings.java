package com.example.audio_recorder.settings;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {

    private static final String PREFS_NAME = "audio_recorder_prefs";

    private static final String KEY_MONITOR_VIA_OUTPUT = "monitor_via_output";
    private static final String KEY_LAST_DEVICE_KEY = "last_device_key";

    // EQ keys mirror Matrix Player's namespace so the two apps could share
    // a preferences file if they were ever co-located.
    private static final String KEY_EQ_ENABLED = "eq_enabled";
    private static final String KEY_EQ_PROFILE_NAME = "eq_profile_name";
    private static final String KEY_EQ_PROFILE_SOURCE = "eq_profile_source";
    private static final String KEY_EQ_PROFILE_FORM = "eq_profile_form";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String dacKey(int vendorId, int productId) {
        return vendorId + ":" + productId;
    }

    public int getSampleRate(String dacKey, int fallback) {
        return prefs.getInt("sample_rate_" + dacKey, fallback);
    }

    public void setSampleRate(String dacKey, int rate) {
        prefs.edit().putInt("sample_rate_" + dacKey, rate).apply();
    }

    public int getBitDepth(String dacKey, int fallback) {
        return prefs.getInt("bit_depth_" + dacKey, fallback);
    }

    public void setBitDepth(String dacKey, int bits) {
        prefs.edit().putInt("bit_depth_" + dacKey, bits).apply();
    }

    public int getChannelCount(String dacKey, int fallback) {
        return prefs.getInt("channels_" + dacKey, fallback);
    }

    public void setChannelCount(String dacKey, int channels) {
        prefs.edit().putInt("channels_" + dacKey, channels).apply();
    }

    public boolean isMonitorViaOutput() {
        return prefs.getBoolean(KEY_MONITOR_VIA_OUTPUT, false);
    }

    public void setMonitorViaOutput(boolean enabled) {
        prefs.edit().putBoolean(KEY_MONITOR_VIA_OUTPUT, enabled).apply();
    }

    public float getMonitorVolume(String dacKey, float fallback) {
        return prefs.getFloat("monitor_volume_" + dacKey, fallback);
    }

    public void setMonitorVolume(String dacKey, float linear01) {
        if (Float.isNaN(linear01)) linear01 = 0f;
        if (linear01 < 0f) linear01 = 0f;
        if (linear01 > 1f) linear01 = 1f;
        prefs.edit().putFloat("monitor_volume_" + dacKey, linear01).apply();
    }

    public float getPlaybackVolume(String dacKey, float fallback) {
        return prefs.getFloat("playback_volume_" + dacKey, fallback);
    }

    public void setPlaybackVolume(String dacKey, float linear01) {
        if (Float.isNaN(linear01)) linear01 = 0f;
        if (linear01 < 0f) linear01 = 0f;
        if (linear01 > 1f) linear01 = 1f;
        prefs.edit().putFloat("playback_volume_" + dacKey, linear01).apply();
    }

    public String getLastDeviceKey() {
        return prefs.getString(KEY_LAST_DEVICE_KEY, null);
    }

    public void setLastDeviceKey(String key) {
        prefs.edit().putString(KEY_LAST_DEVICE_KEY, key).apply();
    }

    public boolean isEqEnabled() {
        return prefs.getBoolean(KEY_EQ_ENABLED, false);
    }

    public void setEqEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply();
    }

    public String getEqProfileName() {
        return prefs.getString(KEY_EQ_PROFILE_NAME, "");
    }

    public String getEqProfileSource() {
        return prefs.getString(KEY_EQ_PROFILE_SOURCE, "");
    }

    public String getEqProfileForm() {
        return prefs.getString(KEY_EQ_PROFILE_FORM, "");
    }

    public void setEqProfile(String name, String source, String form) {
        prefs.edit()
                .putString(KEY_EQ_PROFILE_NAME, name != null ? name : "")
                .putString(KEY_EQ_PROFILE_SOURCE, source != null ? source : "")
                .putString(KEY_EQ_PROFILE_FORM, form != null ? form : "")
                .apply();
    }
}
