package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
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
    ) = SelfRepair.EngineState(live, engineOn, trading, loop, feed, keys, positions, leverage)

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
        assertTrue(d.summary().contains("No problems"))
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
}
