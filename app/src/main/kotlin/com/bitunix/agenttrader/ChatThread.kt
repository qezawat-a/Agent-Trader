package com.bitunix.agenttrader

import org.json.JSONArray
import org.json.JSONObject

/** One chat bubble: text, isUser and the HH:mm time it was sent. */
data class ChatMsg(val text: String, val isUser: Boolean, val time: String)

/**
 * The chat thread is kept in private app preferences so the thread still exists
 * when the app is opened again: Android kills the process of an app left in the
 * background after a while, and everything that lived in memory — the bubbles
 * and the model's memory of them — reset then.
 */
object ChatThread {

    /** Only the most recent bubbles are worth keeping. */
    const val MAX_THREAD = 40

    fun toJson(messages: List<ChatMsg>): String =
        JSONArray().also { arr -> messages.forEach { arr.put(toOne(it)) } }.toString()

    fun fromJson(json: String): List<ChatMsg> {
        if (json.isBlank()) return emptyList()
        // A corrupt or absent pref is just an empty thread.
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { o -> ChatMsg(o.optString("text"), o.optBoolean("user"), o.optString("time")) }
        }.getOrDefault(emptyList())
    }

    private fun toOne(m: ChatMsg): JSONObject =
        JSONObject().put("text", m.text).put("user", m.isUser).put("time", m.time)
}
