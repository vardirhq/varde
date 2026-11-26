package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test

class MarkdownMediaTest {
    private val screenshots = """<p>
  <img src="docs/screenshots/01-home.png" width="200" alt="Home screen">
  <img src='docs/screenshots/04-search.png' alt="Search result">
</p>"""

    @Test fun htmlImagesAndText() {
        assertEquals(listOf(MarkdownImageRef("docs/screenshots/01-home.png", "Home screen"),
            MarkdownImageRef("docs/screenshots/04-search.png", "Search result")), htmlImages(screenshots))
        assertEquals("", stripHtml(screenshots))
        assertEquals("Fast & small\nline two", stripHtml("""<p align="center"><b>Fast &amp; small</b><br>line two</p><!-- hidden -->"""))
    }

    @Test fun imageUrlsMapToRepositoryFiles() {
        assertEquals("docs/a b.png", repoImagePath("https://github.com/o/r/blob/main/docs/a%20b.png", "o", "r"))
        assertEquals("img/x.png", repoImagePath("https://github.com/o/r/raw/main/img/x.png?raw=true", "O", "R"))
        assertEquals("img/x.png", repoImagePath("https://raw.githubusercontent.com/o/r/main/img/x.png", "o", "r"))
        assertNull(repoImagePath("https://img.shields.io/badge/x.svg", "o", "r"))
        assertNull(repoImagePath("https://github.com/other/r/blob/main/x.png", "o", "r"))
        assertTrue(isRasterImage("a/B.PNG")); assertFalse(isRasterImage("logo.svg"))
    }

    @Test fun imageOnlyParagraphsBecomeImages() {
        fun firstParagraph(md: String) = WorkspaceMarkdownParser.parse(md).firstChild!!
        assertEquals(2, paragraphImages(firstParagraph("![a](x.png) ![b](y.png)"))!!.size)
        // Badges: images wrapped in links.
        assertEquals("build", paragraphImages(firstParagraph("[![build](https://img.shields.io/b.svg)](https://ci)"))!!.single().alt)
        assertNull(paragraphImages(firstParagraph("See ![a](x.png) here")))
        assertNull(paragraphImages(firstParagraph("Just text")))
    }

    @Test fun tablesAndStrikethroughParse() {
        val doc = WorkspaceMarkdownParser.parse("| Set | Top-1 |\n|---|---:|\n| dev | 100% |\n\n~~old~~")
        assertTrue(doc.firstChild is org.commonmark.ext.gfm.tables.TableBlock)
        assertTrue(doc.lastChild.firstChild is org.commonmark.ext.gfm.strikethrough.Strikethrough)
    }
}
