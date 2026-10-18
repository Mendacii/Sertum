#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

/**
 * Isochronous OUT transport for the UAC2 driver (ADR-0001).
 *
 * Android exposes no isochronous API, so this drives the kernel's usbdevfs directly on the file
 * descriptor UsbDeviceConnection already holds. It is the same kernel interface libusb's
 * linux_usbfs.c uses (USBDEVFS_SUBMITURB / REAPURBNDELAY / URB_ISO_ASAP), minus the library.
 *
 * Three things here are load-bearing, each of which fails silently when wrong:
 *
 *  1. USBDEVFS_URB_ISO_ASAP. Without it the kernel schedules at start_frame (0, in the past) and
 *     xHCI drops every packet while still reporting success.
 *  2. More than one URB in flight. A submit-reap-submit loop leaves gaps between microframes that a
 *     DAC renders as silence.
 *  3. Packet sizes for rates that are not a multiple of 8000. USB high speed delivers 8000
 *     microframes per second, so 44100 Hz needs 5.5125 frames per microframe. Rounding up every
 *     packet over-delivers by 8.8% and shifts pitch; a phase accumulator spreads the remainder so
 *     the long-term rate stays exact.
 *
 * The alt setting is switched by the caller through Java setInterface: only that path allocates
 * isochronous bandwidth in the xHCI scheduler.
 */

#define TAG "SertumUac2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kUrbsInFlight = 8;
constexpr int kPacketsPerUrb = 8;
constexpr int kMicroframesPerSecond = 8000;

struct UrbSlot {
    usbdevfs_urb *urb = nullptr;
    uint8_t *pcm = nullptr;
    size_t capacity = 0;
    std::atomic<bool> inFlight{false};

    UrbSlot() = default;
    UrbSlot(const UrbSlot &) = delete;
    UrbSlot &operator=(const UrbSlot &) = delete;
    // std::vector::resize requires MoveInsertable, and std::atomic deletes the implicit moves. The
    // atomics are only moved during construction, before any thread can observe them.
    UrbSlot(UrbSlot &&other) noexcept
        : urb(other.urb),
          pcm(other.pcm),
          capacity(other.capacity),
          inFlight(other.inFlight.load(std::memory_order_relaxed)) {}
    UrbSlot &operator=(UrbSlot &&other) noexcept {
        if (this != &other) {
            urb = other.urb;
            pcm = other.pcm;
            capacity = other.capacity;
            inFlight.store(other.inFlight.load(std::memory_order_relaxed),
                           std::memory_order_relaxed);
        }
        return *this;
    }
};

struct IsoStream {
    int fd = -1;
    int endpoint = 0;
    int sampleRate = 0;
    int channels = 0;
    int subslotBytes = 0;
    int frameBytes = 0;

    std::vector<UrbSlot> slots;
    std::atomic<bool> running{false};
    std::thread pump;

    std::mutex ringMutex;
    std::vector<uint8_t> ring;

    uint32_t phaseAccumulator = 0;
    uint32_t maxPacketBytes = 0;

    std::atomic<long long> submitted{0};
    std::atomic<long long> completed{0};
    std::atomic<long long> failed{0};
    std::atomic<long long> framesSent{0};
    std::atomic<long long> underruns{0};
    std::atomic<int> lastError{0};
};

IsoStream *fromHandle(jlong handle) {
    return reinterpret_cast<IsoStream *>(handle);
}

int nextMicroframeFrames(IsoStream *stream) {
    stream->phaseAccumulator += static_cast<uint32_t>(stream->sampleRate);
    const int frames = static_cast<int>(stream->phaseAccumulator / kMicroframesPerSecond);
    stream->phaseAccumulator %= kMicroframesPerSecond;
    return frames;
}

void fillPacket(IsoStream *stream, uint8_t *destination, size_t bytes) {
    std::memset(destination, 0, bytes);
    std::lock_guard<std::mutex> lock(stream->ringMutex);
    if (stream->ring.empty()) {
        stream->underruns.fetch_add(1);
        return;
    }
    const size_t take = std::min(bytes, stream->ring.size());
    std::memcpy(destination, stream->ring.data(), take);
    stream->ring.erase(stream->ring.begin(), stream->ring.begin() + take);
}

