package no.vardir.varde

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFeaturesTest {
    private val repo = Repo(1, "app", "acme", "", false, true, "https://github.com/acme/app", defaultBranch = "main")

    @Test fun pageRoutesRoundTrip() {
        val pages = listOf(WorkspacePage.Overview, WorkspacePage.Releases, WorkspacePage.Issue(4), WorkspacePage.Pull(12), WorkspacePage.Changes(12),
            WorkspacePage.Checks("abc1234"), WorkspacePage.Run(99), WorkspacePage.Log(5, 99), WorkspacePage.Log(5, null),
            WorkspacePage.Files(""), WorkspacePage.Files("docs/GUIDE.md"))
        pages.forEach { assertEquals(it, WorkspacePage.parse(it.route)) }
    }

    @Test fun malformedRoutesAreRejected() {
        listOf("pull:", "pull:-1", "pull:abc", "checks:not-a-sha", "files:../secret", "bogus", "run:0").forEach {
            assertNull(it, WorkspacePage.parse(it))
        }
    }

    @Test fun listPagesLoadEveryPage() {
        assertTrue(WorkspacePage.IssueComments(3).source(repo).all)
        assertTrue(WorkspacePage.Changes(3).source(repo).let { it.all && it.maxItems == 3000 })
        assertFalse(WorkspacePage.Runs.source(repo).all)
        assertEquals("repos/acme/app/contents/docs/a%20b.md", WorkspacePage.Files("docs/a b.md").source(repo).path)
        assertEquals("Pull request #7", workspacePageTitle("pull:7"))
        assertEquals("Repository", workspacePageTitle("nonsense"))
    }

    @Test fun patchLinesKnowTheirLineNumbers() {
        val lines = parsePatch("@@ -10,3 +20,4 @@ fun x()\n context\n-old\n+new\n+added\n\\ No newline at end of file")
        assertEquals(DiffKind.Hunk, lines[0].kind)
        assertEquals(DiffLine(" context", DiffKind.Context, 20, DiffSide.RIGHT), lines[1])
        assertEquals(DiffLine("-old", DiffKind.Removed, 11, DiffSide.LEFT), lines[2])
        assertEquals(DiffLine("+new", DiffKind.Added, 21, DiffSide.RIGHT), lines[3])
        assertEquals(22, lines[4].line)
        assertNull(lines[5].line)
    }

    @Test fun nextVersionBumpsThePatch() {
        assertEquals("v1.2.4", nextVersion("v1.2.3"))
        assertEquals("2.0.1", nextVersion("2.0.0"))
        assertEquals("v0.1.0", nextVersion(null))
        assertEquals("", nextVersion("nightly-2024"))
    }

    @Test fun latestReleaseSkipsDraftsAndPrereleases() {
        val releases = parseReleases("""[{"tag_name":"v2.0.0-rc1","prerelease":true},{"tag_name":"","draft":true},{"tag_name":"v1.9.0","name":"Big one"}]""")
        assertEquals("v1.9.0", latestRelease(releases)?.tag)
    }

    @Test fun unreleasedCommitsAreNewestFirst() {
        val u = parseUnreleased("""{"ahead_by":2,"commits":[{"commit":{"message":"First\n\nbody"}},{"commit":{"message":"Second"}}]}""")
        assertEquals(Unreleased(2, listOf("Second", "First")), u)
    }

    @Test fun mergeMethodsFollowRepositorySettings() {
        assertEquals(listOf("squash"), allowedMergeMethods(JSONObject("""{"allow_merge_commit":false,"allow_squash_merge":true,"allow_rebase_merge":false}""")))
        assertEquals(emptyList<String>(), allowedMergeMethods(JSONObject("{}")))
    }

    @Test fun agentOptionsMatchThePage() {
        assertEquals(listOf("Ask @claude on #5", "Ask @copilot on #5"), agentDeliveries(5, null).map { it.label() })
        assertEquals(listOf("Ask @claude on #8", "Assign #8 to Copilot"), agentDeliveries(null, 8).map { it.label() })
        assertEquals(listOf("Open an issue for Claude", "Open an issue for Copilot"), agentDeliveries(null, null).map { it.label() })
    }

    @Test fun mentionsComeFirstExceptForCopilotAssignments() {
        assertEquals("@claude\n\nbrief", agentBody(AgentDelivery.Comment(AgentTarget.Claude, 1, false), "brief"))
        assertEquals("@copilot\n\nbrief", agentBody(AgentDelivery.Comment(AgentTarget.Copilot, 1, true), "brief"))
        assertEquals("brief", agentBody(AgentDelivery.NewIssue(AgentTarget.Copilot), "brief"))
        assertEquals("brief", agentBody(AgentDelivery.Assign(3), "brief"))
    }

    @Test fun copilotAssignmentIsVerified() {
        assertTrue(copilotAssigned("""{"assignees":[{"login":"Copilot"}]}"""))
        assertFalse(copilotAssigned("""{"assignees":[{"login":"someone"}]}"""))
    }

    @Test fun onlyActiveWorkflowsCanBeStarted() {
        val w = parseWorkflows("""{"workflows":[{"id":1,"name":"CI","path":".github/workflows/ci.yml","state":"active"},{"id":2,"name":"Old","state":"disabled_manually"}]}""")
        assertEquals(listOf(Workflow(1, "CI", ".github/workflows/ci.yml")), w)
        assertTrue(dispatchFailure(HttpFailure(422, "x")).contains("workflow_dispatch"))
    }

    @Test fun binaryFilesAreNotShownAsText() {
        assertEquals("Binary file; open it on GitHub.", displayableText("a\u0000b"))
        assertTrue(displayableText("x".repeat(20), maxChars = 10).endsWith("[File truncated]"))
    }

    @Test fun issueLinksFromNotificationsAreAllowed() {
        assertTrue(isNotificationPage("issue:4"))
        assertFalse(isNotificationPage("files:secret"))
    }
}
