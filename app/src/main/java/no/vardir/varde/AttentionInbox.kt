package no.vardir.varde

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Assigned work plus the cross-repository attention feed. Partial loads are reported, never shown as an empty inbox. */
internal class AttentionInbox(private val scope: CoroutineScope) {
    var assigned by mutableStateOf(emptyList<AttentionItem>()); private set
    var assignedLoaded by mutableStateOf(false); private set
    var assignedError by mutableStateOf<String?>(null); private set
    var feedItems by mutableStateOf(emptyList<AttentionItem>()); private set
    var feedLoading by mutableStateOf(false); private set
    var feedIncomplete by mutableStateOf(false); private set
    private var feedJob: Job? = null
    private var generation = 0

    /** Everything in the inbox, minus repositories hidden in Varde. */
    fun items(excluded: Set<Long>): List<AttentionItem> = includedAttention(feedItems + assigned, excluded)

    /** True when the inbox may be missing items; [offline] is true when showing saved repositories. */
    fun mayBeIncomplete(offline: Boolean): Boolean = feedLoading || feedIncomplete || !assignedLoaded || offline

    /** Loads assigned work. Session expiry is rethrown so the caller can sign out; other failures become [assignedError]. */
    suspend fun loadAssigned(fetch: suspend () -> List<AttentionItem>) {
        try {
            assigned = fetch()
            assignedLoaded = true
            assignedError = null
        } catch (e: CancellationException) { throw e }
        catch (e: Unauthorized) { throw e }
        catch (_: Exception) {
            assigned = emptyList()
            assignedLoaded = false
            assignedError = "Check your connection and refresh. Assigned work may require additional GitHub permissions."
        }
    }

    /** Restarts the feed for [repos]; a newer start cancels an older one. */
    fun startFeed(repos: List<Repo>, user: String, get: suspend (String) -> String): Job? {
        feedJob?.cancel()
        val current = ++generation
        if (user.isBlank()) return null
        feedLoading = true
        feedItems = emptyList()
        feedIncomplete = false
        return scope.launch {
            try {
                val result = loadAttentionFeed(repos, user, get)
                feedItems = result.items
                feedIncomplete = result.incomplete
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedIncomplete = true }
            finally { if (current == generation) feedLoading = false }
        }.also { feedJob = it }
    }

    fun clear() {
        generation++; feedJob?.cancel()
        feedItems = emptyList(); feedIncomplete = false; feedLoading = false
        assigned = emptyList(); assignedLoaded = false; assignedError = null
    }
}
