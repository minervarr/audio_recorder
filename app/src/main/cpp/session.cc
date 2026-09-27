#include "session.hh"

#include "aaudio_sink.h"
#include "aaudio_source.h"
#include "usb_audio.h"

#if RECORDER_HAVE_FLAC
#include "flac_decoder.h"
#include "flac_encoder.h"
#endif

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "RecorderCore", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "RecorderCore", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RecorderCore", __VA_ARGS__)

namespace rec {
namespace {

constexpr double kDbFloor = -60.0;

float linearToGain(float linear) {
    if (linear <= 0.f) return 0.f;
    if (linear > 1.f) linear = 1.f;
    double db = kDbFloor * (1.0 - std::cbrt((double)linear));
    return (float)std::pow(10.0, db / 20.0);
}

int pickMonitorChannels(const std::vector<int>& outCh, int inCh) {
    if (outCh.empty()) return inCh;
    int best = 0;
    for (int c : outCh) {
        if (c == inCh) return c;
        if (c > inCh && (best == 0 || c < best)) best = c;
    }
    return best;
}

std::vector<int> sortedUnique(std::vector<int> v) {
    std::sort(v.begin(), v.end());
    v.erase(std::unique(v.begin(), v.end()), v.end());
    return v;
}

// Left-aligned subslot little-endian -> full-scale int32 (MSB at bit 31).
void unpackToInt32(const uint8_t* in, int samples, int subslot, int32_t* out) {
    int shift = (4 - subslot) * 8;
    if (shift < 0) shift = 0;
    for (int i = 0; i < samples; ++i) {
        uint32_t v = 0;
        for (int b = 0; b < subslot && b < 4; ++b)
            v |= (uint32_t)in[i * subslot + b] << (8 * b);
        if (shift) v <<= shift;
        out[i] = (int32_t)v;
    }
}

void expandChannels(const uint8_t* in, int frames, int inCh, int outCh, int sub,
                    std::vector<uint8_t>& out) {
    if (outCh <= inCh) {
        out.assign(in, in + (size_t)frames * inCh * sub);
        return;
    }
    out.resize((size_t)frames * outCh * sub);
    for (int f = 0; f < frames; ++f) {
        const uint8_t* src = in + (size_t)f * inCh * sub;
        uint8_t* dst = out.data() + (size_t)f * outCh * sub;
        std::memcpy(dst, src, (size_t)inCh * sub);
        for (int c = inCh; c < outCh; ++c)
            std::memcpy(dst + (size_t)c * sub, src, (size_t)sub);
    }
}

struct WavPcm {
    int rate = 0, channels = 0, bits = 0, subslot = 0;
    int64_t dataBytes = 0;
    int64_t dataOff = 0;
    int fd = -1;
};

bool parseWav(int fd, WavPcm& w) {
    uint8_t hdr[12];
    if (pread(fd, hdr, 12, 0) != 12) return false;
    if (std::memcmp(hdr, "RIFF", 4) || std::memcmp(hdr + 8, "WAVE", 4)) return false;
    off_t pos = 12;
    bool gotFmt = false, gotData = false;
    while (pos < 1024 * 1024) {
        uint8_t chunk[8];
        if (pread(fd, chunk, 8, pos) != 8) break;
        uint32_t sz = (uint32_t)chunk[4] | ((uint32_t)chunk[5] << 8) |
                      ((uint32_t)chunk[6] << 16) | ((uint32_t)chunk[7] << 24);
        pos += 8;
        if (std::memcmp(chunk, "fmt ", 4) == 0 && sz >= 16) {
            uint8_t fmt[16];
            if (pread(fd, fmt, 16, pos) != 16) return false;
            int audioFmt = fmt[0] | (fmt[1] << 8);
            if (audioFmt != 1) return false;
            w.channels = fmt[2] | (fmt[3] << 8);
            w.rate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            int block = fmt[12] | (fmt[13] << 8);
            w.bits = fmt[14] | (fmt[15] << 8);
            w.subslot = w.channels > 0 ? block / w.channels : 0;
            gotFmt = w.rate > 0 && w.channels > 0 && w.subslot > 0;
        } else if (std::memcmp(chunk, "data", 4) == 0) {
            w.dataOff = pos;
            w.dataBytes = sz;
            gotData = true;
            break;
        }
        pos += sz + (sz & 1);
    }
    w.fd = fd;
    return gotFmt && gotData;
}

struct Session {
    std::mutex mu;
    UsbAudioDriver usb;
    bool usb_open = false;
    int latency = UsbAudioDriver::PROFILE_STABLE;

