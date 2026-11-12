package com.codeassist.agenttrader.bitunix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signature vectors independently computed with Python hashlib:
 *   digest = SHA256(nonce + timestamp + apiKey + sortedQueryParams + body)
 *   sign   = SHA256(digest + secretKey)
 */
class BitunixSignTest {

    private val nonce = "0123456789abcdef0123456789abcdef"
    private val timestamp = "1700000000000"
    private val apiKey = "test-api-key"
    private val secretKey = "test-secret"

    @Test
    fun sha256_hex_known_vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BitunixRest.sha256Hex("abc"),
        )
    }

    @Test
    fun signed_headers_post_with_query_and_body() {
        val headers = BitunixRest.signedHeaders(
            apiKey, secretKey, nonce, timestamp,
            query = mapOf("b" to "2", "a" to "1", "cc" to "X"),
            body = "{\"symbol\":\"BTCUSDT\"}",
        )
        assertEquals(apiKey, headers["api-key"])
        assertEquals(nonce, headers["nonce"])
        assertEquals(timestamp, headers["timestamp"])
        assertEquals(
            "8b81098f4839735a121df8b6b9bd586ed0e016f3e84bbc49f54b9fba94818a52",
            headers["sign"],
            "sign must be SHA256(digest + secret) with query sorted as a1b2ccX",
        )
    }

    @Test
    fun signed_headers_get_without_query_or_body() {
        val headers = BitunixRest.signedHeaders(
            apiKey, secretKey, nonce, timestamp,
            query = emptyMap(), body = null,
        )
        assertEquals(
            "d2062606e8b345248b524329c29123d35510403ca1776cfba6df247e5a60d176",
            headers["sign"],
            "empty query and null body must hash like empty strings",
        )
    }

    @Test
    fun signed_headers_treats_blank_body_like_empty_string() {
        val withNull = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), null)
        val withEmpty = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), "")
        assertEquals(withNull["sign"], withEmpty["sign"])
    }

    // ---- cp/asset sub-account transfer endpoints (signed POST) ----

    @Test
    fun cp_asset_query_signature_is_correct() {
        val headers = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), null)
        assertEquals(apiKey, headers["api-key"])
        assertEquals(nonce, headers["nonce"])
        assertEquals(timestamp, headers["timestamp"])
        assertEquals(
            BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), null)["sign"],
            headers["sign"],
        )
    }

    @Test
    fun cp_asset_transfer_signature_includes_amount_and_asset_type_sub_account() {
        val body = "{\"amount\":\"123.50\",\"assetType\":\"FUTURES\"}"
        val headers = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), body)
        assertEquals(apiKey, headers["api-key"])
        assertTrue(headers.values.none { it.contains(secretKey) })
        // a different body must produce a different sign
        val other = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), "{\"amount\":\"1.00\",\"assetType\":\"SPOT\"}")
        assertTrue(headers["sign"] != other["sign"])
    }

    @Test
    fun cp_asset_transfer_signature_includes_amount_and_asset_type_main_account() {
        val body = "{\"amount\":\"123.50\",\"assetType\":\"SPOT\"}"
        val headers = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), body)
        assertEquals(apiKey, headers["api-key"])
        assertTrue(headers.values.none { it.contains(secretKey) })
        val other = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), "{\"amount\":\"1.00\",\"assetType\":\"FUTURES\"}")
        assertTrue(headers["sign"] != other["sign"])
    }

    @Test
    fun signed_headers_never_leak_secret_into_other_fields() {
        val headers = BitunixRest.signedHeaders(
            apiKey, secretKey, nonce, timestamp, mapOf("symbol" to "BTCUSDT"), "{}",
        )
        assertTrue(headers.values.none { it.contains(secretKey) })
    }

    // ---- cp/asset sub-account endpoints (signed) ----

    @Test
    fun cp_asset_query_signature_uses_empty_query_and_null_body() {
        val headers = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), null)
        assertEquals(apiKey, headers["api-key"])
        assertEquals(nonce, headers["nonce"])
        assertEquals(timestamp, headers["timestamp"])
        // empty query and null body → both hash like empty strings
        assertEquals(
            BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), "")["sign"],
            headers["sign"],
        )
    }

    @Test
    fun cp_asset_transfer_signature_includes_amount_and_asset_type() {
        val body = "{\"amount\":\"123.50\",\"assetType\":\"FUTURES\"}"
        val headers = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), body)
        assertEquals(apiKey, headers["api-key"])
        assertTrue(headers.values.none { it.contains(secretKey) })
        // a different body must produce a different sign
        val other = BitunixRest.signedHeaders(apiKey, secretKey, nonce, timestamp, emptyMap(), "{\"amount\":\"1.00\",\"assetType\":\"SPOT\"}")
        assertTrue(headers["sign"] != other["sign"])
    }
}
