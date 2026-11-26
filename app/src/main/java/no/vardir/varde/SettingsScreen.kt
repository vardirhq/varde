package no.vardir.varde

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.text.DateFormat
import java.util.Date
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
    sync: SyncInfo, user: String, onSignOut: () -> Unit, repositories: RepositoryState, portfolio: PortfolioStore,
    notifications: NotificationPrefs, onSetNotifications: (Boolean) -> Unit, onManageRepos: () -> Unit,
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
        item { SectionHeader("Notifications") }
        item { NotificationSettings(notifications, onSetNotifications) }
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

@Composable
private fun NotificationSettings(prefs: NotificationPrefs, onSet: (Boolean) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(VardeNotifications.canPost(context)) }
    // Pick up results the background check wrote and permission changes made in system settings.
    LifecycleResumeEffect(Unit) { prefs.reload(); allowed = VardeNotifications.canPost(context); onPauseOrDispose { } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = VardeNotifications.canPost(context)
        if (granted) onSet(true)
    }
    fun enable() {
        if (Build.VERSION.SDK_INT >= 33 && !allowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onSet(true)
    }
    VardeCard(contentPadding = PaddingValues(vertical = 8.dp)) {
        SwitchRow("Background checks", "Checks GitHub about every 30 minutes while you're online, using your GitHub API rate limit. Nothing leaves your phone except requests to GitHub.",
            prefs.enabled) { if (it) enable() else onSet(false) }
        if (prefs.enabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AlertCategory.entries.forEach { category ->
                SwitchRow(category.label, category.detail, category in prefs.categories) { prefs.setCategory(category, it) }
            }
        }
        if (prefs.enabled && !allowed) Notice("Notifications are blocked", "Android won't show Varde's notifications. Allow them in system settings.",
            NoticeKind.Warning, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (prefs.enabled && !allowed) TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open notification settings") }
        if (prefs.enabled) Text(
            if (prefs.lastCheck == 0L) "Not checked yet. The first check only notes what's already there."
            else "Last check ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.lastCheck))}: ${prefs.lastResult}",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
