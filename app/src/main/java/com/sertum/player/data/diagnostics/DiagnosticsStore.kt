package com.sertum.player.data.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class DiagnosticLevel { INFO, WARNING, ERROR }

data class DiagnosticsCounts(
    val totalEntries: Int = 0,
    val totalErrors: Int = 0,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
)

/**
 * PRD 7.13 / 7.14: local-only, rolling diagnostics. One file per UTC day,
 * files older than [retentionDays] are deleted on every write and read.
 * Never leaves app-private storage and never sends anything anywhere.
 */
class DiagnosticsStore(
    val directory: File,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(retentionDays in 1..7) { "retentionDays must be in 1..7 (PRD 7.14)" }
    }

    private var totalEntries = 0
    private var totalErrors = 0

    val counts: DiagnosticsCounts
        @Synchronized get() = DiagnosticsCounts(
            totalEntries = totalEntries,
            totalErrors = totalErrors,
            fileCount = currentFiles().size,
            totalBytes = currentFiles().sumOf { it.length() },
        )

    @Synchronized
    fun log(level: DiagnosticLevel, tag: String, message: String) {
        pruneOldFiles()
        directory.mkdirs()
        val now = clock()
        val safe = message.replace('\n', ' ').replace('\r', ' ').replace('|', '/')
        val line = "${now}|$level|${tag.replace('|', '/')}|$safe"
        File(directory, fileName(now)).appendText(line + "\n")
        totalEntries += 1
        if (level == DiagnosticLevel.ERROR) totalErrors += 1
    }

    /** One entry per line, oldest first, for the in-app diagnostics view. */
    @Synchronized
    fun readAll(): List<String> = currentFiles()
        .sortedBy { it.name }
        .flatMap { file -> file.readLines().filter { it.isNotBlank() } }

    /**
     * Human-readable export body; the caller writes it to the user-chosen document.
     *
     * [buildFingerprint] is supplied by the caller rather than read here so this
     * data-layer class keeps no dependency on the app-level version helpers. It is
     * what makes an exported log traceable back to an exact build.
     */
    @Synchronized
    fun exportText(buildFingerprint: String = ""): String {
        pruneOldFiles()
        val now = clock()
        val snapshot = counts
        val header = listOf(
            "Sertum diagnostics export",
            "GeneratedAtEpochMs=$now",
            "Entries=${snapshot.totalEntries}",
            "Errors=${snapshot.totalErrors}",
            "Files=${snapshot.fileCount}",
            "RetentionDays=$retentionDays",
        ) + if (buildFingerprint.isNotBlank()) {
            listOf("Build=$buildFingerprint")
        } else {
            emptyList()
        } + listOf("")
        return (header + readAll()).joinToString("\n") + "\n"
    }

    /** Deletes daily files older than the retention window. Returns deleted count. */
    @Synchronized
    fun pruneOldFiles(): Int {
        val now = clock()
        val cutoffDay = dayKey(now - retentionDays * MILLIS_PER_DAY)
        var deleted = 0
        for (file in currentFiles()) {
            val day = dayKeyFromName(file.name) ?: continue
            if (day <= cutoffDay && file.delete()) deleted += 1
        }
        return deleted
    }

    private fun currentFiles(): List<File> =
        directory.listFiles { f -> f.isFile && FILE_PATTERN.matches(f.name) }?.toList().orEmpty()

    private fun fileName(epochMs: Long): String = "sertum-${dayKey(epochMs)}.log"

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        private const val MILLIS_PER_DAY = 86_400_000L
        private val FILE_PATTERN = Regex("""sertum-\d{8}\.log""")

        private val dayFormat: SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun dayKey(epochMs: Long): String = dayFormat.format(Date(epochMs))

        private fun dayKeyFromName(name: String): String? =
            FILE_PATTERN.matchEntire(name)?.let { it.value.substringAfter("sertum-").removeSuffix(".log") }
    }
}
