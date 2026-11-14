package com.codeassist.agenttrader.trader

/**
 * Holds the most recent LLM-provider failure so /autopsy and /diagnose can
 * inspect it.
 *
 * Exchange/engine failures (Bitunix REST, WebSocket, trading loop) must go to
 * [EngineRuntimeError] instead — otherwise a DNS outage is reported as
 * "last provider call failed" and the repair flow suggests switching models.
 */
object AgentRuntimeError {
    @Volatile
    var last: String = ""
}