bool submitSlot(IsoStream *stream, UrbSlot &slot) {
    size_t offset = 0;
    for (int i = 0; i < kPacketsPerUrb; ++i) {
        const int frames = nextMicroframeFrames(stream);
        const int packetBytes = frames * stream->frameBytes;
        if (packetBytes < 0 || offset + static_cast<size_t>(packetBytes) > slot.capacity) {
            LOGE("packet exceeds buffer: packet=%d offset=%zu capacity=%zu",
                 packetBytes, offset, slot.capacity);
            return false;
        }
        slot.urb->iso_frame_desc[i].length = static_cast<unsigned int>(packetBytes);
        slot.urb->iso_frame_desc[i].actual_length = 0;
        slot.urb->iso_frame_desc[i].status = 0;
        fillPacket(stream, slot.pcm + offset, static_cast<size_t>(packetBytes));
        offset += static_cast<size_t>(packetBytes);
    }
    stream->framesSent.fetch_add(static_cast<long long>(offset / stream->frameBytes));

    slot.urb->type = USBDEVFS_URB_TYPE_ISO;
    slot.urb->endpoint = static_cast<unsigned char>(stream->endpoint);
    slot.urb->status = 0;
    slot.urb->flags = USBDEVFS_URB_ISO_ASAP;
    slot.urb->buffer = slot.pcm;
    slot.urb->buffer_length = static_cast<int>(offset);
    slot.urb->actual_length = 0;
    slot.urb->start_frame = 0;
    slot.urb->number_of_packets = kPacketsPerUrb;
    slot.urb->error_count = 0;

    if (ioctl(stream->fd, USBDEVFS_SUBMITURB, slot.urb) < 0) {
        stream->lastError.store(errno);
        LOGE("submiturb failed: errno=%d %s", errno, strerror(errno));
        return false;
    }
    slot.inFlight.store(true);
    stream->submitted.fetch_add(1);
    return true;
}

void pumpLoop(IsoStream *stream) {
    while (stream->running.load()) {
        usbdevfs_urb *done = nullptr;
        const int rc = ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (rc == 0 && done != nullptr) {
            int packetErrors = 0;
            for (int i = 0; i < done->number_of_packets; ++i) {
                if (done->iso_frame_desc[i].status != 0) packetErrors++;
            }
            if (done->status != 0 || packetErrors > 0) {
                stream->failed.fetch_add(1);
            } else {
                stream->completed.fetch_add(1);
            }
            for (auto &slot : stream->slots) {
                if (slot.urb == done) {
                    slot.inFlight.store(false);
                    if (stream->running.load()) submitSlot(stream, slot);
                    break;
                }
            }
            continue;
        }
        if (rc < 0 && errno != EAGAIN) {
            stream->lastError.store(errno);
            LOGE("reapurb failed: errno=%d %s", errno, strerror(errno));
            stream->running.store(false);
            break;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(400));
    }
}