    std::atomic<bool> rec_run{false};
    std::thread rec_thread;
#if RECORDER_HAVE_FLAC
    std::unique_ptr<ae::FlacEncoder> flac;
#endif
    std::string rec_path;
    int inRate = 0, inCh = 0, inBits = 0, inSub = 0;
    int outRate = 0, outCh = 0, outBits = 0, outSub = 0;
    bool mon_active = false;
    bool mon_mismatch = false;
    std::atomic<int64_t> frames{0};
    bool phone = false;
    float mon_volume = 1.f;
    std::unique_ptr<ae::AAudioSource> mic;

    std::atomic<bool> play_run{false};
    std::atomic<bool> play_pause{false};
    std::atomic<bool> play_done{false};
    std::thread play_thread;
    int64_t play_pos_frames = 0;
    int64_t play_total_frames = 0;
    int play_rate = 0;
    int play_bits = 0;
    bool play_usb = false;
    float play_volume = 1.f;
    std::unique_ptr<ae::AAudioSink> speaker;
    std::atomic<int64_t> seek_ms{-1};
};

Session& S() {
    static Session s;
    return s;
}

void applyUsbGain(UsbAudioDriver& usb, float linear) {
    usb.setSoftwareGain(linearToGain(linear));
}

void feedMonitor(Session& s, const uint8_t* data, int bytes) {
    if (!s.mon_active || s.inSub <= 0 || s.inCh <= 0) return;
    int frame = s.inSub * s.inCh;
    if (frame <= 0) return;
    int frames = bytes / frame;
    if (frames <= 0) return;
    std::vector<uint8_t> expanded;
    const uint8_t* src = data;
    int srcSamples = frames * s.inCh;
    if (s.outCh != s.inCh) {
        expandChannels(data, frames, s.inCh, s.outCh, s.inSub, expanded);
        src = expanded.data();
        srcSamples = frames * s.outCh;
    }
    float gain = linearToGain(s.mon_volume);
    if (gain >= 0.9999f && s.outSub == s.inSub) {
        s.usb.write(src, srcSamples * s.inSub);
        return;
    }
    std::vector<int32_t> i32((size_t)srcSamples);
    unpackToInt32(src, srcSamples, s.inSub, i32.data());
    s.usb.writeInt32(i32.data(), srcSamples);
}

void recordLoop(Session* s) {
    std::vector<uint8_t> buf(16 * 1024);
    while (s->rec_run.load(std::memory_order_relaxed)) {
        int n = 0;
        if (s->phone && s->mic) n = s->mic->read(buf.data(), (int)buf.size());
        else n = s->usb.readCapture(buf.data(), (int)buf.size());
        if (n < 0) break;
        if (n == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        int frame = s->inSub * s->inCh;
        if (frame > 0) n = (n / frame) * frame;
        if (n <= 0) continue;
#if RECORDER_HAVE_FLAC
        if (s->flac && s->flac->encode(buf.data(), n))
            s->frames.fetch_add(n / frame, std::memory_order_relaxed);
        else
            break;
#else
        (void)n;
        break;
#endif
        if (!s->phone) feedMonitor(*s, buf.data(), n);
    }
}

bool armUsb(Session& s, int rate, int channels, int bits, bool monitor) {
    s.usb.setLatencyProfile(s.latency);
    if (!s.usb.configureCapture(rate, channels, bits)) return false;
    s.inRate = s.usb.getConfiguredCaptureRate();
    s.inCh = s.usb.getConfiguredCaptureChannels();
    s.inBits = s.usb.getConfiguredCaptureBitDepth();
    s.inSub = s.usb.getConfiguredCaptureSubslotSize();
    if (s.inSub <= 0) s.inSub = (s.inBits + 7) / 8;
    s.outRate = s.outCh = s.outBits = s.outSub = 0;
    s.mon_active = false;
    s.mon_mismatch = false;
    if (monitor) {
        int mch = pickMonitorChannels(s.usb.getOutputChannelCounts(), s.inCh);
        if (mch > 0 && s.usb.configure(s.inRate, mch, s.inBits) && s.usb.start()) {
            s.outRate = s.usb.getConfiguredRate();
            s.outCh = s.usb.getConfiguredChannels();
            s.outBits = s.usb.getConfiguredBitDepth();
            s.outSub = s.usb.getConfiguredSubslotSize();
            if (s.outRate != s.inRate) {
                s.usb.stop();
                s.mon_mismatch = true;
                LOGW("monitor rate mismatch %d vs %d", s.outRate, s.inRate);
            } else {
                applyUsbGain(s.usb, s.mon_volume);
                s.mon_active = true;
            }
        } else {
            LOGW("monitor output failed");
        }
    }
    if (!s.usb.startCapture()) return false;
    return true;
}

void disarmUsb(Session& s) {
    s.mon_active = false;
    s.usb.stopCapture();
    s.usb.stop();
}

bool armMic(Session& s, int rate, int channels) {
    s.mic.reset(new ae::AAudioSource());
    ae::AudioFormat fmt;
    fmt.sampleRate = rate;
    fmt.channels = channels;
    fmt.bitDepth = 16;
    fmt.subslotBytes = 2;
    if (!s.mic->configure(fmt) || !s.mic->start()) {
        s.mic.reset();
        return false;
    }
    ae::AudioFormat g = s.mic->activeFormat();
    s.inRate = g.sampleRate;
    s.inCh = g.channels;
    s.inBits = 16;
    s.inSub = 2;
    s.outRate = s.outCh = s.outBits = s.outSub = 0;
    s.mon_active = false;
    s.mon_mismatch = false;
    return true;
}

void joinRecord(Session& s) {
    s.rec_run.store(false, std::memory_order_relaxed);
    if (s.phone && s.mic) s.mic->stop();
    else s.usb.stopCapture();
    if (s.rec_thread.joinable()) s.rec_thread.join();
    if (s.phone) s.mic.reset();
    else disarmUsb(s);
}

bool openFlac(Session& s, const std::string& path) {
#if RECORDER_HAVE_FLAC
    int fd = ::open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0644);
    if (fd < 0) return false;
    s.flac.reset(new ae::FlacEncoder());
    ae::AudioFormat fmt;
    fmt.sampleRate = s.inRate;
    fmt.channels = s.inCh;
    fmt.bitDepth = s.inBits > 0 ? s.inBits : s.inSub * 8;
    fmt.subslotBytes = s.inSub > 0 ? s.inSub : (fmt.bitDepth + 7) / 8;
    if (!s.flac->open(fd, fmt)) {
        s.flac.reset();
        ::close(fd);
        std::remove(path.c_str());
        return false;
    }
    ::close(fd);
    s.rec_path = path;
    return true;
#else
    (void)s;
    (void)path;
    return false;
#endif
}

void sealFlac(Session& s) {
#if RECORDER_HAVE_FLAC
    if (s.flac) {
        s.flac->close();
        s.flac.reset();
    }
#endif
    if (s.frames.load() == 0 && !s.rec_path.empty()) std::remove(s.rec_path.c_str());
    s.rec_path.clear();
}

void scalePcm(uint8_t* data, int bytes, int subslot, float gain) {
    if (gain >= 0.9999f || subslot <= 0) return;
    int samples = bytes / subslot;
    for (int i = 0; i < samples; ++i) {
        uint8_t* p = data + (size_t)i * subslot;
        int32_t v = 0;
        for (int b = 0; b < subslot && b < 4; ++b) v |= (int32_t)p[b] << (8 * b);
        int shift = (4 - subslot) * 8;
        if (subslot < 4) {
            v <<= shift;
            v = (int32_t)((double)v * gain);
            v >>= shift;
        } else {
            v = (int32_t)((double)v * gain);
        }
        for (int b = 0; b < subslot && b < 4; ++b) p[b] = (uint8_t)((v >> (8 * b)) & 0xff);
    }
}

struct PcmSource {
    virtual ~PcmSource() = default;
    virtual int read(uint8_t* out, int max) = 0;
    virtual bool seekMs(int64_t ms) = 0;
    int rate = 0, channels = 0, bits = 0, subslot = 0;
    int64_t totalFrames = 0;
};

struct WavSource : PcmSource {
    WavPcm w;
    int fd = -1;
    int64_t frameOff = 0;
    bool open(int srcfd) {
        fd = ::dup(srcfd);
        if (fd < 0 || !parseWav(fd, w)) return false;
        rate = w.rate;
        channels = w.channels;
        bits = w.bits;
        subslot = w.subslot;
        int fb = subslot * channels;
        totalFrames = fb > 0 ? w.dataBytes / fb : 0;
        return true;
    }
    ~WavSource() override { if (fd >= 0) ::close(fd); }
    int read(uint8_t* out, int max) override {
        int fb = subslot * channels;
        if (fb <= 0) return -1;
        int64_t left = (totalFrames - frameOff) * fb;
        if (left <= 0) return -1;
        if (max > left) max = (int)left;
        max = (max / fb) * fb;
        if (max <= 0) return 0;
        off_t at = (off_t)(w.dataOff + frameOff * fb);
        int n = (int)::pread(fd, out, (size_t)max, at);
        if (n <= 0) return -1;
        n = (n / fb) * fb;
        frameOff += n / fb;
        return n;
    }
    bool seekMs(int64_t ms) override {
        if (rate <= 0) return false;
        int64_t f = ms * rate / 1000;
        if (f < 0) f = 0;
        if (f > totalFrames) f = totalFrames;
        frameOff = f;
        return true;
    }
};

#if RECORDER_HAVE_FLAC
struct FlacSource : PcmSource {
    ae::FlacDecoder dec;
    bool open(int srcfd) {
        if (!dec.open(srcfd, 0, -1)) return false;
        ae::AudioFormat f = dec.format();
        rate = f.sampleRate;
        channels = f.channels;
        bits = f.bitDepth;
        subslot = f.subslotBytes > 0 ? f.subslotBytes : (bits + 7) / 8;
        int64_t ms = dec.durationMs();
        totalFrames = (ms > 0 && rate > 0) ? ms * rate / 1000 : 0;
        return rate > 0 && channels > 0 && subslot > 0;
    }
    int read(uint8_t* out, int max) override { return dec.read(out, max); }
    bool seekMs(int64_t ms) override { return dec.seekMs(ms); }
};
#endif

void playLoop(Session* s, std::unique_ptr<PcmSource> src) {
    std::vector<uint8_t> buf(16 * 1024);
    int fb = src->subslot * src->channels;
    while (s->play_run.load(std::memory_order_relaxed)) {
        int64_t sk = s->seek_ms.exchange(-1, std::memory_order_relaxed);
        if (sk >= 0 && src->seekMs(sk)) {
            std::lock_guard<std::mutex> lock(s->mu);
            s->play_pos_frames = s->play_rate > 0 ? sk * s->play_rate / 1000 : 0;
        }
        if (s->play_pause.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }
        int n = src->read(buf.data(), (int)buf.size());
        if (n == 0) continue;
        if (n < 0) break;
        if (fb > 0) n = (n / fb) * fb;
        bool usb = false;
        float volume = 1.f;
        ae::AAudioSink* speaker = nullptr;
        {
            std::lock_guard<std::mutex> lock(s->mu);
            usb = s->play_usb;
            volume = s->play_volume;
            speaker = s->speaker.get();
            s->play_pos_frames += fb > 0 ? n / fb : 0;
        }
        if (usb) {
            applyUsbGain(s->usb, volume);
            if (src->subslot == 4)
                s->usb.writeInt32(reinterpret_cast<int32_t*>(buf.data()), n / 4);
            else if (src->subslot == 3)
                s->usb.writeInt24Packed(buf.data(), n);
            else if (src->subslot == 2)
                s->usb.writeInt16(reinterpret_cast<int16_t*>(buf.data()), n / 2);
            else
                s->usb.write(buf.data(), n);
        } else if (speaker) {
            scalePcm(buf.data(), n, src->subslot, linearToGain(volume));
            speaker->write(buf.data(), n);
        }
    }
    s->play_done.store(true, std::memory_order_relaxed);
    s->play_run.store(false, std::memory_order_relaxed);
}

void joinPlay(Session& s) {
    s.play_run.store(false, std::memory_order_relaxed);
    s.play_pause.store(false, std::memory_order_relaxed);
    if (s.play_thread.joinable()) s.play_thread.join();
    if (s.play_usb) s.usb.stop();
    if (s.speaker) {
        s.speaker->stop();
        s.speaker.reset();
    }
    s.play_usb = false;
}

}  // namespace

int openUsb(int fd) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    if (s.rec_run.load() || s.play_run.load()) return 1;
    if (!s.usb.open(fd) || !s.usb.parseDescriptors()) {
        s.usb.close();
        s.usb_open = false;
        return 1;
    }
    if (!s.usb.hasCaptureFormats()) {
        s.usb.close();
        s.usb_open = false;
        return 2;
    }
    s.usb.setLatencyProfile(s.latency);
    s.usb_open = true;
    LOGI("usb open, uac %d", s.usb.getUacVersion());
    return 0;
}

