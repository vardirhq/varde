package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    sync: SyncInfo, user: String, onSignOut: () -> Unit, repositories: RepositoryState, portfolio: PortfolioStore, onManageRepos: () -> Unit,
) {
    val visible = repositories.included.size
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Settings") }
        item { SyncStatus(sync) }
        item { SectionHeader("GitHub account") }
        item {
            VardeCard {
                Text(user.ifBlank { "GitHub user" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Signed in with GitHub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = sync::refresh, enabled = !sync.busy) { Text("Refresh data") }
                    OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
        }
        item { SectionHeader("Repositories") }
        item {
            NavRow("Visible repositories", "$visible shown · ${repositories.excluded.size} hidden", onClick = onManageRepos)
        }
        item { SectionHeader("Portfolio") }
        item { VardeCard { PortfolioBackupControls(portfolio) } }
    }
}

@Composable
internal fun RepoVisibilityScreen(repositories: RepositoryState, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = repositories.repos.filter { it.name.contains(query, true) || it.owner.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DetailTopBar("Visible repositories", onBack,
                subtitle = "Hidden repositories and their work are left out of Varde. GitHub is unchanged.")
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = ScreenGutter),
                placeholder = { Text("Find a repository") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true)
        }
        if (matches.isEmpty()) item {
            Notice("No matching repositories", "Try another search or refresh GitHub.", modifier = Modifier.padding(horizontal = ScreenGutter))
        }
        items(matches, key = { it.id }) { repo ->
            val included = repo.id !in repositories.excluded
            Row(Modifier.fillMaxWidth()
                .toggleable(included, role = Role.Switch) { repositories.setExcluded(repo.id, !it) }
                .padding(horizontal = ScreenGutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.bodyLarge)
                    Text(repo.owner, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = included, onCheckedChange = null)
            }
        }
    }
}
