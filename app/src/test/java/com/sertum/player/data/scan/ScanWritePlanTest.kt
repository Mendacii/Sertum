package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which stored rows a scan keeps and which it removes.
 *
 * The write itself (Room's `applyLibrary`) needs a device, but the decision that used to delete
 * tracks whose files merely failed to open is this split, and it is exactly what these cases pin:
 *
 * - a file whose metadata read failed keeps its row,
 * - a file that no enumerated source reports loses it,
 * - a new file is read and never appears in the delete list,
 * - and a source that could not be enumerated at all (no media permission, unreadable SAF tree)
 *   deletes nothing, because its silence is not evidence.
 *
 * The full-scan shape is `reparsedUris == reportedUris`: a full scan opens every candidate. The
 * incremental shape re-reads only the changed ones.
 */
class ScanWritePlanTest {

    private val engine = ScanEngine()

    private fun row(uri: String) = ExistingTrack(uri, sizeBytes = 10, modifiedAtEpochMs = 1)

    private fun candidate(uri: String) = ScanCandidate(uri, "/music/$uri.flac", 10, 1)

    @Test
    fun `a failed read keeps the stored row`() {
        // Two files are listed; one of them cannot be opened this time. The row for the failure
        // must survive: the file still exists, only this attempt to read it did not.
        val existing = listOf(row("uri://ok"), row("uri://fails"))
        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://ok", "uri://fails"),
            reparsedUris = setOf("uri://ok", "uri://fails"),
            protectedUris = setOf("uri://fails"),
        )

        assertThat(write.keptUris).containsExactly("uri://fails")
        // The successfully read file is replaced by its fresh row in the same transaction.
        assertThat(write.urisToDelete).containsExactly("uri://ok")
    }

    @Test
    fun `a file that no source reported is deleted`() {
        val existing = listOf(row("uri://here"), row("uri://gone"))
        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://here"),
            reparsedUris = setOf("uri://here"),
            protectedUris = emptySet(),
        )

        assertThat(write.urisToDelete).containsExactly("uri://here", "uri://gone")
        // Deletion is the complement of "kept"; nothing unreported is silently kept.
        assertThat(write.keptUris).isEmpty()
    }

    @Test
    fun `a new file is read and never deleted`() {
        // Nothing is stored for the new file yet, so it has no row to delete - it is scheduled for
        // reading, which is what puts it in the library.
        val existing = listOf(row("uri://old"))
        val newFile = candidate("uri://new")
        val plan = engine.plan(existing, listOf(candidate("uri://old"), newFile))
        assertThat(plan.toAdd).containsExactly(newFile)

        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://old", "uri://new"),
            reparsedUris = setOf("uri://old", "uri://new"),
            protectedUris = emptySet(),
        )
        assertThat(write.urisToDelete).containsExactly("uri://old")
        assertThat(write.urisToDelete).doesNotContain("uri://new")
    }

    @Test
    fun `an incremental scan keeps unchanged rows and removes re-read and vanished ones`() {
        val existing = listOf(row("uri://same"), row("uri://changed"), row("uri://gone"))
        // Same size and modification time as the stored row: not re-read. The other candidate
        // differs, so the incremental plan opens it and replaces its row.
        val changed = ScanCandidate("uri://changed", "/music/changed.flac", 11, 2)
        val plan = engine.plan(existing, listOf(candidate("uri://same"), changed))
        assertThat(plan.toParse.map { it.uri }).containsExactly("uri://changed")

        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://same", "uri://changed"),
            reparsedUris = plan.toParse.map { it.uri }.toSet(),
            protectedUris = emptySet(),
        )
        assertThat(write.keptUris).containsExactly("uri://same")
        assertThat(write.urisToDelete).containsExactly("uri://changed", "uri://gone")
    }

    @Test
    fun `a full scan where every read failed keeps the whole library`() {
        // The full-scan shape with every parse failing: this is the state that used to empty the
        // library, because the table was cleared before anything could notice the reads had failed.
        val existing = listOf(row("uri://1"), row("uri://2"))
        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://1", "uri://2"),
            reparsedUris = setOf("uri://1", "uri://2"),
            protectedUris = setOf("uri://1", "uri://2"),
        )

        assertThat(write.keptUris).containsExactly("uri://1", "uri://2")
        assertThat(write.urisToDelete).isEmpty()
    }

    @Test
    fun `a scan that read nothing deletes nothing`() {
        // The state a missing media permission produces: the source could not be enumerated, so it
        // reported no files. That must not be read as "all files were deleted" - this is the bug
        // that emptied the library, expressed at the decision layer.
        val existing = listOf(row("uri://1"), row("uri://2"), row("uri://3"))
        val write = engine.writePlan(
            existing = existing,
            reportedUris = emptySet(),
            reparsedUris = emptySet(),
            protectedUris = setOf("uri://1", "uri://2", "uri://3"),
        )

        assertThat(write.keptUris).containsExactly("uri://1", "uri://2", "uri://3")
        assertThat(write.urisToDelete).isEmpty()
    }

    @Test
    fun `a failed read is kept even when its source did not report it`() {
        // Defensive rather than reachable today: protected wins over absence, so no combination of
        // inputs can turn a read failure into a deletion.
        val existing = listOf(row("uri://unreported"), row("uri://vanished"))
        val write = engine.writePlan(
            existing = existing,
            reportedUris = emptySet(),
            reparsedUris = emptySet(),
            protectedUris = setOf("uri://unreported"),
        )

        assertThat(write.keptUris).containsExactly("uri://unreported")
        assertThat(write.urisToDelete).containsExactly("uri://vanished")
    }

    @Test
    fun `a CUE rip's several rows share one delete entry`() {
        // Carved tracks keep the file's URI and differ by start offset, so the existing list can
        // hold several rows for one URI. Deleting addresses the file once, not once per row.
        val existing = listOf(
            ExistingTrack("uri://disc", 10, 1),
            ExistingTrack("uri://disc", 10, 1),
            ExistingTrack("uri://disc", 10, 1),
        )
        val write = engine.writePlan(
            existing = existing,
            reportedUris = setOf("uri://disc"),
            reparsedUris = setOf("uri://disc"),
            protectedUris = emptySet(),
        )

        assertThat(write.urisToDelete).containsExactly("uri://disc")
    }

    @Test
    fun `the delete list only names stored rows`() {
        val write = engine.writePlan(
            existing = listOf(row("uri://stored")),
            reportedUris = setOf("uri://new", "uri://stored"),
            reparsedUris = setOf("uri://new", "uri://stored"),
            protectedUris = emptySet(),
        )

        // A new file is an insert, not a delete: it must not leak into the removal statement.
        assertThat(write.urisToDelete).containsExactly("uri://stored")
    }
}