void closeUsb() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    joinRecord(s);
    joinPlay(s);
    if (s.usb_open) s.usb.close();
    s.usb_open = false;
}

bool usbOpen() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    return s.usb_open;
}

int uacMajor() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    int v = s.usb_open ? s.usb.getUacVersion() : 0;
    return v >= 0x200 ? 2 : (v >= 0x100 ? 1 : 0);
}

void setLatency(int profile) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    s.latency = profile == 0 ? UsbAudioDriver::PROFILE_LOW_LATENCY
                             : UsbAudioDriver::PROFILE_STABLE;
    if (s.usb_open && !s.rec_run.load() && !s.play_run.load())
        s.usb.setLatencyProfile(s.latency);
}

std::vector<int> captureRates() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getCaptureRates() : std::vector<int>{});
}
std::vector<int> captureBits() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getCaptureBitDepths() : std::vector<int>{});
}
std::vector<int> captureChannels() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getCaptureChannelCounts() : std::vector<int>{});
}
std::vector<int> outputRates() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getOutputRates() : std::vector<int>{});
}
std::vector<int> outputBits() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getOutputBitDepths() : std::vector<int>{});
}
std::vector<int> outputChannels() {
    std::lock_guard<std::mutex> lock(S().mu);
    return sortedUnique(S().usb_open ? S().usb.getOutputChannelCounts() : std::vector<int>{});
}

