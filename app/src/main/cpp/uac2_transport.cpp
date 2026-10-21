#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
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
 * ## Why the handle is a registry key, not a pointer
 *
 * A stream is reached from two threads at once, and the old `reinterpret_cast<IsoStream *>(jlong)`
 * had no answer for that. `Uac2Backend.release()` (audio/backend/Uac2Backend.kt:119) runs on
 * whichever thread Media3 uses to tear an output down; it calls `releaseStream()` (:157), which
 * enters `Uac2Session.closeStream` (:64) and from there `nativeStop` + `nativeClose` - the only
 * place the stream is destroyed. In parallel the playback thread can still be inside `writePcm`
 * (:70) or `play` (:86), and `stopTransport` (:151) can already have called `nativeStop`. With a
 * raw pointer in the jlong, `nativeClose` could free the object between `nativeWrite`'s lookup and
 * its first field access (use-after-free), or join a pump while another thread was starting one on
 * the same object. The output is also handed to more than one owner - the provider releases every
 * exclusive output (RoutedAudioOutputProvider.kt:196) and the per-output wrapper releases its
 * backend (BackendAudioOutput.kt:250) - so the same handle can be closed twice (double free).
 *
 * The answer, ported from `aaudio_backend.cpp`:
 *
 *  - Handles are monotonic integers keyed into `g_streams`, never reused, so a stale handle can
 *    only fail to resolve - it can never alias a newer stream.
 *  - Every entry resolves the handle to a `shared_ptr` under `g_registryMutex` and releases that
 *    mutex before doing anything else. The object therefore cannot disappear under a call that has
 *    already found it, and no ioctl or join ever runs while the registry is locked.
 *  - Each stream has an `io` mutex and a `closed` flag. The JNI entries serialise on `io`, so
 *    `nativeStart`/`nativeStop`/`nativeWrite`/`nativeFlush`/`nativeStats` never run concurrently
 *    with each other or with the teardown in `nativeClose`.
 *  - `nativeClose` erases the entry *first*, then takes `io` and tears the stream down. A repeated
 *    close finds no entry and returns; a call that already holds the shared_ptr finishes before the
 *    last reference (and the object) goes away.
 *
 * The pump thread is deliberately not part of this protocol: it only reads the facts fixed at open
 * (fd, endpoint, sizes), the atomics, the URB arrays and the ring (under `ringMutex`), and every
 * path that frees any of those joins it first. That is also why `nativeClose` may hold `io` across
 * the join without risking a lock cycle.
 *
 * ## Evidence for "healthy counters, broken audio"
 *
 * `failed == 0`, `underruns == 0` and a plausible feedback sample only prove that the pump ran and
 * the ring had data. They cannot tell a wrong clock estimate from packets the host controller
 * silently discards, so this file also logs, rate limited: feedback changes (old/new rate plus the
 * raw 16.16 bytes that caused it), feedback URBs that complete malformed, feedback values rejected
 * by the sanity window, the teardown completion histogram (-EXDEV is a cancellation, -EOVERFLOW is
 * a packet that did not fit its interval), the packet-size clamp backstop, and the ring state at
 * start. The per-packet path stays silent on purpose: logging it would change the timing the log is
 * meant to measure.
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
 * How long teardown waits for cancelled URBs to come back, in 1 ms slices.
 *
 * USBDEVFS_DISCARDURB only queues the unlink; the completion arrives later, on the host
 * controller's completion interrupt. Each slice below is one sleep after an empty REAPURBNDELAY, so
 * the worst case is 200 ms and the normal case is the first one or two slices. Freeing a URB the
 * kernel still owns is the failure this budget exists to avoid.
 */
constexpr int kTeardownReapAttempts = 200;

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

/**
 * Caps one repeated log line at five per second.
 *
 * The feedback endpoint reports every millisecond, and a broken one can complete malformed every
 * microframe. Logging each event would both flood logcat and perturb the timing being measured;
 * five a second keeps the shape of a change visible without hiding it. Only the pump thread touches
 * these, so no lock is needed.
 */
