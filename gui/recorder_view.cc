#include "recorder_view.hh"

#include "plat.hh"
#include "recorder_ctl.hh"
#include "session.hh"

#include "app_paths.hh"
#include "canvas.hh"
#include "host.hh"
#include "msdf.hh"
#include "renderer.hh"
#include "ui_metrics.hh"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

constexpr Color kBg{0.020f, 0.020f, 0.020f, 1.f};
constexpr Color kCard{0.055f, 0.055f, 0.055f, 1.f};
constexpr Color kElev{0.086f, 0.086f, 0.086f, 1.f};
constexpr Color kGreen{0.f, 0.784f, 0.325f, 1.f};
constexpr Color kBright{0.f, 0.902f, 0.463f, 1.f};
constexpr Color kDimG{0.106f, 0.369f, 0.125f, 1.f};
constexpr Color kText{0.831f, 0.831f, 0.831f, 1.f};
constexpr Color kDim{0.408f, 0.408f, 0.408f, 1.f};
constexpr Color kAmber{1.f, 0.702f, 0.f, 1.f};
constexpr Color kBlack{0.f, 0.f, 0.f, 1.f};

constexpr int kMicRates[] = {44100, 48000};
constexpr int kMicBits[] = {16};
constexpr int kMicCh[] = {1, 2};
constexpr int kFbRates[] = {44100, 48000, 88200, 96000, 176400, 192000};
constexpr int kFbBits[] = {16, 24, 32};
constexpr int kFbCh[] = {1, 2};

enum Id : int {
    kNone = 0,
    kDevice, kPhone, kBest, kRate, kBits, kCh, kMon, kLat,
    kMonSlider, kRecord, kPlay, kSeek, kPlayVol, kRoute,
    kPickBase = 1000,
    kRecBase = 2000
};

struct Hit {
    float x, y, w, h;
    int id;
    bool contains(float px, float py) const {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
};

struct Profile {
    bool best = true;
    bool lowLat = false;
    bool monitor = false;
    int rate = 48000;
    int bits = 24;
    int channels = 2;
    float monVol = 0.5f;
    float playVol = 0.8f;
    bool playUsb = true;
};

struct Shared {
    std::mutex mu;
    bool recording = false;
    bool arming = false;
    std::string path;
    std::string error;
    int gen = 0;
    bool usb = false;
};

Shared g_shared;

std::string mmss(int64_t ms) {
    if (ms < 0) ms = 0;
    int s = (int)(ms / 1000);
    char b[16];
    std::snprintf(b, sizeof b, "%02d:%02d", s / 60, s % 60);
    return b;
}

float sliderToLinear(float frac) {
    if (frac <= 0.f) return 0.f;
    if (frac > 1.f) frac = 1.f;
    double db = -45.0 + (double)frac * 45.0;
    double c = 1.0 + db / 60.0;
    if (c < 0) c = 0;
    if (c > 1) c = 1;
    return (float)(c * c * c);
}

float linearToFrac(float linear) {
    if (linear <= 0.f) return 0.f;
    if (linear > 1.f) linear = 1.f;
    double db = -60.0 * (1.0 - std::cbrt((double)linear));
    double frac = (db + 45.0) / 45.0;
    if (frac < 0) frac = 0;
    if (frac > 1) frac = 1;
    return (float)frac;
}

std::string dbLabel(float linear) {
    if (linear <= 0.f) return "muted";
    double db = -60.0 * (1.0 - std::cbrt((double)linear));
    char b[24];
    std::snprintf(b, sizeof b, "%+.1f dB", db);
    return b;
}

bool has(const std::vector<int>& v, int x) {
    return std::find(v.begin(), v.end(), x) != v.end();
}

int highest(const std::vector<int>& v, int fallback) {
    return v.empty() ? fallback : v.back();
}

std::vector<int> orFb(std::vector<int> v, const int* fb, int n) {
    if (!v.empty()) return v;
    return std::vector<int>(fb, fb + n);
}

std::vector<int> intersect(const std::vector<int>& a, const std::vector<int>& b) {
    std::vector<int> out;
    for (int x : a)
        if (has(b, x)) out.push_back(x);
    return out;
}

}  // namespace

struct RecorderView::Impl {
    std::unique_ptr<Renderer> renderer;
    std::unique_ptr<MsdfFont> font;
    std::vector<float> curves, quads;
    bool surfaceOk = false;
    bool dirty = true;
    bool timerOn = false;
    int seenGen = 0;

    std::map<std::string, Profile> profiles;
    std::string sourceKey = "phone";
    bool phone = false;
    bool usbReady = false;
    std::string devName, devLabel;
    int vendor = 0, product = 0;
    bool needGrant = false;
    std::string error;
    std::vector<std::string> failedOpen;

