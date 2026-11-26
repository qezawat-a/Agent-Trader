package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The authority model: reads are free, settings need the user to have asked,
 * money/risk/lifecycle always need the one-tap confirmation, and anything the
 * engine cannot do is denied instead of reported as done.
 */
class ToolPolicyTest {

    @Test
    fun read_tools_are_always_allowed_even_without_a_user_message() {
        listOf("status", "balance", "positions", "pnl", "funding", "signals", "scan once").forEach {
            assertEquals(ToolDecision.Allow, ToolPolicy.authorize(it, ""), it)
        }
    }

    @Test
    fun settings_change_is_allowed_when_the_user_asked_for_it() {
        assertEquals(
            ToolDecision.Allow,
            ToolPolicy.authorize("set timeframes 15m,1h", "use the 15m and 1h timeframes please"),
        )
        assertEquals(
            ToolDecision.Allow,
            ToolPolicy.authorize("set universe BTCUSDT", "add BTCUSDT to the universe"),
        )
    }

    @Test
    fun settings_change_without_the_user_asking_needs_a_confirmation() {
        val decision = ToolPolicy.authorize("set timeframes 1m", "what do you think about bitcoin today")
        assertTrue(decision is ToolDecision.Confirm, "$decision")
    }

    @Test
    fun risk_money_and_lifecycle_always_need_a_confirmation() {
        // Even when the user's message seems to ask for all of it: the prompt is
        // advice, the gate is the rule.
        val userSaidEverything = "close my BTC position, set leverage 20 and start trading"
        listOf(
            "close position BTCUSDT",
            "close all",
            "manual entry BTCUSDT long 10",
            "cancel orders",
            "set leverage 20",
            "set risk 50",
            "set sl 2",
            "start trading",
            "stop trading",
            "engine on",
            "engine off",
            "set live on",
        ).forEach {
            assertTrue(ToolPolicy.authorize(it, userSaidEverything) is ToolDecision.Confirm, it)
        }
    }

    @Test
    fun commands_the_engine_does_not_have_are_denied() {
        val decision = ToolPolicy.authorize("install malware", "please") as ToolDecision.Deny
        assertTrue(decision.reason.contains("nothing was executed"))
    }

    @Test
    fun unknown_commands_fall_back_to_money_level_authority() {
        assertEquals(ToolCapability.MONEY, ToolPolicy.capabilityOf("some unknown command"))
    }

    @Test
    fun longest_command_wins_when_prefixes_overlap() {
        assertEquals(ToolCapability.RISK, ToolPolicy.capabilityOf("set tp r 3"))
        assertEquals(ToolCapability.RISK, ToolPolicy.capabilityOf("set tp 2.5"))
        assertEquals(ToolCapability.READ, ToolPolicy.capabilityOf("positions history"))
        assertEquals(ToolCapability.READ, ToolPolicy.capabilityOf("positions"))
        assertEquals(ToolCapability.SETTINGS, ToolPolicy.capabilityOf("set timeframes 1M"))
    }

    @Test
    fun the_prompt_tool_list_contains_only_real_engine_commands() {
        // A tool name in the prompt that the engine cannot run would make the
        // model emit calls that either never execute or get denied.
        assertTrue(AgentBrain.TOOLS.contains("set tp r"))
        AgentBrain.TOOLS.forEach { assertTrue(ToolPolicy.isKnown(it), it) }
    }

    @Test
    fun every_spec_is_paired_with_keywords_only_where_they_make_sense() {
        ToolPolicy.specs.forEach { spec ->
            if (spec.capability == ToolCapability.SETTINGS) {
                assertTrue(spec.keywords.isNotEmpty(), "${spec.command} would never be auto-allowed")
            } else {
                assertTrue(spec.keywords.isEmpty(), "${spec.command} does not need keywords")
            }
        }
    }
}