struct LogLimiter {
    static constexpr int kPerSecond = 5;
    std::chrono::steady_clock::time_point window = std::chrono::steady_clock::now();
    int inWindow = 0;

    bool allow() {
        const auto now = std::chrono::steady_clock::now();
        if (now - window >= std::chrono::seconds(1)) {
            window = now;
            inWindow = 0;
        }
        if (inWindow >= kPerSecond) return false;
        ++inWindow;
        return true;
    }
};

struct IsoStream {
    /**
     * Serialises the JNI entry points against each other and against nativeClose.
     *
     * The pump thread never takes this lock: it only reads the facts fixed at open, the atomics,
     * the URB arrays and the ring (under ringMutex), and it is joined before any of those are
     * freed, so holding `io` across that join cannot deadlock.
     */
    std::mutex io;
    /** Set under `io` once the registry entry is gone and teardown has started. */
    bool closed = false;

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

    /** Byte count of a nominal packet: ceil(rate / 8000) frames, kept for the logs and comparison. */
    uint32_t nominalPacketBytes = 0;
    /** Largest packet the URB buffer can hold: maxFramesPerPacket * frameBytes. */
    uint32_t maxPacketBytes = 0;
    /**
     * Frames a packet may carry: ceil(rate / 8000) plus one.
     *
     * The extra frame is not padding. A DAC whose clock runs slightly fast legitimately asks for it:
     * 96 kHz reports 12.0 frames per microframe, a +100 ppm crystal reports 12.0012, and the phase
     * accumulator then rounds up to 13. Sizing the URB for the nominal 12 dropped that packet (and,
     * with it, one URB of pipeline) while every health counter still claimed success.
     */
    int maxFramesPerPacket = 0;
    /** Bytes per URB (maxPacketBytes * kPacketsPerUrb), sized for the largest allowed packet. */
    size_t bytesPerUrb = 0;

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

    /**
     * Data-URB completion-status histogram, printed by teardown.
     *
     * `completed`/`failed` collapse every non-zero URB status into one number, which cannot tell a
     * cancelled transfer (-EXDEV, expected on stop) from a bandwidth overrun (-EOVERFLOW, the host
     * controller saying a packet did not fit its microframe). The noise report needs that
     * distinction. Packet-level errors are counted apart because a URB can have `status == 0` while
     * individual packets inside it failed. Feedback URBs are excluded: their completions have their
     * own abnormal log and counters, and mixing them in would bury the data path under the feedback
     * endpoint's microframe-rate completions.
     */
    std::atomic<long long> urbOk{0};
    std::atomic<long long> urbExdev{0};
    std::atomic<long long> urbOverflow{0};
    std::atomic<long long> urbOther{0};
    std::atomic<long long> urbPacketErrors{0};
    /** Feedback URBs whose payload was not the expected 4 bytes or whose statuses were non-zero. */
    std::atomic<long long> feedbackAbnormal{0};
    /** Well-formed feedback values the sanity window rejected (no clock change applied). */
    std::atomic<long long> feedbackRejected{0};
    /** Packets reduced to the hard cap; only reachable with arithmetic outside the accepted window. */
    std::atomic<long long> clampedPackets{0};
    /**
     * Submits the pump made that failed. Nothing else counts them: the re-arm path ignores
     * submitSlot()'s return value, so a pipeline that keeps silently losing URBs still reports
     * `failed == 0` and `underruns == 0`.
     */
    std::atomic<long long> submitFailures{0};
    /** Feedback URBs that could not be re-armed; the clock estimate freezes when this grows. */
    std::atomic<long long> feedbackSubmitFailures{0};
    /** Microframes produced since open; the timestamp for the feedback logs. */
    std::atomic<long long> microframeCount{0};

