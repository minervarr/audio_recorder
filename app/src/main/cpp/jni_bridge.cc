#include "session.hh"

#include <jni.h>

#include <string>
#include <vector>

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

jintArray ints(JNIEnv* env, const std::vector<int>& v) {
    jintArray a = env->NewIntArray((jsize)v.size());
    if (!a || v.empty()) return a;
    env->SetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_audio_1recorder_NativeAudio_openUsb(JNIEnv*, jclass, jint fd) {
    return rec::openUsb(fd);
}
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_closeUsb(JNIEnv*, jclass) { rec::closeUsb(); }
JNIEXPORT jboolean JNICALL
Java_com_example_audio_1recorder_NativeAudio_usbOpen(JNIEnv*, jclass) {
    return rec::usbOpen() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_example_audio_1recorder_NativeAudio_uacMajor(JNIEnv*, jclass) { return rec::uacMajor(); }
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_setLatency(JNIEnv*, jclass, jint profile) {
    rec::setLatency(profile);
}

JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_captureRates(JNIEnv* e, jclass) {
    return ints(e, rec::captureRates());
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_captureBits(JNIEnv* e, jclass) {
    return ints(e, rec::captureBits());
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_captureChannels(JNIEnv* e, jclass) {
    return ints(e, rec::captureChannels());
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_outputRates(JNIEnv* e, jclass) {
    return ints(e, rec::outputRates());
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_outputBits(JNIEnv* e, jclass) {
    return ints(e, rec::outputBits());
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_outputChannels(JNIEnv* e, jclass) {
    return ints(e, rec::outputChannels());
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_1recorder_NativeAudio_startRecord(
        JNIEnv* env, jclass, jstring path, jint rate, jint ch, jint bits,
        jboolean monitor, jfloat vol, jboolean phoneMic) {
    bool ok = rec::startRecord(jstr(env, path), rate, ch, bits,
                               monitor == JNI_TRUE, vol, phoneMic == JNI_TRUE);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL
Java_com_example_audio_1recorder_NativeAudio_stopRecord(JNIEnv*, jclass) {
    return rec::stopRecord();
}
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_setMonitorVolume(JNIEnv*, jclass, jfloat v) {
    rec::setMonitorVolume(v);
}
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_recStats(JNIEnv* env, jclass) {
    rec::Stats s = rec::stats();
    jint v[10] = {
        s.inRate, s.inCh, s.inBits, s.inSub,
        s.outRate, s.outCh, s.outBits, s.outSub,
        s.monitorActive ? 1 : 0, s.monitorMismatch ? 1 : 0
    };
    jintArray a = env->NewIntArray(10);
    env->SetIntArrayRegion(a, 0, 10, v);
    return a;
}
JNIEXPORT jlong JNICALL
Java_com_example_audio_1recorder_NativeAudio_recFrames(JNIEnv*, jclass) {
    return rec::stats().frames;
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_1recorder_NativeAudio_play(
        JNIEnv*, jclass, jint fd, jboolean useUsb, jfloat volume) {
    return rec::play(fd, useUsb == JNI_TRUE, volume) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_pausePlay(JNIEnv*, jclass) { rec::pausePlay(); }
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_resumePlay(JNIEnv*, jclass) { rec::resumePlay(); }
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_stopPlay(JNIEnv*, jclass) { rec::stopPlay(); }
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_seekPlay(JNIEnv*, jclass, jlong ms) {
    rec::seekPlay(ms);
}
JNIEXPORT void JNICALL
Java_com_example_audio_1recorder_NativeAudio_setPlayVolume(JNIEnv*, jclass, jfloat v) {
    rec::setPlayVolume(v);
}
// [playing, paused, done, usbOut, rate, bits, positionMs, durationMs]
JNIEXPORT jintArray JNICALL
Java_com_example_audio_1recorder_NativeAudio_playInfo(JNIEnv* env, jclass) {
    rec::PlayInfo i = rec::playInfo();
    jint v[8] = {
        i.playing ? 1 : 0, i.paused ? 1 : 0, i.done ? 1 : 0, i.usbOut ? 1 : 0,
        i.sourceRate, i.sourceBits,
        (jint)i.positionMs, (jint)i.durationMs
    };
    jintArray a = env->NewIntArray(8);
    env->SetIntArrayRegion(a, 0, 8, v);
    return a;
}

}  // extern "C"
