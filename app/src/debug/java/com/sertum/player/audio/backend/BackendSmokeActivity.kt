package com.sertum.player.audio.backend

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.sertum.player.domain.playback.StreamSpec
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * Device smoke test for the production AAudio backend.
 * Launch with:
 *   adb shell am start -n com.sertum.player/com.sertum.player.audio.backend.BackendSmokeActivity
 */
class BackendSmokeActivity : ComponentActivity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(label)
        if (started) return
        started = true

        thread {
            val rates = intArrayOf(44_100, 48_000, 96_000, 192_000)
            val bits = intArrayOf(16, 24)
            var passed = 0
            val backend = AaudioExclusiveBackend()
            for (rate in rates) {
                for (bit in bits) {
                    val spec = StreamSpec(rate, 2, bit, isExclusive = true)
                    val opened = backend.open(spec)
                    if (opened.isFailure) {
                        Log.w("SertumSmoke", "open failed $spec ${opened.exceptionOrNull()?.message}")
                        continue
                    }
                    val info = backend.streamInfo
                    Log.i("SertumSmoke", "opened $spec -> $info")
                    backend.play()
                    val ok = writeTone(backend, rate, bit)
                    backend.stop()
                    Log.i("SertumSmoke", "tone rate=$rate bits=$bit ok=$ok")
                    if (ok && info.actualRate == rate) passed++
                    Thread.sleep(200)
                }
            }
            backend.release()
            Log.i("SertumSmoke", "=== backend smoke done passed=$passed/8 ===")
            runOnUiThread { label.text = "Backend smoke done: $passed/8 passed" }
        }
    }

    private fun writeTone(backend: AaudioExclusiveBackend, rate: Int, bits: Int): Boolean {
        val seconds = 2
        val frames = rate * seconds
        val bytesPerFrame = if (bits >= 24) 6 else 4
        val bytes = ByteArray(frames * bytesPerFrame)
        for (i in 0 until frames) {
            val v = (0.2 * (if (bits >= 24) 8388607.0 else 32767.0) * sin(2.0 * Math.PI * 1000.0 * i / rate)).toInt()
            val off = i * bytesPerFrame
            if (bits >= 24) {
                val s = v and 0xFFFFFF
                bytes[off] = (s and 0xFF).toByte()
                bytes[off + 1] = ((s shr 8) and 0xFF).toByte()
                bytes[off + 2] = ((s shr 16) and 0xFF).toByte()
                bytes[off + 3] = bytes[off]
                bytes[off + 4] = bytes[off + 1]
                bytes[off + 5] = bytes[off + 2]
            } else {
                val s = v.toShort()
                bytes[off] = (s.toInt() and 0xFF).toByte()
                bytes[off + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                bytes[off + 2] = bytes[off]
                bytes[off + 3] = bytes[off + 1]
            }
        }
        val writtenFrames = backend.writePcm(bytes, 0, bytes.size).getOrNull() ?: -1
        Log.i("SertumSmoke", "tone rate=$rate bits=$bits writtenFrames=$writtenFrames expected=$frames")
        return writtenFrames == frames
    }
}
