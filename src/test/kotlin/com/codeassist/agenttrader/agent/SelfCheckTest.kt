package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live checks: evidence rendering and the prompt that forbids false reassurance. */
class SelfCheckTest {

    @Test
    fun counts_include_pass_fail_and_skip() {
        val checks = listOf(
            SelfCheck.pass("a", "ok"),
            SelfCheck.pass("b", "ok"),
            SelfCheck.fail("c", "bad"),
            SelfCheck.skip("d", "n/a"),
        )
        assertEquals("2 passed, 1 failed, 1 skipped", SelfCheck.counts(checks))
    }

    @Test
    fun a_throwing_probe_is_a_failure_not_a_pass() {
        val check = SelfCheck.guarded("provider HTTP") { throw IllegalStateException("boom") }
        assertEquals(SelfCheck.Status.FAIL, check.status)
        assertTrue(check.detail.contains("boom"), check.detail)
    }

    @Test
    fun guarded_returns_the_block_result() {
        val check = SelfCheck.guarded("x") { SelfCheck.pass("x", "fine") }
        assertEquals(SelfCheck.Status.PASS, check.status)
    }

    @Test
    fun evidence_block_carries_checks_rules_and_runtime() {
        val block = SelfCheck.evidenceBlock(
            listOf(SelfCheck.fail("market price", "no price")),
            "[WARN] no feed",
            "Engine status: on",
        )
        assertTrue(block.contains("✖ market price"), block)
        assertTrue(block.contains("[WARN] no feed"), block)
        assertTrue(block.contains("Runtime snapshot"), block)
        assertTrue(block.contains("1 failed"), block)
    }

    @Test
    fun the_analyst_prompt_forbids_false_reassurance() {
        val prompt = SelfCheck.analystSystemPrompt("commands")
        assertTrue(prompt.contains("Never answer"))
        assertTrue(prompt.contains("no issues found"))
        assertTrue(prompt.contains("say that in one line"), prompt)
    }

    @Test
    fun render_marks_every_status() {
        val text = SelfCheck.render(
            listOf(
                SelfCheck.pass("p", "d1"),
                SelfCheck.fail("f", "d2"),
                SelfCheck.skip("s", "d3"),
            ),
        )
        assertTrue(text.contains("✔ p"), text)
        assertTrue(text.contains("✖ f"), text)
        assertTrue(text.contains("– s"), text)
    }
}