    std::chrono::steady_clock::time_point recStart{};
    bool playing = false;
    bool paused = false;
    std::string playUri;

    std::vector<plat::Recording> recs;
    float scroll = 0.f;
    int picker = 0;
    int slider = 0;
    int sliderPointer = -1;
    bool dragged = false;
    std::vector<Hit> hits;
    float contentH = 0.f;

    Host* host = nullptr;

    Profile& prof() { return profiles[sourceKey]; }

    void save() {
        std::ofstream out(app_paths::stateDir() + "recorder.cfg", std::ios::trunc);
        for (const auto& kv : profiles) {
            const Profile& p = kv.second;
            out << '[' << kv.first << "]\n"
                << "best " << (p.best ? 1 : 0) << "\n"
                << "low " << (p.lowLat ? 1 : 0) << "\n"
                << "mon " << (p.monitor ? 1 : 0) << "\n"
                << "rate " << p.rate << "\n"
                << "bits " << p.bits << "\n"
                << "ch " << p.channels << "\n"
                << "mvol " << p.monVol << "\n"
                << "pvol " << p.playVol << "\n"
                << "pusb " << (p.playUsb ? 1 : 0) << "\n";
        }
    }

    void load() {
        std::ifstream in(app_paths::stateDir() + "recorder.cfg");
        std::string line, section;
        while (std::getline(in, line)) {
            if (line.size() >= 2 && line.front() == '[') {
                auto end = line.find(']');
                section = line.substr(1, end == std::string::npos ? std::string::npos : end - 1);
                continue;
            }
            auto sp = line.find(' ');
            if (section.empty() || sp == std::string::npos) continue;
            std::string k = line.substr(0, sp);
            std::string v = line.substr(sp + 1);
            Profile& p = profiles[section];
            if (k == "best") p.best = v != "0";
            else if (k == "low") p.lowLat = v == "1";
            else if (k == "mon") p.monitor = v == "1";
            else if (k == "rate") p.rate = std::atoi(v.c_str());
            else if (k == "bits") p.bits = std::atoi(v.c_str());
            else if (k == "ch") p.channels = std::atoi(v.c_str());
            else if (k == "mvol") p.monVol = std::strtof(v.c_str(), nullptr);
            else if (k == "pvol") p.playVol = std::strtof(v.c_str(), nullptr);
            else if (k == "pusb") p.playUsb = v != "0";
        }
    }

    std::vector<int> capRates() const {
        if (phone) return {kMicRates, kMicRates + 2};
        return orFb(rec::captureRates(), kFbRates, 6);
    }
    std::vector<int> capBits() const {
        if (phone) return {kMicBits, kMicBits + 1};
        return orFb(rec::captureBits(), kFbBits, 3);
    }
    std::vector<int> capCh() const {
        if (phone) return {kMicCh, kMicCh + 2};
        return orFb(rec::captureChannels(), kFbCh, 2);
    }
    std::vector<int> rates() const {
        auto cap = capRates();
        if (phone || !profMonitor()) return cap;
        auto out = rec::outputRates();
        if (out.empty()) return cap;
        return intersect(cap, out);
    }
    std::vector<int> channels() const {
        auto cap = capCh();
        if (phone || !profMonitor()) return cap;
        auto out = rec::outputChannels();
        if (out.empty()) return cap;
        int mx = *std::max_element(out.begin(), out.end());
        std::vector<int> keep;
        for (int c : cap)
            if (c <= mx) keep.push_back(c);
        return keep;
    }
    bool profMonitor() const {
        auto it = profiles.find(sourceKey);
        return it != profiles.end() && it->second.monitor;
    }

    void selectKey(const std::string& key) {
        sourceKey = key;
        Profile& p = prof();
        rec::setLatency(p.lowLat ? 0 : 1);
        if (p.best) applyBest();
        else clampFormat();
    }

    void clampFormat() {
        Profile& p = prof();
        auto r = rates(), b = capBits(), c = channels();
        if (!r.empty() && !has(r, p.rate)) p.rate = highest(r, p.rate);
        if (!b.empty() && !has(b, p.bits)) p.bits = highest(b, p.bits);
        if (!c.empty() && !has(c, p.channels)) p.channels = has(c, 2) ? 2 : highest(c, p.channels);
    }

    void applyBest() {
        Profile& p = prof();
        auto r = rates(), b = capBits(), c = channels();
        p.rate = highest(r, 48000);
        p.bits = highest(b, 24);
        p.channels = has(c, 2) ? 2 : highest(c, 2);
    }

    void detachUsb() {
        finishRecording();
        if (playing) {
            rec::stopPlay();
            playing = false;
            paused = false;
        }
        if (rec::usbOpen()) rec::closeUsb();
        plat::closeUsb();
        usbReady = false;
        devName.clear();
        armTimer();
    }

