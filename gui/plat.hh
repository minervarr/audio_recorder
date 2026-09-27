#pragma once

#include <cstdint>
#include <string>
#include <vector>

class Host;
struct android_app;

// The Android objects the NDK does not have: USB permission and the open
// connection, the microphone foreground service, and MediaStore. Implemented
// in platform/android/bridge.cc. gui/ does not include a JNI or NDK header.
namespace plat {

struct UsbDev {
    std::string name;
    std::string label;
    int vendor = 0;
    int product = 0;
    bool permission = false;
    bool fresh = false;        // attached since the previous snapshot
    bool justGranted = false;  // permission dialog just returned yes
    bool denied = false;
};

struct Recording {
    std::string uri;
    std::string name;
    int64_t durationMs = 0;
    int64_t sizeBytes = 0;
};

void setHost(Host* host);
void setApp(android_app* app);

// Registers the USB receiver and remembers the activity. Call after the
// host's looper is up, so a broadcast can wake it.
void bind();

bool hasRecordAudio();
bool hasNotifications();
void requestMissingPermissions();

// Snapshot of audio-class devices. Clears the one-shot fresh/justGranted bits.
std::vector<UsbDev> usbDevices();
void requestUsbPermission(const std::string& deviceName);

// Opens the device and retains the Java connection. The returned fd is borrowed
// from that connection; -1 on failure. closeUsb() drops the connection.
int openUsb(const std::string& deviceName);
void closeUsb();
std::string openedUsb();

std::string cacheDir();

void beginForeground(const std::string& text);
void updateForeground(const std::string& text);
void endForeground();

// Copies a finished recording into Music/Recordings and returns its content Uri.
std::string publishRecording(const std::string& path);
std::vector<Recording> queryRecordings();

// Dup'd fd for a content Uri or file Uri. Caller closes it. -1 on failure.
int openUri(const std::string& uri);

}  // namespace plat
