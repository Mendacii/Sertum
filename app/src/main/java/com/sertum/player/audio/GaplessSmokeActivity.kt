package com.sertum.player.audio

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import com.sertum.player.domain.playback.InMemoryResumePositionStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Gapless / sample-rate switching smoke.
 * Generates 3 WAVs (44.1k sine, 44.1k sine, 48k sine), plays them in one
 * Media3 queue and logs every media-item transition with its timestamp.
 */
class GaplessSmokeActivity : ComponentActivity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(label)
        if (started) return
        started = true

        val engine = PlayerEngine(this)
        val diagnostics = com.sertum.player.data.diagnostics.DiagnosticsStore(File(cacheDir, "smoke-diagnostics"))
        val coordinator = PlaybackCoordinator(
            context = this,
            engine = engine,
            resumeStore = InMemoryResumePositionStore(),
            diagnostics = diagnostics,
        )
        val startMs = SystemClock.elapsedRealtime()
        engine.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val t = SystemClock.elapsedRealtime() - startMs
                Log.i(
                    "SertumSmoke",
                    "transition t=${t}ms reason=$reason item=$mediaItem",
                )
            }
        })

        thread {
            val files = listOf(
                writeWav(44_100, 440, 3),
                writeWav(44_100, 880, 3),
                writeWav(48_000, 440, 3),
            )
            // ExoPlayer requires all player calls on the main thread.
            runOnUiThread {
                coordinator.playTracks(
                    listOf(
                        PlayableTrack(1L, Uri.fromFile(files[0]), "smoke-a", null, null),
                        PlayableTrack(2L, Uri.fromFile(files[1]), "smoke-b", null, null),
                        PlayableTrack(3L, Uri.fromFile(files[2]), "smoke-c", null, null),
                    ),
                    startIndex = 0,
                )
            }
            Thread.sleep(12_000)
            Log.i("SertumSmoke", "=== gapless smoke done ===")
            runOnUiThread { coordinator.release() }
            files.forEach { it.delete() }
            runOnUiThread { label.text = "Gapless smoke done; see logcat SertumSmoke" }
        }
    }

    private fun writeWav(sampleRate: Int, frequency: Int, seconds: Int): File {
        val file = File(cacheDir, "smoke-$sampleRate-$frequency.wav")
        val frames = sampleRate * seconds
        val dataSize = frames * 4
        val out = java.io.ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeLe32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeLe16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeLe32(36 + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe32(16)
        writeLe16(1) // PCM
        writeLe16(2) // stereo
        writeLe32(sampleRate)
        writeLe32(sampleRate * 4)
        writeLe16(4)
        writeLe16(16)
        writeAscii("data")
        writeLe32(dataSize)
        for (i in 0 until frames) {
            val v = (Short.MAX_VALUE * 0.2 * kotlin.math.sin(2.0 * Math.PI * frequency * i / sampleRate)).toInt()
            val s = v.toShort()
            writeLe16(s.toInt())
            writeLe16(s.toInt())
        }
        file.writeBytes(out.toByteArray())
        return file
    }
}