    void openDev(const plat::UsbDev& d) {
        if (usbReady && devName == d.name && rec::usbOpen()) return;
        if (std::find(failedOpen.begin(), failedOpen.end(), d.name) != failedOpen.end())
            return;
        int fd = plat::openUsb(d.name);
        if (fd < 0) {
            error = "Could not open USB device";
            failedOpen.push_back(d.name);
            return;
        }
        int rc = rec::openUsb(fd);
        if (rc != 0) {
            plat::closeUsb();
            failedOpen.push_back(d.name);
            error = rc == 2 ? "This device has no capture-capable formats"
                            : "Engine could not open device";
            return;
        }
        phone = false;
        usbReady = true;
        devName = d.name;
        devLabel = d.label.empty() ? d.name : d.label;
        vendor = d.vendor;
        product = d.product;
        needGrant = false;
        error.clear();
        char key[32];
        std::snprintf(key, sizeof key, "%04x:%04x", vendor, product);
        selectKey(key);
        save();
    }

    void refreshUsb() {
        auto devs = plat::usbDevices();
        bool still = false;
        for (const auto& d : devs)
            if (d.name == devName) still = true;
        if (usbReady && !still) detachUsb();

        const plat::UsbDev* shown = nullptr;
        for (const auto& d : devs) {
            if (!shown) shown = &d;
            if (d.fresh || d.justGranted) {
                failedOpen.erase(std::remove(failedOpen.begin(), failedOpen.end(), d.name),
                                 failedOpen.end());
                if (d.permission) openDev(d);
                else if (!d.denied) plat::requestUsbPermission(d.name);
            }
        }
        if (!usbReady && !phone) {
            for (const auto& d : devs) {
                if (d.permission) {
                    openDev(d);
                    break;
                }
            }
        }
        if (!usbReady) {
            needGrant = false;
            devLabel.clear();
            for (const auto& d : devs) {
                if (!d.permission && !d.denied) {
                    devName = d.name;
                    devLabel = d.label.empty() ? d.name : d.label;
                    vendor = d.vendor;
                    product = d.product;
                    needGrant = true;
                    break;
                }
            }
            if (!needGrant) devName.clear();
        }
        (void)shown;
    }

    void finishRecording() {
        std::string path;
        {
            std::lock_guard<std::mutex> lock(g_shared.mu);
            if (!g_shared.recording && !g_shared.arming) return;
            path = g_shared.path;
            g_shared.recording = false;
            g_shared.arming = false;
            g_shared.path.clear();
            g_shared.gen++;
        }
        int64_t n = rec::stopRecord();
        if (n > 0 && !path.empty()) plat::publishRecording(path);
        if (!path.empty()) ::unlink(path.c_str());
        plat::endForeground();
        if (n <= 0) error = "No audio captured — device did not produce data";
        reloadRecs();
        armTimer();
    }

    void reloadRecs() { recs = plat::queryRecordings(); }

    bool recordingNow() {
        std::lock_guard<std::mutex> lock(g_shared.mu);
        return g_shared.recording;
    }

    bool takeExternal() {
        bool usb = false;
        std::string err;
        int gen;
        {
            std::lock_guard<std::mutex> lock(g_shared.mu);
            gen = g_shared.gen;
            if (gen == seenGen && !g_shared.usb) return false;
            seenGen = gen;
            usb = g_shared.usb;
            g_shared.usb = false;
            err = g_shared.error;
            g_shared.error.clear();
        }
        if (!err.empty()) error = err;
        if (usb) refreshUsb();
        if (!recordingNow()) reloadRecs();
        auto info = rec::playInfo();
        if (playing && info.done) {
            rec::stopPlay();
            playing = false;
            paused = false;
        } else if (playing) {
            paused = info.paused;
        }
        armTimer();
        return true;
    }

    void armTimer() {
        bool want = recordingNow() || playing;
        if (want && !timerOn) {
            host->startTimer(1, 250);
            timerOn = true;
        } else if (!want && timerOn) {
            host->stopTimer(1);
            timerOn = false;
        }
    }

