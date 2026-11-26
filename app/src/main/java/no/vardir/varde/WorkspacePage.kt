package no.vardir.varde

/**
 * A page inside a repository. [route] is its stable string form, kept in navigation routes and notification links
 * (for example `pull:12`); [parse] is the only place that reads it.
 */
internal sealed interface WorkspacePage {
    val route: String

    data object Overview : WorkspacePage { override val route = "overview" }
    data object Readme : WorkspacePage { override val route = "readme" }
    data object Docs : WorkspacePage { override val route = "docs" }
    data object Commits : WorkspacePage { override val route = "commits" }
    data object Issues : WorkspacePage { override val route = "issues" }
    data object Pulls : WorkspacePage { override val route = "pulls" }
    data object Runs : WorkspacePage { override val route = "runs" }
    data object Releases : WorkspacePage { override val route = "releases" }
    /** Installable builds; with [sha], only builds of that commit (a pull request's head). */
    data class Builds(val sha: String? = null) : WorkspacePage { override val route = "builds" + (sha?.let { ":$it" } ?: "") }
    data class Issue(val number: Int) : WorkspacePage { override val route = "issue:$number" }
    data class IssueComments(val number: Int) : WorkspacePage { override val route = "issuecomments:$number" }
    data class Pull(val number: Int) : WorkspacePage { override val route = "pull:$number" }
    data class Changes(val number: Int) : WorkspacePage { override val route = "changes:$number" }
    data class ReviewComments(val number: Int) : WorkspacePage { override val route = "reviewcomments:$number" }
    data class Comments(val number: Int) : WorkspacePage { override val route = "comments:$number" }
    data class Checks(val sha: String) : WorkspacePage { override val route = "checks:$sha" }
    data class Statuses(val sha: String) : WorkspacePage { override val route = "statuses:$sha" }
    data class Run(val id: Long) : WorkspacePage { override val route = "run:$id" }
    data class Log(val jobId: Long, val runId: Long?) : WorkspacePage { override val route = "log:$jobId" + (runId?.let { ":$it" } ?: "") }
    /** A file or folder; an empty [path] is the repository root. */
    data class Files(val path: String) : WorkspacePage { override val route = "files:$path" }

    /** The pull request or issue number this page is about, if any. */
    val itemNumber: Int? get() = when (this) {
        is Issue -> number; is IssueComments -> number; is Pull -> number; is Changes -> number
        is ReviewComments -> number; is Comments -> number
        else -> null
    }

    companion object {
        private val Sha = Regex("[0-9a-fA-F]{7,40}")

        fun parse(route: String): WorkspacePage? {
            val kind = route.substringBefore(':')
            val arg = route.substringAfter(':', "")
            fun n() = arg.toIntOrNull()?.takeIf { it > 0 }
            return when (kind) {
                "overview" -> Overview; "readme" -> Readme; "docs" -> Docs; "commits" -> Commits; "issues" -> Issues
                "pulls" -> Pulls; "runs" -> Runs; "releases" -> Releases
                "builds" -> if (arg.isEmpty()) Builds() else arg.takeIf { Sha.matches(it) }?.let { Builds(it) }
                "issue" -> n()?.let(::Issue)
                "issuecomments" -> n()?.let(::IssueComments)
                "pull" -> n()?.let(::Pull)
                "changes" -> n()?.let(::Changes)
                "reviewcomments" -> n()?.let(::ReviewComments)
                "comments" -> n()?.let(::Comments)
                "checks" -> arg.takeIf { Sha.matches(it) }?.let(::Checks)
                "statuses" -> arg.takeIf { Sha.matches(it) }?.let(::Statuses)
                "run" -> arg.toLongOrNull()?.takeIf { it > 0 }?.let(::Run)
                "log" -> arg.substringBefore(':').toLongOrNull()?.takeIf { it > 0 }?.let { Log(it, arg.substringAfter(':', "").toLongOrNull()) }
                "files" -> if (arg.split('/').any { it == ".." }) null else Files(arg.trim('/'))
                else -> null
            }
        }
    }
}

/** How a page's data is loaded: one request, or every page of a list up to [maxItems]. */
internal data class PageSource(val path: String, val all: Boolean = false, val maxItems: Int = 1000)

