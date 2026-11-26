package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DocsTest {
    @Test fun readmeDecodesAndKeepsItsLinkBase() {
        val content = Base64.getEncoder().encodeToString("# Varde\n\nHello".toByteArray())
        val readme = parseReadme("""{"path":"README.md","content":"$content","html_url":"https://github.com/o/r/blob/main/README.md"}""") {
            Base64.getMimeDecoder().decode(it)
        }!!
        assertEquals("# Varde\n\nHello", readme.text)
        assertTrue(readme.markdown)
        assertFalse(Readme("README.txt", "", "").markdown)
        assertNull(parseReadme("not json") { ByteArray(0) })
    }

    @Test fun previewKeepsWholeParagraphsAndClosesCodeBlocks() {
        assertEquals("short" to false, readmePreview("short\n"))
        val text = (1..30).joinToString("\n\n") { "Paragraph $it " + "x".repeat(60) }
        val (preview, more) = readmePreview(text, maxChars = 300)
        assertTrue(more)
        assertTrue(preview.length <= 400)
        assertTrue(preview.endsWith("x"))
        val code = "Intro\n\n```\n" + "line\n".repeat(200) + "```\n\nAfter"
        val (codePreview, _) = readmePreview(code, maxChars = 50)
        assertEquals(0, Regex("```").findAll(codePreview).count() % 2)
    }

    @Test fun relativeLinksResolveAgainstTheReadme() {
        val base = "https://github.com/o/r/blob/main/README.md"
        assertEquals("https://github.com/o/r/blob/main/docs/VISION.md", resolveLink("docs/VISION.md", base))
        assertEquals("https://github.com/o/r/blob/main/LICENSE", resolveLink("./LICENSE", base))
        assertEquals("https://example.com", resolveLink("https://example.com", base))
        assertEquals("https://github.com/o/r/blob/main/README.md#setup", resolveLink("#setup", base))
        assertNull(resolveLink("mailto:a@b.c", base))
        assertNull(resolveLink("javascript:alert(1)", base))
        assertNull(resolveLink("docs/A.md", null))
    }

    @Test fun docsAreFilteredAndOrderedForReading() {
        val tree = """{"truncated":false,"tree":[
            {"path":"src/Main.kt","type":"blob"},
            {"path":"docs/ROADMAP.md","type":"blob"},
            {"path":"CHANGELOG.md","type":"blob"},
            {"path":"node_modules/x/README.md","type":"blob"},
            {"path":"README.md","type":"blob"},
            {"path":".github/pull_request_template.md","type":"blob"},
            {"path":"AGENTS.md","type":"blob"},
            {"path":"app/NOTES.md","type":"blob"},
            {"path":"notes.md","type":"blob"},
            {"path":"docs","type":"tree"}]}"""
        val index = parseDocIndex(tree)
        assertEquals(listOf("README.md", "AGENTS.md", "CHANGELOG.md", "notes.md", "docs/ROADMAP.md", "app/NOTES.md",
            ".github/pull_request_template.md"), index.docs.map { it.path })
        assertFalse(index.truncated)
        assertTrue(parseDocIndex("""{"truncated":true,"tree":[]}""").truncated)
    }

    @Test fun blobUrlsMapToRepositoryPaths() {
        assertEquals("docs/VISION.md", repoBlobPath("https://github.com/Owner/Repo/blob/main/docs/VISION.md", "owner", "repo"))
        assertNull(repoBlobPath("https://github.com/other/repo/blob/main/A.md", "owner", "repo"))
        assertNull(repoBlobPath("https://github.com/owner/repo/issues/3", "owner", "repo"))
        assertNull(repoBlobPath("https://example.com/owner/repo/blob/main/A.md", "owner", "repo"))
    }
}
