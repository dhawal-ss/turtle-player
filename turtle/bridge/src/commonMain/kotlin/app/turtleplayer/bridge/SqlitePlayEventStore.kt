package app.turtleplayer.bridge

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.DeviceType
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.NetworkType
import app.turtleplayer.events.OpenPlayCheckpoint
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PlayEventStore] in our own SQLite file, separate from upstream's `MusicDatabase` so upstream's
 * schema version and migrations never collide with ours. Plain androidx.sqlite (no Room codegen)
 * keeps the bridge build simple on every platform.
 */
class SqlitePlayEventStore(driver: SQLiteDriver, path: String) : PlayEventStore, OpenPlayCheckpoint {
    private val connection: SQLiteConnection = driver.open(path)
    private val mutex = Mutex()
    private val stream = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 64)

    override val newEvents: SharedFlow<PlayEvent> = stream.asSharedFlow()

    init {
        migrate()
    }

    private fun migrate() {
        val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }
        if (version < 1) {
            connection.execSQL("PRAGMA journal_mode=WAL")
            connection.execSQL(eventTable("play_event"))
            connection.execSQL("CREATE INDEX IF NOT EXISTS idx_play_event_started ON play_event(started_at_ms)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS idx_play_event_track ON play_event(track_key)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS idx_play_event_artist ON play_event(artist_key)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS idx_play_event_source ON play_event(source)")
            connection.execSQL("PRAGMA user_version = 1")
        }
        if (version < 2) {
            // Small settings of ours (discovery dial, ...), kept beside the history they shape.
            connection.execSQL("CREATE TABLE IF NOT EXISTS pref (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            connection.execSQL("PRAGMA user_version = 2")
        }
        if (version < 3) {
            // The play in progress (at most one row), so it survives the app being killed.
            connection.execSQL(eventTable("open_play"))
            connection.execSQL("PRAGMA user_version = 3")
        }
        if (version < 4) {
            // The co-listening graph: artist links and the songs seen per artist (as upstream's SongItem JSON).
            connection.execSQL("CREATE TABLE IF NOT EXISTS artist_link (a TEXT NOT NULL, b TEXT NOT NULL, w REAL NOT NULL, PRIMARY KEY (a, b))")
            connection.execSQL("CREATE TABLE IF NOT EXISTS observed_song (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            connection.execSQL("PRAGMA user_version = 4")
        }
        if (version < 5) {
            // Songs on Home's Turtle shelves, so the day's and week's shelves stay playable after a restart.
            connection.execSQL("CREATE TABLE IF NOT EXISTS shelf_song (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            connection.execSQL("PRAGMA user_version = 5")
        }
        if (version < 6) {
            // Non-play feedback: songs added to a playlist, "not interested" and its undo.
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS track_feedback (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    at_ms INTEGER NOT NULL,
                    track_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artists TEXT NOT NULL,
                    artist_key TEXT NOT NULL,
                    album TEXT,
                    track_duration_ms INTEGER,
                    youtube_id TEXT,
                    explicit INTEGER NOT NULL,
                    detail TEXT,
                    source TEXT NOT NULL
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS idx_track_feedback_at ON track_feedback(at_ms)")
            connection.execSQL("PRAGMA user_version = 6")
        }
    }

    private fun eventTable(name: String) =
        """
        CREATE TABLE IF NOT EXISTS $name (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_key TEXT NOT NULL,
            title TEXT NOT NULL,
            artists TEXT NOT NULL,
            artist_key TEXT NOT NULL,
            album TEXT,
            track_duration_ms INTEGER,
            released_at_ms INTEGER,
            youtube_id TEXT,
            isrc TEXT,
            explicit INTEGER NOT NULL,
            session_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            surface TEXT NOT NULL,
            surface_detail TEXT,
            start_reason TEXT NOT NULL,
            end_reason TEXT NOT NULL,
            started_at_ms INTEGER NOT NULL,
            ms_played INTEGER NOT NULL,
            duration_ms INTEGER,
            seeks INTEGER NOT NULL,
            replayed INTEGER NOT NULL,
            shuffle INTEGER,
            liked INTEGER NOT NULL,
            added_to_playlist INTEGER NOT NULL,
            less_like_this INTEGER NOT NULL,
            hour INTEGER,
            weekday INTEGER,
            device TEXT,
            network TEXT,
            source TEXT NOT NULL
        )
        """.trimIndent()

    /** A stored setting of ours, or null when never set. */
    suspend fun pref(key: String): String? = db {
        connection.prepare("SELECT value FROM pref WHERE key = ?").use {
            it.bindText(1, key)
            if (it.step()) it.getText(0) else null
        }
    }

    suspend fun setPref(key: String, value: String) = db {
        connection.prepare("INSERT OR REPLACE INTO pref (key, value) VALUES (?, ?)").use {
            it.bindText(1, key)
            it.bindText(2, value)
            it.step()
        }
        Unit
    }

    /** Replaces the saved co-listening graph: artist links (a, b, weight) and songs (id, JSON). */
    suspend fun saveCoListening(links: List<Triple<String, String, Float>>, songs: List<Pair<String, String>>) = db {
        transaction {
            connection.execSQL("DELETE FROM artist_link")
            connection.execSQL("DELETE FROM observed_song")
            connection.prepare("INSERT OR REPLACE INTO artist_link (a, b, w) VALUES (?, ?, ?)").use { s ->
                for ((a, b, w) in links) {
                    s.bindText(1, a)
                    s.bindText(2, b)
                    s.bindDouble(3, w.toDouble())
                    s.step()
                    s.reset()
                }
            }
            connection.prepare("INSERT OR REPLACE INTO observed_song (id, json) VALUES (?, ?)").use { s ->
                for ((id, json) in songs) {
                    s.bindText(1, id)
                    s.bindText(2, json)
                    s.step()
                    s.reset()
                }
            }
        }
    }

    /** The saved co-listening graph: artist links and song JSON. */
    suspend fun loadCoListening(): Pair<List<Triple<String, String, Float>>, List<String>> = db {
        val links = connection.prepare("SELECT a, b, w FROM artist_link").use { s ->
            val out = ArrayList<Triple<String, String, Float>>()
            while (s.step()) out += Triple(s.getText(0), s.getText(1), s.getDouble(2).toFloat())
            out
        }
        val songs = connection.prepare("SELECT json FROM observed_song").use { s ->
            val out = ArrayList<String>()
            while (s.step()) out += s.getText(0)
            out
        }
        links to songs
    }

    /** Replaces the saved songs of Home's Turtle shelves: (YouTube id, upstream SongItem JSON). */
    suspend fun saveShelfSongs(songs: List<Pair<String, String>>) {
        db {
            transaction {
                connection.execSQL("DELETE FROM shelf_song")
                connection.prepare("INSERT OR REPLACE INTO shelf_song (id, json) VALUES (?, ?)").use { s ->
                    for ((id, json) in songs) {
                        s.bindText(1, id)
                        s.bindText(2, json)
                        s.step()
                        s.reset()
                    }
                }
            }
        }
    }

    /** The saved shelf songs' JSON, in the order they were saved. */
    suspend fun loadShelfSongs(): List<String> =
        db {
            connection.prepare("SELECT json FROM shelf_song ORDER BY rowid").use { s ->
                val out = ArrayList<String>()
                while (s.step()) out += s.getText(0)
                out
            }
        }

    private fun transaction(block: () -> Unit) {
        connection.execSQL("BEGIN")
        try {
            block()
            connection.execSQL("COMMIT")
        } catch (t: Throwable) {
            connection.execSQL("ROLLBACK")
            throw t
        }
    }

    override suspend fun save(play: PlayEvent?) = db {
        connection.execSQL("DELETE FROM open_play")
        if (play != null) insert(play, table = "open_play")
        Unit
    }

    override suspend fun take(): PlayEvent? = db {
        val saved = query("SELECT * FROM open_play LIMIT 1") {}.firstOrNull()
        connection.execSQL("DELETE FROM open_play")
        saved?.copy(id = 0)
    }

    private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    override suspend fun append(event: PlayEvent): Long {
        val id = db { insert(event) }
        TurtleLog.info("play recorded: ${event.endReason} after ${event.msPlayed / 1000}s (${event.surface})")
        stream.emit(event.copy(id = id))
        return id
    }

    override suspend fun appendAll(events: List<PlayEvent>) {
        db {
            connection.execSQL("BEGIN")
            try {
                events.forEach { insert(it) }
                connection.execSQL("COMMIT")
            } catch (t: Throwable) {
                connection.execSQL("ROLLBACK")
                throw t
            }
        }
    }

    override suspend fun since(sinceMs: Long, limit: Int): List<PlayEvent> = db {
        query("SELECT * FROM play_event WHERE started_at_ms >= ? ORDER BY started_at_ms ASC LIMIT ?") {
            it.bindLong(1, sinceMs)
            it.bindLong(2, limit.toLong())
        }
    }

    override suspend fun forTrack(key: TrackKey, limit: Int): List<PlayEvent> = db {
        query("SELECT * FROM play_event WHERE track_key = ? ORDER BY started_at_ms DESC LIMIT ?") {
            it.bindText(1, key.value)
            it.bindLong(2, limit.toLong())
        }
    }

    override suspend fun forArtist(artist: ArtistKey, limit: Int): List<PlayEvent> = db {
        query("SELECT * FROM play_event WHERE artist_key = ? ORDER BY started_at_ms DESC LIMIT ?") {
            it.bindText(1, artist.value)
            it.bindLong(2, limit.toLong())
        }
    }

    override suspend fun deleteSource(source: EventSource) = db {
        connection.prepare("DELETE FROM play_event WHERE source = ?").use {
            it.bindText(1, source.name)
            it.step()
        }
        Unit
    }

    /** When the first play from [source] started, or null without any. */
    suspend fun firstStartedAt(source: EventSource): Long? = db {
        connection.prepare("SELECT MIN(started_at_ms) FROM play_event WHERE source = ?").use {
            it.bindText(1, source.name)
            if (it.step() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    override suspend fun count(): Long = db {
        connection.prepare("SELECT COUNT(*) FROM play_event").use { if (it.step()) it.getLong(0) else 0L }
    }

    override suspend fun appendFeedback(feedback: TrackFeedback): Long =
        db {
            connection.prepare(INSERT_FEEDBACK).use { s ->
                var i = 0

                fun text(v: String?) = if (v == null) s.bindNull(++i) else s.bindText(++i, v)

                fun long(v: Long?) = if (v == null) s.bindNull(++i) else s.bindLong(++i, v)
                val t = feedback.track
                text(feedback.kind.name)
                long(feedback.atMs)
                text(t.key.value)
                text(t.title)
                text(t.artists.joinToString(ARTIST_SEPARATOR))
                text(t.artistKey.value)
                text(t.album)
                long(t.durationMs)
                text(t.youtubeId)
                long(if (t.explicit) 1L else 0L)
                text(feedback.detail)
                text(feedback.source.name)
                s.step()
            }
            connection.prepare("SELECT last_insert_rowid()").use { if (it.step()) it.getLong(0) else 0L }
        }

    override suspend fun feedbackSince(
        sinceMs: Long,
        limit: Int,
    ): List<TrackFeedback> =
        db {
            connection.prepare("SELECT * FROM track_feedback WHERE at_ms >= ? ORDER BY at_ms ASC, id ASC LIMIT ?").use { s ->
                s.bindLong(1, sinceMs)
                s.bindLong(2, limit.toLong())
                val cols = (0 until s.getColumnCount()).associateBy { s.getColumnName(it) }

                fun text(name: String) = cols.getValue(name).let { if (s.isNull(it)) null else s.getText(it) }

                fun long(name: String) = cols.getValue(name).let { if (s.isNull(it)) null else s.getLong(it) }
                val out = ArrayList<TrackFeedback>()
                while (s.step()) {
                    // A kind this version does not know (written by a newer one) is skipped, never guessed.
                    val kind = text("kind")?.let { k -> TrackFeedbackKind.entries.firstOrNull { it.name == k } } ?: continue
                    val track =
                        TrackMeta(
                            key = TrackKey(text("track_key")!!),
                            title = text("title")!!,
                            artists = text("artists")!!.split(ARTIST_SEPARATOR).filter { it.isNotEmpty() },
                            album = text("album"),
                            durationMs = long("track_duration_ms"),
                            youtubeId = text("youtube_id"),
                            explicit = long("explicit") == 1L,
                        )
                    out +=
                        TrackFeedback(
                            id = long("id")!!,
                            track = track,
                            kind = kind,
                            atMs = long("at_ms")!!,
                            detail = text("detail"),
                            source = enumOr(text("source"), EventSource.LOCAL),
                        )
                }
                out
            }
        }

    fun close() = connection.close()

    private fun insert(e: PlayEvent, table: String = "play_event"): Long {
        connection.prepare(insertInto(table)).use { s ->
            var i = 0
            fun text(v: String?) = if (v == null) s.bindNull(++i) else s.bindText(++i, v)
            fun long(v: Long?) = if (v == null) s.bindNull(++i) else s.bindLong(++i, v)
            fun bool(v: Boolean?) = long(v?.let { if (it) 1L else 0L })
            text(e.track.key.value)
            text(e.track.title)
            text(e.track.artists.joinToString(ARTIST_SEPARATOR))
            text(e.track.artistKey.value)
            text(e.track.album)
            long(e.track.durationMs)
            long(e.track.releasedAtMs)
            text(e.track.youtubeId)
            text(e.track.isrc)
            bool(e.track.explicit)
            text(e.sessionId)
            long(e.position.toLong())
            text(e.surface.name)
            text(e.surfaceDetail)
            text(e.startReason.name)
            text(e.endReason.name)
            long(e.startedAtMs)
            long(e.msPlayed)
            long(e.durationMs)
            long(e.seeks.toLong())
            bool(e.replayed)
            bool(e.shuffle)
            bool(e.liked)
            bool(e.addedToPlaylist)
            bool(e.lessLikeThis)
            long(e.context?.hour?.toLong())
            long(e.context?.weekday?.toLong())
            text(e.context?.device?.name)
            text(e.context?.network?.name)
            text(e.source.name)
            s.step()
        }
        return connection.prepare("SELECT last_insert_rowid()").use { if (it.step()) it.getLong(0) else 0L }
    }

    private fun query(sql: String, bind: (SQLiteStatement) -> Unit): List<PlayEvent> =
        connection.prepare(sql).use { s ->
            bind(s)
            val cols = (0 until s.getColumnCount()).associateBy { s.getColumnName(it) }
            fun text(name: String) = cols.getValue(name).let { if (s.isNull(it)) null else s.getText(it) }
            fun long(name: String) = cols.getValue(name).let { if (s.isNull(it)) null else s.getLong(it) }
            fun bool(name: String) = long(name)?.let { it != 0L }
            val out = ArrayList<PlayEvent>()
            while (s.step()) {
                val hour = long("hour")
                val weekday = long("weekday")
                out += PlayEvent(
                    id = long("id")!!,
                    track = TrackMeta(
                        key = TrackKey(text("track_key")!!),
                        title = text("title")!!,
                        artists = text("artists")!!.split(ARTIST_SEPARATOR).filter { it.isNotEmpty() },
                        album = text("album"),
                        durationMs = long("track_duration_ms"),
                        releasedAtMs = long("released_at_ms"),
                        youtubeId = text("youtube_id"),
                        isrc = text("isrc"),
                        explicit = bool("explicit") == true,
                    ),
                    sessionId = text("session_id")!!,
                    position = long("position")!!.toInt(),
                    surface = enumOr(text("surface"), Surface.UNKNOWN),
                    surfaceDetail = text("surface_detail"),
                    startReason = enumOr(text("start_reason"), StartReason.UNKNOWN),
                    endReason = enumOr(text("end_reason"), EndReason.UNKNOWN),
                    startedAtMs = long("started_at_ms")!!,
                    msPlayed = long("ms_played")!!,
                    durationMs = long("duration_ms"),
                    seeks = long("seeks")!!.toInt(),
                    replayed = bool("replayed") == true,
                    shuffle = bool("shuffle"),
                    liked = bool("liked") == true,
                    addedToPlaylist = bool("added_to_playlist") == true,
                    lessLikeThis = bool("less_like_this") == true,
                    context = if (hour != null && weekday != null) {
                        PlayContext(
                            hour.toInt(),
                            weekday.toInt(),
                            enumOr(text("device"), DeviceType.UNKNOWN),
                            enumOr(text("network"), NetworkType.UNKNOWN),
                        )
                    } else {
                        null
                    },
                    source = enumOr(text("source"), EventSource.LOCAL),
                )
            }
            out
        }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    private companion object {
        const val ARTIST_SEPARATOR = "\u001f"
        val INSERT_FEEDBACK =
            """
            INSERT INTO track_feedback (
                kind, at_ms, track_key, title, artists, artist_key, album, track_duration_ms, youtube_id, explicit, detail, source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        fun insertInto(table: String) = """
            INSERT INTO $table (
                track_key, title, artists, artist_key, album, track_duration_ms, released_at_ms, youtube_id, isrc, explicit,
                session_id, position, surface, surface_detail, start_reason, end_reason, started_at_ms, ms_played,
                duration_ms, seeks, replayed, shuffle, liked, added_to_playlist, less_like_this, hour, weekday,
                device, network, source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    }
}
