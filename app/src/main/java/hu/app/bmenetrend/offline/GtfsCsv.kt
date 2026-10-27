package hu.app.bmenetrend.offline

import java.io.Reader

/** Streaming RFC 4180 reader with block reads and indexed rows, avoiding a map per record. */
internal class GtfsCsv(reader: Reader) : AutoCloseable {
    private val input = reader
    private val buffer = CharArray(64 * 1024)
    private var position = 0
    private var limit = 0
    private var pushed = -1
    private var ended = false
    private var width = 16
    private val field = StringBuilder(128)
    val headers = (row() ?: error("Empty GTFS file")).map { it.trim().removePrefix("\uFEFF") }
    private val indices = headers.withIndex().associate { it.value to it.index }
    init { width = headers.size; require(indices.size == headers.size) { "Duplicate GTFS columns" } }

    fun next(): Map<String, String>? {
        var values = row() ?: return null
        while (values.size == 1 && values[0].isBlank()) values = row() ?: return null
        require(values.size == headers.size) { "Malformed GTFS CSV row" }
        return IndexedRow(indices, values)
    }

    private class IndexedRow(private val indices: Map<String, Int>, private val fields: List<String>) : AbstractMap<String, String>() {
        override fun get(key: String): String? = indices[key]?.let { fields[it] }
        override fun containsKey(key: String) = indices.containsKey(key)
        override val size get() = fields.size
        override val entries: Set<Map.Entry<String, String>> get() = indices.map { (key, index) ->
            java.util.AbstractMap.SimpleImmutableEntry(key, fields[index])
        }.toSet()
    }

    private fun readChar(): Int {
        if (pushed >= 0) return pushed.also { pushed = -1 }
        if (position >= limit) {
            do { limit = input.read(buffer, 0, buffer.size) } while (limit == 0)
            position = 0
            if (limit < 0) return -1
        }
        return buffer[position++].code
    }

    private fun row(): List<String>? {
        if (ended) return null
        val values = ArrayList<String>(width)
        field.setLength(0)
        var quoted = false
        var any = false
        while (true) {
            val code = readChar()
            if (code == -1) {
                ended = true
                require(!quoted) { "Unterminated GTFS field" }
                if (!any && values.isEmpty() && field.isEmpty()) return null
                values += field.toString(); return values
            }
            any = true
            val c = code.toChar()
            if (c == '"') {
                if (quoted) {
                    val next = readChar()
                    if (next == '"'.code) field.append('"') else { quoted = false; pushed = next }
                } else { require(field.isEmpty()); quoted = true }
            } else if (!quoted && c == ',') { values += field.toString(); field.setLength(0) }
            else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r') { val next = readChar(); if (next != '\n'.code) pushed = next }
                values += field.toString(); return values
            } else field.append(c)
            require(field.length < 1_000_000) { "Oversized GTFS field" }
        }
    }
    override fun close() = input.close()
}