    void recordOrStop() {
        if (recordingNow()) {
            finishRecording();
            return;
        }
        if (!plat::hasRecordAudio()) {
            plat::requestMissingPermissions();
            error = "Microphone access is required to record";
            return;
        }
        if (!phone && !rec::usbOpen()) {
            error = "Plug in a USB audio device";
            return;
        }
        Profile& p = prof();
        if (p.monitor && !phone && (rates().empty() || channels().empty())) {
            p.monitor = false;
            error = "Monitor disabled: input and output share no common format";
            save();
            return;
        }
        std::string dir = plat::cacheDir();
        if (dir.empty()) {
            error = "Could not start capture";
            return;
        }
        std::time_t now = std::time(nullptr);
        std::tm tm{};
        localtime_r(&now, &tm);
        char name[64];
        std::strftime(name, sizeof name, "rec-%Y%m%d-%H%M%S.flac", &tm);
        std::string path = dir + "/" + name;
        {
            std::lock_guard<std::mutex> lock(g_shared.mu);
            g_shared.path = path;
            g_shared.arming = true;
            g_shared.recording = true;
        }
        plat::beginForeground("Starting…");
        bool ok = rec::startRecord(path, p.rate, p.channels, p.bits,
                                   p.monitor && !phone, p.monVol, phone);
        bool cancelled = false;
        {
            std::lock_guard<std::mutex> lock(g_shared.mu);
            g_shared.arming = false;
            cancelled = !g_shared.recording;
            if (!ok) {
                g_shared.recording = false;
                g_shared.path.clear();
            }
        }
        if (!ok) {
            ::unlink(path.c_str());
            plat::endForeground();
            error = "Could not start capture";
            return;
        }
        if (cancelled) return;  // the notification stop owns the file
        recStart = std::chrono::steady_clock::now();
        error.clear();
        armTimer();
    }

    void togglePlay(const std::string& uri) {
        if (recordingNow()) return;
        if (playing && playUri == uri) {
            if (paused) {
                rec::resumePlay();
                paused = false;
            } else {
                rec::pausePlay();
                paused = true;
            }
            return;
        }
        rec::stopPlay();
        playing = false;
        paused = false;
        int fd = plat::openUri(uri);
        if (fd < 0) {
            error = "Could not open recording";
            return;
        }
        Profile& p = prof();
        bool usb = p.playUsb && rec::usbOpen();
        bool ok = rec::play(fd, usb, p.playVol);
        ::close(fd);
        if (!ok) {
            error = "Playback error";
            return;
        }
        playUri = uri;
        playing = true;
        paused = false;
        error.clear();
        armTimer();
    }

    void add(float x, float y, float w, float h, int id) {
        hits.push_back(Hit{x, y, w, h, id});
    }

    void layout(float W, float H, float top, float bottom, float left, float right,
                const UiMetrics& m) {
        hits.clear();
        const float pad = m.space(16.f);
        const float x = left + pad;
        const float w = std::max(1.f, W - left - right - pad * 2.f);
        float y = top + pad - scroll;
        const float row = m.space(52.f);
        const float gap = m.space(8.f);
        auto push = [&](float h, int id) {
            if (y + h > top && y < H - bottom) add(x, y, w, h, id);
            y += h + gap;
        };

        push(m.space(84.f), kDevice);
        if (!usbReady) push(row, kPhone);
        push(row, kBest);
        float bw = (w - gap * 2.f) / 3.f;
        if (y + row > top && y < H - bottom) {
            add(x, y, bw, row, kRate);
            add(x + bw + gap, y, bw, row, kBits);
            add(x + 2.f * (bw + gap), y, bw, row, kCh);
        }
        y += row + gap;
        if (!phone && usbReady) push(row, kMon);
        if (usbReady && !phone && prof().monitor) push(row, kMonSlider);
        push(row, kLat);

        float rw = m.space(168.f);
        float rx = x + (w - rw) * 0.5f;
        if (y + m.space(64.f) > top && y < H - bottom)
            add(rx, y, rw, m.space(64.f), kRecord);
        y += m.space(64.f) + gap;
        y += m.text.body + gap;  // elapsed
        y += m.space(28.f);      // header

        for (int i = 0; i < (int)recs.size(); ++i) {
            if (y + row > top && y < H - bottom) add(x, y, w, row, kRecBase + i);
            y += row;
        }
        if (playing) {
            y += gap;
            if (y + row > top && y < H - bottom) add(x, y, w, row, kPlay);
            y += row + gap;
            if (y + row > top && y < H - bottom) add(x, y, w, row, kSeek);
            y += row + gap;
            float half = (w - gap) * 0.5f;
            if (y + row > top && y < H - bottom) {
                add(x, y, half, row, kPlayVol);
                add(x + half + gap, y, half, row, kRoute);
            }
            y += row;
        }
        y += bottom + pad;
        contentH = y + scroll - top;
        float viewH = H - top - bottom;
        float maxScroll = std::max(0.f, contentH - viewH);
        if (scroll < 0.f) scroll = 0.f;
        if (scroll > maxScroll) scroll = maxScroll;

        if (picker) {
            std::vector<int> opts = picker == 1 ? rates() : picker == 2 ? capBits() : channels();
            float ph = m.space(44.f);
            float listH = ph * (float)opts.size();
            float py = std::max(top + pad, (H - listH) * 0.5f);
            float px = x + m.space(24.f);
            float pw = w - m.space(48.f);
            for (int i = 0; i < (int)opts.size(); ++i)
                add(px, py + ph * i, pw, ph - 2.f, kPickBase + opts[i]);
        }
    }

