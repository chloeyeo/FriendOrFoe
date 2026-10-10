package com.chloeyeo.friendorfoe.signal

/** Lower-case hex <-> bytes, as the gadget writes the nonce (`"nonce":"<32 hex>"` for 16 bytes). */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /** Accepts upper or lower case; throws [IllegalArgumentException] on odd length or a non-hex character. */
    fun decode(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0) { "hex string has odd length ${s.length}" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = digit(s[2 * i])
            val lo = digit(s[2 * i + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
