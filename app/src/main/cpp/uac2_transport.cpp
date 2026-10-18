#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <sys/resource.h>
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

/**
 * URBs kept queued, each covering one millisecond.
 *
 * Eight was chosen for the round-trip latency and measured against this device: a foreground
 * stream keeps a 3 ms worst gap between completions, but leaving the app once stretched that to
 * 11.6 ms of scheduling delay, and eight milliseconds of depth cannot absorb the difference - the
 * kernel runs dry and the DAC gets silence for the remainder, which is the click heard when
 * switching away. Thirty-two milliseconds covers that stall with room to spare and costs 18 KB.
 */
constexpr int kUrbsInFlight = 192;
constexpr int kPacketsPerUrb = 8;
constexpr int kMicroframesPerSecond = 8000;
/** Nice value for the pump thread; -8 is aggressive without needing CAP_SYS_NICE. */
constexpr int kPumpPriority = -8;

/**
 * Fixed-size byte queue.
 *
 * The previous version kept a std::vector and erased from its front on every fill, which memmoved
 * the remaining audio - up to 38 KB, eight thousand times a second. That alone kept a core busy and
 * was most of why the phone got warm while playing. A head/tail pair moves nothing.
 */
struct RingBuffer {
    std::vector<uint8_t> data;
    size_t head = 0;
    size_t tail = 0;
    size_t count = 0;
    size_t capacity = 0;

    void reset(size_t cap) {
        data.assign(cap, 0);
        capacity = cap;
        head = tail = count = 0;
    }

    size_t space() const { return capacity - count; }

    size_t push(const uint8_t *src, size_t n) {
        const size_t take = std::min(n, space());
        const size_t first = std::min(take, capacity - tail);
        std::memcpy(data.data() + tail, src, first);
        if (take > first) std::memcpy(data.data(), src + first, take - first);
        tail = (tail + take) % capacity;
        count += take;
        return take;
    }

    size_t pop(uint8_t *dst, size_t n) {
        const size_t take = std::min(n, count);
        const size_t first = std::min(take, capacity - head);
        std::memcpy(dst, data.data() + head, first);
        if (take > first) std::memcpy(dst + first, data.data(), take - first);
        head = (head + take) % capacity;
        count -= take;
        return take;
    }
};

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
    std::condition_variable ringSpace;
    RingBuffer ring;

    /**
     * Fractional frames carried across microframes.
     *
     * Driven by [effectiveRate] rather than the nominal rate: once the device's feedback endpoint
     * reports its actual clock, the packet sizes follow the crystal instead of our arithmetic.
     */
    double phaseFrames = 0.0;
    double effectiveRate = 0.0;

    /** The device's asynchronous feedback: ISO IN endpoint, one 4-byte 16.16 value per interval. */
    int feedbackEndpoint = 0;
    std::vector<UrbSlot> feedbackSlots;
    std::atomic<long long> feedbackSeen{0};
    std::atomic<int> feedbackPpm{0};

    uint32_t maxPacketBytes = 0;

    std::atomic<long long> submitted{0};
    std::atomic<long long> completed{0};
    std::atomic<long long> failed{0};
    std::atomic<long long> framesSent{0};
    /**
     * Fills that used silence because the ring was empty.
     *
     * Split in two because they mean different things: priming happens once per stream start
     * (before the decoder has produced anything) and is normal; an underrun happens after real PCM
     * has flowed and is an audible gap. Counting them together made a healthy stream look broken.
     */
    std::atomic<long long> primed{0};
    std::atomic<long long> underruns{0};
    std::atomic<bool> pcmSeen{false};

    /**
     * The device went away underneath the transport.
     *
     * usbfs reports it as an ioctl failure, and the pump thread stopped - but `nativeWrite` would
     * have kept accepting bytes into a ring  drains, so the backend reported successful
     * writes and the player kept counting. Writes now fail fast once this is set.
     */
    std::atomic<bool> dead{false};

    /**
     * Longest gap between two completed URBs, in microseconds.
     *
     * A process-wide stall does not show up in `underruns`:  fills a packet, the kernel just
     * runs out of queued URBs and the device gets silence. Eight milliseconds of pipeline is easily
     * consumed by a background transition, so this number is what says whether the buffer is deep
     * enough for the stalls this device actually has.
     */
    std::atomic<long long> maxGapUs{0};
    std::chrono::steady_clock::time_point lastReap = std::chrono::steady_clock::now();
    std::atomic<int> lastError{0};
};

IsoStream *fromHandle(jlong handle) {
    return reinterpret_cast<IsoStream *>(handle);
}

/** Errors that mean the device is gone rather than this call being wrong. */
bool isFatalErrno(int code) {
    return code == ENODEV || code == ESHUTDOWN || code == EPROTO || code == ENXIO;
}

