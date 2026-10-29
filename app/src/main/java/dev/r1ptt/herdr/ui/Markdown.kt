package dev.r1ptt.herdr.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * The markdown agents reply in, as styled text for a bubble: headings, bold, italic, inline code,
 * lists, quotes and links. Code blocks stay monospace; tables read as rows of cells. [plain] drops
 * the marks for big type and for speech.
 */
object Markdown {
    private val INLINE = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|\[([^\]]+)]\([^)\s]+\)|\*([^*\s][^*]*)\*|(?<![A-Za-z0-9])_([^_\s][^_]*)_(?![A-Za-z0-9])""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+""")
    private val NUMBER = Regex("""^(\s*)(\d+)[.)]\s+""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    /** [ink] is the bubble's text color; inline code sits on a faint wash of it. */
    fun render(md: String, ink: Int = 0): CharSequence {
        val out = SpannableStringBuilder()
        var fenced = false
        var blank = false
        for (raw in md.replace("\r", "").lines()) {
            if (raw.trimStart().startsWith("```")) { fenced = !fenced; continue }
            if (fenced) {
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                val st = out.length
                out.append(raw)
                out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                blank = false; continue
            }
            if (TABLE_SEPARATOR.matches(raw) && raw.contains('-') && raw.contains('|')) continue
            if (raw.isBlank() || RULE.matches(raw)) {
                // One empty line between paragraphs, however many the agent wrote.
                if (!blank && out.isNotEmpty()) { out.append("\n\n"); blank = true }
                continue
            }
            blank = false
            val heading = HEADING.find(raw)
            val bullet = BULLET.find(raw)
            val number = NUMBER.find(raw)
            when {
                heading != null -> line(out, raw.substring(heading.range.last + 1), ink) { st ->
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.1f), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                raw.trimStart().startsWith("|") -> line(out, raw.trim().trim('|').split('|').joinToString("  ·  ") { it.trim() }, ink)
                raw.trimStart().startsWith(">") -> line(out, raw.trimStart().removePrefix(">").trimStart(), ink) { st ->
                    out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet != null -> line(out, "  ".repeat(bullet.groupValues[1].length / 2) + "•  " + raw.substring(bullet.range.last + 1), ink)
                number != null -> line(out, "  ".repeat(number.groupValues[1].length / 2) + "${number.groupValues[2]}.  " + raw.substring(number.range.last + 1), ink)
                else -> line(out, raw, ink)
            }
        }
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    fun plain(md: String): String = render(md).toString()

    /** Appends one line with its inline marks turned into spans, then [style] over the whole line. */
    private fun line(out: SpannableStringBuilder, text: String, ink: Int = 0, style: (Int) -> Unit = {}) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        val start = out.length
        var at = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, at, m.range.first)
            val (value, span) = when {
                m.groups[1] != null -> m.groupValues[1] to listOfNotNull(TypefaceSpan("monospace"), if (ink != 0) BackgroundColorSpan((ink and 0xFFFFFF) or 0x24000000) else null)
                m.groups[2] != null -> m.groupValues[2] to listOf(StyleSpan(Typeface.BOLD))
                m.groups[3] != null -> m.groupValues[3] to listOf(StyleSpan(Typeface.BOLD))
                m.groups[4] != null -> m.groupValues[4] to listOf(UnderlineSpan())
                m.groups[5] != null -> m.groupValues[5] to listOf(StyleSpan(Typeface.ITALIC))
                else -> m.groupValues[6] to listOf(StyleSpan(Typeface.ITALIC))
            }
            val st = out.length
            out.append(value)
            span.forEach { out.setSpan(it, st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            at = m.range.last + 1
        }
        out.append(text, at, text.length)
        style(start)
    }
}
