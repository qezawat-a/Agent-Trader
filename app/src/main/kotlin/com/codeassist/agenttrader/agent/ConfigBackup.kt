package com.codeassist.agenttrader.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Saves and restores the user's settings — provider, model, Bitunix keys, engine
 * configuration — as one JSON file.
 *
 * Why this exists: Android wipes app data when an update cannot be installed
 * over the existing app (a different signing key) and the user has to
 * uninstall first. Without a backup that costs the user their API key, their
 * exchange keys and every setting they tuned. With it, a reinstall takes one
 * command.
 *
 * The file lives in the app's own external folder (Android/data/<pkg>/files),
 * which only this app can read on Android 11+ and which survives an uninstall.
 */
object ConfigBackup {

    const val FILE_NAME = "agent-trader-backup.json"

    /** Flattens the settings into a JSON string (pure — unit tested). */
    fun encode(settings: Map<String, Any?>): String {
        val json = JSONObject()
        for ((k, v) in settings) {
            when (v) {
                null -> json.put(k, JSONObject.NULL)
                is Boolean -> json.put(k, v)
                is Int -> json.put(k, v)
                is Long -> json.put(k, v)
                is Float -> json.put(k, v.toDouble())
                is Double -> json.put(k, v)
                else -> json.put(k, v.toString())
            }
        }
        return json.toString()
    }

    /**
     * Reads a backup string back into settings; unknown keys are ignored.
     *
     * JSON has one number type, so every number comes back as a Double — the
     * importer converts it to the type each setting expects.
     */
    fun decode(text: String): Map<String, Any?> {
        val json = JSONObject(text)
        val out = linkedMapOf<String, Any?>()
        for (key in json.keys()) {
            val v = json.get(key)
            out[key] = when {
                v === JSONObject.NULL -> null
                v is Number -> v.toDouble()
                else -> v
            }
        }
        return out
    }

    /** Writes the backup file and returns its path. */
    fun write(context: Context, settings: Map<String, Any?>): String {
        val file = file(context)
        file.writeText(encode(settings))
        return file.absolutePath
    }

    /** Reads the backup file, or null when there is none / it is broken. */
    fun read(context: Context): Map<String, Any?>? = try {
        val file = file(context)
        if (file.exists()) decode(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Where the backup lives on this device. */
    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }
}
