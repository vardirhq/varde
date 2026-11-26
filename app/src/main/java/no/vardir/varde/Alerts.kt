package no.vardir.varde

/** Notification categories the user can switch on or off. */
internal enum class AlertCategory(val label: String, val detail: String) {
    Failures("Failing checks and workflows", "A pull request's checks or a workflow's latest run failed."),
    Reviews("Review requests", "Someone asked for your review."),
    Delegations("Delegated work", "An agent's pull request is ready for review or its checks fail."),
    YourPulls("Reviews of your pull requests", "Someone approved your pull request or asked for changes."),
    Mentions("Mentions", "Someone mentioned you in an issue or pull request."),
}

/**
 * Something worth a notification. [key] identifies it across checks so it notifies once; [page] is the workspace
 * page to open (for example "pull:12"), or null to open the Inbox.
 */
internal data class Alert(
    val key: String, val category: AlertCategory, val title: String, val text: String, val repoId: Long, val page: String?,
)

/** Inbox items that deserve a notification. Assigned issues and PRs wait for the next time you open the app. */
internal fun attentionAlerts(items: List<AttentionItem>): List<Alert> = mergeAttention(items).mapNotNull { item ->
    val category = when (item.kind) {
        AttentionKind.ChecksFailing, AttentionKind.WorkflowFailed -> AlertCategory.Failures
        AttentionKind.ReviewRequested -> AlertCategory.Reviews
        AttentionKind.ChangesRequested, AttentionKind.Approved -> AlertCategory.YourPulls
        AttentionKind.Mentioned -> AlertCategory.Mentions
        else -> return@mapNotNull null
    }
    Alert("${item.kind}:${item.url}", category, "${item.kind.label} · ${item.repository}", item.title, item.repositoryId, attentionRoute(item))
}

/** Delegations whose next step is yours. The stage is part of the key, so failing and then becoming ready notify separately. */
internal fun delegationAlerts(checked: List<Pair<Delegation, DelegationStatus>>): List<Alert> = checked.mapNotNull { (d, status) ->
    if (!status.stage.needsYou) return@mapNotNull null
    val number = status.pull?.number ?: d.pullNumber
    Alert("delegation:${d.id}:${status.stage}", AlertCategory.Delegations, "${status.stage.label}: ${d.title}",
        status.detail, d.repoId, number?.let { "pull:$it" })
}

/** Bounds what is remembered across partial checks. */
private const val MaxSeen = 1000

internal data class AlertDecision(val post: List<Alert>, val seen: Set<String>)

/**
 * Decides what to post. The first check only records what already exists. Items you switched off are still recorded,
 * so switching a category on later doesn't replay old items. When GitHub returned partial data ([complete] false),
 * nothing is forgotten, so items that briefly went missing don't notify again.
 */
internal fun decideAlerts(
    current: List<Alert>, seen: Set<String>, enabled: Set<AlertCategory>, firstRun: Boolean, complete: Boolean,
): AlertDecision {
    val keys = current.map { it.key }.toSet()
    val post = if (firstRun) emptyList() else current.filter { it.key !in seen && it.category in enabled }.distinctBy { it.key }
    val remembered = if (complete) keys else (seen + keys).let { if (it.size > MaxSeen) keys else it }
    return AlertDecision(post, remembered)
}

/** Only pages the app itself links to may be opened from a notification. */
internal fun isNotificationPage(page: String): Boolean = Regex("(pull|run|issue):[0-9]{1,12}").matches(page)