bool startRecord(const std::string& path, int rate, int channels, int bits,
                 bool monitor, float monitorVolume, bool phoneMic) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    if (s.rec_run.load()) return false;
    joinPlay(s);
    s.frames.store(0);
    s.mon_volume = monitorVolume;
    s.phone = phoneMic;
    bool armed = phoneMic ? armMic(s, rate, channels)
                          : (s.usb_open && armUsb(s, rate, channels, bits, monitor));
    if (!armed) {
        if (phoneMic) s.mic.reset();
        else disarmUsb(s);
        return false;
    }
    if (!openFlac(s, path)) {
        if (phoneMic) s.mic.reset();
        else disarmUsb(s);
        return false;
    }
    s.rec_run.store(true);
    s.rec_thread = std::thread(recordLoop, &s);

    // Stuck-ADC recovery: some UAC2 devices accept SET_CUR and then send
    // nothing until the alt-setting is armed a second time.
    if (!phoneMic) {
        auto waitPacket = [&s]() {
            for (int i = 0; i < 50; ++i) {
                if (s.frames.load() > 0) return true;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            return s.frames.load() > 0;
        };
        if (!waitPacket()) {
            LOGW("no capture packet; re-arming once");
            joinRecord(s);
            sealFlac(s);
            s.frames.store(0);
            if (!armUsb(s, rate, channels, bits, monitor) || !openFlac(s, path)) {
                disarmUsb(s);
                return false;
            }
            s.rec_run.store(true);
            s.rec_thread = std::thread(recordLoop, &s);
            if (!waitPacket()) {
                LOGE("capture still silent after retry");
                joinRecord(s);
                sealFlac(s);
                return false;
            }
        }
    }
    return true;
}

