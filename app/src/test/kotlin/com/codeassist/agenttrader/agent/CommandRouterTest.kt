package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertTrue

/** Trader commands typed in chat must reach the ENGINE, never the LLM. */
class CommandRouterTest {

    @Test
    fun scanner_commands_go_to_the_engine_not_the_llm() {
        assertTrue(CommandRouter.parse("set universe INITUSDT,KGENUSDT") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set symbols INITUSDT") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set timeframes 3m,15m,1h") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set scan top 10") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set min agree 4") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set min tf 60") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set min conf 70") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set leverage 20") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set universe BTCUSDT, ETHUSDT") is CommandRouter.Parsed.Trader)
    }

    @Test
    fun plain_talk_still_goes_to_the_brain() {
        assertTrue(CommandRouter.parse("hi, why is there no signal?") is CommandRouter.Parsed.Chat)
        assertTrue(CommandRouter.parse("/help") is CommandRouter.Parsed.Agent)
    }
}