void teardown(IsoStream *stream) {
    stream->running.store(false);
    if (stream->pump.joinable()) stream->pump.join();
    for (auto &slot : stream->slots) {
        if (slot.inFlight.exchange(false)) {
            ioctl(stream->fd, USBDEVFS_DISCARDURB, slot.urb);
        }
    }
    for (int i = 0; i < kUrbsInFlight * 4; ++i) {
        usbdevfs_urb *done = nullptr;
        if (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) break;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeOpen(
    JNIEnv *, jobject, jint usbFd, jint endpoint, jint sampleRate, jint channels, jint subslotBytes) {
    if (usbFd < 0 || endpoint <= 0 || sampleRate <= 0 || channels <= 0 || subslotBytes <= 0) {
        LOGE("nativeOpen: bad arguments fd=%d ep=%d rate=%d ch=%d subslot=%d",
             usbFd, endpoint, sampleRate, channels, subslotBytes);
        return 0;
    }
    auto *stream = new IsoStream();
    stream->fd = dup(usbFd);
    if (stream->fd < 0) {
        LOGE("dup failed: errno=%d %s", errno, strerror(errno));
        delete stream;
        return 0;
    }
    stream->endpoint = endpoint;
    stream->sampleRate = sampleRate;
    stream->channels = channels;
    stream->subslotBytes = subslotBytes;
    stream->frameBytes = channels * subslotBytes;

    const int maxFrames = (sampleRate + kMicroframesPerSecond - 1) / kMicroframesPerSecond;
    stream->maxPacketBytes = static_cast<uint32_t>(maxFrames * stream->frameBytes);
    const size_t bytesPerUrb = static_cast<size_t>(stream->maxPacketBytes) * kPacketsPerUrb;

    stream->slots.resize(kUrbsInFlight);
    for (auto &slot : stream->slots) {
        const size_t urbSize =
            sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
        slot.urb = static_cast<usbdevfs_urb *>(calloc(1, urbSize));
        slot.pcm = static_cast<uint8_t *>(calloc(1, bytesPerUrb));
        slot.capacity = bytesPerUrb;
        if (slot.urb == nullptr || slot.pcm == nullptr) {
            LOGE("URB allocation failed");
            for (auto &s : stream->slots) {
                free(s.urb);
                free(s.pcm);
            }
            close(stream->fd);
            delete stream;
            return 0;
        }
    }
    LOGI("open fd=%d ep=0x%02X rate=%d ch=%d subslot=%d maxPacket=%u urb=%zuB depth=%d",
         stream->fd, endpoint, sampleRate, channels, subslotBytes,
         stream->maxPacketBytes, bytesPerUrb, kUrbsInFlight);
    return reinterpret_cast<jlong>(stream);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStart(JNIEnv *, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr || stream->running.load()) return JNI_FALSE;
    for (auto &slot : stream->slots) {
        if (!submitSlot(stream, slot)) return JNI_FALSE;
    }
    stream->running.store(true);
    stream->pump = std::thread(pumpLoop, stream);
    LOGI("started: %d URBs x %d packets", kUrbsInFlight, kPacketsPerUrb);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStop(JNIEnv *, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr) return;
    teardown(stream);
    LOGI("stopped: submitted=%lld completed=%lld failed=%lld frames=%lld underruns=%lld",
         stream->submitted.load(), stream->completed.load(), stream->failed.load(),
         stream->framesSent.load(), stream->underruns.load());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeClose(JNIEnv *, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr) return;
    if (stream->running.load() || stream->pump.joinable()) teardown(stream);
    for (auto &slot : stream->slots) {
        free(slot.urb);
        free(slot.pcm);
    }
    if (stream->fd >= 0) close(stream->fd);
    delete stream;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeWrite(
    JNIEnv *env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr || data == nullptr || offset < 0 || length <= 0) return -1;
    if (offset + length > env->GetArrayLength(data)) return -1;
    std::vector<uint8_t> chunk(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(chunk.data()));
    const size_t budget =
        static_cast<size_t>(stream->frameBytes) * static_cast<size_t>(stream->sampleRate) / 5;
    std::lock_guard<std::mutex> lock(stream->ringMutex);
    if (stream->ring.size() >= budget) return 0;
    const size_t room = budget - stream->ring.size();
    const size_t take = std::min(room, chunk.size());
    stream->ring.insert(stream->ring.end(), chunk.begin(), chunk.begin() + take);
    return static_cast<jint>(take);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStats(JNIEnv *env, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    jlong values[6] = {0, 0, 0, 0, 0, 0};
    if (stream != nullptr) {
        values[0] = stream->submitted.load();
        values[1] = stream->completed.load();
        values[2] = stream->failed.load();
        values[3] = stream->framesSent.load();
        values[4] = stream->lastError.load();
        values[5] = stream->underruns.load();
    }
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}