package com.codeassist.agenttrader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chat thread is what survives a process kill: it round-trips and a corrupt pref is just empty. */
class ChatThreadTest {

    @Test
    fun thread_round_trips_through_json() {
        val msgs = listOf(
            ChatMsg("what is the price of BTC?", true, "10:25"),
            ChatMsg("~40,120 USD", false, "10:25"),
            ChatMsg("and the 1h close?", true, "10:26"),
        )
        assertEquals(msgs, ChatThread.fromJson(ChatThread.toJson(msgs)))
    }

    @Test
    fun empty_and_corrupt_json_are_an_empty_thread() {
        assertTrue(ChatThread.fromJson("").isEmpty())
        assertTrue(ChatThread.fromJson("   ").isEmpty())
        assertTrue(ChatThread.fromJson("not json").isEmpty())
    }
}