int64_t stopRecord() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    if (!s.rec_thread.joinable() && !s.rec_run.load()) return s.frames.load();
    joinRecord(s);
    int64_t n = s.frames.load();
    sealFlac(s);
    return n;
}

void setMonitorVolume(float linear01) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    s.mon_volume = linear01;
    if (s.mon_active) applyUsbGain(s.usb, linear01);
}

Stats stats() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    Stats st;
    st.inRate = s.inRate;
    st.inCh = s.inCh;
    st.inBits = s.inBits;
    st.inSub = s.inSub;
    st.outRate = s.outRate;
    st.outCh = s.outCh;
    st.outBits = s.outBits;
    st.outSub = s.outSub;
    st.monitorActive = s.mon_active;
    st.monitorMismatch = s.mon_mismatch;
    st.frames = s.frames.load();
    return st;
}

bool play(int fd, bool useUsb, float volume) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    if (s.rec_run.load()) return false;
    joinPlay(s);

    uint8_t magic[4] = {};
    if (pread(fd, magic, 4, 0) != 4) return false;

    std::unique_ptr<PcmSource> src;
    if (std::memcmp(magic, "RIFF", 4) == 0) {
        std::unique_ptr<WavSource> w(new WavSource());
        if (!w->open(fd)) return false;
        src = std::move(w);
    }
