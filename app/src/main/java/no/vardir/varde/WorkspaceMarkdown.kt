package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.node.*

/** Base URL that relative links resolve against, e.g. a README's GitHub page. */
private val LocalLinkBase = staticCompositionLocalOf<String?> { null }

/** Opens a resolved link inside Varde when it can (returns true), e.g. another doc in the same repository. */
internal val LocalInAppLink = staticCompositionLocalOf<(String) -> Boolean> { { false } }

/** Renders markdown. With [linkBase], relative links like `docs/GUIDE.md` open that file on GitHub. */
@Composable
internal fun WorkspaceMarkdown(markdown: String, linkBase: String? = null) {
    val document = remember(markdown) { WorkspaceMarkdownParser.parse(markdown) }
    CompositionLocalProvider(LocalLinkBase provides linkBase) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MarkdownChildren(document)
        }
    }
}

/** An absolute http(s) URL for [destination], resolving relative links against [base]; null when it can't be opened. */
internal fun resolveLink(destination: String, base: String?): String? {
    val target = destination.trim()
    if (target.startsWith("https://") || target.startsWith("http://")) return target
    if (base == null || target.isEmpty() || target.contains(':') && !target.startsWith("#")) return null
    return runCatching { java.net.URI(base).resolve(target.replace(" ", "%20")).toString() }.getOrNull()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

@Composable
private fun MarkdownChildren(parent: Node) {
    var node = parent.firstChild
    while (node != null) {
        val current = node
        when (current) {
            is Heading -> MarkdownInline(current, when (current.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }, bold = true)
            is org.commonmark.node.Paragraph -> paragraphImages(current)?.let { MarkdownImages(it, LocalLinkBase.current) }
                ?: MarkdownInline(current, MaterialTheme.typography.bodyMedium)
            is FencedCodeBlock -> WorkspaceCode(current.literal.trimEnd(), current.info)
            is IndentedCodeBlock -> WorkspaceCode(current.literal.trimEnd(), "")
            is BlockQuote -> Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(3.dp).heightIn(min = 28.dp).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarkdownChildren(current)
                }
            }
            is ListBlock -> {
                var item = current.firstChild
                var number = if (current is OrderedList) current.startNumber else 1
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    while (item != null) {
                        val listItem = item
                        Row(Modifier.fillMaxWidth()) {
                            Text(if (current is OrderedList) "${number++}." else "•",
                                Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                MarkdownChildren(listItem)
                            }
                        }
                        item = item.next
                    }
                }
            }
            is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            // README authors use HTML mostly for image rows and centering; show the images and the plain text.
            is HtmlBlock -> {
                val images = htmlImages(current.literal)
                if (images.isNotEmpty()) MarkdownImages(images, LocalLinkBase.current)
                val text = stripHtml(current.literal)
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            is org.commonmark.ext.gfm.tables.TableBlock -> MarkdownTable(current)
            else -> if (current.firstChild != null) MarkdownChildren(current)
        }
        node = current.next
    }
}

@Composable
private fun MarkdownInline(node: Node, style: TextStyle, bold: Boolean = false, wrap: Boolean = true) {
    val uri = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.background
    val linkBase = LocalLinkBase.current
    val inApp = LocalInAppLink.current
    // Links are real link annotations, so TalkBack lists them and they can be focused and activated.
    val annotated = remember(node, linkColor, codeBackground, linkBase, inApp) {
        val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            fun visit(parent: Node) {
                var child = parent.firstChild
                while (child != null) {
                    val current = child
                    when (current) {
                        is org.commonmark.node.Text -> append(current.literal)
                        is SoftLineBreak, is HardLineBreak -> append("\n")
                        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(current.literal) }
                        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { visit(current) }
                        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { visit(current) }
                        is Link -> {
                            val target = resolveLink(current.destination, linkBase)
                            if (target == null) visit(current)
                            else withLink(LinkAnnotation.Clickable(target, linkStyle) {
                                if (!inApp(target)) runCatching { uri.openUri(target) }
                            }) { visit(current) }
                        }
                        is Image -> visit(current)
                        is HtmlInline -> append(stripHtml(current.literal).ifEmpty { if (current.literal.startsWith("<br", true)) "\n" else "" })
                        is org.commonmark.ext.gfm.strikethrough.Strikethrough ->
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { visit(current) }
                        else -> visit(current)
                    }
                    child = current.next
                }
            }
            visit(node)
        }
    }
    Text(annotated, style = style.copy(color = contentColor, fontWeight = if (bold) FontWeight.Bold else style.fontWeight),
        softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis)
}

@Composable
internal fun WorkspaceCode(code: String, languageHint: String) {
    val language = remember(languageHint) { CodeHighlight.language(languageHint) }
    val palette = VardeTheme.colors
    val highlighted = remember(code, language, palette) {
        buildAnnotatedString {
            append(code)
            CodeHighlight.tokens(code, language).forEach { token ->
                val color = when (token.kind) {
                    CodeHighlight.Kind.KEYWORD -> palette.codeKeyword
                    CodeHighlight.Kind.STRING -> palette.codeString
                    CodeHighlight.Kind.COMMENT -> palette.codeComment
                    CodeHighlight.Kind.NUMBER -> palette.codeNumber
                }
                addStyle(SpanStyle(color = color), token.start, token.end)
            }
        }
    }
    // Code keeps its line structure (trees, tables, aligned columns) and scrolls sideways instead of wrapping.
    Text(highlighted, modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
        .horizontalScroll(rememberScrollState()).padding(12.dp),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface, softWrap = false)
}

/** GitHub-style table: header row emphasized, cells on one line, the whole table scrolls sideways when wide. */
@Composable
private fun MarkdownTable(table: org.commonmark.ext.gfm.tables.TableBlock) {
    val rows = mutableListOf<Pair<Boolean, List<Node>>>()
    var section = table.firstChild
    while (section != null) {
        val header = section is org.commonmark.ext.gfm.tables.TableHead
        var row = section.firstChild
        while (row != null) {
            val cells = mutableListOf<Node>()
            var cell = row.firstChild
            while (cell != null) { cells += cell; cell = cell.next }
            rows += header to cells
            row = row.next
        }
        section = section.next
    }
    val columns = rows.maxOfOrNull { it.second.size } ?: return
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min)
        .border(1.dp, line, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        for (c in 0 until columns) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                rows.forEachIndexed { index, (header, cells) ->
                    Box(Modifier.fillMaxWidth().widthIn(max = 320.dp)
                        .background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val cellNode = cells.getOrNull(c)
                        if (cellNode != null) MarkdownInline(cellNode, MaterialTheme.typography.bodySmall, bold = header, wrap = false)
                        else Text(" ", style = MaterialTheme.typography.bodySmall)
                    }
                    if (index < rows.lastIndex) HorizontalDivider(color = line)
                }
            }
            if (c < columns - 1) VerticalDivider(Modifier.fillMaxHeight(), color = line)
        }
    }
}
