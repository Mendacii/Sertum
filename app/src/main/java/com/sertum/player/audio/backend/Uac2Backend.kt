package com.sertum.player.audio.backend

import android.os.SystemClock
import com.sertum.player.audio.uac2.Uac2HardwareVolumeStatus
import com.sertum.player.audio.uac2.Uac2Session
import com.sertum.player.audio.uac2.Uac2Transport
import com.sertum.player.audio.uac2.Uac2VolumeAction
import com.sertum.player.audio.uac2.Uac2VolumeCorrectionGate
import com.sertum.player.audio.uac2.Uac2VolumePolicy
import com.sertum.player.audio.uac2.Uac2VolumeRange
import com.sertum.player.data.prefs.AppPreferences
import com.sertum.player.data.prefs.Uac2VolumeHistory
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The UAC2 backend: PCM leaves the app as isochronous USB packets, with no mixer in the path.
 *
 * Position is wall clock based rather than taken from the device. The DAC does publish a feedback
 * endpoint that would give the exact rate, but nothing reads it yet, and a hardware-rate clock would
 * only be better by the crystal's tolerance over a track; the wall clock cannot drift far enough to
 * matter for a seek bar.
 */
class Uac2Backend(private val session: Uac2Session) : AudioOutputBackend {

    override var capabilities =
        BackendCapabilities(supportsHardwareVolume = false, isExclusive = false)
        private set

    private var stream: Uac2Session.OpenStream? = null
    private var frameBytes = 0
    private var sampleRate = 0
    private var hardwareVolumeUsable = false
    private val position = com.sertum.player.audio.uac2.PositionClock()

    /**
     * The last proven Feature Unit range, used by the monitor to turn a raw value into a
     * percentage without asking the device for GET_MIN/GET_MAX every 500ms.
     */
    @Volatile
    private var measuredVolumeRange: Uac2VolumeRange? = null

    /** Seed for the monitor so its first successful poll is not logged as a change. */
    @Volatile
    private var lastObservedVolumeRaw: Int? = null

    /**
     * The last value this route established as safe. The monitor replays this (or the -40 dB
     * fail-safe when there is no history) if the DAC jumps loud after the initial restore.
     */
    @Volatile
    private var safeTargetRaw: Int? = null

    private val correctionGate = Uac2VolumeCorrectionGate()

    private val volumeMonitorLock = Any()
    private var volumeMonitor: ScheduledExecutorService? = null
    private var lastMonitoredRaw: Int? = null
    private var monitorFailureLogged = false

    /** True from a successful open/start until stop/release; keeps the 2s phase alive. */
    @Volatile
    private var streamAliveForVolume = false

    init {
        // The manager constructs this backend immediately after the claim, so this window starts
        // at the claim even if playback does not begin for several seconds.
        startVolumeMonitor()
    }

    /**
     * Whether the transport is currently submitting URBs.
     *
     * Kept separately from [position] because they answer different questions: the clock can be
     * paused while the transport is still draining, and `nativeStart` refuses a stream that is
     * already running - the failure that showed up as `IllegalStateException: UAC2 start failed`
     * every time Media3 asked to play a stream that was already playing.
     */
    private var transportRunning = false

    /**
     * Invoked once when the transport reports the device is gone.
     *
     * Deliberately not surfaced as a write failure: Media3 treats that as a bad track and skips it,
     * while an unplug should pause and keep the queue.
     */
    var onDeviceLost: (() -> Unit)? = null

    private var deviceLostReported = false

    // No permanent "released" flag here. The backend is shared by every AudioOutput the provider
    // hands out, and Media3 releases an output when it reconfigures for a new format - so a flag
    // set on that path would poison the backend for the rest of the session and the next open()
    // after a sample-rate change would fail. The session itself is the real lifetime: once it is
    // closed, openStream() returns null and open() fails on its own.

