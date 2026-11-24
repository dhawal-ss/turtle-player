package app.turtleplayer.events.imports

import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.SourceRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One file from a user's data export, already unzipped by the platform layer. [sizeChars] is the
 * size the platform reports (checked against [ImportLimits] before anything is read; the text read
 * is checked again, since a zip entry's size can lie). The text is read only when the file is
 * recognised and within the limits, one file at a time.
 */
class ExportFile(val name: String, val sizeChars: Long, private val read: () -> String) {
    constructor(name: String, text: String) : this(name, text.length.toLong(), { text })

    internal fun readText(): String = read()

    /** The name without the folders the export zip put it in. */
    internal val baseName: String get() = name.substringAfterLast('/').substringAfterLast('\\')
}

/**
 * Bounds on what one import reads, so a wrong or hostile file can never exhaust memory. A file over
 * a limit is never decoded; it fails the import instead.
 */
data class ImportLimits(
    val maxFiles: Int = 64,
    val maxFileChars: Long = 32_000_000,
    val maxTotalChars: Long = 160_000_000,
    val maxRows: Int = 2_000_000,
)

/** Why a recognised history file could not be used. */
enum class FileProblem {
    /** Not valid history in the expected format (not JSON, a different file, cut short). */
    UNREADABLE,

    /** Larger than [ImportLimits.maxFileChars], or would take the import past [ImportLimits.maxTotalChars]. */
    TOO_LARGE,

    /** More history files than [ImportLimits.maxFiles]. */
    TOO_MANY_FILES,

    /** Would take the import past [ImportLimits.maxRows]. */
    TOO_MANY_ROWS,
}

data class FailedFile(val name: String, val problem: FileProblem)

/**
 * What an import would do, before anything is written. Every row lands in exactly one count.
 * Nothing replaces stored history unless [canCommit].
 */
data class ImportPreview(
    val source: EventSource,
    /** Plays that would be stored. */
    val accepted: Int,
    /** Rows already present in another file of this import (or covered by richer Extended rows). */
    val duplicates: Int,
    /** Rows with a time but no usable song or artist name. */
    val unresolved: Int,
    /** Rows without a readable time, and lines that are not valid JSON. */
    val rejected: Int,
    /** Podcast episodes and audiobooks. */
    val nonMusic: Int,
    /** Plays from a private session, left out unless the listener asks for them. */
    val privateSession: Int,
    /** Rows outside the importer's window (plays the app has already recorded itself). */
    val excluded: Int,
    /** History files decoded. */
    val filesRead: Int,
    /** Files in the export that are not listening history (account data, PDFs, ...). */
    val filesSkipped: List<String>,
    /** Recognised history files that could not be used; any of these blocks the import. */
    val filesFailed: List<FailedFile>,
    val firstPlayMs: Long?,
    val lastPlayMs: Long?,
    val distinctTracks: Int,
    val distinctArtists: Int,
    /** What is stored from this source now, which a commit replaces. */
    val existing: SourceRange?,
) {
    /** True when committing would store plays and every recognised file was read in full. */
    val canCommit: Boolean get() = accepted > 0 && filesFailed.isEmpty()

    /**
     * True when this import covers less time than what is stored from the same source: it starts
     * later (a year of Account data replacing Extended history) or ends earlier (only the oldest
     * file of an export picked). Committing would lose plays, so ask first.
     */
    val shrinksHistory: Boolean
        get() = existing != null && firstPlayMs != null && lastPlayMs != null &&
            (firstPlayMs > existing.firstMs || lastPlayMs < existing.lastMs)
}

/** A parsed import held in memory until [commit]; nothing has been written. */
class StagedImport internal constructor(
    val preview: ImportPreview,
    internal val events: List<PlayEvent>,
) {
    val source: EventSource get() = preview.source
}

data class ImportReport(
    val source: EventSource,
    val filesRead: Int,
    val filesSkipped: List<String>,
    val plays: Int,
    /** Podcast episodes, audiobooks, private sessions, rows without track names and rows outside the window. */
    val rowsIgnored: Int,
    /** Rows without a readable time, undecodable lines, and history files that could not be used. */
    val malformedRows: Int,
    val firstPlayMs: Long?,
    val lastPlayMs: Long?,
    val distinctTracks: Int,
    val distinctArtists: Int,
    /** False when nothing was written and stored history is exactly as before. */
    val committed: Boolean = true,
)

fun ImportPreview.toReport(committed: Boolean) = ImportReport(
    source = source,
    filesRead = filesRead,
    filesSkipped = filesSkipped,
    plays = accepted,
    rowsIgnored = nonMusic + unresolved + privateSession + excluded,
    malformedRows = rejected + filesFailed.size,
    firstPlayMs = firstPlayMs,
    lastPlayMs = lastPlayMs,
    distinctTracks = distinctTracks,
    distinctArtists = distinctArtists,
    committed = committed,
)

/**
 * Replaces the stored plays of [staged]'s source with it, all or nothing. Throws
 * [IllegalStateException] when the preview does not allow it ([ImportPreview.canCommit]) or when
 * history from the same source changed since the preview was made (a stale preview never
 * overwrites a newer import). Callers that must not be interrupted between the write and their
 * own rebuild run this under `NonCancellable`.
 */
suspend fun PlayEventStore.commit(staged: StagedImport): ImportReport {
    val preview = staged.preview
    check(preview.canCommit) { "this import cannot be committed" }
    check(sourceRange(preview.source) == preview.existing) { "history from ${preview.source} changed since the preview" }
    replaceSource(preview.source, staged.events)
    return preview.toReport(committed = true)
}

/** The one-step import: commits only what is safe without asking (complete, and not shrinking history). */
internal suspend fun PlayEventStore.commitIfSafe(staged: StagedImport): ImportReport =
    if (staged.preview.canCommit && !staged.preview.shrinksHistory) commit(staged) else staged.preview.toReport(committed = false)

/** Row and file counts gathered while an importer reads its input. */
internal class ImportTally {
    var duplicates = 0
    var unresolved = 0
    var rejected = 0
    var nonMusic = 0
    var privateSession = 0
    var excluded = 0
    var filesRead = 0
    var rows = 0
    val filesSkipped = mutableListOf<String>()
    val filesFailed = mutableListOf<FailedFile>()
    private var recognised = 0
    private var totalChars = 0L

    /**
     * Reads a recognised history file within [limits], or records why not and returns null. Checks
     * for cancellation first: reading and decoding one file cannot be interrupted.
     */
    suspend fun read(file: ExportFile, limits: ImportLimits): String? {
        currentCoroutineContext().ensureActive()
        val name = file.baseName
        if (++recognised > limits.maxFiles) return fail(name, FileProblem.TOO_MANY_FILES)
        if (file.sizeChars > limits.maxFileChars || totalChars + file.sizeChars > limits.maxTotalChars) {
            return fail(name, FileProblem.TOO_LARGE)
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return fail(name, FileProblem.UNREADABLE)
        }
        if (text.length > limits.maxFileChars || totalChars + text.length > limits.maxTotalChars) {
            return fail(name, FileProblem.TOO_LARGE)
        }
        totalChars += text.length
        return text
    }

    /** Counts [count] decoded rows against [limits]; false (and the file failed) when over. */
    fun admitRows(name: String, count: Int, limits: ImportLimits): Boolean {
        if (rows.toLong() + count > limits.maxRows) {
            fail(name, FileProblem.TOO_MANY_ROWS)
            return false
        }
        rows += count
        filesRead++
        return true
    }

    fun fail(name: String, problem: FileProblem): String? {
        filesFailed += FailedFile(name, problem)
        return null
    }
}

/**
 * Shared post-processing for every importer: sorts plays, rebuilds listening sessions from time
 * gaps (exports have no session IDs), fills local-time context, and reads what is stored from the
 * same source now. Writes nothing: [commit] replaces the previous import, so re-importing a newer
 * export never double counts and a bad one never wipes a good one.
 */
internal suspend fun stageImport(
    source: EventSource,
    plays: List<PlayEvent>,
    tally: ImportTally,
    store: PlayEventStore,
    time: LocalTimeResolver,
    sessionGapMs: Long = 30 * MINUTE_MS,
): StagedImport {
    val sorted = plays.sortedBy { it.startedAtMs }
    val withSessions = ArrayList<PlayEvent>(sorted.size)
    var sessionStart = Long.MIN_VALUE
    var lastEnd = Long.MIN_VALUE
    var position = 0
    for (e in sorted) {
        if (lastEnd == Long.MIN_VALUE || e.startedAtMs - lastEnd > sessionGapMs) {
            sessionStart = e.startedAtMs
            position = 0
        }
        val (hour, weekday) = time.hourAndWeekday(e.startedAtMs)
        withSessions += e.copy(
            sessionId = "${source.name.lowercase()}-$sessionStart",
            position = position++,
            context = e.context ?: PlayContext(hour, weekday),
        )
        lastEnd = maxOf(lastEnd, e.startedAtMs + e.msPlayed)
    }
    val preview = ImportPreview(
        source = source,
        accepted = withSessions.size,
        duplicates = tally.duplicates,
        unresolved = tally.unresolved,
        rejected = tally.rejected,
        nonMusic = tally.nonMusic,
        privateSession = tally.privateSession,
        excluded = tally.excluded,
        filesRead = tally.filesRead,
        filesSkipped = tally.filesSkipped.toList(),
        filesFailed = tally.filesFailed.toList(),
        firstPlayMs = withSessions.firstOrNull()?.startedAtMs,
        lastPlayMs = withSessions.lastOrNull()?.startedAtMs,
        distinctTracks = withSessions.mapTo(HashSet()) { it.trackKey }.size,
        distinctArtists = withSessions.mapTo(HashSet()) { it.artistKey }.size,
        existing = store.sourceRange(source),
    )
    return StagedImport(preview, withSessions)
}
