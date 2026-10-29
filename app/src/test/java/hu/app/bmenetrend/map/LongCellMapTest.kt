package hu.app.bmenetrend.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LongCellMapTest {

    @Test
    fun zeroKeySupportedCorrectly() {
        val map = LongCellMap<String>(16)
        assertNull("Empty map must return null for 0L", map[0L])

        map[0L] = "zero_val"
        assertEquals("zero_val", map[0L])

        // Update value at 0L
        map[0L] = "zero_updated"
        assertEquals("zero_updated", map[0L])

        // Add other keys and verify 0L remains intact
        map[1L] = "one"
        map[-1L] = "minus_one"
        assertEquals("zero_updated", map[0L])
        assertEquals("one", map[1L])
        assertEquals("minus_one", map[-1L])
    }

    @Test
    fun negativeKeysSupportedCorrectly() {
        val map = LongCellMap<String>(32)
        val negativeKeys = listOf(-1L, -2L, -100L, -9999999999L, Long.MIN_VALUE, Long.MIN_VALUE + 1L)

        for (k in negativeKeys) {
            assertNull("Unset negative key $k should be null", map[k])
            map[k] = "val_$k"
        }

        for (k in negativeKeys) {
            assertEquals("val_$k", map[k])
        }

        // Overwrite
        for (k in negativeKeys) {
            map[k] = "new_$k"
            assertEquals("new_$k", map[k])
        }
    }

    @Test
    fun hashCollisionResolutionWithLinearProbing() {
        // Small capacity map (expectedSize = 4 -> capacity 16, mask 15)
        val map = LongCellMap<String>(4)

        // Find keys that hash to the exact same initial slot
        // LongCellMap hashing:
        // mixed = key
        // mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
        // mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
        // return (mixed xor (mixed ushr 33)).toInt() and mask
        fun hashIndex(key: Long, mask: Int): Int {
            var mixed = key
            mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
            mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
            return (mixed xor (mixed ushr 33)).toInt() and mask
        }

        val mask = 15
        val collisions = mutableListOf<Long>()
        var probeKey = 0L
        val targetSlot = hashIndex(0L, mask)

        while (collisions.size < 4 && probeKey < 100_000L) {
            if (hashIndex(probeKey, mask) == targetSlot) {
                collisions.add(probeKey)
            }
            probeKey++
        }

        assertTrue("Should find at least 3 colliding keys", collisions.size >= 3)

        // Insert colliding keys
        for (k in collisions) {
            map[k] = "coll_$k"
        }

        // Verify all colliding keys can be retrieved accurately
        for (k in collisions) {
            assertEquals("coll_$k", map[k])
        }

        // Update one colliding key
        val firstKey = collisions.first()
        map[firstKey] = "updated_$firstKey"
        assertEquals("updated_$firstKey", map[firstKey])
        for (k in collisions.drop(1)) {
            assertEquals("coll_$k", map[k])
        }

        // Non-existent key in the same collision slot
        val absentKey = -99999L
        assertNull(map[absentKey])
    }

    @Test
    fun spatialCellKeyEncodingWithNegativeGridCells() {
        fun cellKey(y: Int, x: Int) = (y.toLong() shl 32) xor (x.toLong() and 0xffffffffL)

        val map = LongCellMap<String>(64)
        val cells = listOf(
            0 to 0,
            -1 to -1,
            -10 to 10,
            10 to -10,
            -1000 to -2000,
            Int.MIN_VALUE to Int.MAX_VALUE,
            -1 to 0,
            0 to -1
        )

        for ((y, x) in cells) {
            val key = cellKey(y, x)
            map[key] = "cell_${y}_${x}"
        }

        for ((y, x) in cells) {
            val key = cellKey(y, x)
            assertEquals("cell_${y}_${x}", map[key])
        }
    }

    @Test
    fun highVolumeConsistencyAgainstReferenceMap() {
        val count = 2000
        val rnd = Random(12345L)
        val reference = mutableMapOf<Long, String>()
        val map = LongCellMap<String>(count)

        for (i in 0 until count) {
            val key = when (i % 5) {
                0 -> 0L
                1 -> -rnd.nextLong(1, 1_000_000_000L)
                2 -> rnd.nextLong(1, 1_000_000_000L)
                3 -> -rnd.nextLong(1, Long.MAX_VALUE)
                else -> rnd.nextLong()
            }
            val value = "val_$i"
            reference[key] = value
            map[key] = value
        }

        for ((k, v) in reference) {
            assertEquals("Value for key $k must match reference", v, map[k])
        }

        // Verify non-existent keys return null
        for (i in 0 until 100) {
            val nonExistentKey = rnd.nextLong()
            if (!reference.containsKey(nonExistentKey)) {
                assertNull(map[nonExistentKey])
            }
        }
    }
}
