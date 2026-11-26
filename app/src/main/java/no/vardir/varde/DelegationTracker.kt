package no.vardir.varde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Delegations for one GitHub account, kept in app-private storage, plus their last known GitHub status. */
internal class DelegationTracker(context: Context) {
    private val prefs = context.getSharedPreferences("delegations", Context.MODE_PRIVATE)
    private var key = ""
    private var runs = 0

    var delegations by mutableStateOf(emptyList<Delegation>()); private set
    var statuses by mutableStateOf<Map<String, DelegationStatus>>(emptyMap()); private set
    var checking by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    val active: List<Delegation> get() = delegations.filterNot { it.done }
    fun find(id: String): Delegation? = delegations.firstOrNull { it.id == id }

    fun open(user: String) {
        key = user.lowercase(Locale.ROOT)
        statuses = emptyMap()
        delegations = try {
            prefs.getString(key, null)?.let(::decodeDelegations).orEmpty().also { error = null }
        } catch (_: Exception) {
            error = "Saved delegations could not be read. They have not been changed."
            emptyList()
        }
    }

    private fun persist(updated: List<Delegation>): Boolean {
        if (key.isBlank() || error != null) return false
        // Keep finished delegations for a while, but don't let storage grow without bound.
        val trimmed = updated.filterNot { it.done } + updated.filter { it.done }.takeLast(50)
        if (!prefs.edit().putString(key, encodeDelegations(trimmed)).commit()) return false
        delegations = trimmed
        return true
    }

    fun add(delegation: Delegation): Boolean =
        find(delegation.id) == null && persist(delegations + delegation)

    fun update(delegation: Delegation): Boolean = persist(delegations.map { if (it.id == delegation.id) delegation else it })

    fun linkPull(id: String, number: Int): Boolean {
        val d = find(id) ?: return false
        statuses = statuses - id
        return update(d.copy(pullNumber = number, startSha = ""))
    }

    fun markDone(id: String): Boolean = find(id)?.let { update(it.copy(done = true)) } ?: false
    fun remove(id: String): Boolean { statuses = statuses - id; return persist(delegations.filterNot { it.id == id }) }

    /** Checks each active delegation on GitHub. Merged or closed delegations are marked done. Session expiry is rethrown. */
    suspend fun refresh(findRepo: (Long) -> Repo?, load: suspend (String) -> CachedResponse) {
        // The caller cancels an older refresh before starting a new one; only the newest clears the flag.
        val run = ++runs
        checking = true
        try {
            for (d in active.take(25)) {
                val (updated, status) = checkDelegation(d, findRepo(d.repoId), load)
                statuses = statuses + (d.id to status)
                val next = if (status.stage.finished) updated.copy(done = true) else updated
                // Skip the write if the delegation was linked, finished or removed while GitHub was being checked.
                if (next != d && find(d.id) == d) update(next)
            }
        } catch (e: CancellationException) { throw e }
        finally { if (run == runs) checking = false }
    }

    fun clear() { runs++; key = ""; delegations = emptyList(); statuses = emptyMap(); error = null; checking = false }
}
