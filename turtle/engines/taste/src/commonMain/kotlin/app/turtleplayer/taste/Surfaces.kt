package app.turtleplayer.taste

/** Home and player surfaces. The UI maps each kind and [Reason] to localized text. */
enum class ShelfKind {
    /** Autoplay / Up Next: session model, re-ranked on every play event. */
    UP_NEXT,
    /**
     * One per taste cluster, about 70% familiar. Without vectors (no clusters): up to three artist
     * mixes ([Reason.DailyMix.seedArtist] set), about 60% familiar. Refresh daily.
     */
    DAILY_MIX,
    /**
     * "Because you replayed / liked / listened to X": tracks like one they keep coming back to.
     * Without a vector for X it is built from X's upstream radio plus their own tracks. Refresh daily.
     */
    MORE_LIKE,
    /**
     * Never-heard tracks only, collaborative filtering plus sound; without vectors, from the upstream
     * radios of their top tracks, never-heard artists first. Refresh Mondays.
     */
    WEEKLY_DISCOVERY,
    /** New releases from affinity artists. Refresh Fridays. */
    FRESH_FOR_YOU,
    /** Loved but unheard for 60+ days. Refresh daily. */
    REDISCOVER,
    /** Radio from any seed track or artist. On demand. */
    RADIO,
    /** Their most-finished tracks of the last 30 days, all familiar ([Reason.OnRepeat]). Needs no vectors. Refresh daily. */
    ON_REPEAT,
}

/**
 * A shelf of recommendations with its typed reason ("Because you replayed X"). Every item carries
 * its own reason too. Pass [lessLikeThis] (or an item's) to [TasteEngine.lessLikeThis].
 */
data class Shelf(
    /** Stable within a refresh, e.g. `daily_mix:0`; use for UI keys and caching. */
    val id: String,
    val kind: ShelfKind,
    val reason: Reason,
    val items: List<QueueItem>,
) {
    val lessLikeThis: LessLikeThis get() = LessLikeThis.ForReason(reason)
}

val QueueItem.lessLikeThis: LessLikeThis get() = LessLikeThis.Track(key, track.artistKey)
