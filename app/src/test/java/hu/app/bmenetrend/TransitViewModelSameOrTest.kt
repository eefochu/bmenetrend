package hu.app.bmenetrend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TransitViewModelSameOrTest {

    @Test
    fun sameOr_whenListsAreEqual_returnsPreviousInstance() {
        val previous = listOf("alfa", "beta", "gamma")
        val current = listOf("alfa", "beta", "gamma")
        assertNotSame(previous, current)

        val result = current.sameOr(previous)
        assertSame(previous, result)
    }

    @Test
    fun sameOr_whenListsDifferInElements_returnsCurrentInstance() {
        val previous = listOf("alfa", "beta")
        val current = listOf("alfa", "delta")

        val result = current.sameOr(previous)
        assertSame(current, result)
        assertEquals(listOf("alfa", "delta"), result)
    }

    @Test
    fun sameOr_whenListsDifferInSize_returnsCurrentInstance() {
        val previous = listOf("alfa")
        val current = listOf("alfa", "beta")

        val result = current.sameOr(previous)
        assertSame(current, result)
    }

    @Test
    fun sameOr_bothEmpty_returnsPreviousInstance() {
        val previous = emptyList<String>()
        val current = listOf<String>()

        val result = current.sameOr(previous)
        assertSame(previous, result)
    }

    @Test
    fun sameOr_currentEmptyPreviousNonEmpty_returnsCurrentInstance() {
        val previous = listOf("elem")
        val current = emptyList<String>()

        val result = current.sameOr(previous)
        assertSame(current, result)
    }

    @Test
    fun sameOr_previousEmptyCurrentNonEmpty_returnsCurrentInstance() {
        val previous = emptyList<String>()
        val current = listOf("elem")

        val result = current.sameOr(previous)
        assertSame(current, result)
    }

    @Test
    fun sameOr_withDataObjects_returnsPreviousWhenEqual() {
        data class Sample(val id: Int, val name: String)

        val prevList = listOf(Sample(1, "A"), Sample(2, "B"))
        val currList = listOf(Sample(1, "A"), Sample(2, "B"))
        assertNotSame(prevList, currList)

        assertSame(prevList, currList.sameOr(prevList))

        val modifiedList = listOf(Sample(1, "A"), Sample(2, "C"))
        assertSame(modifiedList, modifiedList.sameOr(prevList))
    }
}
