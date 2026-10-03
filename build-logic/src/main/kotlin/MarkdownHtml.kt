/**
 * A small Markdown to HTML converter for the documentation site. It has no dependencies and covers
 * the subset the repository's documents use: ATX headings (with GitHub-style anchors), paragraphs,
 * nested bullet and numbered lists, fenced code blocks, block quotes, pipe tables, thematic breaks,
 * inline code, emphasis, links and images. Raw HTML is never passed through (it is escaped), and
 * HTML comments on their own lines are dropped. Unsupported on purpose: reference-style links,
 * setext headings, indented code blocks, strikethrough and bare-URL autolinks.
 *
 * One instance renders one page: heading ids are made unique per instance.
 */
class MarkdownHtml(private val rewriteLink: (String) -> String = { it }) {
    /** A heading of the rendered page. */
    data class Heading(val level: Int, val text: String, val id: String)

    private val usedIds = mutableSetOf<String>()
    private val found = mutableListOf<Heading>()

    /** The headings of the document rendered so far, in order. */
    val headings: List<Heading> get() = found

    fun render(markdown: String): String =
        blocks(markdown.replace("\r\n", "\n").replace('\t', ' ').lines(), tight = false)

    // ---- blocks ----

    private class Marker(val ordered: Boolean, val number: Int, val delimiter: Char,
                         val contentOffset: Int, val rest: String)

    private class Fence(val indent: Int, val char: Char, val length: Int, val info: String)

