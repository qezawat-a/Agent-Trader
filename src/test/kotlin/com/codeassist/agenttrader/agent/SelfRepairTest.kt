package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-diagnosis: what is broken, what can be repaired automatically. */
class SelfRepairTest {

    private fun provider(
        url: String = "https://api.openai.com",
        key: Boolean = true,
        model: String = "gpt-4o",
        error: String = "",
    ) = SelfRepair.ProviderState(url, key, model, 0, error)

    private fun engine(
        live: Boolean = false,
        engineOn: Boolean = true,
        trading: Boolean = false,
        loop: Boolean = false,
        feed: Boolean = false,
        keys: Boolean = false,
        positions: Int = 0,
        leverage: Int = 5,
        error: String = "",
    ) = SelfRepair.EngineState(
        live = live,
        engineOn = engineOn,
        trading = trading,
        loopRunning = loop,
        feedConnected = feed,
        bitunixKeysPresent = keys,
        openPositions = positions,
        leverage = leverage,
        lastError = error,
    )

    @Test
    fun missing_provider_is_critical_and_not_auto_fixed() {
        val d = SelfRepair.diagnose(provider(url = "", key = false), engine())
        assertEquals("CRITICAL", d.worst)
        assertTrue(d.findings.none { it.autoFixable })
    }

    @Test
    fun live_without_keys_is_critical() {
        val d = SelfRepair.diagnose(provider(), engine(live = true, keys = false))
        assertTrue(d.findings.any { it.severity == "CRITICAL" && it.issue.contains("Bitunix keys") })
    }

    @Test
    fun missing_model_is_auto_fixable_when_the_provider_is_set() {
        val d = SelfRepair.diagnose(provider(model = ""), engine())
        val finding = d.findings.first { it.issue.contains("No model selected") }
        assertTrue(finding.autoFixable)
    }

    @Test
    fun dead_loop_and_feed_are_auto_fixable() {
        val d = SelfRepair.diagnose(
            provider(),
            engine(trading = true, loop = false, live = true, keys = true, feed = false, positions = 2),
        )
        assertTrue(d.findings.any { it.issue.contains("loop is not running") && it.autoFixable })
        assertTrue(d.findings.any { it.issue.contains("WebSocket feed") && it.autoFixable })
    }

    @Test
    fun leverage_above_the_exchange_max_is_flagged() {
        val d = SelfRepair.diagnose(provider(), engine(leverage = 250))
        assertTrue(d.findings.any { it.issue.contains("Leverage") })
    }

    @Test
    fun leverage_at_the_listed_maximum_is_accepted() {
        // BTCUSDT/ETHUSDT list 200x, so 200 must not be flagged.
        val d = SelfRepair.diagnose(provider(), engine(leverage = 200))
        assertTrue(d.findings.none { it.issue.contains("Leverage") }, d.summary())
    }

    @Test
    fun healthy_system_has_no_findings() {
        val d = SelfRepair.diagnose(
            provider(),
            engine(engineOn = true, trading = true, loop = true, live = true, keys = true, feed = true),
        )
        assertTrue(d.findings.isEmpty(), d.summary())
        // The old text ("No problems found. Everything looks healthy.") is exactly
        // what the user complained about: it must never be the answer again.
        assertFalse(d.summary().contains("No problems found"))
        assertTrue(d.summary().contains("No rule fired"), d.summary())
    }

    @Test
    fun complaint_is_echoed_even_when_every_rule_passes() {
        val complaint = "verify says no issues but positions never open"
        val d = SelfRepair.diagnose(provider(), engine(), complaint = complaint)
        assertTrue(d.findings.isEmpty())
        assertTrue(d.summary().startsWith("Your report:"), d.summary())
        assertTrue(d.summary().contains(complaint))
        assertTrue(d.summary().contains("does not mean your problem is gone"), d.summary())
    }

    @Test
    fun a_failed_live_check_is_a_warning_even_with_no_rule() {
        val checks = listOf(
            SelfCheck.fail("market price", "no price for AKEUSDT — Bitunix unreachable"),
            SelfCheck.pass("engine", "off — idle"),
        )
        val d = SelfRepair.diagnose(provider(), engine(), checks = checks)
        assertTrue(d.findings.isEmpty())
        assertEquals("WARN", d.worst)
        assertTrue(d.summary().contains("1 failed"), d.summary())
    }

