package dev.r1ptt.herdr

import java.net.URI
import java.net.URLDecoder

/**
 * A scanned `herdr://pair?u=<bridge url>&c=<one-time code>` from `herdr-bridge enroll --qr`, with
 * the scopes (`s`) and expiry (`x`) newer bridges add so the R1 can say what pairing grants.
 */
data class PairingLink(val url: String, val code: String, val scopes: List<String> = emptyList(), val expiresAt: String? = null) {
    companion object {
        /** Null for anything that isn't a Herdr pairing link with an http(s) bridge and a code. */
        fun parse(text: String): PairingLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "herdr" || uri.host != "pair") return null
            val query = uri.rawQuery?.split('&')?.associate { part ->
                part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.orEmpty()
            val url = query["u"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
            val code = query["c"]?.takeIf { it.isNotBlank() } ?: return null
            return PairingLink(url, code, query["s"]?.split(',')?.filter(String::isNotBlank).orEmpty(), query["x"]?.takeIf(String::isNotBlank))
        }
    }
}