    int hitAt(float x, float y) const {
        for (int i = (int)hits.size() - 1; i >= 0; --i)
            if (hits[i].contains(x, y)) return hits[i].id;
        return kNone;
    }

    Hit* findHit(int id) {
        for (auto& h : hits)
            if (h.id == id) return &h;
        return nullptr;
    }

    void applySlider(int id, float x) {
        Hit* h = findHit(id);
        if (!h || h->w <= 1.f) return;
        float frac = (x - h->x) / h->w;
        if (frac < 0.f) frac = 0.f;
        if (frac > 1.f) frac = 1.f;
        Profile& p = prof();
        if (id == kMonSlider) {
            p.monVol = sliderToLinear(frac);
            if (recordingNow()) rec::setMonitorVolume(p.monVol);
            save();
        } else if (id == kPlayVol) {
            p.playVol = sliderToLinear(frac);
            if (playing) rec::setPlayVolume(p.playVol);
            save();
        } else if (id == kSeek && playing) {
            auto info = rec::playInfo();
            rec::seekPlay((int64_t)(frac * (float)info.durationMs));
        }
    }

    void activate(int id) {
        if (picker && id < kPickBase) {
            picker = 0;
            return;
        }
        if (id >= kPickBase) {
            int value = id - kPickBase;
            Profile& p = prof();
            if (picker == 1) p.rate = value;
            else if (picker == 2) p.bits = value;
            else p.channels = value;
            picker = 0;
            save();
            return;
        }
        Profile& p = prof();
        switch (id) {
        case kDevice:
            if (needGrant && !devName.empty()) plat::requestUsbPermission(devName);
            break;
        case kPhone:
            if (usbReady) detachUsb();
            phone = true;
            needGrant = false;
            selectKey("phone");
            error.clear();
            save();
            break;
        case kBest:
            p.best = !p.best;
            if (p.best) applyBest();
            save();
            break;
        case kRate:
            if (!p.best && (phone || usbReady)) picker = 1;
            break;
        case kBits:
            if (!p.best && (phone || usbReady)) picker = 2;
            break;
        case kCh:
            if (!p.best && (phone || usbReady)) picker = 3;
            break;
        case kMon: {
            if (phone || !usbReady) break;
            p.monitor = !p.monitor;
            if (p.monitor && (rates().empty() || channels().empty())) {
                p.monitor = false;
                error = "Monitor disabled: input and output share no common format";
            } else if (p.monitor) {
                int oldR = p.rate, oldB = p.bits, oldC = p.channels;
                if (p.best) applyBest();
                else clampFormat();
                if (p.rate != oldR || p.bits != oldB || p.channels != oldC)
                    error = "Format adjusted for monitor compatibility";
            }
            save();
            break;
        }
        case kLat:
            p.lowLat = !p.lowLat;
            rec::setLatency(p.lowLat ? 0 : 1);
            save();
            break;
        case kRecord:
            recordOrStop();
            break;
        case kPlay:
            if (!playUri.empty()) togglePlay(playUri);
            break;
        case kRoute:
            p.playUsb = !p.playUsb;
            save();
            if (playing && !playUri.empty()) {
                std::string uri = playUri;
                playing = false;
                rec::stopPlay();
                togglePlay(uri);
            }
            break;
        default:
            if (id >= kRecBase && id < kRecBase + (int)recs.size())
                togglePlay(recs[id - kRecBase].uri);
            break;
        }
    }