    @Test
    fun checks_without_a_complaint_still_show_what_ran() {
        val checks = listOf(SelfCheck.pass("provider HTTP", "GET /models → 12 models"))
        val d = SelfRepair.diagnose(provider(), engine(), checks = checks)
        assertTrue(d.summary().contains("provider HTTP"), d.summary())
        assertTrue(d.summary().contains("1 passed"), d.summary())
    }

    @Test
    fun the_exact_phrase_the_user_reported_triggers_the_diagnosis_path() {
        val text = "ever hit code review or something like that verify simplify it tells me no problem find"
        assertTrue(SelfRepair.asksForDiagnosis(text))
    }

    @Test
    fun a_bug_report_triggers_the_diagnosis_path() {
        assertTrue(SelfRepair.asksForDiagnosis("the app is not working, no issues found again"))
        assertTrue(SelfRepair.asksForDiagnosis("باگ داره کار نمیکنه"))
    }

    @Test
    fun a_feature_request_is_not_a_bug_report() {
        assertFalse(SelfRepair.asksForDiagnosis("i want to add a new strategy"))
        assertFalse(SelfRepair.asksForDiagnosis("please add a button"))
    }

    @Test
    fun normal_chat_is_not_hijacked() {
        assertFalse(SelfRepair.asksForDiagnosis("what is the btc price"))
        assertFalse(SelfRepair.asksForDiagnosis("set leverage 10"))
    }

    @Test
    fun health_questions_reach_the_checks() {
        assertTrue(SelfRepair.asksForDiagnosis("is everything ok?"))
        assertTrue(SelfRepair.asksForDiagnosis("is it working now"))
    }

    @Test
    fun a_market_question_is_not_a_bug_report() {
        // "what's wrong with BTC" is a market question: it must not be hijacked
        // into the self-debugger and answered with an app health report.
        assertFalse(SelfRepair.asksForDiagnosis("what's wrong with BTC today"))
        assertFalse(SelfRepair.asksForDiagnosis("whats wrong with eth"))
    }

    @Test
    fun app_questions_still_reach_the_checks() {
        assertTrue(SelfRepair.asksForDiagnosis("what's wrong with the app"))
        assertTrue(SelfRepair.asksForDiagnosis("what's wrong with my engine"))
        assertTrue(SelfRepair.asksForDiagnosis("what's wrong with app"))
    }

    @Test
    fun auto_fixes_are_applied_and_reported() {
        val d = SelfRepair.diagnose(provider(model = ""), engine())
        val applied = SelfRepair.applyAutoFixes(d) { action ->
            when (action) {
                "pick_model" -> "model set to gpt-4o-mini"
                else -> "did $action"
            }
        }
        assertTrue(applied.contains("gpt-4o-mini"), applied)
    }

    @Test
    fun critical_findings_are_never_auto_applied() {
        val d = SelfRepair.diagnose(provider(url = ""), engine(live = true))
        val applied = SelfRepair.applyAutoFixes(d) { "should not happen" }
        assertTrue(applied.contains("Nothing needed"), applied)
    }

    @Test
    fun findings_are_ordered_by_severity() {
        val d = SelfRepair.diagnose(provider(url = "", model = ""), engine(live = true))
        val severities = d.findings.map { it.severity }
        assertEquals("CRITICAL", severities.first())
    }

    @Test
    fun exchange_dns_failure_is_not_blamed_on_the_model() {
        val dns = "Bitunix unreachable (DNS) — check this device's internet connection; retrying in 30s"
        val d = SelfRepair.diagnose(provider(), engine(error = dns))

        val finding = d.findings.first { it.issue.startsWith("Exchange unreachable") }
        assertFalse(finding.autoFixable)
        assertTrue(finding.fix.contains("internet"))
        assertTrue(finding.fix.contains("no model change"))
        assertTrue(d.findings.none { it.issue.contains("Last provider call failed") })
    }

    @Test
    fun exchange_dns_failure_is_not_auto_probed() {
        val d = SelfRepair.diagnose(provider(), engine(error = "Bitunix unreachable (DNS) — check this device's internet connection"))
        val applied = SelfRepair.applyAutoFixes(d) { action -> "unexpected $action" }
        assertTrue(applied.contains("Nothing needed"), applied)
    }

    @Test
    fun provider_errors_keep_the_provider_finding() {
        val d = SelfRepair.diagnose(provider(error = "Error: HTTP 401"), engine())
        assertTrue(d.findings.any { it.issue.contains("Last provider call failed") })
        assertTrue(d.findings.none { it.issue.startsWith("Exchange unreachable") })
    }
}