    /** Rate limiters for the event logs a broken device could otherwise flood. */
    LogLimiter feedbackChangeLog;
    LogLimiter feedbackAbnormalLog;
    LogLimiter feedbackRejectLog;

    /**
     * Safety net for a stream that never reached nativeClose: the failure paths inside nativeOpen,
     * and any future caller that drops a handle without closing it. Out of line because it uses the
     * teardown/release helpers defined below.
     */
    ~IsoStream();
};

/**
 * The live streams, keyed by integer handle.
 *
 * A handle is never a pointer and never reused: the old `jlong`-as-IsoStream* let nativeClose and
 * nativeWrite race on the same object, and let a second release free it twice. See the file header
 * for the full race path.
 */
std::unordered_map<jlong, std::shared_ptr<IsoStream>> g_streams;
std::mutex g_registryMutex;
/** Starts at 1, so 0 keeps meaning "no stream" to the Kotlin side. */
std::atomic<jlong> g_nextHandle{1};

/**
 * Resolves a handle. The returned shared_ptr keeps the stream alive for the whole JNI call even if
 * nativeClose erases the entry on another thread immediately afterwards.
 *
 * Only this lookup takes the registry mutex; it is released before any ioctl, join or stream field
 * is touched.
 */
std::shared_ptr<IsoStream> findStream(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_registryMutex);
    auto it = g_streams.find(handle);
    return it == g_streams.end() ? nullptr : it->second;
}

/** Files one reaped URB into the teardown histogram. */
void recordUrbStatus(IsoStream *stream, int status) {
    // usbfs reports completion as a negative errno. -EXDEV is a cancelled URB, which every stop
    // produces; -EOVERFLOW is the host controller saying the packet did not fit the interval, the
    // signature of packet sizing that disagrees with the device.
    if (status == 0) {
        stream->urbOk.fetch_add(1, std::memory_order_relaxed);
    } else if (status == -EXDEV) {
        stream->urbExdev.fetch_add(1, std::memory_order_relaxed);
    } else if (status == -EOVERFLOW) {
        stream->urbOverflow.fetch_add(1, std::memory_order_relaxed);
    } else {
        stream->urbOther.fetch_add(1, std::memory_order_relaxed);
    }
}

/** Errors that mean the device is gone rather than this call being wrong. */
bool isFatalErrno(int code) {
    return code == ENODEV || code == ESHUTDOWN || code == EPROTO || code == ENXIO;
}

int nextMicroframeFrames(IsoStream *stream) {
    // Frames accumulate in the double domain so a feedback-corrected rate can be fractional; the
    // carry is carried over, which keeps the long-term average exact.
    //
    // Also the clock for the forensic logs: every call is one microframe of audio produced, whether
    // the packet built from it reaches the device or not.
    stream->microframeCount.fetch_add(1, std::memory_order_relaxed);
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
        int frames = nextMicroframeFrames(stream);
        // The buffer allows ceil(rate / 8000) + 1 frames per packet, so following the device's
        // feedback clock does not run into the end of the URB: a fast DAC legitimately asks for the
        // extra frame, and clamping it would make the transport send fewer frames than the DAC
        // consumes - its ring would grow until writes were rejected or the position drifted. The
        // check below is only the hard backstop for arithmetic outside the accepted feedback window
        // (the sanity window bounds a value below nominal * 1.1): the extra frames then stay in the
        // ring rather than being written past the buffer, and the clamped counter records it.
        if (frames > stream->maxFramesPerPacket) {
            if (stream->clampedPackets.fetch_add(1) == 0) {
                LOGW("packet clamp backstop: requested %d frames > max %d (frameBytes=%d "
                     "maxPacket=%u microframes=%lld)",
                     frames, stream->maxFramesPerPacket, stream->frameBytes,
                     stream->maxPacketBytes, stream->microframeCount.load());
            }
            frames = stream->maxFramesPerPacket;
        }
        const int packetBytes = frames * stream->frameBytes;
        if (packetBytes < 0 || offset + static_cast<size_t>(packetBytes) > slot.capacity) {
            // Unreachable after the clamp; kept as the hard backstop in case maxPacketBytes and the
            // per-packet budget ever diverge.
            LOGE("packet exceeds buffer: packet=%d offset=%zu capacity=%zu",
                 packetBytes, offset, slot.capacity);
            stream->submitFailures.fetch_add(1);
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
        stream->submitFailures.fetch_add(1);
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
        stream->feedbackSubmitFailures.fetch_add(1);
        return false;
    }
    slot.inFlight.store(true);
    return true;
}

