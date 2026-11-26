package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
            is org.commonmark.node.Paragraph -> MarkdownInline(current, MaterialTheme.typography.bodyMedium)
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
            is HtmlBlock -> Text(current.literal, style = MaterialTheme.typography.bodySmall)
            else -> if (current.firstChild != null) MarkdownChildren(current)
        }
        node = current.next
    }
}

@Composable
private fun MarkdownInline(node: Node, style: TextStyle, bold: Boolean = false) {
    val uri = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.background
    val annotated = remember(node, linkColor, codeBackground) {
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
                            pushStringAnnotation("URL", current.destination)
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { visit(current) }
                            pop()
                        }
                        is Image -> visit(current)
                        is HtmlInline -> append(current.literal)
                        else -> visit(current)
                    }
                    child = current.next
                }
            }
            visit(node)
        }
    }
    val linkBase = LocalLinkBase.current
    val inApp = LocalInAppLink.current
    ClickableText(text = annotated, style = style.copy(color = contentColor,
        fontWeight = if (bold) FontWeight.Bold else style.fontWeight), onClick = { offset ->
        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.item
            ?.let { resolveLink(it, linkBase) }?.let { url -> if (!inApp(url)) runCatching { uri.openUri(url) } }
    })
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
    Text(highlighted, modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)).padding(12.dp),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface)
}
