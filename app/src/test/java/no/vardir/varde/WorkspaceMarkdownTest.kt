package no.vardir.varde

import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMarkdownTest {
    @Test fun parsesPullRequestDescription() {
        val document = WorkspaceMarkdownParser.parse(
            "## Validation\n\n- **Build** passes\n- See [details](https://example.com)"
        )
        assertTrue(document.firstChild is Heading)
        val list = document.firstChild.next
        assertTrue(list is BulletList)
        val first = list.firstChild.firstChild.firstChild
        assertTrue(first is StrongEmphasis)
        val second = list.firstChild.next.firstChild
        assertTrue(second.lastChild is Link)
    }

    @Test fun parsesFencedCodeAndPreservesLanguage() {
        val node = WorkspaceMarkdownParser.parse("```kotlin\nval answer = 42\n```").firstChild as FencedCodeBlock
        assertEquals("kotlin", node.info)
        assertEquals("val answer = 42\n", node.literal)
    }

    @Test fun highlightsKnownCodeAndLeavesUnknownPlain() {
        assertEquals("kotlin", CodeHighlight.language("Main.kt"))
        assertEquals("rust", CodeHighlight.language("rust"))
        assertNull(CodeHighlight.language("data.unknown"))
        val code = "val answer = 42 // result"
        val tokens = CodeHighlight.tokens(code, CodeHighlight.language("kotlin"))
        assertEquals(listOf(CodeHighlight.Kind.KEYWORD, CodeHighlight.Kind.NUMBER, CodeHighlight.Kind.COMMENT),
            tokens.map { it.kind })
        assertEquals(listOf("val", "42", "// result"), tokens.map { code.substring(it.start, it.end) })
        assertTrue(CodeHighlight.tokens(code, null).isEmpty())
    }
}
