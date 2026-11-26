package no.vardir.varde

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/*
 * A product's week, counted from what GitHub reports: pull requests merged, releases published, and work that's stuck.
 * No scores or estimates. When something couldn't be loaded, the overview says so rather than showing fewer items.
 */

/** One thing that happened or is waiting. [route] is the repository page that shows it; [at] is GitHub's timestamp. */
internal data class WeekItem(val repoId: Long, val repo: String, val title: String, val route: String, val at: String, val detail: String = "")

internal data class ProductWeek(
    val merged: List<WeekItem>,
    val released: List<WeekItem>,
    /** Open pull requests nobody has touched for [StaleDays] days or more. */
    val idle: List<WeekItem>,
    /** True when part of the data couldn't be loaded, was cut short, or came from the offline copy. */
    val incomplete: Boolean,
    val checkedAt: Long,
)

/** Readiness of one repository for its next release. Null fields weren't available. */
internal data class RepoReadiness(val repo: Repo, val latestTag: String?, val unreleased: Int?, val checks: CheckSummary?, val failed: Boolean = false)

internal const val WeekDays = 7L
internal const val StaleDays = 7L

/** A pull request from the search API. */
internal data class FoundPull(val repoId: Long, val repo: String, val number: Int, val title: String, val updatedAt: String,
    val mergedAt: String, val draft: Boolean)

/** Pull requests in a search response, kept only for [repos] (search may return others with the same name). */
internal fun parseFoundPulls(body: String, repos: List<Repo>): Pair<List<FoundPull>, Boolean> {
    val byName = repos.associateBy { "${it.owner}/${it.name}".lowercase() }
    val o = JSONObject(body)
    val items = o.optJSONArray("items") ?: JSONArray()
    val pulls = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { item ->
        val repo = byName[jsonText(item, "repository_url").substringAfter("/repos/").lowercase()] ?: return@mapNotNull null
        val merged = item.optJSONObject("pull_request")?.let { jsonText(it, "merged_at") }.orEmpty().ifBlank { jsonText(item, "closed_at") }
        FoundPull(repo.id, repo.name, item.optInt("number"), jsonText(item, "title"), jsonText(item, "updated_at"), merged, item.optBoolean("draft"))
    }
    return pulls to (o.optInt("total_count") > items.length() || o.optBoolean("incomplete_results"))
}

private fun Instant.days(): String = atOffset(ZoneOffset.UTC).toLocalDate().toString()

private fun age(at: String, now: Instant): Duration? = runCatching { Duration.between(Instant.parse(at), now) }.getOrNull()

/** Open pull requests not updated for [days] days, oldest first. */
internal fun idlePulls(open: List<FoundPull>, now: Instant, days: Long = StaleDays): List<WeekItem> = open
    .filter { (age(it.updatedAt, now)?.toDays() ?: -1) >= days }
    .sortedBy { it.updatedAt }
    .map { WeekItem(it.repoId, it.repo, "#${it.number} ${it.title}", WorkspacePage.Pull(it.number).route, it.updatedAt, if (it.draft) "draft" else "") }

/** Releases published since [since], from GET /releases. Drafts aren't shipped, so they don't count. */
internal fun releasesSince(repo: Repo, body: String, since: Instant): List<WeekItem> {
    val array = JSONArray(body)
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        .filter { !it.optBoolean("draft") && runCatching { Instant.parse(jsonText(it, "published_at")) >= since }.getOrDefault(false) }
        .map {
            val tag = jsonText(it, "tag_name")
            val title = jsonText(it, "name").ifBlank { tag }
            // The tag is shown once: as the title when the release has no name of its own.
            val detail = listOfNotNull(tag.takeIf { t -> t != title }, if (it.optBoolean("prerelease")) "pre-release" else null).joinToString(" · ")
            WeekItem(repo.id, repo.name, title, WorkspacePage.Releases.route, jsonText(it, "published_at"), detail)
        }
}

/**
 * Loads a product's week: two searches per eight repositories (merged in the last week; open), plus each repository's
 * recent releases. Search results outside the product's repositories are ignored.
 */
