package hu.app.bmenetrend.map

/** Per-scene spatial buckets. Primitive keys avoid boxing on every neighbour probe. */
internal class LongCellMap<V : Any>(expectedSize: Int) {
    private val keys: LongArray
    private val values: Array<Any?>
    private val mask: Int

    init {
        var capacity = 16
        val required = expectedSize.toLong() * 2
        while (capacity < required && capacity < (1 shl 29)) capacity = capacity shl 1
        keys = LongArray(capacity)
        values = arrayOfNulls(capacity)
        mask = capacity - 1
    }

    private fun index(key: Long): Int {
        var mixed = key
        mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
        mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
        return (mixed xor (mixed ushr 33)).toInt() and mask
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        var slot = index(key)
        while (true) {
            val value = values[slot] ?: return null
            if (keys[slot] == key) return value as V
            slot = (slot + 1) and mask
        }
    }

    operator fun set(key: Long, value: V) {
        var slot = index(key)
        while (values[slot] != null && keys[slot] != key) slot = (slot + 1) and mask
        keys[slot] = key
        values[slot] = value
    }
}
