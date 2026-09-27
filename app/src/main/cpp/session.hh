#pragma once

#include <cstdint>
#include <string>
#include <vector>

// One process-wide audio session. The USB fd is borrowed from the Java
// service, which keeps the UsbDeviceConnection alive. Recording writes a FLAC
// file the service then copies into MediaStore. Playback accepts WAV and FLAC.
// The session outlives the activity.
namespace rec {

struct Stats {
    int inRate = 0, inCh = 0, inBits = 0, inSub = 0;
    int outRate = 0, outCh = 0, outBits = 0, outSub = 0;
    bool monitorActive = false;
    bool monitorMismatch = false;
    int64_t frames = 0;
};

struct PlayInfo {
    bool playing = false;
    bool paused = false;
    bool done = false;
    bool usbOut = false;
    int64_t positionMs = 0;
    int64_t durationMs = 0;
    int sourceRate = 0;
    int sourceBits = 0;
};

// 0 ok, 1 could not open, 2 device has no capture format.
int openUsb(int fd);
void closeUsb();
bool usbOpen();
int uacMajor();
void setLatency(int profile);   // 0 low, 1 stable

std::vector<int> captureRates();
std::vector<int> captureBits();
std::vector<int> captureChannels();
std::vector<int> outputRates();
std::vector<int> outputBits();
std::vector<int> outputChannels();

bool startRecord(const std::string& path, int rate, int channels, int bits,
                 bool monitor, float monitorVolume, bool phoneMic);
// Joins the capture thread and returns frames written. 0 means discard the file.
int64_t stopRecord();
void setMonitorVolume(float linear01);
Stats stats();

// `fd` is dup'd. Returns false if the file is neither WAV nor FLAC.
bool play(int fd, bool useUsb, float volume);
void pausePlay();
void resumePlay();
void stopPlay();
void seekPlay(int64_t positionMs);
void setPlayVolume(float linear01);
PlayInfo playInfo();

}  // namespace rec
