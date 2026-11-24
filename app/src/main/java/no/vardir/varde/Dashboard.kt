package no.vardir.varde

data class AttentionItem(val title: String, val repository: String, val kind: String, val url: String, val repositoryId: Long)

internal fun includedRepositories(repos: List<Repo>, excluded: Set<Long>): List<Repo> = repos.filterNot { it.id in excluded }

internal fun includedAttention(items: List<AttentionItem>, excluded: Set<Long>): List<AttentionItem> = items.filterNot { it.repositoryId in excluded }

internal fun dashboardRepositories(repos: List<Repo>): List<Repo> =
    repos.filterNot { it.archived }.take(4)

internal fun filterRepositories(repos: List<Repo>, query: String, owner: String?, showArchived: Boolean): List<Repo> =
    repos.filter {
        (owner == null || it.owner == owner) && (showArchived || !it.archived) &&
            (it.name.contains(query, true) || it.owner.contains(query, true))
    }
