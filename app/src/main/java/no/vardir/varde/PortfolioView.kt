package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun prioritySort(priority: String) = when (priority) { "High" -> 0; "Normal" -> 1; else -> 2 }

@Composable
internal fun ProductsScreen(vm: Workspace, onOpen: (Product) -> Unit, onCreate: () -> Unit) {
    val sorted = vm.products.sortedWith(compareByDescending<Product> { it.focused }
        .thenBy { prioritySort(it.priority) }.thenBy { it.name.lowercase() })
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScreenTitle("Products", "Group repositories, set priorities and track next steps") }
            vm.portfolioError?.let { item { Notice("Portfolio unavailable", it, NoticeKind.Error) } }
            if (vm.products.isEmpty() && vm.portfolioError == null) item {
                Notice("No products yet", "A product groups related repositories with its stage, priority and next step.")
            }
            items(sorted, key = { it.id }) { product -> ProductCard(product) { onOpen(product) } }
        }
        if (vm.portfolioError == null) ExtendedFloatingActionButton(onClick = onCreate,
            icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("New product") },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
internal fun ProductScreen(
    vm: Workspace, productId: String, onBack: () -> Unit, onEdit: () -> Unit, onOpenRepo: (Repo, String) -> Unit,
) {
    val product = vm.products.firstOrNull { it.id == productId }
    var confirmDelete by remember { mutableStateOf(false) }
    var showHandover by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (product == null) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Product not found", onBack)
            Notice("Product not found", "It may have been deleted.", NoticeKind.Warning, Modifier.padding(horizontal = ScreenGutter))
        }
        return
    }
    val available = product.repositoryIds.mapNotNull { id -> vm.repos.firstOrNull { it.id == id && id !in vm.excludedRepos } }
    val missing = product.repositoryIds.size - available.size
    if (showHandover) ProductHandover(vm, product, available) { showHandover = false }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${product.name}?") },
        text = { Text("This removes the local product, notes, and milestones. GitHub repositories are unchanged.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            if (vm.deleteProduct(product.id)) onBack()
        }) { Text("Delete product") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            DetailTopBar(product.name, onBack, eyebrow = "Product", actions = {
                TextButton(onClick = onEdit, enabled = vm.portfolioError == null) { Text("Edit") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More product actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete product…") }, enabled = vm.portfolioError == null,
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            })
        }
        item {
            Row(Modifier.padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (product.focused) Tag("Focused", emphasized = true)
                Tag(product.stage)
                Tag("${product.priority} priority")
            }
        }
        vm.portfolioError?.let { item { Notice("Portfolio unavailable", it, NoticeKind.Error, Modifier.padding(horizontal = ScreenGutter)) } }
        item { Box(Modifier.padding(horizontal = ScreenGutter)) { ProductOverview(product) } }
        item {
            OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenGutter)) {
                Text("Prepare agent handover")
            }
        }
        item { Box(Modifier.padding(horizontal = ScreenGutter)) { ProductWork(vm, product, available) } }
        item { SectionHeader("Repositories", Modifier.padding(horizontal = ScreenGutter)) }
        if (available.isEmpty()) item { Notice("No visible repositories linked", "Edit the product to link repositories.",
            modifier = Modifier.padding(horizontal = ScreenGutter)) }
        if (missing > 0) item { Notice("$missing linked repositories are hidden or unavailable", null, NoticeKind.Warning,
            Modifier.padding(horizontal = ScreenGutter)) }
        items(available, key = { it.id }) { repo ->
            Box(Modifier.padding(horizontal = ScreenGutter)) {
                NavRow(repo.name, repo.owner) { onOpenRepo(repo, "overview") }
            }
        }
        item { Box(Modifier.padding(horizontal = ScreenGutter)) { ProductActivity(vm, product, available, onOpenRepo) } }
    }
}

@Composable
internal fun ProductCard(product: Product, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val open = product.work.count { !it.done }
    VardeCard(modifier, onClick = onClick) {
        Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (product.focused) Tag("Focused", emphasized = true)
            Tag(product.stage)
            Tag("${product.priority} priority")
        }
        if (product.next.isNotBlank()) Text(product.next, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(
            if (open > 0) "$open open work items" else null,
            "${product.repositoryIds.size} linked repositories",
        ).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProductOverview(product: Product) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (product.description.isNotBlank()) Text(product.description, style = MaterialTheme.typography.bodyMedium)
        listOf("What's next" to product.next, "Milestones" to product.milestones, "Ideas" to product.ideas)
            .filter { it.second.isNotBlank() }.forEach { (title, value) ->
                VardeCard {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
    }
}
