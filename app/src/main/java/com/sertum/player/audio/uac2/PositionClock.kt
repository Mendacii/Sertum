package com.sertum.player.audio.uac2

/**
 * Stream position for a backend whose device reports nothing back.
 *
 * The DAC's feedback endpoint would give a hardware-accurate rate, but the transport does not read
 * it yet, and over a track the wall clock cannot drift far enough to matter for a seek bar. Frozen on
 * pause so a paused player keeps its position, and reset only where the audio contract says the
 * stream restarts (a seek).
 */
class PositionClock(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var baseUs = 0L
    private var startedAt = 0L
    private var running = false

    fun start() {
        if (running) return
        startedAt = nanoTime()
        running = true
    }

    fun pause() {
        if (!running) return
        baseUs += (nanoTime() - startedAt) / 1_000
        running = false
    }

    /** For a flush/seek: the stream starts again at zero. */
    fun reset() {
        pause()
        baseUs = 0L
    }

    fun positionUs(): Long =
        if (running) baseUs + (nanoTime() - startedAt) / 1_000 else baseUs
}