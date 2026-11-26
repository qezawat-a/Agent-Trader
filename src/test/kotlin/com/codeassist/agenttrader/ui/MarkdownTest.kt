package com.codeassist.agenttrader.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The renderer must remove the Markdown punctuation the model emits and record
 * where the emphasis was, so the bubble shows real bold/headings instead of
 * literal asterisks.
 */
class MarkdownTest {

    @Test
    fun headings_are_detected_by_level() {
        val lines = Markdown.parse("# Title\n## Sub\nbody")
        assertEquals(3, lines.size)
        assertEquals(1, lines[0].heading)
        assertEquals("Title", lines[0].text)
        assertEquals(2, lines[1].heading)
        assertEquals("Sub", lines[1].text)
        assertEquals(0, lines[2].heading)
        assertEquals("body", lines[2].text)
    }

    @Test
    fun bullets_are_prefixed_and_spans_shift_with_the_prefix() {
        val lines = Markdown.parse("- **risk** first")
        assertEquals(1, lines.size)
        assertTrue(lines[0].bullet)
        assertEquals("• risk first", lines[0].text)
        val span = lines[0].spans.single()
        assertTrue(span.bold)
        assertEquals(2, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun bold_and_inline_code_markers_are_removed_and_mapped() {
        val (text, spans) = Markdown.inline("keep **this** and `that`")
        assertEquals("keep this and that", text)
        assertEquals(2, spans.size)
        assertTrue(spans[0].bold)
        assertFalse(spans[0].code)
        assertEquals(5, spans[0].start)
        assertEquals(9, spans[0].end)
        assertTrue(spans[1].code)
        assertEquals(14, spans[1].start)
        assertEquals(18, spans[1].end)
    }

    @Test
    fun unmatched_markers_stay_literal_text() {
        val (text, spans) = Markdown.inline("a ** b with one `tick")
        assertEquals("a ** b with one `tick", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun numbered_lists_are_left_alone() {
        val lines = Markdown.parse("1. first\n2. second")
        assertFalse(lines[0].bullet)
        assertEquals("1. first", lines[0].text)
    }

    @Test
    fun asterisks_without_a_closing_pair_do_not_swallow_the_line() {
        // A single ** must not turn the rest of the message bold.
        val (text, spans) = Markdown.inline("profit ** and loss")
        assertEquals("profit ** and loss", text)
        assertTrue(spans.isEmpty())
    }
}
