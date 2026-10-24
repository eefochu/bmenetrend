package hu.app.bmenetrend

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun reportCanBeReadAfterRestartAndRemovedAfterDismissal() {
        val file = File(temporary.root,"crash.txt")
        CrashReports(file).record(Thread.currentThread(),IllegalStateException("planner rendering failed"),"test / API 30")
        val reopened = CrashReports(file)
        assertTrue(reopened.read()!!.contains("planner rendering failed"))
        assertTrue(reopened.read()!!.contains("API 30"))
        reopened.clear()
        assertNull(reopened.read())
    }
    @Test fun handlerAlwaysForwardsTheFailureToAndroidsOriginalHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded: Throwable? = null
        val error = IllegalStateException("source failure")
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, failure -> forwarded=failure }
            // A directory cannot be opened as a report file; capture failure must not hide the crash.
            CrashReports(temporary.root).install("test")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(),error)
            assertSame(error,forwarded)
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test fun reportReadIsBoundedSoAHugeStackCannotFillTheStartupDialog() {
        val file=temporary.newFile()
        file.writeText("x".repeat(100_000))
        assertEquals(64_000,CrashReports(file).read()!!.length)
    }
}