internal suspend fun loadProductWeek(repos: List<Repo>, now: Instant, load: suspend (String) -> CachedResponse): ProductWeek {
    val since = now.minus(Duration.ofDays(WeekDays))
    var incomplete = false
    val merged = mutableListOf<FoundPull>()
    val open = mutableListOf<FoundPull>()
    val released = mutableListOf<WeekItem>()
    suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e } catch (e: Unauthorized) { throw e } catch (_: Exception) { incomplete = true; null }
    for (batch in repos.chunked(8)) {
        val qualifiers = batch.joinToString(" ") { "repo:${it.owner}/${it.name}" }
        attempt {
            val r = load("search/issues?q=" + pathPart("$qualifiers is:pr is:merged merged:>=${since.days()}") + "&per_page=100")
            val (found, cut) = parseFoundPulls(r.body, batch)
            merged += found; if (cut || r.cached) incomplete = true
        }
        attempt {
            val r = load("search/issues?q=" + pathPart("$qualifiers is:pr is:open") + "&sort=updated&order=asc&per_page=100")
            val (found, cut) = parseFoundPulls(r.body, batch)
            open += found; if (cut || r.cached) incomplete = true
        }
    }
    for (repo in repos) attempt {
        val r = load(repoApi(repo) + "/releases?per_page=10")
        released += releasesSince(repo, r.body, since); if (r.cached) incomplete = true
    }
    val mergedItems = merged.filter { runCatching { Instant.parse(it.mergedAt) >= since }.getOrDefault(true) }
        .sortedByDescending { it.mergedAt }
        .map { WeekItem(it.repoId, it.repo, "#${it.number} ${it.title}", WorkspacePage.Pull(it.number).route, it.mergedAt) }
    return ProductWeek(mergedItems, released.sortedByDescending { it.at }, idlePulls(open, now), incomplete, now.toEpochMilli())
}

/** Unreleased commits since the latest release, and the default branch's checks, for one repository. */
internal suspend fun loadReadiness(repo: Repo, load: suspend (String) -> CachedResponse): RepoReadiness {
    var failed = false
    suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e } catch (e: Unauthorized) { throw e } catch (_: Exception) { failed = true; null }
    val branch = repo.defaultBranch.ifBlank { "HEAD" }
    val latest = attempt { latestRelease(parseReleases(load(repoApi(repo) + "/releases?per_page=10").body)) }
    val unreleased = latest?.let { release ->
        attempt { parseUnreleased(load(repoApi(repo) + "/compare/" + pathPart(release.tag) + "..." + pathPart(branch) + "?per_page=1").body).ahead }
    }
    val checks = attempt { load(repoApi(repo) + "/commits/" + pathPart(branch) + "/check-runs?per_page=100").let { parseCheckRuns(it.body, it.cached) } }
    return RepoReadiness(repo, latest?.tag, unreleased, checks, failed)
}

/** One line for a product card, such as "This week: 4 merged · 1 release · 2 idle". Null when nothing happened. */
internal fun weekLine(week: ProductWeek, failing: Int): String? {
    fun n(count: Int, one: String, many: String = one + "s") = if (count == 0) null else "$count " + if (count == 1) one else many
    val parts = listOfNotNull(n(week.merged.size, "merged", "merged"), n(week.released.size, "release"), n(failing, "failing", "failing"),
        n(week.idle.size, "idle PR"))
    return if (parts.isEmpty()) null else "This week: " + parts.joinToString(" · ")
}

/** Readiness in words, from what GitHub reported. */
internal fun readinessLine(r: RepoReadiness): String = listOfNotNull(
    when {
        r.latestTag == null && !r.failed -> "No releases yet"
        r.latestTag == null -> null
        r.unreleased == null -> "Last release ${r.latestTag}"
        r.unreleased == 0 -> "Nothing new since ${r.latestTag}"
        else -> "${r.unreleased} unreleased commit${if (r.unreleased == 1) "" else "s"} since ${r.latestTag}"
    },
    r.checks?.let { c ->
        when {
            c.failed > 0 -> "${c.failed} of ${c.total} checks failing"
            c.pending > 0 -> "checks running"
            c.total == 0 -> "no checks"
            else -> "checks passing"
        }
    },
).joinToString(" · ").ifBlank { "Couldn't check this repository" }

internal fun readinessTone(r: RepoReadiness): StatusTone = when {
    r.checks?.failed?.let { it > 0 } == true -> StatusTone.Failure
    r.checks?.pending?.let { it > 0 } == true -> StatusTone.Pending
    (r.unreleased ?: 0) > 0 && r.checks != null && r.checks.failed == 0 -> StatusTone.Success
    else -> StatusTone.Neutral
}

/** Week overviews for the products, kept for this session so product cards can show their line. */
internal class ProductInsights {
    var weeks by androidx.compose.runtime.mutableStateOf<Map<String, ProductWeek>>(emptyMap()); private set
    private var checking = mutableSetOf<String>()

    /** Reloads [product]'s week unless it was checked in the last [maxAgeMillis]. */
    suspend fun refresh(product: Product, repos: List<Repo>, load: suspend (String) -> CachedResponse, force: Boolean = false, maxAgeMillis: Long = 10 * 60_000) {
        val previous = weeks[product.id]
        if (!force && previous != null && System.currentTimeMillis() - previous.checkedAt < maxAgeMillis) return
        if (repos.isEmpty() || !checking.add(product.id)) return
        try { weeks = weeks + (product.id to loadProductWeek(repos, Instant.now(), load)) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* Shown as not checked yet. */ }
        finally { checking.remove(product.id) }
    }

    fun clear() { weeks = emptyMap() }
}
