#include "plat.hh"
#include "recorder_ctl.hh"

#include "host.hh"

#include <android/log.h>
#include <jni.h>
#include <android_native_app_glue.h>

#include <cstdlib>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Recorder", __VA_ARGS__)

namespace {

Host* g_host = nullptr;
android_app* g_app = nullptr;
jclass g_svc = nullptr;

JNIEnv* env() {
    if (!g_app || !g_app->activity || !g_app->activity->vm) return nullptr;
    JNIEnv* e = nullptr;
    JavaVM* vm = g_app->activity->vm;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) == JNI_OK) return e;
    if (vm->AttachCurrentThread(&e, nullptr) != JNI_OK) return nullptr;
    return e;
}

bool ok(JNIEnv* e) {
    if (!e || !e->ExceptionCheck()) return e != nullptr;
    e->ExceptionDescribe();
    e->ExceptionClear();
    return false;
}

jclass service(JNIEnv* e) {
    if (g_svc) return g_svc;
    if (!g_app || !g_app->activity) return nullptr;
    jobject act = g_app->activity->clazz;
    jclass actCls = e->GetObjectClass(act);
    jmethodID getLoader = e->GetMethodID(actCls, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = e->CallObjectMethod(act, getLoader);
    if (!ok(e) || !loader) return nullptr;
    jclass loaderCls = e->GetObjectClass(loader);
    jmethodID loadClass = e->GetMethodID(
            loaderCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = e->NewStringUTF("com.example.audio_recorder.service.RecorderService");
    jobject cls = e->CallObjectMethod(loader, loadClass, name);
    e->DeleteLocalRef(name);
    if (!ok(e) || !cls) {
        LOGE("RecorderService class not found");
        return nullptr;
    }
    g_svc = static_cast<jclass>(e->NewGlobalRef(cls));
    return g_svc;
}

jmethodID method(JNIEnv* e, const char* name, const char* sig) {
    jclass cls = service(e);
    if (!cls) return nullptr;
    jmethodID id = e->GetStaticMethodID(cls, name, sig);
    if (!ok(e)) return nullptr;
    return id;
}

std::string jstr(JNIEnv* e, jstring s) {
    if (!s) return {};
    const char* p = e->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) e->ReleaseStringUTFChars(s, p);
    return out;
}

std::vector<std::string> jstrings(JNIEnv* e, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = e->GetArrayLength(arr);
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(e->GetObjectArrayElement(arr, i));
        out.push_back(jstr(e, s));
        if (s) e->DeleteLocalRef(s);
    }
    return out;
}

void wake() {
    if (g_host) g_host->postAppEvent(1);
}

int fieldInt(const std::string& s, int index) {
    int field = 0;
    std::string cur;
    for (char c : s) {
        if (c == '\t') {
            if (field == index) return std::atoi(cur.c_str());
            ++field;
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    if (field == index) return std::atoi(cur.c_str());
    return 0;
}

std::string fieldStr(const std::string& s, int index) {
    int field = 0;
    std::string cur;
    for (char c : s) {
        if (c == '\t') {
            if (field == index) return cur;
            ++field;
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    if (field == index) return cur;
    return {};
}

}  // namespace

namespace plat {

void setHost(Host* host) { g_host = host; }
void setApp(android_app* app) { g_app = app; }

void bind() {
    JNIEnv* e = env();
    if (!e || !g_app || !g_app->activity) return;
    jmethodID m = method(e, "bind", "(Landroid/app/Activity;)V");
    if (!m) return;
    e->CallStaticVoidMethod(g_svc, m, g_app->activity->clazz);
    ok(e);
}

bool hasRecordAudio() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "hasRecordAudio", "()Z") : nullptr;
    if (!m) return false;
    jboolean v = e->CallStaticBooleanMethod(g_svc, m);
    return ok(e) && v == JNI_TRUE;
}

bool hasNotifications() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "hasNotifications", "()Z") : nullptr;
    if (!m) return false;
    jboolean v = e->CallStaticBooleanMethod(g_svc, m);
    return ok(e) && v == JNI_TRUE;
}

void requestMissingPermissions() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "requestMissing", "()V") : nullptr;
    if (!m) return;
    e->CallStaticVoidMethod(g_svc, m);
    ok(e);
}

