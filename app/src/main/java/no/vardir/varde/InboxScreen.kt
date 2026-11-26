package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
internal fun InboxScreen(
    sync: SyncInfo, user: String, inbox: AttentionInbox, repositories: RepositoryState, portfolio: PortfolioStore,
    delegations: DelegationTracker, dismissed: DismissedItems, onDelegationsChanged: () -> Unit, onOpenItem: (AttentionItem) -> Unit, onOpenPull: (Long, Int) -> Unit,
    onOpenProduct: (Product) -> Unit, onShowProducts: () -> Unit,
) {
    val delegated = inboxDelegations(delegations)
    // A tracked pull request is shown once, under Delegated, with its delegation status.
    val trackedPulls = delegated.mapNotNull { it.second?.pull?.htmlUrl?.takeIf { url -> url.isNotBlank() } }.toSet()
    val allSections = inboxSections(inbox.items(repositories.excluded).filterNot { it.url in trackedPulls })
    val sections = allSections.map { (s, entries) -> s to entries.filterNot { isDismissed(it, dismissed.keys) } }.filter { it.second.isNotEmpty() }
    val hiddenCount = allSections.sumOf { it.second.size } - sections.sumOf { it.second.size }
    val count = sections.sumOf { it.second.size } + delegated.count { it.second?.stage?.needsYou == true }
    // Expire dismissals whose items are gone, but only after a complete load, so a partial one can't resurface items.
    val complete = inbox.assignedLoaded && !inbox.feedLoading && !inbox.feedIncomplete && sync.snapshotTime == null
    LaunchedEffect(complete, inbox.feedItems, inbox.assigned) { if (complete) dismissed.expire(inbox.items(repositories.excluded)) }
    var undo by remember { mutableStateOf<List<AttentionItem>?>(null) }
    fun dismiss(items: List<AttentionItem>) { dismissed.dismiss(items); undo = items }
    var openDelegation by remember { mutableStateOf<String?>(null) }
    openDelegation?.let { id -> delegations.find(id) }?.let { d ->
        val id = d.id
        DelegationDialog(d, delegations.statuses[id], canOpen = repositories.included.any { it.id == d.repoId },
            onOpenPull = { number -> openDelegation = null; onOpenPull(d.repoId, number) },
            onLink = { number -> delegations.linkPull(id, number); openDelegation = null; onDelegationsChanged() },
            onDone = { delegations.markDone(id); openDelegation = null },
            onRemove = { delegations.remove(id); openDelegation = null },
            onClose = { openDelegation = null })
    }
    val now = remember(inbox.feedItems, inbox.assigned) { java.time.Instant.now() }
    val focused = portfolio.products.filter { it.focused }.sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hero(user, count, loading = inbox.feedLoading || (sync.busy && !inbox.assignedLoaded)) }
        item { SyncStatus(sync, Modifier.padding(horizontal = ScreenGutter)) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Needs attention", Modifier.weight(1f))
                TextButton(onClick = sync::refresh, enabled = !sync.busy) { Text("Refresh") }
            }
        }
        if (inbox.feedLoading) item { Notice("Checking repositories…", "Looking for review requests, failing checks and workflows.",
            modifier = Modifier.padding(horizontal = ScreenGutter)) }
        if (inbox.feedIncomplete) item { Notice("Some items may be missing",
            "Permissions, rate limits or result limits kept part of the inbox from loading.", NoticeKind.Warning,
            Modifier.padding(horizontal = ScreenGutter)) }
        val emptyState: Pair<String, String>? = when {
            inbox.assignedError != null -> null
            !inbox.assignedLoaded && sync.busy -> "Loading assigned work" to "Checking GitHub for open issues and pull requests assigned to you."
            !inbox.assignedLoaded -> "Assigned work not loaded" to "Refresh to check GitHub for assigned work."
            count == 0 && delegated.isEmpty() && !inbox.feedLoading && !inbox.feedIncomplete -> "You're all caught up" to "Review requests, failing checks, workflows and assigned work will appear here."
            else -> null
        }
        inbox.assignedError?.let { item { Notice("Couldn't load assigned work", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
        emptyState?.let { (title, message) -> item { Notice(title, message, modifier = Modifier.padding(horizontal = ScreenGutter)) } }
        undo?.let { items -> item(key = "undo") {
            UndoBar(if (items.size == 1) "Dismissed \u201c${items.single().title}\u201d" else "Dismissed ${items.size} items",
                Modifier.padding(horizontal = ScreenGutter), onUndo = { dismissed.restore(items); undo = null }, onClose = { undo = null })
        } }
        // Delegated work sits after review requests and before assigned work.
        val delegatedAt = sections.indexOfFirst { it.first == InboxSection.Assigned }.let { if (it < 0) sections.size else it }
        sections.forEachIndexed { index, (section, entries) ->
            if (index == delegatedAt) delegatedSection(delegated, delegations, now) { openDelegation = it }
            item(key = "section:${section.name}") {
                Text("${section.title} · ${entries.size}", Modifier.padding(horizontal = ScreenGutter).padding(top = 4.dp)
                    .semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val stale = if (section == InboxSection.Failing) staleFailures(entries, now) else emptyList()
            if (stale.isNotEmpty()) item(key = "stale") {
                TextButton(onClick = { dismiss(stale) }, modifier = Modifier.padding(horizontal = ScreenGutter - 8.dp)) {
                    Text("Dismiss the ${stale.size} older than 2 weeks")
                }
            }
            items(entries, key = { "${it.kind}:${it.url}" }) { entry ->
                // Assigned work stays: it's yours on GitHub until it's unassigned or closed.
                if (section == InboxSection.Assigned) AttentionCard(entry, now, Modifier.padding(horizontal = ScreenGutter)) { onOpenItem(entry) }
                else SwipeToDismiss(onDismiss = { dismiss(listOf(entry)) }, modifier = Modifier.padding(horizontal = ScreenGutter)) {
                    AttentionCard(entry, now) { onOpenItem(entry) }
                }
            }
        }
        if (delegatedAt == sections.size) delegatedSection(delegated, delegations, now) { openDelegation = it }
        if (hiddenCount > 0) item(key = "hidden") {
            TextButton(onClick = { val all = allSections.flatMap { it.second }.filter { isDismissed(it, dismissed.keys) }; dismissed.restore(all); undo = null },
                modifier = Modifier.padding(horizontal = ScreenGutter - 8.dp)) { Text("Show $hiddenCount dismissed") }
        }
        item { SectionHeader("Focused products", Modifier.padding(horizontal = ScreenGutter), "All products", onShowProducts) }
        portfolio.error?.let { item { Notice("Portfolio unavailable", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
        if (focused.isEmpty() && portfolio.error == null) item {
            Notice("Keep a product close", "Focus a product to keep its next step here.", modifier = Modifier.padding(horizontal = ScreenGutter))
        }
        items(focused, key = { it.id }) { product ->
            ProductCard(product, Modifier.padding(horizontal = ScreenGutter)) { onOpenProduct(product) }
        }
    }
}

@Composable
private fun Hero(user: String, count: Int, loading: Boolean) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    val seed = remember { Calendar.getInstance().let { it.get(Calendar.YEAR).toLong() * 1000 + it.get(Calendar.DAY_OF_YEAR) } }
    val summary = when {
        loading -> "Checking your repositories…"
        count == 0 -> "Nothing needs you right now."
        count == 1 -> "1 item needs your attention."
        else -> "$count items need your attention."
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
        LandscapeBanner(seed = seed, night = hour < 7 || hour >= 19, modifier = Modifier.matchParentSize())
        // The landscape is always dark, so hero text stays light in both themes.
        Column(Modifier.statusBarsPadding().padding(horizontal = ScreenGutter, vertical = 20.dp)) {
            Text(if (user.isBlank()) greeting else "$greeting, $user", Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF5EEE8))
            Text(summary, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD9D2CB))
        }
    }
}

private fun AttentionKind.tone(): StatusTone = when (this) {
    AttentionKind.ChecksFailing, AttentionKind.WorkflowFailed -> StatusTone.Failure
    AttentionKind.ReviewRequested -> StatusTone.Pending
    else -> StatusTone.Neutral
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionCard(entry: AttentionItem, now: java.time.Instant, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = entry.kind.tone()
    val age = relativeAge(entry.updatedAt, now)
    val accent = when (tone) {
        StatusTone.Failure -> VardeTheme.colors.danger
        StatusTone.Pending -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    VardeCard(modifier, onClick = onClick, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(48.dp).background(accent, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (age != null) Text(age, Modifier.padding(start = 8.dp).semantics { contentDescription = if (age == "now") "Updated just now" else "Updated ${spokenAge(age)} ago" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(entry.repository, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(StatusLabel(tone, entry.kind.label))
                    entry.alsoKinds.sortedBy { it.ordinal }.forEach { Tag(it.label) }
                }
            }
            Chevron()
        }
    }
}

private fun spokenAge(age: String): String {
    val n = age.takeWhile { it.isDigit() }
    val unit = when (age.drop(n.length)) { "m" -> "minute"; "h" -> "hour"; "d" -> "day"; "w" -> "week"; "mo" -> "month"; else -> return age }
    return "$n $unit" + if (n == "1") "" else "s"
}

private fun LazyListScope.delegatedSection(
    delegated: List<Pair<Delegation, DelegationStatus?>>, tracker: DelegationTracker, now: java.time.Instant, onOpen: (String) -> Unit,
) {
    if (delegated.isEmpty() && tracker.error == null) return
    item(key = "section:delegated") {
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Delegated · ${delegated.size}", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tracker.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    tracker.error?.let { item { Notice("Delegations unavailable", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
    items(delegated, key = { "delegation:${it.first.id}" }) { (d, status) ->
        DelegationCard(d, status, now, Modifier.padding(horizontal = ScreenGutter)) { onOpen(d.id) }
    }
}

/** Swipe either way to dismiss; TalkBack gets the same action as a custom action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismiss(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) { onDismiss(); true } else false
    })
    SwipeToDismissBox(state = state,
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("Dismiss") { onDismiss(); true }) },
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh, CardShape).padding(horizontal = 24.dp),
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) { content() }
}

@Composable
private fun UndoBar(text: String, modifier: Modifier = Modifier, onUndo: () -> Unit, onClose: () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
            TextButton(onClick = onClose) { Text("OK", color = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }
}