    override fun open(spec: StreamSpec): Result<Unit> {
        releaseStream()
        streamAliveForVolume = false
        val opened = session.openStream(spec.sampleRate, spec.channelCount, spec.bitDepth)
            ?: return Result.failure(
                IllegalStateException("UAC2 open failed at ${spec.sampleRate}Hz/${spec.bitDepth}bit"),
            )
        streamAliveForVolume = true
        correctionGate.reset()
        // After setInterface/clock and nativeOpen, but before nativeStart: if the alt-setting
        // change itself resets the DAC to its default, this is the observation that catches it.
        hardwareVolumeUsable = applyVolumeSafety()
        // Covers the interval between the claim and the first nativeStart; play() starts a fresh
        // window around every later start.
        startVolumeMonitor()
        stream = opened
        frameBytes = spec.channelCount * opened.format.subslotBytes
        sampleRate = spec.sampleRate
        position.reset()
        transportRunning = false
        deviceLostReported = false
        capabilities = BackendCapabilities(
            supportsHardwareVolume = hardwareVolumeUsable,
            isExclusive = true,
        )
        return Result.success(Unit)
    }

    /**
     * Reads the DAC's hardware volume after the claim and before the isochronous stream starts.
     *
     * Safety contract, in order:
     * 1. GET_CUR, GET_MIN and GET_MAX must all succeed. Without a proven range nothing is written;
     *    the code logs the failure and leaves the DAC exactly as it is.
     * 2. The only values ever written are the exact raw value previously observed on this
     *    vendorId/productId, or the first-run fail-safe at `clamp(-40 dB)`. There is no arbitrary
     *    default and no value borrowed from another DAC.
     * 3. A write only ever goes DOWN: the target must be below a fresh GET_CUR taken immediately
     *    before SET_CUR, and below the current observation. The monitor uses the same rule.
     * 4. With no history and a current value below the danger threshold, the value is recorded and
     *    nothing is written. With no history at/above the threshold, the fail-safe writes
     *    `clamp(-40 dB)` from the *proven* range; leaving full scale alone on a first connection
     *    is the exact injury this path exists to prevent.
     * 5. The feature unit must advertise Volume (D1) or a runtime GET range must prove it. The
     *    DAWN PRO2 fails both (STALL), so it never reaches SET_CUR.
     *
     * `force=true` on [com.sertum.player.audio.uac2.Uac2DeviceManager] detaches the kernel's
     * snd-usb-audio driver (USBDEVFS_DISCONNECT); this code never issues a USB reset. A DAC can
     * still return from a power cycle at its own default, which is why the value is observed here
     * rather than assumed, and why an unobserved value cannot be corrected by the app.
     */
    private fun applyVolumeSafety(): Boolean {
        measuredVolumeRange = null
        safeTargetRaw = null
        val vendorId = session.device.vendorId
        val productId = session.device.productId
        val prefs = AppPreferences.current()
        if (prefs == null) {
            session.report("volume: preference store unavailable; history disabled")
        }
        val observation = session.observeVolume()
        if (observation == null) {
            Uac2HardwareVolumeStatus.set(false)
            return false
        }
        val range = observation.range
        Uac2HardwareVolumeStatus.set(range.isValid)
        measuredVolumeRange = range
        val current = observation.currentRaw
        lastObservedVolumeRaw = current
        val unitId = session.descriptors.featureUnit?.unitId
        val record = prefs?.uac2VolumeHistory(vendorId, productId)
        val history = if (record != null && isSameVolumeFingerprint(record, range, unitId)) {
            record.raw
        } else {
            if (record != null) {
                session.report(
                    "volume: history fingerprint changed " +
                        "(unit=${record.unitId}->${unitId ?: "none"} " +
                        "range=${record.minRaw}..${record.maxRaw}->${range.minRaw}..${range.maxRaw}); " +
                        "not restoring it",
                )
            }
            null
        }
        val percent = range.percent(current)
        val decision = Uac2VolumePolicy.decide(current, range, history)
        session.report(
            "volume: decision=${decision.action} current=$current " +
                "percent=${percent ?: "invalid"} history=${history ?: "none"} " +
                "range=${range.minRaw}..${range.maxRaw}",
        )

        when (decision.action) {
            Uac2VolumeAction.RESTORE,
            Uac2VolumeAction.FIRST_RUN_FAIL_SAFE -> {
                val label = if (decision.action == Uac2VolumeAction.FIRST_RUN_FAIL_SAFE) {
                    "first-run fail-safe"
                } else {
                    "restore"
                }
                val target = range.clamp(decision.targetRaw ?: current)
                if (target >= current) {
                    // The current value is already the safer of the two. Record it only when it is
                    // not itself dangerous; otherwise there is no lower target to write.
                    if (!Uac2VolumePolicy.isDangerous(current, range)) {
                        recordHistory(current, range, unitId)
                    } else {
                        session.report(
                            "volume: $label target=$target is not below current=$current; no write",
                        )
                    }
                    return range.isValid
                }
                writeDownTo(target, range, label)
            }
            Uac2VolumeAction.RECORD_CURRENT,
            Uac2VolumeAction.KEEP_CURRENT -> {
                recordHistory(current, range, unitId)
                session.report(
                    "volume: recorded current=$current percent=${percent ?: "invalid"} " +
                        "persisted=${prefs != null}",
                )
            }
            Uac2VolumeAction.ALREADY_AT_HISTORY -> {
                recordHistory(current, range, unitId)
                session.report("volume: current already equals history; no write")
            }
            Uac2VolumeAction.HISTORY_OUT_OF_RANGE -> {
                if (Uac2VolumePolicy.isDangerous(current, range)) {
                    // The stored value cannot be trusted against this range; use the same
                    // fail-safe as a first connection instead of leaving full scale untouched.
                    writeDownTo(
                        Uac2VolumePolicy.failSafeRaw(range),
                        range,
                        "history-range-mismatch fail-safe",
                    )
                } else {
                    session.report(
                        "volume: history=${history ?: "none"} outside proven range; not writing",
                    )
                }
            }
            Uac2VolumeAction.UNPROVEN_RANGE -> {
                session.report("volume: no proven range; not writing")
            }
        }
        return range.isValid
    }

