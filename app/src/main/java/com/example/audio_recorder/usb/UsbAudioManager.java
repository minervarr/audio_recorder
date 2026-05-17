package com.example.audio_recorder.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UsbAudioManager {

    private static final String TAG = "UsbAudioManager";
    private static final String ACTION_USB_PERMISSION =
            "com.example.audio_recorder.USB_PERMISSION";

    public interface UsbAudioListener {
        /**
         * The user just attached a USB audio device (fresh broadcast or launch
         * intent). Implementations should auto-prompt for permission if not
         * already held -- the user just performed the physical action so the
         * system dialog is expected.
         */
        void onUsbDacConnected(UsbDevice device);

        /**
         * The app started up and Android's {@code getDeviceList()} contains a
         * USB-audio device. This may be stale OTG state (Android sometimes
         * caches a device after a transient disconnect). Implementations
         * should NOT auto-prompt; render the device and let the user tap to
         * grant access.
         */
        void onUsbDacFoundAtStartup(UsbDevice device);

        void onUsbDacDisconnected();
        void onUsbPermissionGranted(UsbDevice device);
        void onUsbPermissionDenied(UsbDevice device);
    }

    private final Context context;
    private final UsbManager usbManager;
    private UsbAudioListener listener;
    private UsbDevice connectedDevice;
    private boolean registered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            Log.d(TAG, "onReceive: " + action);

            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null && isAudioDevice(device)) {
                        connectedDevice = device;
                        if (listener != null) listener.onUsbDacConnected(device);
                    }
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null && connectedDevice != null
                            && device.getDeviceId() == connectedDevice.getDeviceId()) {
                        connectedDevice = null;
                        if (listener != null) listener.onUsbDacDisconnected();
                    }
                    break;
                }
                case ACTION_USB_PERMISSION: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (device != null && listener != null) {
                        if (granted) listener.onUsbPermissionGranted(device);
                        else listener.onUsbPermissionDenied(device);
                    }
                    break;
                }
            }
        }
    };

    public UsbAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(UsbAudioListener listener) {
        this.listener = listener;
    }

    public void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        registered = true;
        scanExistingDevices();
    }

    public void unregister() {
        if (!registered) return;
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {}
        registered = false;
    }

    private void scanExistingDevices() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (isAudioDevice(device)) {
                connectedDevice = device;
                if (listener != null) listener.onUsbDacFoundAtStartup(device);
                return;
            }
        }
    }

    public List<UsbDevice> enumerateAudioClassDevices() {
        List<UsbDevice> out = new ArrayList<>();
        if (usbManager == null) return out;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isAudioDevice(d)) out.add(d);
        }
        return out;
    }

    public UsbDevice getConnectedDevice() {
        return connectedDevice;
    }

    public void requestPermission(UsbDevice device) {
        if (usbManager == null || device == null) return;
        Log.d(TAG, "requestPermission: " + device.getDeviceName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, pi);
    }

    public boolean hasPermission(UsbDevice device) {
        return usbManager != null && device != null && usbManager.hasPermission(device);
    }

    public UsbDeviceConnection openDevice(UsbDevice device) {
        if (usbManager == null || device == null) return null;
        return usbManager.openDevice(device);
    }

    public UsbManager getUsbManager() {
        return usbManager;
    }

    private static boolean isAudioDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                return true;
            }
        }
        return false;
    }
}