internal fun WorkspacePage.source(repo: Repo): PageSource {
    val base = repoApi(repo)
    return when (this) {
        WorkspacePage.Overview -> PageSource(base)
        WorkspacePage.Readme -> PageSource("$base/readme")
        WorkspacePage.Docs -> PageSource("$base/git/trees/HEAD?recursive=1")
        WorkspacePage.Commits -> PageSource("$base/commits?per_page=30")
        // The issues list includes pull requests, which are filtered out, so it needs every page.
        WorkspacePage.Issues -> PageSource("$base/issues?state=open&per_page=100", all = true, maxItems = 500)
        WorkspacePage.Pulls -> PageSource("$base/pulls?state=open&per_page=100", all = true, maxItems = 300)
        WorkspacePage.Runs -> PageSource("$base/actions/runs?per_page=50")
        WorkspacePage.Releases -> PageSource("$base/releases?per_page=30")
        is WorkspacePage.Builds -> PageSource("$base/actions/artifacts?per_page=100")
        is WorkspacePage.Issue -> PageSource("$base/issues/$number")
        is WorkspacePage.IssueComments -> PageSource("$base/issues/$number/comments?per_page=100", all = true)
        is WorkspacePage.Pull -> PageSource("$base/pulls/$number")
        // GitHub lists at most 3000 changed files.
        is WorkspacePage.Changes -> PageSource("$base/pulls/$number/files?per_page=100", all = true, maxItems = 3000)
        is WorkspacePage.ReviewComments -> PageSource("$base/pulls/$number/comments?per_page=100", all = true)
        is WorkspacePage.Comments -> PageSource("$base/issues/$number/comments?per_page=100", all = true)
        is WorkspacePage.Checks -> PageSource("$base/commits/${pathPart(sha)}/check-runs?per_page=100")
        is WorkspacePage.Statuses -> PageSource("$base/commits/${pathPart(sha)}/status")
        is WorkspacePage.Run -> PageSource("$base/actions/runs/$id/jobs?per_page=100")
        is WorkspacePage.Log -> PageSource("$base/actions/jobs/$jobId/logs")
        is WorkspacePage.Files -> PageSource("$base/contents/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { pathPart(it) })
    }
}

internal val WorkspacePage.title: String get() = when (this) {
    WorkspacePage.Overview -> "Repository overview"
    WorkspacePage.Readme -> "README"
    WorkspacePage.Docs -> "Docs"
    WorkspacePage.Commits -> "Recent commits"
    WorkspacePage.Issues -> "Open issues"
    WorkspacePage.Pulls -> "Pull requests"
    WorkspacePage.Runs -> "Workflow runs"
    WorkspacePage.Releases -> "Releases"
    is WorkspacePage.Builds -> if (sha == null) "Install a build" else "Builds of this pull request"
    is WorkspacePage.Issue -> "Issue #$number"
    is WorkspacePage.IssueComments -> "Issue conversation"
    is WorkspacePage.Pull -> "Pull request #$number"
    is WorkspacePage.Changes -> "Changed files"
    is WorkspacePage.ReviewComments -> "Review comments"
    is WorkspacePage.Comments -> "Conversation"
    is WorkspacePage.Checks -> "Checks"
    is WorkspacePage.Statuses -> "Commit status"
    is WorkspacePage.Run -> "Workflow jobs"
    is WorkspacePage.Log -> "Job log"
    is WorkspacePage.Files -> path.ifBlank { "Files" }
}

internal val WorkspacePage.loadingLabel: String get() = when (this) {
    WorkspacePage.Overview -> "Loading repository…"
    WorkspacePage.Readme -> "Loading README…"
    WorkspacePage.Docs -> "Finding docs…"
    is WorkspacePage.Pull -> "Loading pull request #$number…"
    is WorkspacePage.Issue -> "Loading issue #$number…"
    is WorkspacePage.Log -> "Loading job log…"
    is WorkspacePage.Files -> "Loading files…"
    else -> "Loading ${title.lowercase()}…"
}

/** Title for a page route; "Repository" when the route isn't a known page. */
internal fun workspacePageTitle(route: String): String = WorkspacePage.parse(route)?.title ?: "Repository"
