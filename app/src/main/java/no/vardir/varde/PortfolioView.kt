package no.vardir.varde

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun PortfolioView(
    vm: Workspace, initialProductId: String? = null, onBack: () -> Unit,
    onOpenRepo: (Repo, String, String) -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf(initialProductId) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showHandover by remember { mutableStateOf(false) }
    val selected = vm.products.firstOrNull { it.id == selectedId }
    fun back() {
        when {
            editing != null -> editing = null
            selectedId != null -> selectedId = null
            else -> onBack()
        }
    }
    BackHandler { back() }
    if (showHandover && selected != null) {
        val chosen = selected.repositoryIds.mapNotNull { id -> vm.repos.firstOrNull { it.id == id && id !in vm.excludedRepos } }
        ProductHandover(vm, selected, chosen) { showHandover = false }
    }
    if (confirmDelete && selected != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.name}?") },
            text = { Text("This removes the local product, notes, and milestones. GitHub repositories are unchanged.") },
            confirmButton = { TextButton(onClick = {
                if (vm.deleteProduct(selected.id)) selectedId = null
                confirmDelete = false
            }) { Text("Delete product") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
    val editor = editing
    if (editor != null) {
        ProductEditor(editor, vm.repos.filterNot { it.id in vm.excludedRepos }, vm.portfolioError,
            onCancel = { editing = null }, onSave = {
                if (vm.saveProduct(it)) { selectedId = it.id; editing = null }
            })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = { back() }) { Text("‹  Back") }
                Text(selected?.name ?: "Portfolio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (selected == null) "Your products and their repositories" else "${selected.stage} · ${selected.priority} priority",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.portfolioError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (selected == null) {
                item {
                    Button(onClick = { editing = Product(UUID.randomUUID().toString(), "") },
                        enabled = vm.portfolioError == null, modifier = Modifier.fillMaxWidth()) { Text("Create product") }
                }
                if (vm.products.isEmpty() && vm.portfolioError == null) item {
                    Text("Group related repositories into a product, then add your priorities and next steps.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { PortfolioBackupControls(vm) }
                val sorted = vm.products.sortedWith(compareByDescending<Product> { it.focused }
                    .thenBy { when (it.priority) { "High" -> 0; "Normal" -> 1; else -> 2 } }.thenBy { it.name.lowercase() })
                items(sorted, key = { it.id }) { product ->
                    ProductCard(product) { selectedId = product.id }
                }
            } else {
                item { ProductOverview(selected) }
                item {
                    OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Prepare product handover") }
                }
                item { ProductWork(vm, selected) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = selected }, enabled = vm.portfolioError == null) { Text("Edit") }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = vm.portfolioError == null) { Text("Delete") }
                } }
                val available = selected.repositoryIds.mapNotNull { id -> vm.repos.firstOrNull { it.id == id && id !in vm.excludedRepos } }
                val missing = selected.repositoryIds.size - available.size
                item { Text("Repositories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                if (available.isEmpty()) item { Text("No visible repositories linked.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (missing > 0) item { Text("$missing linked repositories are hidden or unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(available, key = { it.id }) { repo ->
                    OutlinedCard(onClick = { onOpenRepo(repo, "overview", selected.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(repo.name, style = MaterialTheme.typography.titleMedium)
                            Text(repo.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { ProductActivity(vm, selected, available) { repo, route -> onOpenRepo(repo, route, selected.id) } }
            }
        }
    }
}

@Composable
internal fun ProductCard(product: Product, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text((if (product.focused) "Focused · " else "") + "${product.stage} · ${product.priority} priority",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (product.next.isNotBlank()) Text(product.next, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if (product.work.any { !it.done }) Text("${product.work.count { !it.done }} open work items",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("${product.repositoryIds.size} linked repositories", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProductOverview(product: Product) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (product.description.isNotBlank()) Text(product.description, style = MaterialTheme.typography.bodyMedium)
    listOf("What's next" to product.next, "Milestones" to product.milestones, "Ideas" to product.ideas)
        .filter { it.second.isNotBlank() }.forEach { (title, value) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
