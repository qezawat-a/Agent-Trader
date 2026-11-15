package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The liquidation buffer used to default to 5%, and the app wrote that value
 * into the settings on every chat message. So an install that never chose a
 * buffer still carried an inherited 5 that silently capped the leverage —
 * 75x became 17x. The default went to 0, and this is the rule that makes the
 * inherited value stop counting.
 */
class LiqBufferMigrationTest {

    private val legacy = 5f

    @Test
    fun a_fresh_install_has_no_buffer() {
        assertEquals(0f, effectiveLiqBuffer(stored = null, chosen = false, legacyDefault = legacy))
    }

    @Test
    fun the_inherited_legacy_default_stops_counting() {
        // v2.4.8 persisted 5 without the user ever asking for it.
        assertEquals(0f, effectiveLiqBuffer(stored = 5f, chosen = false, legacyDefault = legacy))
    }

    @Test
    fun a_value_the_user_did_choose_stands() {
        // v2.4.8 also persisted whatever the user typed — that was their choice.
        assertEquals(2f, effectiveLiqBuffer(stored = 2f, chosen = false, legacyDefault = legacy))
        assertEquals(1.5f, effectiveLiqBuffer(stored = 1.5f, chosen = false, legacyDefault = legacy))
    }

    @Test
    fun an_explicit_choice_beats_the_legacy_default() {
        // The user typed 5 themselves after the upgrade — that is their call.
        assertEquals(5f, effectiveLiqBuffer(stored = 5f, chosen = true, legacyDefault = legacy))
        assertEquals(0f, effectiveLiqBuffer(stored = 0f, chosen = true, legacyDefault = legacy))
    }

    @Test
    fun a_chosen_value_survives_a_missing_store() {
        assertEquals(0f, effectiveLiqBuffer(stored = null, chosen = true, legacyDefault = legacy))
    }
}
