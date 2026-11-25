package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProductActivity(vm: Workspace, product: Product, repos: List<Repo>, onOpenRepo: (Repo, String) -> Unit) {
    var evidence by remember(product.id, repos) { mutableStateOf<ProductEvidence?>(null) }
    LaunchedEffect(product.id, repos) {
        evidence = null
        evidence = loadProductEvidence(repos) { vm.workspaceLoad(it) }
    }
    val failures = productAttention(product, vm.feedItems, vm.excludedRepos)
        .filter { it.kind == AttentionKind.WorkflowFailed }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("GitHub activity")
        if (vm.feedLoading) Text("Checking workflow runs…", style = MaterialTheme.typography.bodySmall)
        if (vm.feedIncomplete || !vm.attentionLoaded || vm.snapshotTime != null) Text("Workflow coverage may be incomplete or unavailable.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (evidence == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        evidence?.notes?.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Text("Failed workflows", style = MaterialTheme.typography.titleSmall)
        if (failures.isEmpty() && !vm.feedLoading && !vm.feedIncomplete && vm.attentionLoaded && vm.snapshotTime == null) Text("No failed workflows found in the latest observed runs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failures.forEach { entry ->
            val repo = repos.firstOrNull { it.id == entry.repositoryId }
            val id = entry.url.substringAfterLast('/').toLongOrNull()
            if (repo != null && id != null) OutlinedButton(onClick = { onOpenRepo(repo, "run:$id") }, modifier = Modifier.fillMaxWidth()) {
                Text("${repo.name} · ${entry.title}")
            }
        }
        Text("Across your repositories", style = MaterialTheme.typography.titleSmall)
        if (evidence != null && evidence!!.events.isEmpty()) Text("No activity in the available results.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        evidence?.events?.forEach { event ->
            val repo = repos.firstOrNull { it.id == event.repositoryId }
            if (repo != null) VardeCard(onClick = { onOpenRepo(repo, event.route) }, contentPadding = PaddingValues(12.dp)) {
                Column {
                    Text(event.title, style = MaterialTheme.typography.bodyMedium)
                    Text("${event.repository} · ${event.kind}" +
                        if (event.timestamp.isBlank()) "" else " · ${event.timestamp.take(10)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
