package hu.app.bmenetrend.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OtaDownloadTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun repository(content: String, length: Long = content.length.toLong()): OtaRepository {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object: ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun source() = Buffer().writeUtf8(content)
                }).build()
        }.build()
        return OtaRepository(ApplicationProvider.getApplicationContext<Application>(),client)
    }
    @Test fun completedDownloadReplacesCacheOnlyAfterAllBytesArrive() = runBlocking {
        val directory=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,"complete").apply { mkdirs() }
        val target=File(directory,"update.apk").apply { writeText("old complete file") }
        val progress=mutableListOf<Float>()
        val result=repository("complete new bytes").downloadApk("https://github.com/eefochu/bmenetrend/releases/download/v1/app-release.apk",target) { progress+=it }
        assertEquals(target,result)
        assertEquals("complete new bytes",target.readText())
        assertEquals(1f,progress.last())
        assertTrue(directory.listFiles()!!.none { it.extension=="part" })
    }
    @Test fun truncatedDownloadDoesNotOverwriteACompleteFileOrLeaveAPartialApk() = runBlocking {
        val directory=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,"truncated").apply { mkdirs() }
        val target=File(directory,"update.apk").apply { writeText("previous complete file") }
        try {
            repository("short",100).downloadApk("https://github.com/eefochu/bmenetrend/releases/download/v1/app-debug.apk",target) {}
            fail("Truncated APK must fail")
        } catch (_: IOException) { }
        assertEquals("previous complete file",target.readText())
        assertTrue(directory.listFiles()!!.none { it.extension=="part" })
    }
}
