package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SignInScreen(vm: Workspace) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var privateAccess by rememberSaveable { mutableStateOf(false) }
    var publicWrite by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenGutter, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_varde_mark), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Varde", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Plan, delegate, review and ship your products from your phone. GitHub stays the source of truth.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val code = vm.code
            if (code == null) {
                VardeCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    Text("GitHub access", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    AccessOption("Private repositories", "Grants broad repo access, including writes.",
                        privateAccess, enabled = !vm.busy) { privateAccess = it }
                    if (!privateAccess) AccessOption("Write to public repositories", "Comment, review and merge on public repositories.",
                        publicWrite, enabled = !vm.busy) { publicWrite = it }
                }
                Text("Without these, Varde can read public repositories and your organizations only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { vm.signIn(privateAccess, publicWrite) }, enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign in with GitHub") }
            } else {
                VardeCard {
                    Text("Enter this code on GitHub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(code, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                        Button(onClick = { runCatching { uri.openUri("https://github.com/login/device") } }) { Text("Open GitHub") }
                    }
                    Text("Waiting for approval…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancel) { Text("Cancel") }
            }
            vm.error?.let { Notice("Sign-in problem", it, NoticeKind.Error) }
        }
    }
}

@Composable
private fun AccessOption(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
