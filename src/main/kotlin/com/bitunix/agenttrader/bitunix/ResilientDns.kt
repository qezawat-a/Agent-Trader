package com.bitunix.agenttrader.bitunix

import okhttp3.Dns
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS for the WebSocket that survives a flaky resolver (common behind a VPN):
 *  1. the system resolver, as before;
 *  2. on failure, DNS-over-HTTPS straight to resolver IPs (no DNS needed to
 *     reach them): Cloudflare 1.1.1.1, then Google 8.8.8.8;
 *  3. on failure, the addresses this host last resolved to in this session.
 * Every success refreshes the cache. The TLS certificate is still checked
 * against the host name, so a wrong address cannot impersonate Bitunix.
 */
class ResilientDns(
    private val system: Dns = Dns.SYSTEM,
    private val doh: (String) -> List<InetAddress> = ::dohLookup,
) : Dns {
    private val lastGood = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val first = runCatching { system.lookup(hostname) }
        first.getOrNull()?.takeIf { it.isNotEmpty() }?.let { lastGood[hostname] = it; return it }
        runCatching { doh(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            lastGood[hostname] = it
            return it
        }
        lastGood[hostname]?.let { return it }
        throw (first.exceptionOrNull() as? UnknownHostException)
            ?: UnknownHostException("Unable to resolve host \"$hostname\"")
    }

    companion object {
        private val ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query?type=A&name=",
            "https://8.8.8.8/resolve?type=A&name=",
        )

        fun dohLookup(hostname: String): List<InetAddress> {
            for (base in ENDPOINTS) {
                val ips = runCatching {
                    val conn = URL(base + URLEncoder.encode(hostname, "UTF-8")).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("Accept", "application/dns-json")
                    try {
                        conn.inputStream.bufferedReader().use { parseDohAnswer(it.readText()) }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull().orEmpty()
                if (ips.isNotEmpty()) {
                    return ips.mapNotNull { ip -> runCatching { InetAddress.getByAddress(hostname, ipv4Bytes(ip)) }.getOrNull() }
                }
            }
            return emptyList()
        }

        /** IPv4 addresses (type 1 records) from a DNS JSON answer. */
        internal fun parseDohAnswer(body: String): List<String> {
            val arr = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                o.optString("data").takeIf { o.optInt("type") == 1 && ipv4OrNull(it) != null }
            }
        }

        private fun ipv4OrNull(s: String): ByteArray? {
            val p = s.split('.')
            if (p.size != 4) return null
            val b = p.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            return ByteArray(4) { b[it].toByte() }
        }

        private fun ipv4Bytes(s: String): ByteArray = ipv4OrNull(s)!!
    }
}