/** Reads a completed feedback URB and re-arms it. */
void consumeFeedback(IsoStream *stream, UrbSlot &slot) {
    const int status = slot.urb->iso_frame_desc[0].status;
    const unsigned int actual = slot.urb->iso_frame_desc[0].actual_length;
    const int urbStatus = slot.urb->status;
    const long long seen = stream->feedbackSeen.load();
    if (status != 0 || actual != 4 || urbStatus != 0) {
        // A stalled or short feedback IN used to be invisible after the first eight accepted
        // samples: the value was simply not applied and no counter moved, so the transport looked
        // healthy while its clock estimate was frozen. Log it (rate limited) and count it.
        const long long total = stream->feedbackAbnormal.fetch_add(1) + 1;
        if (stream->feedbackAbnormalLog.allow()) {
            LOGW("feedback URB abnormal: packetStatus=%d urbStatus=%d actual=%u total=%lld "
                 "bytes=%02X %02X %02X %02X microframes=%lld",
                 status, urbStatus, actual, total,
                 slot.pcm[0], slot.pcm[1], slot.pcm[2], slot.pcm[3],
                 stream->microframeCount.load());
        }
    } else if (seen < 8) {
        LOGI("feedback: status=%d actual=%u urbStatus=%d bytes=%02X %02X %02X %02X",
             status, actual, urbStatus,
             slot.pcm[0], slot.pcm[1], slot.pcm[2], slot.pcm[3]);
    }
    if (status == 0 && actual >= 4) {
        uint32_t raw = 0;
        std::memcpy(&raw, slot.pcm, sizeof(raw));
        const double frames = framesPerUframeFromRaw(raw, stream->sampleRate);
        const double nominal = static_cast<double>(stream->sampleRate) / kMicroframesPerSecond;
        // Reject values that are not a clock at all (a stalled endpoint can return zeros).
        if (nominal > 0.0 && frames > nominal * 0.9 && frames < nominal * 1.1) {
            const double newRate = frames * kMicroframesPerSecond;
            if (newRate != stream->effectiveRate && stream->feedbackChangeLog.allow()) {
                // The raw bytes belong in this line: the feedback convention (ratio versus absolute
                // count) is decided from them, and a change log without them cannot be audited.
                LOGI("feedback change: framesPerUframe %.6f -> %.6f rate %.3f -> %.3f "
                     "raw=%02X %02X %02X %02X microframes=%lld",
                     stream->effectiveRate / kMicroframesPerSecond, frames,
                     stream->effectiveRate, newRate,
                     slot.pcm[0], slot.pcm[1], slot.pcm[2], slot.pcm[3],
                     stream->microframeCount.load());
            }
            stream->effectiveRate = newRate;
            stream->feedbackPpm.store(feedbackPpmFromRaw(raw, stream->sampleRate));
            stream->feedbackSeen.fetch_add(1);
        } else {
            // Well-formed but outside the sanity window: the old code dropped it silently, which is
            // exactly how a frozen or garbage clock reads as "healthy" - effectiveRate keeps its
            // last good value while no packet follows the device any more. Counted and rate limited;
            // the acceptance policy itself is unchanged.
            const long long total = stream->feedbackRejected.fetch_add(1) + 1;
            if (stream->feedbackRejectLog.allow()) {
                LOGW("feedback rejected: framesPerUframe=%.6f nominal=%.6f raw=%02X %02X %02X %02X "
                     "total=%lld microframes=%lld",
                     frames, nominal, slot.pcm[0], slot.pcm[1], slot.pcm[2], slot.pcm[3],
                     total, stream->microframeCount.load());
            }
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
                     "dead=%d maxGap=%lld clamped=%lld submitFail=%lld feedbackAbnormal=%lld "
                     "feedbackRejected=%lld feedbackSubmitFail=%lld",
                     stream->submitted.load(), stream->completed.load(),
                     stream->framesSent.load(), stream->underruns.load(),
                     stream->dead.load() ? 1 : 0, stream->maxGapUs.load(),
                     stream->clampedPackets.load(), stream->submitFailures.load(),
                     stream->feedbackAbnormal.load(), stream->feedbackRejected.load(),
                     stream->feedbackSubmitFailures.load());
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
                recordUrbStatus(stream, done->status);
                stream->urbPacketErrors.fetch_add(packetErrors);
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

    // Cancel whatever is still queued, and count it: each cancelled URB owes one completion.
    int pending = 0;
    for (auto &slot : stream->slots) {
        if (slot.inFlight.exchange(false)) {
            ioctl(stream->fd, USBDEVFS_DISCARDURB, slot.urb);
            ++pending;
        }
    }
    for (auto &slot : stream->feedbackSlots) {
        if (slot.inFlight.exchange(false)) {
            ioctl(stream->fd, USBDEVFS_DISCARDURB, slot.urb);
            ++pending;
        }
    }

    // USBDEVFS_DISCARDURB only queues the unlink; the completion comes back on a later completion
    // interrupt. The previous loop broke on the first EAGAIN - which is exactly what the kernel
    // answers before that interrupt lands - so on the usual path it reaped nothing and left every
    // cancelled URB owned by the kernel while releaseResources freed its memory. Keep reaping, in
    // bounded 1 ms slices, until every cancelled URB has come back; a healthy device needs one or
    // two slices. If the budget runs out, say so: the warning is the only trace of a URB that
    // outlived its buffer.
    int attempts = 0;
    while (pending > 0 && attempts < kTeardownReapAttempts) {
        usbdevfs_urb *done = nullptr;
        const int rc = ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (rc == 0 && done != nullptr) {
            // Only data URBs feed the histogram; feedback completions have their own log above.
            bool isFeedback = false;
            for (auto &slot : stream->feedbackSlots) {
                if (slot.urb == done) {
                    isFeedback = true;
                    break;
                }
            }
            if (!isFeedback) recordUrbStatus(stream, done->status);
            --pending;
            continue;
        }
        if (rc < 0 && errno != EAGAIN && errno != EINTR) {
            // EBADF/ENODEV: the descriptor or device is gone, so no completion can be delivered.
            LOGW("teardown: reap stopped early: errno=%d %s pending=%d",
                 errno, strerror(errno), pending);
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        ++attempts;
    }
    if (pending > 0) {
        LOGW("teardown: %d URB(s) still owned by the kernel after %d ms",
             pending, attempts);
    }

    // The evidence lines: the first separates cancellations from overruns and counts the silent
    // failure paths `failed`/`underruns` cannot see; the second pins the parameters packets were
    // sized from, so a stream that reported `failed == 0` can still be explained after the fact.
    size_t ringLeft = 0;
    {
        std::lock_guard<std::mutex> ringLock(stream->ringMutex);
        ringLeft = stream->ring.count;
    }
    LOGI("teardown: urbs ok=%lld exdev=%lld overflow=%lld other=%lld packetErr=%lld "
         "submitFail=%lld clamped=%lld feedbackAbnormal=%lld feedbackRejected=%lld "
         "feedbackSubmitFail=%lld reapLeft=%d submitted=%lld",
         stream->urbOk.load(), stream->urbExdev.load(), stream->urbOverflow.load(),
         stream->urbOther.load(), stream->urbPacketErrors.load(), stream->submitFailures.load(),
         stream->clampedPackets.load(), stream->feedbackAbnormal.load(),
         stream->feedbackRejected.load(), stream->feedbackSubmitFailures.load(), pending,
         stream->submitted.load());
    LOGI("teardown: params nominalPacket=%u maxPacket=%u urbBytes=%zu framesPerUframe=%.6f "
         "effectiveRate=%.3f ringLeft=%zu",
         stream->nominalPacketBytes, stream->maxPacketBytes, stream->bytesPerUrb,
         stream->effectiveRate / kMicroframesPerSecond, stream->effectiveRate, ringLeft);
}

/**
 * Frees the URBs and the duplicated descriptor.
 *
 * Caller holds stream->io (or owns the stream exclusively, as the destructor does). Idempotent:
 * every pointer is nulled and the fd reset, so nativeClose followed by the destructor cannot free
 * the same allocation twice. teardown before this point waits, bounded, for every cancelled URB to
 * come back and logs the ones that did not (`reapLeft`); those are the only URBs the kernel could
 * still reference when this frees their memory.
 */
void releaseResources(IsoStream *stream) {
    for (auto &slot : stream->slots) {
        slot.inFlight.store(false);
        free(slot.urb);
        free(slot.pcm);
        slot.urb = nullptr;
        slot.pcm = nullptr;
        slot.capacity = 0;
    }
    stream->slots.clear();
    for (auto &slot : stream->feedbackSlots) {
        slot.inFlight.store(false);
        free(slot.urb);
        free(slot.pcm);
        slot.urb = nullptr;
        slot.pcm = nullptr;
        slot.capacity = 0;
    }
    stream->feedbackSlots.clear();
    if (stream->fd >= 0) {
        close(stream->fd);
        stream->fd = -1;
    }
}

IsoStream::~IsoStream() {
    // The pump thread never holds a shared_ptr, so it is the one reader that can still be inside
    // this object when the last reference goes away (a handle dropped without close). Stop and join
    // it before any member it touches is freed. The mutex is not taken: no caller holds a
    // reference, so only the pump is left, and teardown's join is what orders against it.
    if (running.load() || pump.joinable()) teardown(this);
    releaseResources(this);
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
    auto stream = std::make_shared<IsoStream>();
    stream->fd = dup(usbFd);
    if (stream->fd < 0) {
        LOGE("dup failed: errno=%d %s", errno, strerror(errno));
        return 0;  // the destructor closes the fd and frees whatever was already allocated
    }
    stream->endpoint = endpoint;
    stream->sampleRate = sampleRate;
    stream->channels = channels;
    stream->subslotBytes = subslotBytes;
    stream->frameBytes = channels * subslotBytes;
    stream->ring.reset(static_cast<size_t>(stream->frameBytes) * static_cast<size_t>(sampleRate) / 5);
    stream->feedbackEndpoint = feedbackEndpoint;
    stream->effectiveRate = static_cast<double>(sampleRate);

    // One frame of headroom over the nominal ceil(), so the packets that follow the feedback clock
    // always fit: see maxFramesPerPacket. This is the budget the URB buffers are allocated from.
    const int nominalFrames = (sampleRate + kMicroframesPerSecond - 1) / kMicroframesPerSecond;
    stream->maxFramesPerPacket = nominalFrames + 1;
    stream->nominalPacketBytes = static_cast<uint32_t>(nominalFrames * stream->frameBytes);
    stream->maxPacketBytes =
        static_cast<uint32_t>(stream->maxFramesPerPacket * stream->frameBytes);
    stream->bytesPerUrb = static_cast<size_t>(stream->maxPacketBytes) * kPacketsPerUrb;
    const size_t bytesPerUrb = stream->bytesPerUrb;

    stream->slots.resize(kUrbsInFlight);
    for (auto &slot : stream->slots) {
        const size_t urbSize =
            sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
        slot.urb = static_cast<usbdevfs_urb *>(calloc(1, urbSize));
        slot.pcm = static_cast<uint8_t *>(calloc(1, bytesPerUrb));
        slot.capacity = bytesPerUrb;
        if (slot.urb == nullptr || slot.pcm == nullptr) {
            LOGE("URB allocation failed");
            return 0;  // the destructor closes the fd and frees every slot
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
    // Registered last: everything above can fail without a handle ever escaping, and no ioctl runs
    // under the registry mutex.
    const jlong handle = g_nextHandle.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_registryMutex);
        g_streams[handle] = stream;
    }
    LOGI("open handle=%lld fd=%d ep=0x%02X feedback=0x%02X rate=%d ch=%d subslot=%d "
         "nominalPacket=%u maxPacket=%u urbBytes=%zu depth=%d",
         static_cast<long long>(handle), stream->fd, endpoint, feedbackEndpoint, sampleRate,
         channels, subslotBytes, stream->nominalPacketBytes, stream->maxPacketBytes, bytesPerUrb,
         kUrbsInFlight);
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStart(JNIEnv *, jobject, jlong handle) {
    auto stream = findStream(handle);
    if (!stream) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(stream->io);
    if (stream->closed) return JNI_FALSE;
    // Idempotent on purpose. Reporting failure for an already-running stream left the caller
    // believing it had to retry, and every retry failed the same way - which is how the player ended
    // up stuck buffering with the transport filling silence underneath it.
    if (stream->running.load()) return JNI_TRUE;
    if (stream->dead.load()) return JNI_FALSE;
    // The pump can leave `running` false on its own when usbfs fails mid-stream, and only stop or
    // close joins it. Starting again on top of that joinable thread would make the assignment below
    // call std::terminate, so reap the finished thread first.
    if (stream->pump.joinable()) stream->pump.join();

    // What the decoder has already queued at start, and whether it is frame-aligned. A nonzero
    // remainder means a writer ended mid-frame: packet sizing cannot repair that, and the DAC would
    // render channel-swapped PCM, so the number belongs beside the transport's health counters.
    size_t ringBytes = 0;
    size_t ringRemainder = 0;
    {
        std::lock_guard<std::mutex> ringLock(stream->ringMutex);
        ringBytes = stream->ring.count;
        ringRemainder = stream->frameBytes > 0
            ? ringBytes % static_cast<size_t>(stream->frameBytes)
            : 0;
    }
    LOGI("start: ringBytes=%zu frameAlignRemainder=%zu frameBytes=%d ringCapacity=%zu "
         "clamped=%lld submitFail=%lld feedbackAbnormal=%lld feedbackRejected=%lld",
         ringBytes, ringRemainder, stream->frameBytes, stream->ring.capacity,
         stream->clampedPackets.load(), stream->submitFailures.load(),
         stream->feedbackAbnormal.load(), stream->feedbackRejected.load());

    for (auto &slot : stream->slots) {
        if (!submitSlot(stream.get(), slot)) {
            const int code = errno;
            // Roll back everything that did go out. Reuse teardown so the cancelled URBs are reaped
            // like any other: the old inline discard returned without waiting, and the close that
            // followed then freed URBs the kernel still owned.
            teardown(stream.get());
            LOGE("start failed: errno=%d %s", code, strerror(code));
            return JNI_FALSE;
        }
    }
    for (auto &slot : stream->feedbackSlots) {
        submitFeedbackSlot(stream.get(), slot);
    }
    // The gap metric measures stalls within a run; without this reset it would count the pause
    // between two runs as a two-second freeze.
    stream->lastReap = std::chrono::steady_clock::now();
    stream->running.store(true);
    try {
        stream->pump = std::thread(pumpLoop, stream.get());
    } catch (...) {
        // Thread creation can fail under memory pressure (EAGAIN). Leaving `running` true would make
        // every later start answer "already running" with no pump behind it, so roll back to the
        // stopped state and discard the URBs that were just queued.
        stream->running.store(false);
        teardown(stream.get());
        LOGE("pump thread creation failed");
        return JNI_FALSE;
    }
    LOGI("started: %d URBs x %d packets", kUrbsInFlight, kPacketsPerUrb);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto stream = findStream(handle);
    if (!stream) return;
    std::lock_guard<std::mutex> lock(stream->io);
    if (stream->closed) return;
    teardown(stream.get());
    LOGI("stopped: submitted=%lld completed=%lld failed=%lld frames=%lld primed=%lld "
         "underruns=%lld feedback=%lld ratePpm=%d maxGapUs=%lld",
         stream->submitted.load(), stream->completed.load(), stream->failed.load(),
         stream->framesSent.load(), stream->primed.load(), stream->underruns.load(),
         stream->feedbackSeen.load(), stream->feedbackPpm.load(), stream->maxGapUs.load());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeClose(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<IsoStream> stream;
    {
        std::lock_guard<std::mutex> registryLock(g_registryMutex);
        auto it = g_streams.find(handle);
        if (it == g_streams.end()) return;
        stream = it->second;
        // Erased before the stream lock is taken: no new caller can resolve this handle while it is
        // being torn down, and a second close finds nothing and returns.
        g_streams.erase(it);
    }
    std::lock_guard<std::mutex> lock(stream->io);
    if (stream->closed) return;
    stream->closed = true;
    teardown(stream.get());
    releaseResources(stream.get());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeWrite(
    JNIEnv *env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
    auto stream = findStream(handle);
    if (!stream || data == nullptr || offset < 0 || length <= 0) return -1;
    if (offset + length > env->GetArrayLength(data)) return -1;
    // One entry at a time per stream. A close that races this call waits for the bounded ring wait
    // below before it can tear the stream down, so the wait is capped on purpose.
    std::lock_guard<std::mutex> lock(stream->io);
    if (stream->closed) return -1;
    // -1 is "bad call", 0 is "ring full"; -2 tells the backend the device is gone.
    if (stream->dead.load()) return -2;
    std::vector<uint8_t> chunk(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(chunk.data()));
    // Wait briefly for room instead of answering "zero frames" immediately: Media3 retries a zero
    // return in a tight loop, and that spin was a large part of why playing in exclusive mode cost
    // twice the CPU of the standard route.
    std::unique_lock<std::mutex> ringLock(stream->ringMutex);
    if (stream->ring.space() == 0 && !stream->dead.load()) {
        stream->ringSpace.wait_for(ringLock, std::chrono::milliseconds(5));
    }
    const size_t take = stream->ring.push(chunk.data(), chunk.size());
    return static_cast<jint>(take);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeFlush(JNIEnv *, jobject, jlong handle) {
    auto stream = findStream(handle);
    if (!stream) return;
    std::lock_guard<std::mutex> lock(stream->io);
    if (stream->closed) return;
    // Seeking: drop queued audio so the next microframe carries the new position, not the old one.
    std::lock_guard<std::mutex> ringLock(stream->ringMutex);
    stream->ring.head = stream->ring.tail = stream->ring.count = 0;
    // A seek restarts the stream at a new position, so the silence that follows is priming again,
    // not a mid-stream dropout.
    stream->pcmSeen.store(false);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sertum_player_audio_uac2_Uac2Transport_nativeStats(JNIEnv *env, jobject, jlong handle) {
    auto stream = findStream(handle);
    jlong values[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    if (stream) {
        std::lock_guard<std::mutex> lock(stream->io);
        if (!stream->closed) {
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