    void draw(int W, int H) {
        if (!renderer || W <= 0 || H <= 0) return;
        UiMetrics m = computeUiMetrics((float)std::min(W, H));
        SafeInsets in = host->safeInsets();
        layout((float)W, (float)H, (float)in.top, (float)in.bottom,
               (float)in.left, (float)in.right, m);
        curves.clear();
        quads.clear();
        Canvas c(curves, (uint32_t)W, (uint32_t)H, nullptr,
                 (float)in.top, (float)in.bottom, (float)in.left, (float)in.right);
        if (font) c.useMsdf(font.get(), &quads);
        c.clear(kBg);

        const Profile& p = prof();
        const float size = m.text.body;
        const float small = m.text.caption;
        auto labelRow = [&](int id, const std::string& text, bool on, bool enabled) {
            Hit* h = findHit(id);
            if (!h) return;
            Color bg = on ? kDimG : kCard;
            Color fg = enabled ? (on ? kBright : kText) : kDim;
            c.rect(h->x, h->y, h->w, h->h, bg, h->h * 0.22f);
            c.text(text, h->x + m.space(14.f), h->y + (h->h - size) * 0.45f, size, fg);
        };

        if (Hit* h = findHit(kDevice)) {
            c.rect(h->x, h->y, h->w, h->h, kCard, m.space(12.f));
            std::string title = "Plug in a USB audio device";
            std::string sub;
            Color titleC = kBright;
            if (phone) {
                title = "Built-in microphone";
                sub = "Voice-recognition source · platform DSP may apply";
                titleC = kAmber;
            } else if (usbReady) {
                title = devLabel;
                int mx = highest(capRates(), 0);
                char b[80];
                std::snprintf(b, sizeof b, "UAC%d · %d ch · up to %d Hz",
                              rec::uacMajor(), highest(capCh(), 0), mx);
                sub = b;
            } else if (needGrant) {
                title = devLabel.empty() ? "USB audio device" : devLabel;
                sub = "Tap to allow access";
            }
            if (!error.empty()) sub = error;
            c.text(title, h->x + m.space(14.f), h->y + m.space(14.f), m.text.title, titleC);
            if (!sub.empty())
                c.text(sub, h->x + m.space(14.f), h->y + m.space(14.f) + m.text.title + 6.f,
                       small, error.empty() ? kDim : kAmber);
        }
        labelRow(kPhone, "Use built-in microphone", phone, true);
        labelRow(kBest, std::string("Best available format  ") + (p.best ? "On" : "Off"),
                 p.best, phone || usbReady);

        auto fmtBtn = [&](int id, const std::string& text) {
            Hit* h = findHit(id);
            if (!h) return;
            bool enabled = !p.best && (phone || usbReady);
            c.rect(h->x, h->y, h->w, h->h, kCard, h->h * 0.22f);
            c.textCentered(text, h->x + h->w * 0.5f, h->y + (h->h - small) * 0.45f, small,
                           enabled ? kBright : kDim);
        };
        char b[32];
        std::snprintf(b, sizeof b, "%d Hz", p.rate);
        fmtBtn(kRate, b);
        std::snprintf(b, sizeof b, "%d-bit", p.bits);
        fmtBtn(kBits, b);
        std::snprintf(b, sizeof b, "%d ch", p.channels);
        fmtBtn(kCh, b);

        labelRow(kMon, std::string("Monitor via USB output  ") + (p.monitor ? "On" : "Off"),
                 p.monitor, usbReady && !phone);
        if (Hit* h = findHit(kMonSlider)) {
            c.rect(h->x, h->y + h->h * 0.4f, h->w, m.space(6.f), kDimG, 3.f);
            float frac = linearToFrac(p.monVol);
            c.rect(h->x, h->y + h->h * 0.4f, h->w * frac, m.space(6.f), kBright, 3.f);
            c.textRight(dbLabel(p.monVol), h->x + h->w, h->y + 2.f, small, kBright);
        }
        labelRow(kLat, std::string("Low-latency mode  ") + (p.lowLat ? "On" : "Off"),
                 p.lowLat, true);

        if (Hit* h = findHit(kRecord)) {
            bool recOn = recordingNow();
            c.rect(h->x, h->y, h->w, h->h, recOn ? kAmber : kGreen, h->h * 0.5f);
            std::string t = recOn ? "Stop" : "Record";
            c.textCentered(t, h->x + h->w * 0.5f, h->y + (h->h - size) * 0.45f, size, kBlack);
        }

        float elapsedY = 0.f;
        if (Hit* h = findHit(kRecord)) elapsedY = h->y + h->h + m.space(8.f);
        if (recordingNow() && elapsedY > 0.f) {
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::steady_clock::now() - recStart)
                              .count();
            auto st = rec::stats();
            std::string line = "REC " + mmss(ms);
            char diag[96];
            std::snprintf(diag, sizeof diag, "   in %d Hz / %d-bit / %d ch",
                          st.inRate, st.inBits, st.inCh);
            c.textCentered(line + diag, (float)W * 0.5f, elapsedY, small, kBright);
        }

        float headerY = elapsedY > 0.f ? elapsedY + small + m.space(16.f)
                                       : (float)in.top + m.space(16.f);
        // The header is not a hit target; place it from the record button when
        // that button was laid out, otherwise skip — the list rows carry it.
        if (Hit* rec = findHit(kRecord)) {
            headerY = rec->y + rec->h + m.space(36.f);
            c.text("Past recordings", rec->x, headerY, size, kText);
        }

