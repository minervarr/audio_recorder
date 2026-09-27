package com.example.audio_recorder.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.example.audio_recorder.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * The only Java in the app. NativeActivity draws the screen. This class holds
 * the objects the NDK cannot: the USB connection (the fd dies with it), the
 * microphone foreground notification, and the MediaStore row.
 */
public class RecorderService extends Service {

    private static final String TAG = "RecorderService";
    private static final String CHANNEL_ID = "recorder";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.example.audio_recorder.action.STOP_RECORDING";
    private static final String ACTION_USB_PERMISSION =
            "com.example.audio_recorder.USB_PERMISSION";

    static { System.loadLibrary("recorder_core"); }

    private static native void nativeOnStop();
    private static native void nativeOnUsb();

    private static Context app;
    private static Activity activity;
    private static UsbManager usb;
    private static boolean registered;
    private static RecorderService instance;
    private static String noteText = "Starting…";

    private static final List<Slot> slots = new ArrayList<>();
    private static UsbDeviceConnection connection;
    private static String openName = "";

    private static final class Slot {
        UsbDevice device;
        boolean fresh;
        boolean justGranted;
        boolean denied;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            synchronized (RecorderService.class) {
                String action = intent.getAction();
                if (action == null) return;
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    UsbDevice d = deviceExtra(intent);
                    if (d != null && isAudio(d)) {
                        Slot s = slot(d);
                        s.fresh = true;
                        s.denied = false;
                        nativeOnUsb();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice d = deviceExtra(intent);
                    if (d != null) {
                        removeSlot(d.getDeviceName());
                        nativeOnUsb();
                    }
                } else if (ACTION_USB_PERMISSION.equals(action)) {
                    UsbDevice d = deviceExtra(intent);
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (d != null) {
                        Slot s = slot(d);
                        s.justGranted = granted;
                        s.denied = !granted;
                        nativeOnUsb();
                    }
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ensureContext(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.recording_notification_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            nativeOnStop();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification(noteText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Called from native ──────────────────────────────────────────────────

    public static synchronized void bind(Activity a) {
        activity = a;
        ensureContext(a);
    }

    public static boolean hasRecordAudio() {
        return granted(android.Manifest.permission.RECORD_AUDIO);
    }

    public static boolean hasNotifications() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return granted(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    public static void requestMissing() {
        if (activity == null) return;
        List<String> need = new ArrayList<>();
        if (!hasRecordAudio()) need.add(android.Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications())
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 33
                && !granted(android.Manifest.permission.READ_MEDIA_AUDIO))
            need.add(android.Manifest.permission.READ_MEDIA_AUDIO);
        else if (Build.VERSION.SDK_INT < 33
                && !granted(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        if (need.isEmpty()) return;
        activity.requestPermissions(need.toArray(new String[0]), 1);
    }

    /** name, label, vendor, product, permission, fresh, justGranted, denied. */
    public static synchronized String[] devices() {
        if (usb == null) return new String[0];
        HashMap<String, UsbDevice> live = usb.getDeviceList();
        List<String> out = new ArrayList<>();
        List<Slot> keep = new ArrayList<>();
        for (Slot s : slots) {
            UsbDevice d = s.device;
            if (d == null || !live.containsKey(d.getDeviceName())) continue;
            keep.add(s);
            String label = d.getProductName() != null ? d.getProductName() : d.getDeviceName();
            int perm = usb.hasPermission(d) ? 1 : 0;
            out.add(d.getDeviceName() + "\t" + label + "\t" + d.getVendorId()
                    + "\t" + d.getProductId() + "\t" + perm
                    + "\t" + (s.fresh ? 1 : 0)
                    + "\t" + (s.justGranted ? 1 : 0)
                    + "\t" + (s.denied ? 1 : 0));
            s.fresh = false;
            s.justGranted = false;
        }
        slots.clear();
        slots.addAll(keep);
        // Devices that appeared without a broadcast (already plugged in).
        for (UsbDevice d : live.values()) {
            if (!isAudio(d) || find(d.getDeviceName()) != null) continue;
            Slot s = slot(d);
            String label = d.getProductName() != null ? d.getProductName() : d.getDeviceName();
            int perm = usb.hasPermission(d) ? 1 : 0;
            out.add(d.getDeviceName() + "\t" + label + "\t" + d.getVendorId()
                    + "\t" + d.getProductId() + "\t" + perm + "\t0\t0\t0");
        }
        return out.toArray(new String[0]);
    }

    public static synchronized void requestPermission(String name) {
        Slot s = find(name);
        if (s == null || usb == null || s.device == null) return;
        if (usb.hasPermission(s.device)) {
            s.justGranted = true;
            nativeOnUsb();
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        usb.requestPermission(s.device, pi);
    }

    public static synchronized int open(String name) {
        Slot s = find(name);
        if (s == null || usb == null || s.device == null) return -1;
        if (!usb.hasPermission(s.device)) return -1;
        if (connection != null && name.equals(openName))
            return connection.getFileDescriptor();
        close();
        connection = usb.openDevice(s.device);
        if (connection == null) return -1;
        openName = name;
        return connection.getFileDescriptor();
    }

    public static synchronized void close() {
        if (connection != null) {
            try { connection.close(); } catch (Throwable ignored) {}
            connection = null;
        }
        openName = "";
    }

    public static synchronized String opened() { return openName == null ? "" : openName; }

    public static String cacheDir() {
        if (app == null) return "";
        return app.getCacheDir().getAbsolutePath();
    }

    public static void beginForeground(String text) {
        noteText = text != null ? text : "Starting…";
        if (app == null) return;
        Intent i = new Intent(app, RecorderService.class);
        app.startForegroundService(i);
    }

    public static void updateForeground(String text) {
        noteText = text != null ? text : noteText;
        if (instance == null) return;
        NotificationManager nm = instance.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, instance.buildNotification(noteText));
    }

    public static void endForeground() {
        if (instance == null) return;
        instance.stopForeground(STOP_FOREGROUND_REMOVE);
        instance.stopSelf();
    }

    public static String publish(String path) {
        if (app == null || path == null) return "";
        File source = new File(path);
        if (!source.isFile()) return "";
        try {
            Uri uri = promote(source);
            return uri != null ? uri.toString() : "";
        } catch (Throwable t) {
            Log.e(TAG, "publish failed", t);
            return "";
        }
    }

    public static String[] recordings() {
        if (app == null) return new String[0];
        List<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryStore(out);
        else scanLegacy(out);
        return out.toArray(new String[0]);
    }

    public static int openUri(String uri) {
        if (app == null || uri == null || uri.isEmpty()) return -1;
        try {
            ParcelFileDescriptor pfd = app.getContentResolver()
                    .openFileDescriptor(Uri.parse(uri), "r");
            if (pfd == null) return -1;
            return pfd.detachFd();
        } catch (Throwable t) {
            Log.e(TAG, "openUri failed", t);
            return -1;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static synchronized void ensureContext(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(new RecorderService().usbReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(new RecorderService().usbReceiver, filter);
        }
        registered = true;
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (isAudio(d)) slot(d);
            }
        }
    }

    private static boolean granted(String perm) {
        Context c = activity != null ? activity : app;
        if (c == null) return false;
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static Slot slot(UsbDevice d) {
        Slot s = find(d.getDeviceName());
        if (s != null) {
            s.device = d;
            return s;
        }
        s = new Slot();
        s.device = d;
        slots.add(s);
        return s;
    }

    private static Slot find(String name) {
        for (Slot s : slots) {
            if (s.device != null && name.equals(s.device.getDeviceName())) return s;
        }
        return null;
    }

    private static void removeSlot(String name) {
        for (int i = slots.size() - 1; i >= 0; --i) {
            Slot s = slots.get(i);
            if (s.device != null && name.equals(s.device.getDeviceName()))
                slots.remove(i);
        }
    }

    private static boolean isAudio(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33)
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private Notification buildNotification(String text) {
        Intent content = new Intent(this, android.app.NativeActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.recording_notification_action_stop), stopPi).build())
                .build();
    }

    private static Uri promote(File source) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = app.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, source.getName());
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/Recordings/");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Audio.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = resolver.insert(collection, v);
            if (item == null) throw new java.io.IOException("MediaStore insert returned null");
            try (OutputStream os = resolver.openOutputStream(item);
                 InputStream is = new FileInputStream(source)) {
                if (os == null) throw new java.io.IOException("openOutputStream returned null");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(item, v, null, null);
            return item;
        }
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");
        if (!dir.exists() && !dir.mkdirs())
            throw new java.io.IOException("could not create " + dir);
        File dest = new File(dir, source.getName());
        try (InputStream is = new FileInputStream(source);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        MediaScannerConnection.scanFile(app, new String[]{dest.getAbsolutePath()},
                new String[]{"audio/flac"}, null);
        return Uri.fromFile(dest);
    }

    private static void queryStore(List<String> out) {
        ContentResolver resolver = app.getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
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
            while (c.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIdx));
                long dur = durIdx >= 0 ? c.getLong(durIdx) : 0;
                long size = sizeIdx >= 0 ? c.getLong(sizeIdx) : 0;
                String name = c.getString(nameIdx);
                out.add(uri.toString() + "\t" + (name == null ? "" : name)
                        + "\t" + dur + "\t" + size);
            }
        } catch (Throwable t) {
            Log.w(TAG, "query failed", t);
        }
    }

    private static void scanLegacy(List<String> out) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (!f.isFile()) continue;
            out.add(Uri.fromFile(f).toString() + "\t" + f.getName()
                    + "\t0\t" + f.length());
        }
    }
}
