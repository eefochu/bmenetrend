package hu.app.bmenetrend.mav

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MavCacheStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheFile: File
    private lateinit var cacheStore: MavCacheStore

    @Before
    fun setup() {
        cacheFile = File(tempFolder.root, "test_mav_cache.json")
        cacheStore = MavCacheStore(cacheFile)
    }

    @Test
    fun testEmptyCacheReturnsNull() {
        assertNull(cacheStore.getOrders())
        assertEquals(0L, cacheStore.getLastSyncTime())
        assertNull(cacheStore.getTicketImage("receipt_123"))
        assertNull(cacheStore.getPassHolder("ph_123"))
        assertNull(cacheStore.getUser())
    }

    @Test
    fun testSaveAndGetUser() {
        val user = MavUser(
            email = "test@example.com",
            firstName = "János",
            lastName = "Kovács",
            regId = "12345",
            photoBase64 = "base64_photo_data"
        )
        cacheStore.saveUser(user)

        val cached = cacheStore.getUser()
        assertNotNull(cached)
        assertEquals("test@example.com", cached?.email)
        assertEquals("János", cached?.firstName)
        assertEquals("Kovács", cached?.lastName)
        assertEquals("Kovács János", cached?.fullName)
        assertEquals("base64_photo_data", cached?.photoBase64)
    }

    @Test
    fun testSaveAndGetOrders() {
        val testItem = MavTicketItem(
            id = "item_1",
            name = "Országbérlet - 30 napos",
            discount = "Teljesárú",
            validFrom = 1700000000000L,
            validTo = 1702592000000L,
            price = 18900,
            isPass = true,
            status = "Érvényes",
            serviceStatus = null,
            direction = null,
            serviceId = null
        )

        val testReceipt = MavTicketReceipt(
            passengerName = "Kovács János",
            birthDate = 631152000000L,
            ticketNumber = "MAV-987654",
            receiptId = "rec_999",
            items = listOf(testItem)
        )

        val activeOrder = MavOrder(
            orderId = "order_active_1",
            purchaseDate = 1700000000000L,
            deliveryMode = "E-Ticket",
            passHolder = MavPassHolderSummary("hpt_1", "nevesites_1", 1),
            receipts = listOf(testReceipt)
        )

        val pastOrder = MavOrder(
            orderId = "order_past_1",
            purchaseDate = 1690000000000L,
            deliveryMode = "E-Ticket",
            passHolder = null,
            receipts = emptyList()
        )

        val syncTime = 1700000500000L
        cacheStore.saveOrders(listOf(activeOrder), listOf(pastOrder), syncTime = syncTime)

        assertEquals(syncTime, cacheStore.getLastSyncTime())

        val result = cacheStore.getOrders()
        assertNotNull(result)
        val (active, past) = result!!

        assertEquals(1, active.size)
        assertEquals("order_active_1", active[0].orderId)
        assertEquals(1700000000000L, active[0].purchaseDate)
        assertEquals("E-Ticket", active[0].deliveryMode)
        assertEquals("nevesites_1", active[0].passHolder?.id)
        assertEquals("hpt_1", active[0].passHolder?.hptId)

        assertEquals(1, active[0].receipts.size)
        val rec = active[0].receipts[0]
        assertEquals("Kovács János", rec.passengerName)
        assertEquals("rec_999", rec.receiptId)
        assertEquals("MAV-987654", rec.ticketNumber)
        assertEquals(1, rec.items.size)
        assertEquals("Országbérlet - 30 napos", rec.items[0].name)
        assertEquals(18900, rec.items[0].price)
        assertTrue(rec.items[0].isPass)

        assertEquals(1, past.size)
        assertEquals("order_past_1", past[0].orderId)
    }

    @Test
    fun testSaveAndGetTicketImage() {
        val image = MavTicketImage(
            receiptId = "rec_999",
            ticketNumber = "MAV-987654",
            kind = "AZTEC",
            imageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )

        cacheStore.saveTicketImage("rec_999", image)

        val cached = cacheStore.getTicketImage("rec_999")
        assertNotNull(cached)
        assertEquals("rec_999", cached?.receiptId)
        assertEquals("MAV-987654", cached?.ticketNumber)
        assertEquals("AZTEC", cached?.kind)
        assertEquals(image.imageBase64, cached?.imageBase64)
    }

    @Test
    fun testMultiKeyTicketImageLookup() {
        val compositeId = "ODAUTI_AJANLAT#CSCSAZON#CSAZON#1023744049"
        val ticketNumber = "5594003G000448895"
        val image = MavTicketImage(
            receiptId = compositeId,
            ticketNumber = ticketNumber,
            kind = "AZTEC",
            imageBase64 = "sample_aztec_barcode_base64"
        )

        cacheStore.saveTicketImage(compositeId, image)

        // Exact key lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage(compositeId)?.imageBase64)
        // Substring after '#' / orderId lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage("1023744049")?.imageBase64)
        // Ticket number lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage(ticketNumber)?.imageBase64)
    }

    @Test
    fun testMultiKeyPassHolderLookup() {
        val passHolder = MavPassHolder(
            id = "7269605",
            hptId = "HPT_12345",
            photoBase64 = "selfie_photo_base64",
            status = "Érvényes",
            validTo = 1710000000000L,
            name = "Kókai Balázs",
            birthDate = 631152000000L,
            version = 1,
            idCardNumber = "998877AA"
        )

        val compositeKey = "BERLETTOK#7269605"
        cacheStore.savePassHolder(compositeKey, passHolder)

        // Exact key
        assertEquals("selfie_photo_base64", cacheStore.getPassHolder(compositeKey)?.photoBase64)
        // By ID
        assertEquals("selfie_photo_base64", cacheStore.getPassHolder("7269605")?.photoBase64)
        // By HPT ID
        assertEquals("selfie_photo_base64", cacheStore.getPassHolder("HPT_12345")?.photoBase64)
    }

    @Test
    fun testRejectAndEvictCertificateInvoicePdf() {
        // 1. Trying to save Certificate should be ignored
        val certImage = MavTicketImage(
            receiptId = "invoice_1",
            ticketNumber = "SZAMLA-123",
            kind = "Certificate",
            imageBase64 = "JVBERi0xLjQKJeLjz9MKMSAwIG9iag=="
        )
        cacheStore.saveTicketImage("invoice_1", certImage)
        assertNull(cacheStore.getTicketImage("invoice_1"))

        // 2. Trying to save PDF with different kind should also be ignored
        val pdfImage = MavTicketImage(
            receiptId = "invoice_2",
            ticketNumber = "SZAMLA-456",
            kind = "Jegykep",
            imageBase64 = "JVBERi0xLjQKJeLjz9MKMSAwIG9iag=="
        )
        cacheStore.saveTicketImage("invoice_2", pdfImage)
        assertNull(cacheStore.getTicketImage("invoice_2"))
    }

    @Test
    fun testSaveAndGetPassHolder() {
        val passHolder = MavPassHolder(
            id = "nevesites_1",
            hptId = "hpt_1",
            photoBase64 = "photo_bytes_xyz",
            status = "Érvényes",
            validTo = 1710000000000L,
            name = "Kovács János",
            birthDate = 631152000000L,
            version = 2,
            idCardNumber = "123456AB"
        )

        cacheStore.savePassHolder("nevesites_1", passHolder)

        val cached = cacheStore.getPassHolder("nevesites_1")
        assertNotNull(cached)
        assertEquals("nevesites_1", cached?.id)
        assertEquals("hpt_1", cached?.hptId)
        assertEquals("photo_bytes_xyz", cached?.photoBase64)
        assertEquals("123456AB", cached?.idCardNumber)
        assertEquals(2, cached?.version)
    }

    @Test
    fun testClearRemovesAllData() {
        cacheStore.saveOrders(emptyList(), emptyList(), 12345L)
        cacheStore.saveTicketImage("rec_1", MavTicketImage("rec_1", null, null, "base64"))
        assertTrue(cacheFile.exists())

        cacheStore.clear()
        assertFalse(cacheFile.exists())
        assertNull(cacheStore.getOrders())
        assertNull(cacheStore.getTicketImage("rec_1"))
        assertNull(cacheStore.getUser())
    }
    @Test
    fun testLookupByAnyNumericTokenInCompositeId() {
        val compositeId = "ODAUTI#CSCSAZON#1779935735#0#1023744049"
        val ticketNumber = "5594003G000448895"
        val image = MavTicketImage(
            receiptId = compositeId,
            ticketNumber = ticketNumber,
            kind = "AZTEC",
            imageBase64 = "sample_aztec_barcode_base64"
        )

        cacheStore.saveTicketImage(compositeId, image)

        // Middle numeric ID (bizonylatTechnikaiAzonosito) lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage("1779935735")?.imageBase64)
        // Trailing numeric ID lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage("1023744049")?.imageBase64)
        // Full composite ID lookup
        assertEquals(image.imageBase64, cacheStore.getTicketImage(compositeId)?.imageBase64)
    }

    @Test
    fun exactPassHolderLookupDoesNotMatchUnrelatedIds() {
        // Email account (live data): BERLETTOK passholder 1023744049 with HPT id 7269605.
        val holder = MavPassHolder(id = "1023744049", hptId = "7269605", name = "Kókai Balázs", idCardNumber = "1258875960")
        cacheStore.savePassHolder(holder.id, holder)

        assertEquals("1023744049", cacheStore.getPassHolder("1023744049", exact = true)?.id)
        assertEquals("1023744049", cacheStore.getPassHolder("7269605", exact = true)?.id)
        // A saved passenger's id or a composite purchase id must not be treated as this passholder.
        assertNull(cacheStore.getPassHolder("1023604393", exact = true))
        assertNull(cacheStore.getPassHolder("ODAUTI_AJANLAT#CSCSAZON#CSAZON#1023744049X", exact = true))
        assertNull(cacheStore.getPassHolder("102374404", exact = true))
    }
}