        for (int i = 0; i < (int)recs.size(); ++i) {
            Hit* h = findHit(kRecBase + i);
            if (!h) continue;
            bool on = playing && playUri == recs[i].uri;
            c.rect(h->x, h->y + 2.f, h->w, h->h - 4.f, on ? kDimG : kBg, 8.f);
            c.text(recs[i].name, h->x + m.space(8.f), h->y + (h->h - small) * 0.35f, small,
                   on ? kBright : kText);
            std::string right = recs[i].durationMs > 0 ? mmss(recs[i].durationMs) : "";
            if (!right.empty())
                c.textRight(right, h->x + h->w - m.space(8.f),
                            h->y + (h->h - small) * 0.35f, small, kDim);
        }
        if (recs.empty()) {
            if (Hit* record = findHit(kRecord))
                c.text("No recordings yet", record->x,
                       record->y + record->h + m.space(64.f), small, kDim);
        }

        if (playing) {
            if (Hit* h = findHit(kPlay)) {
                c.rect(h->x, h->y, h->w, h->h, kCard, 8.f);
                c.textCentered(paused ? "Play" : "Pause", h->x + h->w * 0.5f,
                               h->y + (h->h - size) * 0.45f, size, kBright);
            }
            if (Hit* h = findHit(kSeek)) {
                auto info = rec::playInfo();
                float frac = info.durationMs > 0
                                     ? (float)info.positionMs / (float)info.durationMs
                                     : 0.f;
                c.rect(h->x, h->y + h->h * 0.45f, h->w, m.space(6.f), kDimG, 3.f);
                c.rect(h->x, h->y + h->h * 0.45f, h->w * frac, m.space(6.f), kBright, 3.f);
                c.text(mmss(info.positionMs), h->x, h->y + 2.f, small, kDim);
                c.textRight(mmss(info.durationMs), h->x + h->w, h->y + 2.f, small, kDim);
            }
            if (Hit* h = findHit(kPlayVol)) {
                float frac = linearToFrac(p.playVol);
                c.rect(h->x, h->y + h->h * 0.45f, h->w, m.space(6.f), kDimG, 3.f);
                c.rect(h->x, h->y + h->h * 0.45f, h->w * frac, m.space(6.f), kBright, 3.f);
                c.text(dbLabel(p.playVol), h->x, h->y + 2.f, small, kDim);
            }
            if (Hit* h = findHit(kRoute)) {
                bool usb = rec::playInfo().usbOut;
                c.rect(h->x, h->y, h->w, h->h, kCard, 8.f);
                c.textCentered(usb ? "USB DAC" : "Phone speaker", h->x + h->w * 0.5f,
                               h->y + (h->h - small) * 0.45f, small, kBright);
            }
        }

        if (picker) {
            c.rect(0, 0, (float)W, (float)H, Color{0, 0, 0, 0.55f}, 0.f);
            std::vector<int> opts = picker == 1 ? rates() : picker == 2 ? capBits() : channels();
            const char* suffix = picker == 1 ? " Hz" : picker == 2 ? "-bit" : " ch";
            for (int v : opts) {
                Hit* h = findHit(kPickBase + v);
                if (!h) continue;
                bool on = (picker == 1 && v == p.rate) || (picker == 2 && v == p.bits) ||
                          (picker == 3 && v == p.channels);
                c.rect(h->x, h->y, h->w, h->h, on ? kDimG : kElev, 8.f);
                std::snprintf(b, sizeof b, "%d%s", v, suffix);
                c.textCentered(b, h->x + h->w * 0.5f, h->y + (h->h - size) * 0.4f, size,
                               on ? kBright : kText);
            }
        }

        if (font) renderer->initMsdf(*font);
        renderer->draw(curves, 0, {}, {}, quads);
    }
};

namespace rec_ui {

void onExternalStop() {
    std::string path;
    {
        std::lock_guard<std::mutex> lock(g_shared.mu);
        if (g_shared.arming && !g_shared.path.empty()) {
            // startRecord still holds the session mutex. Mark cancelled; the
            // UI thread that called start sees recording cleared and the
            // path is stopped below once the mutex is free. Fall through so
            // a stop during the arm still seals the file.
        }
        if (!g_shared.recording && !g_shared.arming) return;
        path = g_shared.path;
        g_shared.recording = false;
        g_shared.path.clear();
        g_shared.gen++;
    }
    int64_t n = rec::stopRecord();
    if (n > 0 && !path.empty()) plat::publishRecording(path);
    if (!path.empty()) ::unlink(path.c_str());
    plat::endForeground();
    if (n <= 0) {
        std::lock_guard<std::mutex> lock(g_shared.mu);
        g_shared.error = "No audio captured — device did not produce data";
    }
}

void onUsbSignal() {
    std::lock_guard<std::mutex> lock(g_shared.mu);
    g_shared.usb = true;
    g_shared.gen++;
}

}  // namespace rec_ui

RecorderView::~RecorderView() { delete self_; }

