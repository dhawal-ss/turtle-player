package app.turtleplayer.bridge

import app.turtleplayer.events.HistoryLens
import app.turtleplayer.events.LensedPlayEventStore
import app.turtleplayer.taste.ExplorationState
import app.turtleplayer.taste.TasteEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Turtle's settings show for repairing older skips and resetting taste. */
data class TasteRepairState(
    /** Skips recorded by older builds are read as neutral. */
    val repairOlderSkips: Boolean = false,
    /** How many skips recorded by older builds the repair reads as neutral; null until counted. */
    val olderSkips: Int? = null,
    /** Taste was reset: plays stored before the reset are left out until it is undone. */
    val reset: Boolean = false,
)

/** The saved [HistoryLens] in our pref table; [LensedPlayEventStore] reads it on every history read. */
internal const val PREF_HISTORY_LENS = "history_lens"

/** Discovery's exploration state from just before the last taste reset, given back by its undo. */
internal const val PREF_EXPLORATION_BEFORE_RESET = "exploration_before_reset"

/**
 * Repairing older skips and resetting taste: both change only how taste reads stored history
 * ([HistoryLens]), never the history itself, so each is undone by turning it off.
 *
 * A reset leaves out every play stored so far (by row id, so imports made later still count) and
 * starts discovery's exploration fresh, saving the old state for the undo; exploration learned between
 * a reset and its undo is lost. Explicit votes (likes and saved songs, playlist adds, Not interested,
 * hidden artists and mixes) are not plays and are kept by both. The Infinity Mix's tuning and the
 * co-listening graph are not reverted by either.
 *
 * Callers rebuild what reads history (the profile, Home, search) when a change returns true.
 */
class TasteHistoryLens(
    private val store: SqlitePlayEventStore,
    private val taste: TasteEngine,
    private val published: MutableStateFlow<TasteRepairState> = MutableStateFlow(TasteRepairState()),
) {
    private val lock = Mutex()

    /** What the settings show; current after [load] and every change. */
    val state: StateFlow<TasteRepairState> = published.asStateFlow()

    /** The saved lens; the default (history read as stored) when none was saved. */
    suspend fun lens(): HistoryLens = HistoryLens.decode(store.pref(PREF_HISTORY_LENS))

    /** Reads the saved options and counts the older skips the repair would read as neutral. */
    suspend fun load(): TasteRepairState =
        lock.withLock {
            val count = HistoryLens.legacySkipCount(store.localPlays())
            publish(lens(), count)
        }

    /** Turns the repair of older skips on or off. False when it already was. */
    suspend fun setRepairOlderSkips(on: Boolean): Boolean =
        lock.withLock {
            val before = lens()
            if (before.repairLegacySkips == on) {
                false
            } else {
                save(before.copy(repairLegacySkips = on))
                true
            }
        }

    /** Taste starts over from the next play (see the class notes). False when it already was reset. */
    suspend fun reset(): Boolean =
        lock.withLock {
            val before = lens()
            if (before.ignoreThroughId != null) {
                false
            } else {
                store.setPref(PREF_EXPLORATION_BEFORE_RESET, encodeExploration(taste.explorationState))
                taste.setExploration(ExplorationState())
                save(before.copy(ignoreThroughId = store.lastPlayId() ?: 0L))
                true
            }
        }

    /** Takes the reset back: every stored play counts again, and exploration is as it was before it. False without a reset. */
    suspend fun undoReset(): Boolean =
        lock.withLock {
            val before = lens()
            if (before.ignoreThroughId == null) {
                false
            } else {
                decodeExploration(store.pref(PREF_EXPLORATION_BEFORE_RESET))?.let { taste.setExploration(it) }
                save(before.copy(ignoreThroughId = null))
                true
            }
        }

    private suspend fun save(lens: HistoryLens) {
        store.setPref(PREF_HISTORY_LENS, lens.encode())
        publish(lens, published.value.olderSkips)
    }

    private fun publish(
        lens: HistoryLens,
        olderSkips: Int?,
    ): TasteRepairState {
        val state = TasteRepairState(lens.repairLegacySkips, olderSkips, lens.ignoreThroughId != null)
        published.value = state
        return state
    }

    private companion object {
        fun encodeExploration(s: ExplorationState) = "${s.alpha},${s.beta}"

        fun decodeExploration(text: String?): ExplorationState? {
            val parts = text?.split(',')?.takeIf { it.size == 2 } ?: return null
            val alpha = parts[0].toDoubleOrNull() ?: return null
            val beta = parts[1].toDoubleOrNull() ?: return null
            return ExplorationState(alpha, beta)
        }
    }
}

/** Taste repair and reset as the settings show them, published by the runtime. */
internal val tasteRepairState = MutableStateFlow(TasteRepairState())