    /**
     * The only path to SET_CUR. Re-reads GET_CUR immediately before the write, refuses any
     * target that is not strictly lower, and persists only after the readback confirms the value.
     */
    private fun writeDownTo(
        target: Int,
        range: Uac2VolumeRange,
        label: String,
    ): Boolean {
        if (!session.hasVolumeControl()) {
            session.report("volume: $label refused, DAC has no usable volume control")
            return false
        }
        val fresh = session.readVolumeCurrent()
        if (fresh == null) {
            session.report("volume: $label refused, fresh GET_CUR failed")
            return false
        }
        if (target >= fresh) {
            if (!Uac2VolumePolicy.isDangerous(fresh, range)) {
                lastObservedVolumeRaw = fresh
                recordHistory(fresh, range, session.descriptors.featureUnit?.unitId)
            }
            session.report("volume: $label refused target=$target fresh=$fresh (write-down only)")
            return false
        }
        val written = session.writeVolume(target, range, fresh)
        val after = if (written) session.readVolumeCurrent() else null
        val confirmed = after == target
        session.report(
            "volume: $label target=$target fresh=$fresh written=$written " +
                "after=${after ?: "unreadable"} confirmed=$confirmed",
        )
        if (written) {
            safeTargetRaw = target
        }
        if (confirmed) {
            lastObservedVolumeRaw = target
            recordHistory(target, range, session.descriptors.featureUnit?.unitId)
        }
        return confirmed
    }

    private fun recordHistory(raw: Int, range: Uac2VolumeRange, unitId: Int?) {
        safeTargetRaw = raw
        val id = unitId ?: return
        AppPreferences.current()?.setUac2VolumeHistory(
            session.device.vendorId,
            session.device.productId,
            Uac2VolumeHistory(
                raw = raw,
                minRaw = range.minRaw,
                maxRaw = range.maxRaw,
                resolutionRaw = range.resolutionRaw,
                unitId = id,
            ),
        )
    }

