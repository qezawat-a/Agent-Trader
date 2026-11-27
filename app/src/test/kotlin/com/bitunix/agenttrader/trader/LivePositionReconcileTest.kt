package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The private feed pushes CHANGES, not state. A position closed during a
 * reconnect sends no removal, so the cache kept naming it as open and the chat
 * described it to the user. A REST snapshot must REPLACE the cache, not merge.
 */
class LivePositionReconcileTest {

    @Test
    fun a_rest_snapshot_drops_a_position_the_feed_left_behind() {
        val e = TraderEngine(BitunixRest("k", "s"))
        e.seedFeedPositions(listOf("TELUSDT", "VELVETUSDT"))

        e.reconcilePositions(JSONArray("""[{"symbol":"VELVETUSDT","qty":"1","side":"LONG"}]"""))

        assertEquals(listOf("VELVETUSDT"), e.feedPositions())
    }

    @Test
    fun an_empty_rest_snapshot_means_no_open_positions() {
        val e = TraderEngine(BitunixRest("k", "s"))
        e.seedFeedPositions(listOf("TELUSDT"))

        e.reconcilePositions(JSONArray())

        assertEquals(emptyList(), e.feedPositions())
    }
}
