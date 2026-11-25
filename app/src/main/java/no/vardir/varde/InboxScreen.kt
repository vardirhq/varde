package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
internal fun InboxScreen(
    vm: Workspace, onOpenItem: (AttentionItem) -> Unit, onOpenProduct: (Product) -> Unit, onShowProducts: () -> Unit,
) {
    val work = includedAttention(vm.feedItems + vm.attention, vm.excludedRepos)
    val focused = vm.products.filter { it.focused }.sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hero(vm.user, work.size, loading = vm.feedLoading || (vm.busy && !vm.attentionLoaded)) }
        item { SyncStatus(vm, Modifier.padding(horizontal = ScreenGutter)) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Needs attention", Modifier.weight(1f))
                TextButton(onClick = vm::refresh, enabled = !vm.busy) { Text("Refresh") }
            }
        }
        if (vm.feedLoading) item { Notice("Checking repositories…", "Looking for review requests, failing checks and workflows.",
            modifier = Modifier.padding(horizontal = ScreenGutter)) }
        if (vm.feedIncomplete) item { Notice("Some items may be missing",
            "Permissions, rate limits or result limits kept part of the inbox from loading.", NoticeKind.Warning,
            Modifier.padding(horizontal = ScreenGutter)) }
        val emptyState: Pair<String, String>? = when {
            vm.attentionError != null -> null
            !vm.attentionLoaded && vm.busy -> "Loading assigned work" to "Checking GitHub for open issues and pull requests assigned to you."
            !vm.attentionLoaded -> "Assigned work not loaded" to "Refresh to check GitHub for assigned work."
            work.isEmpty() && !vm.feedLoading && !vm.feedIncomplete -> "You're all caught up" to "Review requests, failing checks, workflows and assigned work will appear here."
            else -> null
        }
        vm.attentionError?.let { item { Notice("Couldn't load assigned work", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
        emptyState?.let { (title, message) -> item { Notice(title, message, modifier = Modifier.padding(horizontal = ScreenGutter)) } }
        items(work, key = { "${it.kind}:${it.url}" }) { entry ->
            AttentionCard(entry, Modifier.padding(horizontal = ScreenGutter)) { onOpenItem(entry) }
        }
        item { SectionHeader("Focused products", Modifier.padding(horizontal = ScreenGutter), "All products", onShowProducts) }
        vm.portfolioError?.let { item { Notice("Portfolio unavailable", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
        if (focused.isEmpty() && vm.portfolioError == null) item {
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

private data class KindStyle(val label: String, val tone: StatusTone)

private fun kindStyle(kind: String): KindStyle = when (kind) {
    "Latest workflow run failed" -> KindStyle("Workflow failed", StatusTone.Failure)
    "PR checks failing" -> KindStyle("Checks failing", StatusTone.Failure)
    "Review requested" -> KindStyle("Review requested", StatusTone.Pending)
    "Pull request" -> KindStyle("Assigned PR", StatusTone.Neutral)
    "Issue" -> KindStyle("Assigned issue", StatusTone.Neutral)
    else -> KindStyle(kind, StatusTone.Neutral)
}

@Composable
private fun AttentionCard(entry: AttentionItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val style = kindStyle(entry.kind)
    val accent = when (style.tone) {
        StatusTone.Failure -> VardeTheme.colors.danger
        StatusTone.Pending -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    VardeCard(modifier, onClick = onClick, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(48.dp).background(accent, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(StatusLabel(style.tone, style.label))
                    Text(entry.repository, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Chevron()
        }
    }
}
