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
        .filter { it.kind == "Latest workflow run failed" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GitHub activity", style = MaterialTheme.typography.titleLarge)
        if (vm.feedLoading) Text("Checking workflow runs…", style = MaterialTheme.typography.bodySmall)
        if (vm.feedIncomplete) Text("Workflow coverage may be incomplete.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (evidence == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        evidence?.notes?.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Text("Failed workflows", style = MaterialTheme.typography.titleSmall)
        if (failures.isEmpty() && !vm.feedLoading && !vm.feedIncomplete) Text("No failed workflows found in the latest observed runs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failures.forEach { entry ->
            val repo = repos.firstOrNull { it.id == entry.repositoryId }
            val id = entry.url.substringAfterLast('/').toLongOrNull()
            if (repo != null && id != null) OutlinedButton(onClick = { onOpenRepo(repo, "run:$id") }, modifier = Modifier.fillMaxWidth()) {
                Text("${repo.name} · ${entry.title}")
            }
        }
        Text("Open pull requests", style = MaterialTheme.typography.titleSmall)
        if (evidence != null && evidence!!.pullRequests.isEmpty()) Text(
            if (evidence!!.notes.isEmpty()) "No open pull requests found." else "No open pull requests in the available results.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        evidence?.pullRequests?.forEach { entry ->
            val repo = repos.firstOrNull { it.id == entry.repositoryId }
            val id = entry.url.substringAfterLast('/').toIntOrNull()
            if (repo != null && id != null) OutlinedButton(onClick = { onOpenRepo(repo, "pull:$id") }, modifier = Modifier.fillMaxWidth()) {
                Text("${repo.name} · #$id · ${entry.title}")
            }
        }
        Text("Recent commits", style = MaterialTheme.typography.titleSmall)
        evidence?.activity?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (evidence != null && evidence!!.activity.isEmpty()) Text("No commits in the available results.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
