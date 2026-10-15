package com.sertum.player.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueEngineTest {

    private val engine = QueueEngine(seed = 42)

    @Test
    fun `empty queue has no current track`() {
        engine.setQueue(emptyList())
        assertThat(engine.currentTrackId).isNull()
        assertThat(engine.next()).isNull()
        assertThat(engine.previous()).isNull()
    }

    @Test
    fun `single track repeats only in ONE mode`() {
        engine.setQueue(listOf(7))
        assertThat(engine.next()).isNull()
        engine.setRepeat(RepeatMode.ONE)
        assertThat(engine.next()).isEqualTo(7)
    }

    @Test
    fun `next advances and stops at tail in OFF mode`() {
        engine.setQueue(listOf(1, 2, 3))
        assertThat(engine.next()).isEqualTo(2)
        assertThat(engine.next()).isEqualTo(3)
        assertThat(engine.next()).isNull()
    }

    @Test
    fun `next wraps in ALL mode`() {
        engine.setQueue(listOf(1, 2, 3))
        engine.setRepeat(RepeatMode.ALL)
        assertThat(engine.next()).isEqualTo(2)
        assertThat(engine.next()).isEqualTo(3)
        assertThat(engine.next()).isEqualTo(1)
    }

    @Test
    fun `previous clamps at head in OFF mode and wraps in ALL mode`() {
        engine.setQueue(listOf(1, 2, 3), startIndex = 2)
        assertThat(engine.previous()).isEqualTo(2)
        engine.setRepeat(RepeatMode.ALL)
        engine.setQueue(listOf(1, 2, 3), startIndex = 0)
        assertThat(engine.previous()).isEqualTo(3)
    }

    @Test
    fun `remove adjusts current position`() {
        engine.setQueue(listOf(1, 2, 3, 4), startIndex = 2) // current=3
        assertThat(engine.removeAt(2)).isEqualTo(3L)
        assertThat(engine.orderForTest()).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(engine.currentTrackId).isEqualTo(4L)
    }

    @Test
    fun `move reorders without losing current`() {
        engine.setQueue(listOf(1, 2, 3, 4), startIndex = 1) // current=2
        assertThat(engine.move(0, 3)).isTrue()
        assertThat(engine.orderForTest()).containsExactly(2L, 3L, 4L, 1L).inOrder()
        assertThat(engine.currentTrackId).isEqualTo(2L)
    }

    @Test
    fun `clear empties queue`() {
        engine.setQueue(listOf(1, 2))
        engine.clear()
        assertThat(engine.size).isEqualTo(0)
        assertThat(engine.currentTrackId).isNull()
    }

    @Test
    fun `shuffle covers the whole queue`() {
        val ids = (1..20).map { it.toLong() }
        engine.setShuffle(ShuffleMode.ON)
        engine.setQueue(ids)
        val order = engine.orderForTest()
        assertThat(order).containsExactlyElementsIn(ids)
        assertThat(order.size).isEqualTo(20)
    }

    @Test
    fun `seekTo synchronizes the cursor with an external selection`() {
        val queue = QueueEngine(seed = 1L)
        queue.setQueue(listOf(10L, 20L, 30L, 40L), startIndex = 0)
        assertThat(queue.seekTo(30L)).isTrue()
        assertThat(queue.currentTrackId).isEqualTo(30L)
        assertThat(queue.next()).isEqualTo(40L)
        assertThat(queue.seekTo(99L)).isFalse()
        assertThat(queue.currentTrackId).isEqualTo(40L)
    }
}