int nextMicroframeFrames(IsoStream *stream) {
    // Frames accumulate in the double domain so a feedback-corrected rate can be fractional; the
    // carry is carried over, which keeps the long-term average exact.
    const double rate = stream->effectiveRate > 0.0
        ? stream->effectiveRate
        : static_cast<double>(stream->sampleRate);
    stream->phaseFrames += rate / kMicroframesPerSecond;
    const int frames = static_cast<int>(stream->phaseFrames);
    if (frames < 0) return 0;
    stream->phaseFrames -= frames;
    return frames;
}

/**
 * Decodes a 16.16 feedback value into frames per microframe.
 *
 * Two conventions are in the field. Most devices report a ratio of the actual rate to the nominal
 * one (~1.0000), which is what the class spec leads you to expect; this DAWN PRO2 reports the
 * absolute count instead (12.0 at 96 kHz, since 96000 / 8000 = 12). The value itself says which:
 * a ratio sits near 1, an absolute count sits near (sampleRate / 8000), and the two ranges do not
 * overlap for any sane rate.
 */
double framesPerUframeFromRaw(uint32_t raw, int sampleRate) {
    const double decoded = static_cast<double>(raw) / 65536.0;
    const double nominal = static_cast<double>(sampleRate) / kMicroframesPerSecond;
    if (decoded > 0.9 && decoded < 1.1) return nominal * decoded;
    return decoded;
}

/** The device's rate in parts per million away from nominal, or 0 when it reads as one. */
int feedbackPpmFromRaw(uint32_t raw, int sampleRate) {
    const double nominal = static_cast<double>(sampleRate) / kMicroframesPerSecond;
    if (nominal <= 0.0) return 0;
    const double frames = framesPerUframeFromRaw(raw, sampleRate);
    if (frames <= 0.0) return 0;
    return static_cast<int>((frames / nominal - 1.0) * 1e6);
}

void fillPacket(IsoStream *stream, uint8_t *destination, size_t bytes) {
    // Zero is digital silence for packed PCM; this is the fill whenever real audio is not there.
    std::memset(destination, 0, bytes);
    std::lock_guard<std::mutex> lock(stream->ringMutex);
    if (stream->ring.count == 0) {
        if (stream->pcmSeen.load()) {
            stream->underruns.fetch_add(1);
        } else {
            stream->primed.fetch_add(1);
        }
        return;
    }
    stream->ring.pop(destination, bytes);
    stream->pcmSeen.store(true);
    stream->ringSpace.notify_one();
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
        const int code = errno;
        stream->lastError.store(code);
        if (isFatalErrno(code)) stream->dead.store(true);
        LOGE("submiturb failed: errno=%d %s", code, strerror(code));
        return false;
    }
    slot.inFlight.store(true);
    stream->submitted.fetch_add(1);
    return true;
}

bool submitFeedbackSlot(IsoStream *stream, UrbSlot &slot) {
    slot.urb->type = USBDEVFS_URB_TYPE_ISO;
    slot.urb->endpoint = static_cast<unsigned char>(stream->feedbackEndpoint);
    slot.urb->status = 0;
    slot.urb->flags = USBDEVFS_URB_ISO_ASAP;
    slot.urb->buffer = slot.pcm;
    slot.urb->buffer_length = 4;
    slot.urb->actual_length = 0;
    slot.urb->start_frame = 0;
    slot.urb->number_of_packets = 1;
    slot.urb->error_count = 0;
    slot.urb->iso_frame_desc[0].length = 4;
    slot.urb->iso_frame_desc[0].actual_length = 0;
    slot.urb->iso_frame_desc[0].status = 0;
    if (ioctl(stream->fd, USBDEVFS_SUBMITURB, slot.urb) < 0) {
        stream->lastError.store(errno);
        return false;
    }
    slot.inFlight.store(true);
    return true;
}

/** Reads a completed feedback URB and re-arms it. */
void consumeFeedback(IsoStream *stream, UrbSlot &slot) {
    const int status = slot.urb->iso_frame_desc[0].status;
    const unsigned int actual = slot.urb->iso_frame_desc[0].actual_length;
    const long long seen = stream->feedbackSeen.load();
    if (seen < 8) {
        LOGI("feedback: status=%d actual=%u urbStatus=%d bytes=%02X %02X %02X %02X",
             status, actual, slot.urb->status,
             slot.pcm[0], slot.pcm[1], slot.pcm[2], slot.pcm[3]);
    }
    if (status == 0 && actual >= 4) {
        uint32_t raw = 0;
        std::memcpy(&raw, slot.pcm, sizeof(raw));
        const double frames = framesPerUframeFromRaw(raw, stream->sampleRate);
        const double nominal = static_cast<double>(stream->sampleRate) / kMicroframesPerSecond;
        // Reject values that are not a clock at all (a stalled endpoint can return zeros).
        if (nominal > 0.0 && frames > nominal * 0.9 && frames < nominal * 1.1) {
            stream->effectiveRate = frames * kMicroframesPerSecond;
            stream->feedbackPpm.store(feedbackPpmFromRaw(raw, stream->sampleRate));
            stream->feedbackSeen.fetch_add(1);
        }
    }
    slot.inFlight.store(false);
    if (stream->running.load()) submitFeedbackSlot(stream, slot);
}