    private fun blocks(lines: List<String>, tight: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i = when {
                line.isBlank() -> i + 1
                line.trim().startsWith("<!--") -> skipComment(lines, i)
                fenceOf(line) != null -> fence(lines, i, out)
                HEADING.matches(line) -> { heading(line, out); i + 1 }
                RULE.matches(line) -> { out.append("<hr>\n"); i + 1 }
                QUOTE.containsMatchIn(line) -> quote(lines, i, out)
                isTableStart(lines, i) -> table(lines, i, out)
                marker(line) != null -> list(lines, i, out)
                else -> paragraph(lines, i, tight, out)
            }
        }
        return out.toString()
    }

    private fun skipComment(lines: List<String>, start: Int): Int {
        var i = start
        while (i < lines.size && !lines[i].contains("-->")) i++
        return i + 1
    }

    private fun fenceOf(line: String): Fence? {
        val m = FENCE.matchEntire(line) ?: return null
        val run = m.groupValues[2]
        val info = m.groupValues[3].trim()
        if (run[0] == '`' && info.contains('`')) return null
        return Fence(m.groupValues[1].length, run[0], run.length, info)
    }

    private fun fence(lines: List<String>, start: Int, out: StringBuilder): Int {
        val open = fenceOf(lines[start])!!
        var i = start + 1
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.length >= open.length && t.all { it == open.char }) break
            val line = lines[i]
            body += line.substring(minOf(open.indent, indentOf(line)))
            i++
        }
        val language = open.info.split(' ').first().filter { it.isLetterOrDigit() || it in "+-_#." }
        out.append("<pre><code")
        if (language.isNotEmpty()) out.append(" class=\"language-").append(language).append('"')
        out.append('>').append(escape(body.joinToString("\n")))
        if (body.isNotEmpty()) out.append('\n')
        out.append("</code></pre>\n")
        return i + 1
    }

    private fun heading(line: String, out: StringBuilder) {
        val m = HEADING.matchEntire(line)!!
        val level = m.groupValues[1].length
        val html = inline(m.groupValues[2].trim().replace(Regex(" +#+$"), ""))
        val id = uniqueId(slug(html))
        found += Heading(level, plain(html), id)
        out.append("<h$level id=\"$id\">").append(html)
            .append("<a class=\"anchor\" href=\"#").append(id).append("\" aria-label=\"Link to this section\">#</a>")
            .append("</h$level>\n")
    }

    private fun uniqueId(base: String): String {
        var id = base
        var n = 0
        while (!usedIds.add(id)) id = base + "-" + ++n
        return id
    }

    private fun quote(lines: List<String>, start: Int, out: StringBuilder): Int {
        var i = start
        val inner = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (QUOTE.containsMatchIn(line)) {
                inner += line.replaceFirst(QUOTE, "")
            } else if (line.isNotBlank() && inner.isNotEmpty() && inner.last().isNotBlank() && !startsBlock(lines, i)) {
                inner += line
            } else break
            i++
        }
        out.append("<blockquote>\n").append(blocks(inner, false)).append("</blockquote>\n")
        return i
    }

    private fun marker(line: String): Marker? {
        val m = ITEM.matchEntire(line) ?: return null
        if (RULE.matches(line)) return null
        val indent = m.groupValues[1].length
        val token = m.groupValues[2]
        val spaces = m.groupValues[3].length
        val rest = m.groupValues[4]
        val gap = if (rest.isBlank() || spaces > 4) 1 else spaces
        val ordered = token[0].isDigit()
        return Marker(ordered, if (ordered) token.dropLast(1).toInt() else 0, token.last(),
            indent + token.length + gap, if (spaces > 4) " ".repeat(spaces - 1) + rest else rest)
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    private fun list(lines: List<String>, start: Int, out: StringBuilder): Int {
        val first = marker(lines[start])!!
        val items = mutableListOf<List<String>>()
        var loose = false
        var i = start
        while (i < lines.size) {
            val m = marker(lines[i]) ?: break
            if (m.ordered != first.ordered || m.delimiter != first.delimiter) break
            val item = mutableListOf(m.rest)
            i++
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j < lines.size && indentOf(lines[j]) >= m.contentOffset) {
                        repeat(j - i) { item += "" }
                        i = j
                    } else break
                } else if (indentOf(line) >= m.contentOffset) {
                    item += line.substring(m.contentOffset)
                    i++
                } else if (item.last().isNotBlank() && marker(line) == null && !startsBlock(lines, i)) {
                    item += line.trimStart()
                    i++
                } else break
            }
            items += item
            var j = i
            while (j < lines.size && lines[j].isBlank()) j++
            val next = if (j < lines.size) marker(lines[j]) else null
            if (j > i && next != null && next.ordered == first.ordered && next.delimiter == first.delimiter) {
                loose = true
                i = j
            }
        }
        if (items.any { hasInnerBlank(it) }) loose = true
        val tag = if (first.ordered) "ol" else "ul"
        out.append('<').append(tag)
        if (first.ordered && first.number != 1) out.append(" start=\"").append(first.number).append('"')
        out.append(">\n")
        for (item in items) {
            out.append("<li>").append(blocks(item, tight = !loose).trimEnd('\n')).append("</li>\n")
        }
        out.append("</").append(tag).append(">\n")
        return i
    }

    /** Whether a blank line separates content inside an item (outside fenced code). */
    private fun hasInnerBlank(item: List<String>): Boolean {
        var fenced = false
        for (line in item.dropLastWhile { it.isBlank() }) {
            if (fenceOf(line) != null) fenced = !fenced
            else if (!fenced && line.isBlank()) return true
        }
        return false
    }

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        if (fenceOf(line) != null || HEADING.matches(line) || RULE.matches(line) || QUOTE.containsMatchIn(line)) return true
        if (line.trim().startsWith("<!--") || isTableStart(lines, i)) return true
        val m = marker(line) ?: return false
        return m.rest.isNotBlank() && (!m.ordered || m.number == 1)
    }

    private fun paragraph(lines: List<String>, start: Int, tight: Boolean, out: StringBuilder): Int {
        var i = start
        val text = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && (i == start || !startsBlock(lines, i))) {
            text += lines[i].trim()
            i++
        }
        val html = inline(text.joinToString("\n"))
        if (tight) out.append(html).append('\n') else out.append("<p>").append(html).append("</p>\n")
        return i
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size || !lines[i].contains('|')) return false
        val separator = lines[i + 1]
        return TABLE_SEPARATOR.matches(separator) && cells(lines[i]).size == cells(separator).size
    }

    private fun cells(row: String): List<String> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val result = mutableListOf<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < t.length) {
            if (t[k] == '\\' && k + 1 < t.length && t[k + 1] == '|') { cell.append('|'); k += 2; continue }
            if (t[k] == '|') { result += cell.toString().trim(); cell.setLength(0) } else cell.append(t[k])
            k++
        }
        result += cell.toString().trim()
        return result
    }

    private fun table(lines: List<String>, start: Int, out: StringBuilder): Int {
        val head = cells(lines[start])
        val align = cells(lines[start + 1]).map {
            when {
                it.startsWith(":") && it.endsWith(":") -> " style=\"text-align:center\""
                it.endsWith(":") -> " style=\"text-align:right\""
                it.startsWith(":") -> " style=\"text-align:left\""
                else -> ""
            }
        }
        out.append("<div class=\"table-wrap\"><table>\n<thead><tr>")
        head.forEachIndexed { n, c -> out.append("<th").append(align[n]).append('>').append(inline(c)).append("</th>") }
        out.append("</tr></thead>\n<tbody>\n")
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|') && !startsBlock(lines, i)) {
            val row = cells(lines[i])
            out.append("<tr>")
            for (n in head.indices) {
                out.append("<td").append(align[n]).append('>').append(inline(row.getOrElse(n) { "" })).append("</td>")
            }
            out.append("</tr>\n")
            i++
        }
        out.append("</tbody>\n</table></div>\n")
        return i
    }

    // ---- inline ----

    private fun inline(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCTUATION -> { out.append(escape(s[i + 1].toString())); i += 2 }
                c == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> { out.append("<br>\n"); i += 2 }
                c == '`' -> i = codeSpan(s, i, out)
                c == '!' && s.startsWith("![", i) -> {
                    val link = linkAt(s, i + 1)
                    if (link == null) { out.append('!'); i++ } else {
                        out.append("<img src=\"").append(escape(rewriteLink(link.destination))).append("\" alt=\"")
                            .append(escape(plain(inline(link.text)))).append('"')
                        if (link.title != null) out.append(" title=\"").append(escape(link.title)).append('"')
                        out.append('>')
                        i = link.end
                    }
                }
                c == '[' -> {
                    val link = linkAt(s, i)
                    if (link == null) { out.append('['); i++ } else {
                        val href = rewriteLink(link.destination)
                        out.append("<a href=\"").append(escape(href)).append('"')
                        if (link.title != null) out.append(" title=\"").append(escape(link.title)).append('"')
                        if (EXTERNAL.containsMatchIn(href)) out.append(" rel=\"noopener\"")
                        out.append('>').append(inline(link.text)).append("</a>")
                        i = link.end
                    }
                }
                c == '<' -> {
                    val m = AUTOLINK.matchAt(s, i)
                    if (m != null) {
                        val url = m.groupValues[1]
                        out.append("<a href=\"").append(escape(url)).append("\" rel=\"noopener\">").append(escape(url)).append("</a>")
                        i += m.value.length
                    } else { out.append("&lt;"); i++ }
                }
                c == '*' || c == '_' -> i = emphasis(s, i, out)
                c == '\n' -> {
                    if (out.endsWith("  ")) {
                        while (out.endsWith(" ")) out.setLength(out.length - 1)
                        out.append("<br>\n")
                    } else out.append('\n')
                    i++
                }
                else -> { out.append(escape(c.toString())); i++ }
            }
        }
        return out.toString()
    }

    private fun codeSpan(s: String, i: Int, out: StringBuilder): Int {
        val run = runLength(s, i, '`')
        val close = closingBackticks(s, i + run, run)
        if (close < 0) { out.append("`".repeat(run)); return i + run }
        var code = s.substring(i + run, close).replace('\n', ' ')
        if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
            code = code.substring(1, code.length - 1)
        }
        out.append("<code>").append(escape(code)).append("</code>")
        return close + run
    }

    private fun emphasis(s: String, i: Int, out: StringBuilder): Int {
        val c = s[i]
        val run = runLength(s, i, c)
        val afterRun = i + run
        val opens = afterRun < s.length && !s[afterRun].isWhitespace() &&
            (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
        if (opens) {
            val width = minOf(run, 3)
            val from = i + (run - width)
            val close = findClosing(s, from + width, c, width)
            if (close >= 0) {
                out.append(c.toString().repeat(run - width))
                val inner = inline(s.substring(from + width, close))
                out.append(when (width) {
                    1 -> "<em>$inner</em>"
                    2 -> "<strong>$inner</strong>"
                    else -> "<em><strong>$inner</strong></em>"
                })
                return close + width
            }
        }
        out.append(c.toString().repeat(run))
        return afterRun
    }

    /** Index of a closing run of exactly [width] [marker] characters at or after [from], or -1. */
    private fun findClosing(s: String, from: Int, marker: Char, width: Int): Int {
        var k = from
        while (k < s.length) {
            val ch = s[k]
            when {
                ch == '\\' -> k += 2
                ch == '`' -> {
                    val run = runLength(s, k, '`')
                    val close = closingBackticks(s, k + run, run)
                    k = if (close < 0) k + run else close + run
                }
                ch == marker -> {
                    val run = runLength(s, k, marker)
                    val after = if (k + run < s.length) s[k + run] else ' '
                    val closes = (run == width || width == 2 && run == 3) && k > from && !s[k - 1].isWhitespace() &&
                        (marker == '*' || !after.isLetterOrDigit())
                    if (closes) return if (run > width) k + run - width else k
                    k += run
                }
                else -> k++
            }
        }
        return -1
    }

    private class Link(val text: String, val destination: String, val title: String?, val end: Int)

    /** A link `[text](destination "title")` whose text starts at [open], or null. */
    private fun linkAt(s: String, open: Int): Link? {
        var depth = 0
        var k = open
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\\') {
                k++
            } else if (ch == '`') {
                val run = runLength(s, k, '`')
                val close = closingBackticks(s, k + run, run)
                k = if (close < 0) k + run - 1 else close + run - 1
            } else if (ch == '[') {
                depth++
            } else if (ch == ']') {
                depth--
                if (depth == 0) break
            }
            k++
        }
        if (k + 1 >= s.length || s[k] != ']' || s[k + 1] != '(') return null
        var p = k + 2
        while (p < s.length && s[p] == ' ') p++
        val dest = StringBuilder()
        if (p < s.length && s[p] == '<') {
            p++
            while (p < s.length && s[p] != '>' && s[p] != '\n') dest.append(s[p++])
            if (p >= s.length || s[p] != '>') return null
            p++
        } else {
            var parens = 0
            while (p < s.length && !s[p].isWhitespace()) {
                val ch = s[p]
                if (ch == '\\' && p + 1 < s.length && s[p + 1] in PUNCTUATION) { dest.append(s[p + 1]); p += 2; continue }
                if (ch == '(') parens++
                if (ch == ')') { if (parens == 0) break; parens-- }
                dest.append(ch)
                p++
            }
        }
        while (p < s.length && s[p] == ' ') p++
        var title: String? = null
        if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
            val end = s.indexOf(s[p], p + 1)
            if (end < 0) return null
            title = s.substring(p + 1, end)
            p = end + 1
            while (p < s.length && s[p] == ' ') p++
        }
        if (p >= s.length || s[p] != ')') return null
        return Link(s.substring(open + 1, k), dest.toString(), title, p + 1)
    }

    private fun runLength(s: String, from: Int, c: Char): Int {
        var n = 0
        while (from + n < s.length && s[from + n] == c) n++
        return n
    }

    private fun closingBackticks(s: String, from: Int, run: Int): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '`') {
                val n = runLength(s, k, '`')
                if (n == run) return k
                k += n
            } else k++
        }
        return -1
    }

    companion object {
        private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")
        private val HEADING = Regex("^ {0,3}(#{1,6}) +(.*)$")
        private val RULE = Regex("^ {0,3}([-*_])(?: *\\1){2,} *$")
        private val QUOTE = Regex("^ {0,3}> ?")
        private val ITEM = Regex("^( {0,3})([-+*]|\\d{1,9}[.)])( +|$)(.*)$")
        private val TABLE_SEPARATOR = Regex("^ *\\|? *:?-+:? *(\\| *:?-+:? *)*\\|? *$")
        private val AUTOLINK = Regex("<(https?://[^\\s<>]+)>")
        private val EXTERNAL = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        /** Escapes text for HTML content and double-quoted attribute values. */
        fun escape(text: String): String {
            if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
            val sb = StringBuilder(text.length + 16)
            for (c in text) when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            return sb.toString()
        }

        /** The text of rendered HTML without its tags and with entities decoded. */
        fun plain(html: String): String =
            html.replace(Regex("<[^>]*>"), "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&")

        /** GitHub's heading anchor for rendered heading HTML. */
        fun slug(html: String): String =
            plain(html).lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .replace(' ', '-')
    }
}
