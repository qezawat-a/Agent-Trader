package com.bitunix.agenttrader.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Reports which signing key the running app was built with.
 *
 * The project ships two APKs — `Agent-Trader-debug.apk` and
 * `Agent-Trader-release.apk` — signed by two DIFFERENT keys. Android only
 * allows an update to install over an app signed with the SAME key, so a user
 * who installed the release build cannot update with the debug build (and the
 * only thing left to do is uninstall, which wipes the app data).
 *
 * The phone can answer this question itself: this object prints the SHA-256 of
 * the certificate the installed copy was signed with, plus the matching APK
 * file name to download.
 */
object AppIdentity {

    /** Fingerprint of the committed debug keystore (`CN=Android Debug`). */
    const val DEBUG_KEY_PREFIX = "A9:E3:FE:09"

    /** Fingerprint of the CI release keystore (`CN=Agent Trader`). */
    const val RELEASE_KEY_PREFIX = "98:AD:AD:A7"

    /** SHA-256 of the APK's signing certificate, uppercase, colon separated. */
    fun certFingerprint(context: Context): String {
        return try {
            val pm = context.packageManager
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo
                val signer = if (signing != null && signing.hasMultipleSigners()) {
                    signing.apkContentsSigners.firstOrNull()
                } else {
                    signing?.signingCertificateHistory?.firstOrNull()
                }
                signer?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
            }
            if (bytes == null) "unknown" else sha256(bytes)
        } catch (e: Exception) {
            "unknown (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /** Which APK file must be downloaded to update THIS installed copy. */
    fun keyLabel(fingerprint: String): String = when {
        fingerprint.startsWith(DEBUG_KEY_PREFIX) ->
            "DEBUG key (CN=Android Debug) — download Agent-Trader-debug.apk"
        fingerprint.startsWith(RELEASE_KEY_PREFIX) ->
            "RELEASE key (CN=Agent Trader) — download Agent-Trader-release.apk"
        fingerprint == "unknown" || fingerprint.startsWith("unknown (") ->
            "unknown key — try either APK; the one that matches installs, the other is rejected"
        else -> "unknown key ($fingerprint) — try either APK"
    }

    /** Version name + code of the installed copy. */
    @Suppress("DEPRECATION") // versionCode is only read below API 28
    fun versionInfo(context: Context): String = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} (versionCode $code)"
    } catch (e: Exception) {
        "unknown (${e.message ?: e.javaClass.simpleName})"
    }

    /** Full one-screen answer for the user asking "why won't it update?". */
    fun describe(context: Context): String {
        val fp = certFingerprint(context)
        return buildString {
            appendLine("App: ${context.packageName} ${versionInfo(context)}")
            appendLine("Signing key SHA-256: $fp")
            appendLine(keyLabel(fp))
            appendLine()
            appendLine("Updates only install over the SAME key. If the installer says")
            appendLine("\"App not installed\" / INSTALL_FAILED_UPDATE_INCOMPATIBLE, you picked")
            appendLine("the wrong file — use the one named above. Uninstalling is NOT needed")
            appendLine("while a matching file exists, and it would wipe your settings.")
        }.trim()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}