#if RECORDER_HAVE_FLAC
    else if (std::memcmp(magic, "fLaC", 4) == 0) {
        std::unique_ptr<FlacSource> f(new FlacSource());
        if (!f->open(fd)) return false;
        src = std::move(f);
    }
#endif
    else {
        LOGW("playback: not wav/flac");
        return false;
    }

    s.play_volume = volume;
    s.play_rate = src->rate;
    s.play_bits = src->bits;
    s.play_total_frames = src->totalFrames;
    s.play_pos_frames = 0;
    s.play_done.store(false);
    s.play_pause.store(false);
    s.play_usb = false;

    if (useUsb && s.usb_open) {
        s.usb.setLatencyProfile(s.latency);
        if (s.usb.configure(src->rate, src->channels, src->bits) && s.usb.start()) {
            applyUsbGain(s.usb, volume);
            s.play_usb = true;
        } else {
            LOGW("usb playback configure failed, speaker fallback");
        }
    }
    if (!s.play_usb) {
        s.speaker.reset(new ae::AAudioSink());
        ae::AudioFormat fmt;
        fmt.sampleRate = src->rate;
        fmt.channels = src->channels;
        fmt.bitDepth = src->bits;
        fmt.subslotBytes = src->subslot;
        if (!s.speaker->configure(fmt) || !s.speaker->start()) {
            s.speaker.reset();
            LOGE("speaker playback failed");
            return false;
        }
    }
    s.play_run.store(true);
    s.play_thread = std::thread(playLoop, &s, std::move(src));
    return true;
}

void pausePlay() {
    Session& s = S();
    s.play_pause.store(true);
    std::lock_guard<std::mutex> lock(s.mu);
    if (s.play_usb) s.usb.setPaused(true);
    else if (s.speaker) s.speaker->pause();
}
void resumePlay() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    if (s.play_usb) s.usb.setPaused(false);
    else if (s.speaker) s.speaker->resume();
    s.play_pause.store(false);
}
void stopPlay() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    joinPlay(s);
    s.play_done.store(false);
}
void seekPlay(int64_t positionMs) {
    if (positionMs < 0) positionMs = 0;
    S().seek_ms.store(positionMs, std::memory_order_relaxed);
}
void setPlayVolume(float linear01) {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    s.play_volume = linear01;
    if (s.play_usb) applyUsbGain(s.usb, linear01);
}

PlayInfo playInfo() {
    Session& s = S();
    std::lock_guard<std::mutex> lock(s.mu);
    PlayInfo i;
    i.playing = s.play_run.load() && !s.play_pause.load();
    i.paused = s.play_pause.load() && (s.play_thread.joinable() || s.play_run.load());
    i.done = s.play_done.load() && !s.play_run.load();
    i.usbOut = s.play_usb;
    i.sourceRate = s.play_rate;
    i.sourceBits = s.play_bits;
    i.positionMs = s.play_rate > 0 ? s.play_pos_frames * 1000 / s.play_rate : 0;
    i.durationMs = s.play_rate > 0 ? s.play_total_frames * 1000 / s.play_rate : 0;
    return i;
}

}  // namespace rec
