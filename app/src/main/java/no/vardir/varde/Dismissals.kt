package no.vardir.varde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Duration
import java.time.Instant
import java.util.Locale

/*
 * Dismissing hides an inbox item in Varde only. A dismissal covers the item's current reasons, so a new reason (say, a
 * review request on a PR whose failing checks you dismissed) shows it again. Workflow failures are keyed by run, so a
 * new failing run shows up even after the previous one was dismissed. Dismissals expire once their item leaves the
 * inbox (checks fixed, run superseded), so the same item failing again later is not hidden.
 */

/** Keys for every reason an inbox item is currently there. */
internal fun dismissalKeys(item: AttentionItem): Set<String> = (item.alsoKinds + item.kind).map { "$it:${item.url}" }.toSet()

internal fun isDismissed(item: AttentionItem, dismissed: Set<String>): Boolean =
    dismissed.isNotEmpty() && dismissalKeys(item).all { it in dismissed }

/** Dismissals still matching something in the inbox; the rest expire. Only call with a complete inbox. */
internal fun liveDismissals(dismissed: Set<String>, items: List<AttentionItem>): Set<String> =
    dismissed intersect items.flatMap { dismissalKeys(it) }.toSet()

/** Failing items not updated for [days] days or more; undated items are never treated as old. */
internal fun staleFailures(items: List<AttentionItem>, now: Instant, days: Long = 14): List<AttentionItem> = items.filter { item ->
    item.section() == InboxSection.Failing &&
        runCatching { Duration.between(Instant.parse(item.updatedAt), now).toDays() >= days }.getOrDefault(false)
}

/** Per-account dismissals in app-private storage. */
internal class DismissedItems(context: Context) {
    private val prefs = context.getSharedPreferences("dismissed", Context.MODE_PRIVATE)
    private var key = ""
    var keys by mutableStateOf<Set<String>>(emptySet()); private set

    fun open(user: String) { key = user.lowercase(Locale.ROOT); keys = prefs.getStringSet(key, emptySet()).orEmpty().toSet() }
    private fun save(updated: Set<String>) {
        if (key.isBlank()) return
        keys = updated
        prefs.edit().putStringSet(key, updated).apply()
    }
    fun dismiss(items: List<AttentionItem>) = save(keys + items.flatMap { dismissalKeys(it) })
    fun restore(items: List<AttentionItem>) = save(keys - items.flatMap { dismissalKeys(it) }.toSet())
    /** Drops dismissals whose items are gone. Only call after a complete inbox load. */
    fun expire(items: List<AttentionItem>) { val live = liveDismissals(keys, items); if (live != keys) save(live) }
    fun clear() { key = ""; keys = emptySet() }
}
