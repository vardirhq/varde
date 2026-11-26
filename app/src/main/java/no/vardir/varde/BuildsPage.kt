package no.vardir.varde

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Artifact names that turned out to hold an APK, per repository, so they're offered even with unusual names. */
private class KnownBuilds(context: Context) {
    private val prefs = context.getSharedPreferences("known_builds", Context.MODE_PRIVATE)
    fun names(repo: Repo): Set<String> = prefs.getStringSet(repo.id.toString(), emptySet()).orEmpty()
    fun remember(repo: Repo, name: String) {
        prefs.edit().putStringSet(repo.id.toString(), (names(repo) + name).toList().takeLast(20).toSet()).apply()
    }
}

/**
 * Installable builds of a repository: APKs attached to the latest release, and artifacts from workflow runs on the default
 * branch and other branches. With [headSha], only builds of that commit, for trying a pull request.
 */
@Composable
internal fun BuildsPage(github: GitHubApi, repo: Repo, artifactsBody: String, cached: Boolean, headSha: String?) {
    val context = LocalContext.current
    val known = remember(repo.id) { KnownBuilds(context) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var releaseApks by remember(repo.id) { mutableStateOf<List<ReleaseApk>>(emptyList()) }
    var installing by remember { mutableStateOf<BuildSource?>(null) }
    val artifacts = remember(artifactsBody) { runCatching { parseArtifacts(artifactsBody) }.getOrDefault(emptyList()) }
    val candidates = buildCandidates(artifacts, repo.defaultBranch, known.names(repo), all = showAll, headSha = headSha)
    LaunchedEffect(repo.id, headSha) {
        if (headSha != null) return@LaunchedEffect
        releaseApks = try { releaseApks(JSONObject(github.load(repoApi(repo) + "/releases/latest").body)) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    installing?.let { source ->
        InstallBuildDialog(github, source, onDismiss = { installing = null },
            onFoundApk = { if (source is BuildSource.Artifact) known.remember(repo, source.artifact.name) })
    }
    // Re-checked every time the page shows, since the person may have just changed it in Settings.
    var allowed by remember { mutableStateOf(ApkInstaller.allowed(context)) }
    LifecycleResumed { allowed = ApkInstaller.allowed(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!allowed) VardeCard {
            Text("Allow Varde to install apps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Android asks once per app. Varde only installs when you tap Install, and Android confirms every install.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { runCatching { context.startActivity(ApkInstaller.permissionIntent(context)) } }) { Text("Open setting") }
        }
        if (cached) Notice("Offline copy", "Reconnect to download builds.")
        if (releaseApks.isNotEmpty()) {
            SectionHeader("Latest release")
            releaseApks.forEach { apk ->
                NavRow(apk.name, "${apk.tag} · ${megabytes(apk.sizeBytes)}", onClick = if (cached) null else ({ installing = BuildSource.Asset(repo, apk) }))
            }
        }
        val (main, other) = candidates.partition { it.branch == repo.defaultBranch }
        fun row(a: BuildArtifact) = Triple(a.name, listOfNotNull(a.branch.ifBlank { null }, a.headSha.take(7).ifBlank { null },
            relativeAge(a.createdAt, java.time.Instant.now())?.let { "$it ago" }, megabytes(a.sizeBytes)).joinToString(" · "), a)
        if (main.isNotEmpty() && headSha == null) {
            SectionHeader("Builds from ${repo.defaultBranch}")
            main.map(::row).forEach { (title, detail, a) -> NavRow(title, detail, onClick = if (cached) null else ({ installing = BuildSource.Artifact(repo, a) })) }
        }
        val rest = if (headSha == null) other else candidates
        if (rest.isNotEmpty()) {
            SectionHeader(if (headSha == null) "Other branches and pull requests" else "Builds of this commit")
            rest.map(::row).forEach { (title, detail, a) -> NavRow(title, detail, onClick = if (cached) null else ({ installing = BuildSource.Artifact(repo, a) })) }
        }
        if (candidates.isEmpty() && releaseApks.isEmpty()) Notice("No builds found",
            if (headSha != null) "No workflow run for this commit uploaded an artifact that looks like an app build yet. Checks may still be running."
            else "No release has an APK, and no recent workflow run uploaded an artifact that looks like an app build. " +
                "Workflows can upload one with actions/upload-artifact; GitHub keeps artifacts for 90 days by default.")
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show likely app builds only" else "Show all artifacts") }
        Text("Artifacts download as a zip. Varde unzips it, checks there's an APK inside and what app it is, and then asks Android to install it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Runs [block] whenever the screen comes back to the foreground. */
@Composable
private fun LifecycleResumed(block: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) current() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private sealed interface InstallStep {
    data object Confirm : InstallStep
    data class Downloading(val done: Long, val total: Long) : InstallStep
    data object Unpacking : InstallStep
    data class Ready(val apk: File, val identity: ApkIdentity?, val inside: String?) : InstallStep
    data object Installing : InstallStep
    data class Failed(val reason: String) : InstallStep
}

/** Download → unzip → identify → confirm → Android's installer. Files are deleted when the dialog closes. */
@Composable
private fun InstallBuildDialog(github: GitHubApi, source: BuildSource, onDismiss: () -> Unit, onFoundApk: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<InstallStep>(InstallStep.Confirm) }
    val dir = remember { File(context.cacheDir, "builds") }
    DisposableEffect(Unit) { onDispose { dir.deleteRecursively() } }
    LaunchedEffect(Unit) { ApkInstaller.outcome = null }
    val outcome = ApkInstaller.outcome
    fun close() { ApkInstaller.outcome = null; onDismiss() }
    fun start() {
        scope.launch {
            try {
                dir.deleteRecursively(); dir.mkdirs()
                val download = File(dir, if (source is BuildSource.Artifact) "artifact.zip" else "app.apk")
                step = InstallStep.Downloading(0, source.sizeBytes)
                github.download(source.downloadPath, source.accept, download, maxBytes = 600_000_000) { done, total ->
                    step = InstallStep.Downloading(done, if (total > 0) total else source.sizeBytes)
                }
                val (apk, inside) = if (source is BuildSource.Artifact) {
                    step = InstallStep.Unpacking
                    val apk = File(dir, "app.apk")
                    val name = withContext(Dispatchers.IO) { extractApk(download, apk) }
                        ?: throw IllegalStateException("“${source.title}” has no APK inside. It may hold test results or other files.")
                    download.delete()
                    apk to name
                } else download to null
                val identity = withContext(Dispatchers.IO) { ApkInstaller.identify(context, apk) }
                    ?: throw IllegalStateException("The file isn't an app Android can read.")
                onFoundApk()
                step = InstallStep.Ready(apk, identity, inside)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { step = InstallStep.Failed(e.message ?: "The download failed.") }
        }
    }
    val busy = step is InstallStep.Downloading || step is InstallStep.Unpacking || (step is InstallStep.Installing && outcome == InstallOutcome.Waiting)
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Install ${source.title}") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = step) {
                    InstallStep.Confirm -> Text("Downloads ${megabytes(source.sizeBytes)} from GitHub" +
                        (if (source is BuildSource.Artifact) " and unzips it" else "") + ". You'll see what app it is before anything is installed.")
                    is InstallStep.Downloading -> {
                        Text("Downloading… ${megabytes(s.done)}" + if (s.total > 0) " of ${megabytes(s.total)}" else "")
                        if (s.total > 0) LinearProgressIndicator({ (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    InstallStep.Unpacking -> { Text("Unzipping…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is InstallStep.Ready -> {
                        val id = s.identity
                        if (id != null) {
                            Text(id.packageName, fontWeight = FontWeight.SemiBold)
                            Text("Version ${id.versionName.ifBlank { "unknown" }} (${id.versionCode})", style = MaterialTheme.typography.bodySmall)
                            val installed = ApkInstaller.installedVersion(context, id.packageName)
                            Text(if (installed == null) "Not installed on this phone yet." else "Replaces the installed version $installed.",
                                style = MaterialTheme.typography.bodySmall)
                            if (id.packageName == context.packageName) Text("This updates Varde itself; it will close and you can reopen it.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        s.inside?.let { Text("From $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("Android will ask you to confirm.", style = MaterialTheme.typography.bodySmall)
                    }
                    InstallStep.Installing -> when (outcome) {
                        InstallOutcome.Installed -> Notice("Installed", "The app is ready on this phone.", NoticeKind.Success)
                        is InstallOutcome.Failed -> Notice("Not installed", outcome.reason, NoticeKind.Error)
                        else -> { Text("Waiting for Android…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                    is InstallStep.Failed -> Notice("Couldn't get the build", s.reason, NoticeKind.Error)
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                InstallStep.Confirm -> TextButton(onClick = { start() }) { Text("Download") }
                is InstallStep.Ready -> TextButton(enabled = ApkInstaller.allowed(context), onClick = {
                    step = InstallStep.Installing
                    scope.launch { ApkInstaller.install(context, s.apk) }
                }) { Text(if (ApkInstaller.allowed(context)) "Install" else "Allow installs first") }
                is InstallStep.Failed -> TextButton(onClick = { start() }) { Text("Try again") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = ::close) { Text(if (outcome == InstallOutcome.Installed) "Done" else "Close") } },
    )
}
