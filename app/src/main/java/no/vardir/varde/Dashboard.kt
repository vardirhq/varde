package no.vardir.varde

data class AttentionItem(val title: String, val repository: String, val kind: String, val url: String)

internal fun dashboardRepositories(repos: List<Repo>): List<Repo> =
    repos.filterNot { it.archived }.take(4)

internal fun filterRepositories(repos: List<Repo>, query: String, owner: String?, showArchived: Boolean): List<Repo> =
    repos.filter {
        (owner == null || it.owner == owner) && (showArchived || !it.archived) &&
            (it.name.contains(query, true) || it.owner.contains(query, true))
    }
