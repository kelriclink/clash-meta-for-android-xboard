package com.github.kr328.clash.xboard

import android.text.TextUtils

/**
 * Small, dependency-free Markdown renderer for XBoard rich-text fields.
 *
 * XBoard stores notice and plan content as Markdown (and also accepts HTML
 * from its rich-text editor). HtmlCompat does the final conversion to Android
 * spans; this class only translates the Markdown constructs used by the web
 * panel into safe HTML understood by HtmlCompat.
 */
object XboardMarkdown {
    private val headingPattern = Regex("^#{1,6}\\s+(.+)$")
    private val unorderedPattern = Regex("^[-*+]\\s+(.+)$")
    private val orderedPattern = Regex("^\\d+[.)]\\s+(.+)$")
    private val codeFencePattern = Regex("^(`{3,}|~{3,}).*$")
    private val horizontalRulePattern = Regex("^(?:\\*\\s*){3,}$|^(?:-\\s*){3,}$|^(?:_\\s*){3,}$")
    private val tableSeparatorCellPattern = Regex("^:?-{3,}:?$")
    private val codeSpanPattern = Regex("`([^`\\r\\n]+)`")
    private val htmlTagPattern = Regex("</?[A-Za-z][^>]*>")
    private val angleLinkPattern = Regex("<(https?://[^>\\s]+)>")
    private val htmlEntityPattern = Regex("&(?:#[0-9]{1,7}|#x[0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val imagePattern = Regex("!\\[([^]]*)]\\((https?://[^)\\s]+)(?:\\s+\\\"[^\\\"]*\\\")?\\)")
    private val linkPattern = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)(?:\\s+\\\"[^\\\"]*\\\")?\\)")
    private val autoLinkPattern = Regex("(?<![\\\"'=])(https?://[^\\s<]+)")
    private val boldPattern = Regex("\\*\\*([^*\\r\\n]+)\\*\\*|__([^_\\r\\n]+)__")
    private val italicPattern = Regex("(?<!\\*)\\*([^*\\r\\n]+)\\*(?!\\*)|(?<!_)_([^_\\r\\n]+)_(?!_)")
    private val strikePattern = Regex("~~([^~\\r\\n]+)~~")
    private val placeholderPattern = Regex("\\u0000(\\d+)\\u0000")

    fun toHtml(markdown: String): String {
        val lines = markdown
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val output = StringBuilder()
        var codeFence: Char? = null
        var listType: String? = null
        var index = 0

        fun closeList() {
            listType?.let { output.append("</").append(it).append('>') }
            listType = null
        }

        while (index < lines.size) {
            val rawLine = lines[index]
            val trimmed = rawLine.trim()

            if (codeFence != null) {
                if (isClosingFence(trimmed, codeFence!!)) {
                    output.append("</code></pre>")
                    codeFence = null
                } else {
                    output.append(TextUtils.htmlEncode(rawLine)).append('\n')
                }
                index++
                continue
            }

            val fence = codeFencePattern.matchEntire(trimmed)
            if (fence != null) {
                closeList()
                codeFence = fence.value.first()
                output.append("<pre><code>")
                index++
                continue
            }

            if (trimmed.isEmpty()) {
                closeList()
                index++
                continue
            }

            // A table is recognized only when the following line is a valid
            // separator, so ordinary text containing pipes is unaffected.
            if (rawLine.contains('|') && index + 1 < lines.size && isTableSeparator(lines[index + 1])) {
                closeList()
                index = appendTable(output, lines, index)
                continue
            }

            val heading = headingPattern.matchEntire(trimmed)
            if (heading != null) {
                closeList()
                val level = trimmed.takeWhile { it == '#' }.length
                output.append("<h").append(level).append('>')
                    .append(inlineMarkdown(heading.groupValues[1]))
                    .append("</h").append(level).append('>')
                index++
                continue
            }

            if (horizontalRulePattern.matches(trimmed)) {
                closeList()
                output.append("<hr>")
                index++
                continue
            }

            val unordered = unorderedPattern.matchEntire(trimmed)
            val ordered = orderedPattern.matchEntire(trimmed)
            if (unordered != null || ordered != null) {
                val nextType = if (unordered != null) "ul" else "ol"
                if (listType != nextType) {
                    closeList()
                    output.append('<').append(nextType).append('>')
                    listType = nextType
                }

                val rawItem = unordered?.groupValues?.get(1) ?: ordered!!.groupValues[1]
                val (checkbox, item) = when {
                    rawItem.startsWith("[x] ", ignoreCase = true) -> "☑ " to rawItem.substring(4)
                    rawItem.startsWith("[ ] ") -> "☐ " to rawItem.substring(4)
                    else -> "" to rawItem
                }
                output.append("<li>")
                    .append(checkbox)
                    .append(inlineMarkdown(item))
                    .append("</li>")
                index++
                continue
            }

            if (trimmed.startsWith('>')) {
                closeList()
                val quoteLines = buildList {
                    var quoteIndex = index
                    while (quoteIndex < lines.size) {
                        val quote = lines[quoteIndex].trim()
                        if (!quote.startsWith('>')) break
                        add(quote.removePrefix(">").trim())
                        quoteIndex++
                    }
                    index = quoteIndex
                }
                output.append("<blockquote>")
                    .append(quoteLines.joinToString("<br>") { inlineMarkdown(it) })
                    .append("</blockquote>")
                continue
            }

            closeList()
            output.append(inlineMarkdown(trimmed)).append("<br>")
            index++
        }

        closeList()
        if (codeFence != null) output.append("</code></pre>")
        return output.toString().removeSuffix("<br>")
    }

    private fun isClosingFence(line: String, fence: Char): Boolean {
        return line.isNotEmpty() && line.first() == fence &&
            line.takeWhile { it == fence }.length >= 3
    }

    private fun isTableSeparator(line: String): Boolean {
        val cells = splitTableRow(line)
        return cells.size >= 1 && cells.all { tableSeparatorCellPattern.matches(it.trim()) }
    }

    private fun appendTable(output: StringBuilder, lines: List<String>, start: Int): Int {
        val header = splitTableRow(lines[start])
        // HtmlCompat does not implement table layout. Use the same readable
        // column-separated fallback that the web panel shows on narrow
        // screens instead of allowing unsupported table tags to concatenate
        // cell text together.
        output.append("<b>")
            .append(header.joinToString(" | ") { inlineMarkdown(it) })
            .append("</b><br>")

        var index = start + 2
        if (index < lines.size && lines[index].trim().isNotEmpty()) {
            while (index < lines.size && lines[index].trim().isNotEmpty() && lines[index].contains('|')) {
                val row = splitTableRow(lines[index])
                output.append(header.indices.joinToString(" | ") { cellIndex ->
                    inlineMarkdown(row.getOrNull(cellIndex).orEmpty())
                }).append("<br>")
                index++
            }
        }
        return index
    }

    private fun splitTableRow(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith('|')) value = value.substring(1)
        if (value.endsWith('|') && !value.endsWith("\\|")) value = value.dropLast(1)
        if (value.isBlank()) return emptyList()
        return value.split(Regex("(?<!\\\\)\\|"))
            .map { it.trim().replace("\\|", "|") }
    }