std::vector<UsbDev> usbDevices() {
    std::vector<UsbDev> out;
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "devices", "()[Ljava/lang/String;") : nullptr;
    if (!m) return out;
    jobjectArray arr = static_cast<jobjectArray>(e->CallStaticObjectMethod(g_svc, m));
    if (!ok(e)) return out;
    for (const std::string& row : jstrings(e, arr)) {
        UsbDev d;
        d.name = fieldStr(row, 0);
        d.label = fieldStr(row, 1);
        d.vendor = fieldInt(row, 2);
        d.product = fieldInt(row, 3);
        d.permission = fieldInt(row, 4) != 0;
        d.fresh = fieldInt(row, 5) != 0;
        d.justGranted = fieldInt(row, 6) != 0;
        d.denied = fieldInt(row, 7) != 0;
        if (!d.name.empty()) out.push_back(std::move(d));
    }
    return out;
}

void requestUsbPermission(const std::string& deviceName) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "requestPermission", "(Ljava/lang/String;)V") : nullptr;
    if (!m) return;
    jstring s = e->NewStringUTF(deviceName.c_str());
    e->CallStaticVoidMethod(g_svc, m, s);
    e->DeleteLocalRef(s);
    ok(e);
}

int openUsb(const std::string& deviceName) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "open", "(Ljava/lang/String;)I") : nullptr;
    if (!m) return -1;
    jstring s = e->NewStringUTF(deviceName.c_str());
    jint fd = e->CallStaticIntMethod(g_svc, m, s);
    e->DeleteLocalRef(s);
    return ok(e) ? (int)fd : -1;
}

void closeUsb() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "close", "()V") : nullptr;
    if (!m) return;
    e->CallStaticVoidMethod(g_svc, m);
    ok(e);
}

std::string openedUsb() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "opened", "()Ljava/lang/String;") : nullptr;
    if (!m) return {};
    jstring s = static_cast<jstring>(e->CallStaticObjectMethod(g_svc, m));
    if (!ok(e)) return {};
    return jstr(e, s);
}

std::string cacheDir() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "cacheDir", "()Ljava/lang/String;") : nullptr;
    if (!m) return {};
    jstring s = static_cast<jstring>(e->CallStaticObjectMethod(g_svc, m));
    if (!ok(e)) return {};
    return jstr(e, s);
}

void beginForeground(const std::string& text) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "beginForeground", "(Ljava/lang/String;)V") : nullptr;
    if (!m) return;
    jstring s = e->NewStringUTF(text.c_str());
    e->CallStaticVoidMethod(g_svc, m, s);
    e->DeleteLocalRef(s);
    ok(e);
}

void updateForeground(const std::string& text) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "updateForeground", "(Ljava/lang/String;)V") : nullptr;
    if (!m) return;
    jstring s = e->NewStringUTF(text.c_str());
    e->CallStaticVoidMethod(g_svc, m, s);
    e->DeleteLocalRef(s);
    ok(e);
}

void endForeground() {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "endForeground", "()V") : nullptr;
    if (!m) return;
    e->CallStaticVoidMethod(g_svc, m);
    ok(e);
}

std::string publishRecording(const std::string& path) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "publish", "(Ljava/lang/String;)Ljava/lang/String;") : nullptr;
    if (!m) return {};
    jstring s = e->NewStringUTF(path.c_str());
    jstring r = static_cast<jstring>(e->CallStaticObjectMethod(g_svc, m, s));
    e->DeleteLocalRef(s);
    if (!ok(e)) return {};
    return jstr(e, r);
}

std::vector<Recording> queryRecordings() {
    std::vector<Recording> out;
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "recordings", "()[Ljava/lang/String;") : nullptr;
    if (!m) return out;
    jobjectArray arr = static_cast<jobjectArray>(e->CallStaticObjectMethod(g_svc, m));
    if (!ok(e)) return out;
    for (const std::string& row : jstrings(e, arr)) {
        Recording r;
        r.uri = fieldStr(row, 0);
        r.name = fieldStr(row, 1);
        r.durationMs = std::atoll(fieldStr(row, 2).c_str());
        r.sizeBytes = std::atoll(fieldStr(row, 3).c_str());
        if (!r.uri.empty()) out.push_back(std::move(r));
    }
    return out;
}

int openUri(const std::string& uri) {
    JNIEnv* e = env();
    jmethodID m = e ? method(e, "openUri", "(Ljava/lang/String;)I") : nullptr;
    if (!m) return -1;
    jstring s = e->NewStringUTF(uri.c_str());
    jint fd = e->CallStaticIntMethod(g_svc, m, s);
    e->DeleteLocalRef(s);
    return ok(e) ? (int)fd : -1;
}

}  // namespace plat

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

extern "C" JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_service_RecorderService_nativeOnStop(JNIEnv*, jclass) {
    rec_ui::onExternalStop();
    wake();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_service_RecorderService_nativeOnUsb(JNIEnv*, jclass) {
    rec_ui::onUsbSignal();
    wake();
}