void pumpLoop(IsoStream *stream) {
    // Best effort: the audio priority keeps this thread ahead of ordinary work. A refusal here is
    // not fatal - the depth of kUrbsInFlight is what actually makes the stream hold.
    const int priority = setpriority(PRIO_PROCESS, 0, kPumpPriority);
    auto lastHeartbeat = std::chrono::steady_clock::now();
    LOGI("pump priority %d -> %d (errno=%d)", kPumpPriority, getpriority(PRIO_PROCESS, 0),
         priority == 0 ? 0 : errno);
    while (stream->running.load()) {
        // Heartbeat: whether this thread is being scheduled at all is the difference between a
        // stream that stalled and one that was frozen by the platform, and neither leaves a trace
        // in the counters.
        {
            const auto now = std::chrono::steady_clock::now();
            if (now - lastHeartbeat >= std::chrono::seconds(5)) {
                lastHeartbeat = now;
                LOGI("heartbeat submitted=%lld completed=%lld frames=%lld underruns=%lld "
                     "dead=%d maxGap=%lld",
                     stream->submitted.load(), stream->completed.load(),
                     stream->framesSent.load(), stream->underruns.load(),
                     stream->dead.load() ? 1 : 0, stream->maxGapUs.load());
            }
        }
        usbdevfs_urb *done = nullptr;
        const int rc = ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (rc == 0 && done != nullptr) {
            int packetErrors = 0;
            for (int i = 0; i < done->number_of_packets; ++i) {
                if (done->iso_frame_desc[i].status != 0) packetErrors++;
            }
            // Only steady state: the first reaps arrive after one whole pipeline of audio, which is
            // not a stall and would otherwise dominate the maximum forever.
            if (stream->completed.load() > kUrbsInFlight * 2) {
                const auto now = std::chrono::steady_clock::now();
                const long long gap =
                    std::chrono::duration_cast<std::chrono::microseconds>(now - stream->lastReap).count();
                stream->lastReap = now;
                if (gap > stream->maxGapUs.load()) stream->maxGapUs.store(gap);
            } else {
                stream->lastReap = std::chrono::steady_clock::now();
            }
            bool feedbackDone = false;
            for (auto &slot : stream->feedbackSlots) {
                if (slot.urb == done) feedbackDone = true;
            }
            if (!feedbackDone) {
                if (done->status != 0 || packetErrors > 0) {
                    stream->failed.fetch_add(1);
                } else {
                    stream->completed.fetch_add(1);
                }
            }
            bool handled = false;
            for (auto &slot : stream->feedbackSlots) {
                if (slot.urb == done) {
                    consumeFeedback(stream, slot);
                    handled = true;
                    break;
                }
            }
            if (!handled) {
                for (auto &slot : stream->slots) {
                    if (slot.urb == done) {
                        slot.inFlight.store(false);
                        if (stream->running.load()) submitSlot(stream, slot);
                        break;
                    }
                }
            }
            continue;
        }
        if (rc < 0 && errno != EAGAIN) {
            const int code = errno;
            stream->lastError.store(code);
            if (isFatalErrno(code)) stream->dead.store(true);
            LOGE("reapurb failed: errno=%d %s", code, strerror(code));
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
    for (auto &slot : stream->feedbackSlots) {
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
    JNIEnv *, jobject, jint usbFd, jint endpoint, jint sampleRate, jint channels, jint subslotBytes,
    jint feedbackEndpoint) {
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
    stream->ring.reset(static_cast<size_t>(stream->frameBytes) * static_cast<size_t>(sampleRate) / 5);
    stream->feedbackEndpoint = feedbackEndpoint;
    stream->effectiveRate = static_cast<double>(sampleRate);

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
    // Two feedback slots: the device reports every interval, and one in flight is too easy to miss.
    if (feedbackEndpoint > 0) {
        stream->feedbackSlots.resize(2);
        for (auto &slot : stream->feedbackSlots) {
            const size_t urbSize = sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc);
            slot.urb = static_cast<usbdevfs_urb *>(calloc(1, urbSize));
            slot.pcm = static_cast<uint8_t *>(calloc(1, 4));
            slot.capacity = 4;
            if (slot.urb == nullptr || slot.pcm == nullptr) {
                LOGE("feedback URB allocation failed");
                for (auto &s : stream->feedbackSlots) {
                    free(s.urb);
                    free(s.pcm);
                }
                stream->feedbackSlots.clear();
                stream->feedbackEndpoint = 0;
                break;
            }
        }
    }
    LOGI("open fd=%d ep=0x%02X feedback=0x%02X rate=%d ch=%d subslot=%d maxPacket=%u urb=%zuB depth=%d",
         stream->fd, endpoint, feedbackEndpoint, sampleRate, channels, subslotBytes,
         stream->maxPacketBytes, bytesPerUrb, kUrbsInFlight);
    return reinterpret_cast<jlong>(stream);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStart(JNIEnv *, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr) return JNI_FALSE;
    // Idempotent on purpose. Reporting failure for an already-running stream left the caller
    // believing it had to retry, and every retry failed the same way - which is how the player ended
    // up stuck buffering with the transport filling silence underneath it.
    if (stream->running.load()) return JNI_TRUE;
    if (stream->dead.load()) return JNI_FALSE;
    for (auto &slot : stream->slots) {
        if (!submitSlot(stream, slot)) {
            // Roll back what did go out, so a failed start leaves no half-queued stream behind.
            for (auto &other : stream->slots) {
                if (other.inFlight.exchange(false)) ioctl(stream->fd, USBDEVFS_DISCARDURB, other.urb);
            }
            LOGE("start failed: errno=%d", errno);
            return JNI_FALSE;
        }
    }
    for (auto &slot : stream->feedbackSlots) {
        submitFeedbackSlot(stream, slot);
    }
    // The gap metric measures stalls within a run; without this reset it would count the pause
    // between two runs as a two-second freeze.
    stream->lastReap = std::chrono::steady_clock::now();
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
    LOGI("stopped: submitted=%lld completed=%lld failed=%lld frames=%lld primed=%lld "
         "underruns=%lld feedback=%lld ratePpm=%d maxGapUs=%lld",
         stream->submitted.load(), stream->completed.load(), stream->failed.load(),
         stream->framesSent.load(), stream->primed.load(), stream->underruns.load(),
         stream->feedbackSeen.load(), stream->feedbackPpm.load(), stream->maxGapUs.load());
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
    for (auto &slot : stream->feedbackSlots) {
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
    // -1 is "bad call", 0 is "ring full"; -2 tells the backend the device is gone.
    if (stream->dead.load()) return -2;
    std::vector<uint8_t> chunk(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(chunk.data()));
    // Wait briefly for room instead of answering "zero frames" immediately: Media3 retries a zero
    // return in a tight loop, and that spin was a large part of why playing in exclusive mode cost
    // twice the CPU of the standard route.
    std::unique_lock<std::mutex> lock(stream->ringMutex);
    if (stream->ring.space() == 0 && !stream->dead.load()) {
        stream->ringSpace.wait_for(lock, std::chrono::milliseconds(5));
    }
    const size_t take = stream->ring.push(chunk.data(), chunk.size());
    return static_cast<jint>(take);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeFlush(JNIEnv *, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    if (stream == nullptr) return;
    // Seeking: drop queued audio so the next microframe carries the new position, not the old one.
    std::lock_guard<std::mutex> lock(stream->ringMutex);
    stream->ring.head = stream->ring.tail = stream->ring.count = 0;
    // A seek restarts the stream at a new position, so the silence that follows is priming again,
    // not a mid-stream dropout.
    stream->pcmSeen.store(false);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStats(JNIEnv *env, jobject, jlong handle) {
    IsoStream *stream = fromHandle(handle);
    jlong values[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    if (stream != nullptr) {
        values[0] = stream->submitted.load();
        values[1] = stream->completed.load();
        values[2] = stream->failed.load();
        values[3] = stream->framesSent.load();
        values[4] = stream->lastError.load();
        values[5] = stream->underruns.load();
        values[6] = stream->feedbackSeen.load();
        values[7] = stream->feedbackPpm.load();
        values[8] = stream->primed.load();
        values[9] = stream->dead.load() ? 1 : 0;
        values[10] = stream->maxGapUs.load();
    }
    jlongArray result = env->NewLongArray(11);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 11, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeFeedbackPpm(
    JNIEnv *, jobject, jint raw, jint sampleRate) {
    return feedbackPpmFromRaw(static_cast<uint32_t>(raw), sampleRate);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeFeedbackRate(
    JNIEnv *, jobject, jint raw, jint sampleRate) {
    return static_cast<jint>(framesPerUframeFromRaw(static_cast<uint32_t>(raw), sampleRate)
                             * kMicroframesPerSecond);
}