    private fun inlineMarkdown(value: String): String {
        val placeholders = mutableListOf<String>()
        fun stash(content: String): String {
            val key = "\u0000${placeholders.size}\u0000"
            placeholders += content
            return key
        }

        // Protect code, HTML tags, and entities before escaping ordinary text.
        var rendered = value.replace(codeSpanPattern) { match ->
            stash("<tt>${TextUtils.htmlEncode(match.groupValues[1])}</tt>")
        }
        rendered = rendered.replace(angleLinkPattern) { match ->
            val url = match.groupValues[1]
            stash("<a href=\"$url\">$url</a>")
        }
        rendered = rendered.replace(htmlTagPattern) { match -> stash(match.value) }
        rendered = rendered.replace(htmlEntityPattern) { match -> stash(match.value) }
        rendered = TextUtils.htmlEncode(rendered)

        rendered = rendered.replace(imagePattern) { match ->
            val url = match.groupValues[2]
            stash("<a href=\"$url\">🖼 ${match.groupValues[1]}</a>")
        }
        rendered = rendered.replace(linkPattern) { match ->
            stash("<a href=\"${match.groupValues[2]}\">${match.groupValues[1]}</a>")
        }
        rendered = rendered.replace(boldPattern) { match ->
            "<b>${match.groupValues[1].ifEmpty { match.groupValues[2] }}</b>"
        }
        rendered = rendered.replace(strikePattern) { match -> "<s>${match.groupValues[1]}</s>" }
        rendered = rendered.replace(italicPattern) { match ->
            "<i>${match.groupValues[1].ifEmpty { match.groupValues[2] }}</i>"
        }
        rendered = rendered.replace(autoLinkPattern) { match ->
            val url = match.groupValues[1].trimEnd('.', ',', ';', ')', ']', '！', '。')
            "<a href=\"$url\">$url</a>"
        }

        return rendered.replace(placeholderPattern) { match ->
            placeholders.getOrNull(match.groupValues[1].toInt()) ?: match.value
        }
    }
}
