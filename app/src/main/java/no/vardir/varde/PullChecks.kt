package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun PullChecks(vm: Workspace, repo: Repo, body: String, onOpen: (String) -> Unit) {
    val sha = remember(body) { runCatching { JSONObject(body).optJSONObject("head")?.optString("sha").orEmpty() }.getOrDefault("") }
    var status by remember(sha) { mutableStateOf("Loading checks…") }
    var cacheLabel by remember(sha) { mutableStateOf("") }
    LaunchedEffect(repo.id, sha) {
        if (sha.isBlank()) { status = "Head commit unavailable"; return@LaunchedEffect }
        try {
            val result = vm.workspaceLoad(repoApi(repo) + "/commits/" + pathPart(sha) + "/check-runs?per_page=100")
            val json = JSONObject(result.body)
            val checks = json.optJSONArray("check_runs")
            val count = checks?.length() ?: 0
            val failed = (0 until count).count { checks?.optJSONObject(it)?.optString("conclusion") in setOf("failure", "timed_out", "action_required") }
            val pending = (0 until count).count { checks?.optJSONObject(it)?.optString("status") != "completed" }
            status = "$count checks · $failed failed · $pending in progress" +
                if (json.optInt("total_count") > count) " · more available on GitHub" else ""
            cacheLabel = if (result.cached) " · cached" else ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Checks unavailable"; cacheLabel = "" }
    }
    OutlinedButton(onClick = { if (sha.isNotBlank()) onOpen("checks:$sha") }, modifier = Modifier.fillMaxWidth(), enabled = sha.isNotBlank()) {
        Text(status + cacheLabel)
    }
}
