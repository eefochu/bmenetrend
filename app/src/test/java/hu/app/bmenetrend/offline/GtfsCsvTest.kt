package hu.app.bmenetrend.offline
import org.junit.Assert.*
import org.junit.Test

class GtfsCsvTest {
    @Test fun parsesQuotedCommasEscapesNewlinesAndBom() {
        GtfsCsv("\uFEFFid,name\r\n1,\"First, \"\"quoted\"\"\nline\"\r\n2,\r\n".reader()).use { csv ->
            assertEquals(mapOf("id" to "1", "name" to "First, \"quoted\"\nline"), csv.next())
            assertEquals(mapOf("id" to "2", "name" to ""), csv.next())
            assertNull(csv.next())
        }
    }
    @Test fun rejectsIncompleteQuotedRecord() {
        try { GtfsCsv("id,name\n1,\"broken".reader()).use { it.next() }; fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun handlesHoursBeyondMidnightAndRejectsInvalidTime() {
        assertEquals(90300, GtfsImporter.seconds("25:05:00"))
        assertNull(GtfsImporter.seconds("12:99:00"))
        assertNull(GtfsImporter.seconds(""))
    }
    @Test fun handlesQuotesAndNewlinesAcrossTheReadBufferBoundary() {
        val name = "x".repeat(65_520) + "\"quoted\"\r\nnext line"
        val escaped = name.replace("\"", "\"\"")
        GtfsCsv("id,name\r\n1,\"$escaped\"\r\n2,end".reader()).use { csv ->
            val first = csv.next()!!
            assertEquals("end", csv.next()!!["name"])
            assertEquals(name, first["name"])
            assertEquals("1", first["id"])
        }
    }

}
