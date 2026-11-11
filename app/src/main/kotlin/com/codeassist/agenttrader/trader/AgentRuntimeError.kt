package com.codeassist.agenttrader.trader

/** Holds the most recent runtime error so /autopsy and /diagnose can inspect it. */
object AgentRuntimeError {
    @Volatile
    var last: String = ""
}
