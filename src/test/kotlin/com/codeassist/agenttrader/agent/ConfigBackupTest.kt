package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backup exists because Android wipes app data when an update cannot be
 * installed over the old app (different signing key) and the user has to
 * uninstall first. The round trip has to keep every value type intact.
 */
class ConfigBackupTest {

    @Test
    fun round_trip_keeps_every_value_type() {
        val map = mapOf(
            "base_url" to "https://api.example.com/v1",
            "api_key" to "sk-test",
            "leverage" to 5,
            "live" to true,
            "auto_min_volume_usd" to 5_000_000.0,
            "tp_r" to 2.0,
            "universe" to "BTCUSDT,ETHUSDT",
        )
        val decoded = ConfigBackup.decode(ConfigBackup.encode(map))
        assertEquals("https://api.example.com/v1", decoded["base_url"])
        assertEquals("sk-test", decoded["api_key"])
        assertEquals(5.0, decoded["leverage"])
        assertEquals(true, decoded["live"])
        assertEquals(5_000_000.0, decoded["auto_min_volume_usd"])
        assertEquals(2.0, decoded["tp_r"])
        assertEquals("BTCUSDT,ETHUSDT", decoded["universe"])
    }

    @Test
    fun a_backup_holds_the_keys_the_user_would_otherwise_lose() {
        val map = mapOf("api_key" to "sk-live", "bitunix_secret" to "secret")
        val decoded = ConfigBackup.decode(ConfigBackup.encode(map))
        assertEquals("sk-live", decoded["api_key"])
        assertEquals("secret", decoded["bitunix_secret"])
        assertTrue(decoded.containsKey("bitunix_secret"))
    }
}
