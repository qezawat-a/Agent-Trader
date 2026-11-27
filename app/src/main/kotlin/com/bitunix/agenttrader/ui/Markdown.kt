package com.bitunix.agenttrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A deliberately small Markdown renderer.
 *
 * The model writes Markdown whether we ask or not, and Compose's `Text` shows
 * `**bold**` and `# headings` as literal punctuation. Adding a full Markdown
 * dependency for a chat bubble is not worth it, so this handles the subset the
 * model actually emits: headings, bullets and inline bold/code.
 */
data class MdSpan(val start: Int, val end: Int, val bold: Boolean, val code: Boolean)

data class MdLine(
    /** Text with the Markdown markers removed and bullets already prefixed. */
    val text: String,
    /** 0 for body text, 1..6 for `#`…`######` headings. */
    val heading: Int,
    val bullet: Boolean,
    val spans: List<MdSpan>,
)

object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")

    fun parse(source: String): List<MdLine> = source.lines().map { parseLine(it.trimEnd()) }

    private fun parseLine(raw: String): MdLine {
        val heading = HEADING.find(raw)
        if (heading != null) {
            val (body, spans) = inline(heading.groupValues[2].trim())
            return MdLine(body, heading.groupValues[1].length, bullet = false, spans = spans)
        }
        val bullet = BULLET.find(raw)
        if (bullet != null) {
            val (body, spans) = inline(bullet.groupValues[1].trim())
            // The two characters of the "• " prefix shift every span.
            return MdLine("• $body", 0, bullet = true, spans = spans.map { it.copy(start = it.start + 2, end = it.end + 2) })
        }
        val (body, spans) = inline(raw)
        return MdLine(body, 0, bullet = false, spans = spans)
    }

    /** Removes `**bold**` and `` `code` `` markers and records where they were. */
    internal fun inline(raw: String): Pair<String, List<MdSpan>> {
        val out = StringBuilder()
        val spans = mutableListOf<MdSpan>()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(raw, i + 2, end)
                    spans += MdSpan(start, out.length, bold = true, code = false)
                    i = end + 2
                    continue
                }
            }
            if (raw[i] == '`') {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(raw, i + 1, end)
                    spans += MdSpan(start, out.length, bold = false, code = true)
                    i = end + 1
                    continue
                }
            }
            out.append(raw[i])
            i++
        }
        return out.toString() to spans
    }
}

/** Renders [text] as the app's chat body: real bold, real headings, no `**` noise. */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val lines = remember(text) { Markdown.parse(text) }
    Column(modifier) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(3.dp))
            Text(
                annotated(line),
                color = color,
                style = if (line.heading > 0) style.copy(fontWeight = FontWeight.Bold) else style,
            )
        }
    }
}

private fun annotated(line: MdLine): AnnotatedString = buildAnnotatedString {
    append(line.text)
    for (span in line.spans) {
        if (span.end <= span.start) continue
        val spanStyle = when {
            span.code && span.bold ->
                SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            span.code -> SpanStyle(fontFamily = FontFamily.Monospace)
            else -> SpanStyle(fontWeight = FontWeight.Bold)
        }
        addStyle(spanStyle, span.start, span.end)
    }
}
