package no.vardir.varde

/** The GitHub calls screens may make. Screens get this instead of the whole view model, and tests can fake it. */
internal interface GitHubApi {
    /** GET a REST path; falls back to the account's cached copy when offline (logs are never cached). */
    suspend fun load(path: String): CachedResponse
    suspend fun submitWrite(repo: Repo, action: WriteAction)
    suspend fun rerunFailedJobs(repo: Repo, runId: Long)
    /** Returns the new issue's URL. */
    suspend fun createIssue(repo: Repo, title: String, body: String): String
}

/** [GitHubApi] for the signed-in account. [credentials] returns (user, token) or null when signed out. */
internal class SessionGitHub(
    private val context: android.content.Context,
    private val client: GitHub,
    private val credentials: () -> Pair<String, String>?,
) : GitHubApi {
    private fun token(action: String): String = credentials()?.second ?: throw IllegalStateException("Sign in to $action.")
    override suspend fun load(path: String): CachedResponse {
        val (user, token) = credentials() ?: throw IllegalStateException("Sign in to load GitHub data.")
        if (user.isBlank()) throw IllegalStateException("Your account is not loaded yet. Refresh and try again.")
        return WorkspaceData(context, client, user, token).load(path)
    }
    override suspend fun submitWrite(repo: Repo, action: WriteAction) =
        client.submitWrite(repo, action, credentials()?.second ?: throw IllegalStateException("Sign in before submitting an action."))
    override suspend fun rerunFailedJobs(repo: Repo, runId: Long) = client.rerunFailedJobs(repo, runId, token("rerun jobs"))
    override suspend fun createIssue(repo: Repo, title: String, body: String): String =
        client.createIssue(repo, title, body, token("create an issue"))
}
