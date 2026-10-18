#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <unordered_map>

#define TAG "SertumAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/**
 * One open stream plus the lock that keeps its lifetime honest.
 *
 * Falling back to a raw `AAudioStream*` in a map meant every call could be holding a pointer that
 * `nativeClose` was about to free: Media3 releases the output from whichever thread is tearing the
 * player down while the renderer thread may still be inside a write. Entries are held through a
 * shared_ptr, so the pointer cannot disappear underneath a caller, and `io` serialises the calls
 * against a stream so only one of them is ever inside it.
 */
struct StreamEntry {
    AAudioStream *stream = nullptr;
    std::mutex io;
    bool closed = false;
};

static std::unordered_map<jlong, std::shared_ptr<StreamEntry>> g_streams;
static std::mutex g_mu;
static std::atomic<jlong> g_next{1};

/** How long one write may block before reporting backpressure, and the budget for the whole call. */
static constexpr int64_t kWriteSliceMs = 20;
static constexpr int64_t kWriteBudgetMs = 200;

static aaudio_format_t formatForBits(int bits) {
    return bits >= 24 ? AAUDIO_FORMAT_PCM_I24_PACKED : AAUDIO_FORMAT_PCM_I16;
}

static int bytesPerFrame(AAudioStream *stream) {
    const int bytes = AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_I24_PACKED ? 3 : 2;
    return bytes * AAudioStream_getChannelCount(stream);
}

/** Finds an entry; the returned shared_ptr keeps it alive even if close runs next. */
static std::shared_ptr<StreamEntry> findEntry(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto it = g_streams.find(handle);
    return it == g_streams.end() ? nullptr : it->second;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeOpen(
    JNIEnv *, jobject, jint sampleRate, jint channels, jint bits, jint deviceId) {
    AAudioStreamBuilder *builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGW("builder create failed");
        return 0;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, formatForBits(bits));
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    // Bind the stream to the DAC itself. Without this the "exclusive" stream is exclusive on
    // whatever the default output happens to be, which with no USB device attached is the
    // phone's own speaker - exclusive, correctly reported as exclusive, and entirely the wrong
    // device. 0 means "default", kept for callers that have no device to name.
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }

    AAudioStream *stream = nullptr;
    aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &stream);
    if (res != AAUDIO_OK) {
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        res = AAudioStreamBuilder_openStream(builder, &stream);
    }
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        LOGW("open failed rate=%d bits=%d res=%d", sampleRate, bits, res);
        return 0;
    }

    auto entry = std::make_shared<StreamEntry>();
    entry->stream = stream;
    const jlong handle = g_next.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_streams[handle] = entry;
    }
    LOGI("open handle=%lld rate=%d actual=%d format=%d sharing=%d device=%d",
         static_cast<long long>(handle), sampleRate,
         AAudioStream_getSampleRate(stream), AAudioStream_getFormat(stream),
         AAudioStream_getSharingMode(stream), AAudioStream_getDeviceId(stream));
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeStart(JNIEnv *, jobject, jlong handle) {
    auto entry = findEntry(handle);
    if (!entry) return false;
    std::lock_guard<std::mutex> lock(entry->io);
    if (entry->closed) return false;
    return AAudioStream_requestStart(entry->stream) == AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeWrite(
    JNIEnv *env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
    auto entry = findEntry(handle);
    if (!entry) return -1;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return -2;

    int framesWritten = 0;
    {
        std::lock_guard<std::mutex> lock(entry->io);
        if (entry->closed) {
            env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
            return -1;
        }
        const int frameBytes = bytesPerFrame(entry->stream);
        if (frameBytes <= 0) {
            env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
            return -3;
        }
        const int frames = length / frameBytes;
        const auto deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(kWriteBudgetMs);
        while (framesWritten < frames) {
            const aaudio_result_t w = AAudioStream_write(
                entry->stream,
                reinterpret_cast<const void *>(buf + offset + framesWritten * frameBytes),
                frames - framesWritten,
                kWriteSliceMs * 1000 * 1000);
            if (w <= 0) {
                // A short timeout lets the HAL drain a burst before we report backpressure; a
                // 0-frame result goes back to Java so Media3 retries instead of busy-looping.
                framesWritten = framesWritten == 0 ? static_cast<int>(w) : framesWritten;
                break;
            }
            framesWritten += w;
            // Bounded overall: a stuck HAL must not hold this lock, because close waits for it.
            if (std::chrono::steady_clock::now() >= deadline) break;
        }
    }
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return framesWritten;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativePause(JNIEnv *, jobject, jlong handle) {
    auto entry = findEntry(handle);
    if (!entry) return false;
    std::lock_guard<std::mutex> lock(entry->io);
    if (entry->closed) return false;
    return AAudioStream_requestPause(entry->stream) == AAUDIO_OK;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeFlush(JNIEnv *, jobject, jlong handle) {
    auto entry = findEntry(handle);
    if (!entry) return false;
    std::lock_guard<std::mutex> lock(entry->io);
    if (entry->closed) return false;
    return AAudioStream_requestFlush(entry->stream) == AAUDIO_OK;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto entry = findEntry(handle);
    if (!entry) return false;
    std::lock_guard<std::mutex> lock(entry->io);
    if (entry->closed) return false;
    return AAudioStream_requestStop(entry->stream) == AAUDIO_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_backend_AaudioNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<StreamEntry> entry;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        auto it = g_streams.find(handle);
        if (it == g_streams.end()) return;
        entry = it->second;
        // Erased first so no new caller can find this stream while it is being closed.
        g_streams.erase(it);
    }
    std::lock_guard<std::mutex> lock(entry->io);
    if (!entry->closed) {
        entry->closed = true;
        AAudioStream_close(entry->stream);
        entry->stream = nullptr;
    }
}

#define GETTER(NAME, TYPE, CALL)                                                   \
    extern "C" JNIEXPORT TYPE JNICALL                                             \
    Java_com_sertum_player_audio_backend_AaudioNative_nativeGet##NAME(            \
        JNIEnv *, jobject, jlong handle) {                                        \
        auto entry = findEntry(handle);                                           \
        if (!entry) return 0;                                                     \
        std::lock_guard<std::mutex> lock(entry->io);                              \
        if (entry->closed || entry->stream == nullptr) return 0;                  \
        return static_cast<TYPE>(CALL(entry->stream));                            \
    }

GETTER(ChannelCount, jint, AAudioStream_getChannelCount)
GETTER(ActualRate, jint, AAudioStream_getSampleRate)
GETTER(ActualFormat, jint, AAudioStream_getFormat)
GETTER(SharingMode, jint, AAudioStream_getSharingMode)
GETTER(DeviceId, jint, AAudioStream_getDeviceId)
GETTER(PerformanceMode, jint, AAudioStream_getPerformanceMode)
GETTER(FramesPerBurst, jint, AAudioStream_getFramesPerBurst)
GETTER(FramesRead, jlong, AAudioStream_getFramesRead)
