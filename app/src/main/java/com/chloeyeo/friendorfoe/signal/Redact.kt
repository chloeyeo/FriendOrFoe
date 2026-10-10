package com.chloeyeo.friendorfoe.signal

/**
 * SEC-03: logs never carry a full nonce. Any run of 16 or more hex digits in a line meant for the event log or
 * logcat is cut to its first 8 digits plus `…`; [nonce] gives the 8-hex prefix of a nonce for log lines.
 */
object Redact {
    private val LONG_HEX = Regex("[0-9a-fA-F]{16,}")

    fun hexRuns(text: String): String = LONG_HEX.replace(text) { it.value.take(8) + "…" }

    /** The 8-hex prefix of [nonce] plus `…`; `n/a` when the gadget did not report it. */
    fun nonce(nonce: ByteArray?): String = nonce?.let { Hex.encode(it).take(8) + "…" } ?: "n/a"

    /** A gadget line for a log row: hex runs cut, at most [max] characters. */
    fun line(text: String, max: Int = 80): String {
        val cut = hexRuns(text.trim())
        return if (cut.length <= max) cut else cut.take(max) + "…"
    }
}