bool RecorderView::create(std::unique_ptr<Host> host) {
    host_ = std::move(host);
    self_ = new Impl();
    self_->host = host_.get();
    plat::setHost(host_.get());
    if (!host_->init(this)) return false;

    self_->renderer = std::make_unique<Renderer>(host_->surfaceProvider(), host_->assetReader());
    auto font = std::make_unique<MsdfFont>();
    std::string cache = app_paths::stateDir() + "cmu.msdfcache";
    if (font->generate(host_->assetReader(), "fonts/cmu.ttf", cache.c_str())) {
        self_->renderer->initMsdf(*font);
        self_->font = std::move(font);
    }
    self_->load();
    plat::bind();
    plat::requestMissingPermissions();
    self_->refreshUsb();
    self_->reloadRecs();
    {
        std::lock_guard<std::mutex> lock(g_shared.mu);
        self_->seenGen = g_shared.gen;
    }
    if (self_->recordingNow()) self_->armTimer();
    host_->showWindow();
    self_->surfaceOk = true;
    self_->dirty = true;
    return true;
}

void RecorderView::run() {
    while (running_) {
        host_->pump(self_->dirty);
        if (host_->quitRequested()) break;
        if (self_->takeExternal()) self_->dirty = true;
        if (self_->dirty && self_->surfaceOk && self_->renderer) {
            self_->draw(self_->renderer->width(), self_->renderer->height());
            self_->dirty = false;
        }
    }
}

void RecorderView::onHostResized() {
    if (self_ && self_->renderer) self_->renderer->notifyResized();
    if (self_) self_->dirty = true;
}
void RecorderView::onHostLayoutInvalidated() { if (self_) self_->dirty = true; }
void RecorderView::onHostExposed() { if (self_) self_->dirty = true; }
void RecorderView::shutdown() { running_ = false; }

void RecorderView::onHostReady() {
    if (!self_) return;
    self_->refreshUsb();
    self_->dirty = true;
}

void RecorderView::onHostFocusGained() {
    if (!self_) return;
    plat::requestMissingPermissions();
    self_->refreshUsb();
    self_->reloadRecs();
    self_->dirty = true;
}

void RecorderView::onSurfaceLost() {
    if (self_) self_->surfaceOk = false;
}

bool RecorderView::onSurfaceRecreated() {
    if (!self_ || !self_->renderer) return false;
    self_->surfaceOk = self_->renderer->recreate_surface();
    self_->dirty = true;
    return self_->surfaceOk;
}

void RecorderView::onAppEvent(int, intptr_t, intptr_t) {
    if (self_ && self_->takeExternal()) self_->dirty = true;
}

void RecorderView::onLButtonUp(int x, int y) {
    if (!self_) return;
    if (self_->slider) {
        if (!self_->dragged) self_->applySlider(self_->slider, (float)x);
        self_->slider = 0;
        self_->sliderPointer = -1;
        self_->dragged = false;
        self_->dirty = true;
        return;
    }
    if (self_->dragged) {
        self_->dragged = false;
        return;
    }
    self_->activate(self_->hitAt((float)x, (float)y));
    self_->dirty = true;
}

void RecorderView::onPointerDown(int pointerId, int x, int y) {
    if (!self_ || self_->sliderPointer >= 0) return;
    int id = self_->hitAt((float)x, (float)y);
    if (id == kMonSlider || id == kSeek || id == kPlayVol) {
        self_->slider = id;
        self_->sliderPointer = pointerId;
        self_->applySlider(id, (float)x);
        self_->dirty = true;
    }
}

void RecorderView::onPointerMove(int pointerId, int x, int y) {
    if (!self_ || pointerId != self_->sliderPointer) return;
    self_->applySlider(self_->slider, (float)x);
    self_->dirty = true;
}

void RecorderView::onPointerUp(int pointerId, int, int) {
    if (!self_ || pointerId != self_->sliderPointer) return;
    self_->slider = 0;
    self_->sliderPointer = -1;
    self_->save();
}

void RecorderView::onMouseWheel(int, int, int delta) {
    if (!self_ || self_->slider) return;
    self_->scroll -= (float)delta;
    self_->dirty = true;
}

void RecorderView::onDragEnd(int, int) {
    if (self_) self_->dragged = true;
}

void RecorderView::onTimer(int) {
    if (!self_) return;
    if (self_->recordingNow()) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - self_->recStart)
                          .count();
        plat::updateForeground("Recording · " + mmss(ms));
    }
    if (self_->playing) {
        auto info = rec::playInfo();
        self_->paused = info.paused;
        if (info.done) {
            rec::stopPlay();
            self_->playing = false;
            self_->paused = false;
            self_->armTimer();
        }
    }
    self_->dirty = true;
}
