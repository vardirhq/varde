package no.vardir.varde

import org.json.JSONObject

/** The GitHub calls screens may make. Screens get this instead of the whole view model, and tests can fake it. */
internal interface GitHubApi {
    /** GET a REST path; falls back to the account's cached copy when offline (logs are never cached). */
    suspend fun load(path: String): CachedResponse
    /** GET every page of a list (a JSON array response) up to [maxItems]; [CachedResponse.truncated] when there were more. */
    suspend fun loadAll(path: String, maxItems: Int = 1000): CachedResponse = load(path)
    suspend fun submitWrite(repo: Repo, action: WriteAction)
    suspend fun rerunFailedJobs(repo: Repo, runId: Long)
    /** Raw bytes of a repository file, never cached on disk. Used for images and large files. */
    suspend fun rawFile(repo: Repo, path: String, maxBytes: Long): ByteArray
    /** Downloads a GitHub API resource (an artifact zip, a release asset) to [dest]; never cached. */
    suspend fun download(path: String, accept: String, dest: java.io.File, maxBytes: Long, onProgress: (Long, Long) -> Unit)
    suspend fun deleteRun(repo: Repo, runId: Long)
    suspend fun disableWorkflow(repo: Repo, workflowId: Long)
    /** Returns the new issue's URL. */
    suspend fun createIssue(repo: Repo, title: String, body: String): String
    /**
     * A confirmed write to the GitHub REST API. [what] describes it for error messages ("Cancelling the run").
     * Returns the response body. Throws [WriteOutcomeUnknown] when the connection dropped after sending.
     */
    suspend fun send(method: String, path: String, what: String, json: JSONObject? = null): String
    /** A GraphQL mutation, for the few writes the REST API lacks (such as marking a draft ready for review). */
    suspend fun graphql(query: String, variables: JSONObject, what: String): JSONObject
}

/**
 * [GitHubApi] for the signed-in account. [credentials] returns (user, token) or null when signed out. A rejected token
 * anywhere signs the account out through [onUnauthorized], so no screen is left showing errors for a dead session.
 */
internal class SessionGitHub(
    private val cache: ResponseCache,
    private val client: GitHub,
    private val onUnauthorized: () -> Unit = {},
    private val credentials: () -> Pair<String, String>?,
) : GitHubApi {
    private fun token(action: String): String = credentials()?.second ?: throw IllegalStateException("Sign in to $action.")
    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() } catch (e: Unauthorized) { onUnauthorized(); throw e }
    private fun data(): WorkspaceData {
        val (user, token) = credentials() ?: throw IllegalStateException("Sign in to load GitHub data.")
        if (user.isBlank()) throw IllegalStateException("Your account is not loaded yet. Refresh and try again.")
        return WorkspaceData(cache, client, user, token)
    }
    override suspend fun load(path: String): CachedResponse = guarded { data().load(path) }
    override suspend fun loadAll(path: String, maxItems: Int): CachedResponse = guarded { data().loadAll(path, maxItems) }
    override suspend fun submitWrite(repo: Repo, action: WriteAction) =
        guarded { client.submitWrite(repo, action, token("submit an action")) }
    override suspend fun rerunFailedJobs(repo: Repo, runId: Long) = guarded { client.rerunFailedJobs(repo, runId, token("rerun jobs")) }
    override suspend fun rawFile(repo: Repo, path: String, maxBytes: Long): ByteArray = guarded {
        client.rawFile(repoApi(repo) + "/contents/" + path.split('/').joinToString("/") { pathPart(it) }, token("load files"), maxBytes)
    }
    override suspend fun download(path: String, accept: String, dest: java.io.File, maxBytes: Long, onProgress: (Long, Long) -> Unit) =
        guarded { client.download(path, token("download builds"), accept, dest, maxBytes, onProgress) }
    override suspend fun deleteRun(repo: Repo, runId: Long) = guarded { client.deleteRun(repo, runId, token("delete runs")) }
    override suspend fun disableWorkflow(repo: Repo, workflowId: Long) = guarded { client.disableWorkflow(repo, workflowId, token("disable workflows")) }
    override suspend fun createIssue(repo: Repo, title: String, body: String): String =
        guarded { client.createIssue(repo, title, body, token("create an issue")) }
    override suspend fun send(method: String, path: String, what: String, json: JSONObject?): String =
        guarded { client.send(method, path, token("change things on GitHub"), what, json) }
    override suspend fun graphql(query: String, variables: JSONObject, what: String): JSONObject =
        guarded { client.graphql(query, variables, token("change things on GitHub"), what) }
}