    /**
     * Samples GET_CUR every 500ms for the first ten seconds, then every two seconds while the
     * stream stays alive. Logs only a changed value (plus one failure line per window), with an
     * offset from the claim that created [session], so the log answers "did the DAC apply its
     * volume asynchronously?" and keeps capturing the reader's HID button changes after that.
     *
     * The thread is restarted at every nativeStart and stopped by stop()/release(). It normally
     * only reads; a dangerous post-start jump is corrected through the same rate-limited,
     * fresh-GET, write-down-only path as [applyVolumeSafety].
     */
    private fun startVolumeMonitor() {
        val executor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "sertum-uac2-volume").apply { isDaemon = true }
            },
        )
        synchronized(volumeMonitorLock) {
            volumeMonitor?.shutdownNow()
            volumeMonitor = executor
            lastMonitoredRaw = lastObservedVolumeRaw
            monitorFailureLogged = false
        }
        val claimAt = session.claimedAtElapsedMs
        val fastPhaseEnds = SystemClock.elapsedRealtime() + VOLUME_MONITOR_WINDOW_MS
        var lastReadAt = 0L
        val task = object : Runnable {
            override fun run() {
                if (synchronized(volumeMonitorLock) { volumeMonitor !== executor }) return
                val now = SystemClock.elapsedRealtime()
                val inFastPhase = now < fastPhaseEnds
                if (!inFastPhase && !streamAliveForVolume) {
                    synchronized(volumeMonitorLock) {
                        if (volumeMonitor === executor) volumeMonitor = null
                    }
                    executor.shutdown()
                    return
                }
                val interval = if (inFastPhase) {
                    VOLUME_MONITOR_FAST_INTERVAL_MS
                } else {
                    VOLUME_MONITOR_SLOW_INTERVAL_MS
                }
                if (lastReadAt != 0L && now - lastReadAt < interval) return
                lastReadAt = now
                val raw = session.readVolumeForMonitor()
                // A read can take up to the control timeout; a stop/restart may have replaced this
                // executor while it was in flight. Never let the old task overwrite the new state.
                if (synchronized(volumeMonitorLock) { volumeMonitor !== executor }) return
                if (raw == null) {
                    synchronized(volumeMonitorLock) {
                        if (!monitorFailureLogged) {
                            monitorFailureLogged = true
                            Uac2HardwareVolumeStatus.set(false)
                            session.report(
                                "volume monitor: GET_CUR failed at claim+${now - claimAt}ms; " +
                                    "suppressing repeats",
                            )
                        }
                    }
                    return
                }
                val previous = synchronized(volumeMonitorLock) {
                    val old = lastMonitoredRaw
                    lastMonitoredRaw = raw
                    old
                }
                val range = measuredVolumeRange
                val percent = range?.percent(raw)
                if (range != null) {
                    if (Uac2VolumePolicy.isDangerous(raw, range)) {
                        correctAfterJump(raw, range, now, claimAt)
                    } else {
                        // Only a safe value becomes history. A jump back to max is the failure
                        // mode being defended against, so it must never overwrite the value that
                        // will be restored on the next open.
                        lastObservedVolumeRaw = raw
                        recordHistory(raw, range, session.descriptors.featureUnit?.unitId)
                    }
                }
                // The first successful poll is a baseline, not a change: only a value that differs
                // from an already observed one produces a diagnostics line.
                if (previous != null && previous != raw) {
                    session.report(
                        "volume monitor: current=$raw percent=${percent ?: "unknown"} " +
                            "claim+${now - claimAt}ms wall=${System.currentTimeMillis()}",
                    )
                }
            }
        }
        try {
            executor.scheduleAtFixedRate(
                task,
                0L,
                VOLUME_MONITOR_FAST_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            // release()/stop() won the race between publishing the executor and scheduling it.
            executor.shutdownNow()
        }
    }

    /**
     * A post-start jump to a dangerous value gets a rate-limited write-down through the same
     * fresh-GET/down-only guard as the open-time restore. This is the path the reported injury
     * most likely took: the DAC applied its own default after [applyVolumeSafety] had already run.
     */
    private fun correctAfterJump(
        raw: Int,
        range: Uac2VolumeRange,
        now: Long,
        claimAt: Long,
    ) {
        if (!session.hasVolumeControl()) return
        val target = Uac2VolumePolicy.correctionTarget(raw, range, safeTargetRaw) ?: return
        if (!correctionGate.tryAcquire(now)) return
        session.report(
            "volume monitor: dangerous jump raw=$raw claim+${now - claimAt}ms; " +
                "correcting to $target (${correctionGate.count()}/3)",
        )
        writeDownTo(target, range, "post-start correction")
    }

    private fun stopVolumeMonitor() {
        synchronized(volumeMonitorLock) {
            volumeMonitor?.shutdownNow()
            volumeMonitor = null
            lastMonitoredRaw = null
            monitorFailureLogged = false
        }
    }

    override fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int> {
        val current = stream ?: return Result.failure(IllegalStateException("no UAC2 stream"))
        if (frameBytes <= 0) return Result.failure(IllegalStateException("unknown UAC2 frame size"))
        val accepted = Uac2Transport.nativeWrite(current.handle, frame, offset, length)
        if (accepted == DEVICE_GONE) {
            // Report the loss out of band and answer "no frames accepted", never a failure: Media3
            // drains in pause(), where a WriteException is not caught and takes the process down.
            notifyDeviceLost()
            return Result.success(0)
        }
        if (accepted < 0) {
            return Result.failure(IllegalStateException("UAC2 write failed: $accepted"))
        }
        return Result.success(accepted / frameBytes)
    }

    override fun play(): Result<Unit> {
        val current = stream ?: return Result.failure(IllegalStateException("no UAC2 stream"))
        if (!transportRunning) {
            streamAliveForVolume = true
            // Start the observation window before nativeStart so the 500ms series contains both
            // the pre-start value and the transitions that follow it.
            startVolumeMonitor()
            if (!Uac2Transport.nativeStart(current.handle)) {
                android.util.Log.e("SertumUac2", "nativeStart failed handle=${current.handle}")
                return Result.failure(IllegalStateException("UAC2 start failed"))
            }
            transportRunning = true
        }
        position.start()
        return Result.success(Unit)
    }

    override fun pause(): Result<Unit> {
        position.pause()
        stopTransport()
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        streamAliveForVolume = false
        stopVolumeMonitor()
        position.pause()
        stopTransport()
        return Result.success(Unit)
    }

    override fun flush(): Result<Unit> {
        val current = stream ?: return Result.success(Unit)
        Uac2Transport.nativeFlush(current.handle)
        // A flush restarts the stream; Media3 re-anchors its own position around it.
        position.reset()
        return Result.success(Unit)
    }

    override fun release() {
        streamAliveForVolume = false
        stopVolumeMonitor()
        Uac2HardwareVolumeStatus.clear()
        measuredVolumeRange = null
        lastObservedVolumeRaw = null
        position.pause()
        releaseStream()
        capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = false)
    }

    /** No software volume: attenuation would change the samples this path exists to preserve. */
    override fun onVolumeChanged(volume01: Float) = Unit

    override fun getPositionUs(): Long = position.positionUs()

    /**
     * Fifty milliseconds of slack. At ten it was enough to keep playing but not enough to keep the
     * transport's ring fed, and the difference showed up as silence filling the gaps.
     */
    override fun getBufferSizeInFrames(): Int = if (sampleRate > 0) sampleRate / 20 else 0

    /**
     * Longest gap between URBs on the current stream, in microseconds.
     *
     * A frozen process shows up here and nowhere else: the counts, the ring and the position all
     * look healthy, because none of them ran.
     */
    fun maxGapUs(): Long =
        stream?.let { Uac2Transport.nativeStats(it.handle).getOrNull(10) } ?: 0L

    private fun notifyDeviceLost() {
        if (deviceLostReported) return
        deviceLostReported = true
        streamAliveForVolume = false
        stopVolumeMonitor()
        Uac2HardwareVolumeStatus.clear()
        onDeviceLost?.invoke()
    }

    private fun stopTransport() {
        if (!transportRunning) return
        transportRunning = false
        stream?.let { runCatching { Uac2Transport.nativeStop(it.handle) } }
    }

    private fun releaseStream() {
        transportRunning = false
        val current = stream ?: return
        stream = null
        runCatching { session.closeStream(current) }
    }

    private companion object {
        /** Mirrors `kDeviceLost` in uac2_transport.cpp: the ring is fine, the device is not. */
        const val DEVICE_GONE = -2


        /** Ten seconds after claim/nativeStart is the window where an asynchronous jump shows up. */
        const val VOLUME_MONITOR_WINDOW_MS = 10_000L
        const val VOLUME_MONITOR_FAST_INTERVAL_MS = 500L

        /** After the fast window, keep following the DAC's own button changes while it plays. */
        const val VOLUME_MONITOR_SLOW_INTERVAL_MS = 2_000L
    }
}

/**
 * M4: a restore is valid only when the persisted unit and range fingerprint match what the
 * device reports now. A changed min/max/resolution means the raw number belongs to another scale.
 */
internal fun isSameVolumeFingerprint(
    record: Uac2VolumeHistory,
    range: Uac2VolumeRange,
    unitId: Int?,
): Boolean = record.unitId == unitId &&
    record.minRaw == range.minRaw &&
    record.maxRaw == range.maxRaw &&
    record.resolutionRaw == range.resolutionRaw
