package com.chloeyeo.friendorfoe.signal

/**
 * Test double for Ihan's reader on the far side of the serial port. Answers `STATUS <id>` with
 * `reader/status.txt` (or [statusScript]) and each `QUERY <id>` with the next script in [queries], substituting
 * `@ID@`, `@SID@` and `@BOOT@`. Lines are fed back synchronously from inside [write], as the USB reader thread
 * would deliver them, each terminated by `\n` (non-JSON text by `\r\n`, as the firmware's `Serial.println`), and
 * split into [chunk]-byte pieces to exercise reassembly.
 */
class FakeReaderPort(
    private val link: ReaderLink,
    var bootId: String = "ae07effed99ac4d4",
    private val chunk: Int = 7,
) : ReaderLink.Port {
    val written = ArrayList<String>()

    /** One fixture (lines) per QUERY, in order; an empty queue means the reader stays silent. */
    val queries = ArrayDeque<List<String>>()

    /** What `STATUS <id>` gets; null means the reader ignores STATUS (still booting, not a reader). */
    var statusScript: List<String>? = Fixtures.lines("status.txt")

    /** The request id of the last QUERY written. */
    var lastQueryId: String? = null

    override fun write(bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        require(text.endsWith("\n") && text.count { it == '\n' } == 1) { "one LF-terminated line per write: '$text'" }
        require(bytes.size - 1 <= GadgetLineCodec.MAX_COMMAND_BYTES) { "command over 79 bytes: '$text'" }
        val line = text.removeSuffix("\n")
        written += line
        when {
            line.startsWith("STATUS ") -> statusScript?.let { feed(it, sid = line.removePrefix("STATUS ")) }
            line.startsWith("QUERY ") -> {
                val id = line.removePrefix("QUERY ")
                lastQueryId = id
                queries.removeFirstOrNull()?.let { feed(it, id = id) }
            }
        }
    }

    /** Feeds [lines] to the link as the reader would print them. */
    fun feed(lines: List<String>, id: String = lastQueryId ?: "", sid: String = "") {
        for (raw in lines) {
            val line = raw.replace("@ID@", id).replace("@SID@", sid).replace("@BOOT@", bootId)
            val bytes = (line + if (line.startsWith("{")) "\n" else "\r\n").toByteArray(Charsets.UTF_8)
            var i = 0
            while (i < bytes.size) {
                val n = minOf(chunk, bytes.size - i)
                link.feed(bytes, i, n)
                i += n
            }
        }
    }

    val queryLines: List<String> get() = written.filter { it.startsWith("QUERY ") }
}

/** The reader fixtures under `app/src/test/resources/reader/` (see each file's `#` header for its source). */
object Fixtures {
    fun lines(name: String): List<String> {
        val stream = Fixtures::class.java.getResourceAsStream("/reader/$name") ?: error("fixture reader/$name not found")
        return stream.bufferedReader(Charsets.US_ASCII).readLines().filterNot { it.startsWith("#") }
    }
}
