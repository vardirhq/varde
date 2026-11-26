package no.vardir.varde

import org.commonmark.node.*
import org.commonmark.parser.Parser

internal object WorkspaceMarkdownParser {
    // GitHub-flavored tables and ~~strikethrough~~, as used in most READMEs.
    private val parser = Parser.builder()
        .extensions(listOf(org.commonmark.ext.gfm.tables.TablesExtension.create(),
            org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create()))
        .build()
    fun parse(text: String): Node = parser.parse(text)
}

/** Lightweight highlighting for common source languages; unknown files remain plain text. */
internal object CodeHighlight {
    private val keywords = setOf(
        "as", "async", "await", "break", "case", "class", "const", "continue", "def", "default",
        "do", "else", "enum", "export", "false", "for", "from", "fun", "function", "if", "impl",
        "import", "in", "interface", "let", "match", "mod", "mut", "null", "object", "package",
        "private", "pub", "public", "return", "self", "static", "struct", "super", "this", "throw",
        "trait", "true", "try", "type", "use", "val", "var", "when", "while"
    )
    private val languageByExtension = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "js" to "javascript",
        "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "rs" to "rust",
        "py" to "python", "json" to "json", "sh" to "shell", "bash" to "shell",
        "yaml" to "yaml", "yml" to "yaml", "toml" to "toml"
    )
    private val supported = languageByExtension.values.toSet()
    private val token = Regex("""(?m)//[^\n]*|\#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z_0-9]*\b""")

    fun language(name: String): String? {
        val normalized = name.trim().lowercase().substringBefore(' ')
        val fromFile = languageByExtension[normalized.substringAfterLast('.', "")]
        val aliases = mapOf("kt" to "kotlin", "js" to "javascript", "ts" to "typescript",
            "rs" to "rust", "py" to "python", "sh" to "shell", "yml" to "yaml", "bash" to "shell")
        return fromFile ?: (aliases[normalized] ?: normalized).takeIf { it in supported }
    }

    data class Token(val start: Int, val end: Int, val kind: Kind)
    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER }

    fun tokens(code: String, language: String?): List<Token> {
        if (language == null) return emptyList()
        return token.findAll(code).mapNotNull { match ->
            val value = match.value
            val kind = when {
                value.startsWith("//") || value.startsWith("#") -> Kind.COMMENT
                value.startsWith("\"") || value.startsWith("'") -> Kind.STRING
                value.first().isDigit() -> Kind.NUMBER
                value in keywords -> Kind.KEYWORD
                else -> null
            }
            kind?.let { Token(match.range.first, match.range.last + 1, it) }
        }.toList()
    }
}
