package hu.app.bmenetrend.mav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.runtime.Immutable
import java.io.File
import java.time.Instant

@Immutable
data class MavUser(
    val email: String,
    val firstName: String = "",
    val lastName: String = "",
    val regId: String = "",
    val photoBase64: String? = null
) {
    val fullName: String
        get() {
            val combined = listOf(lastName, firstName).filter { it.isNotBlank() }.joinToString(" ")
            return combined.ifBlank { email }
        }

    fun toPhotoBitmap(): Bitmap? {
        val raw = photoBase64 ?: return null
        return runCatching {
            val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

@Immutable
data class MavPassenger(
    val id: String = "",
    val name: String = "",
    val birthDate: Long? = null,
    val birthDateString: String? = null,
    val photoBase64: String? = null,
    val isDefault: Boolean = false,
    val businessId: String = ""
) {
    fun toPhotoBitmap(): Bitmap? {
        val raw = photoBase64 ?: return null
        return runCatching {
            val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

@Immutable
data class MavSession(
    val email: String,
    val token: String,
    val uaid: String,
    val expiresAt: Long = 0L,
    val user: MavUser? = null,
    val vimToken: String? = null
)

@Immutable
data class MavTicketItem(
    val id: String,
    val name: String,
    val discount: String? = null,
    val validFrom: Long = 0L,
    val validTo: Long = 0L,
    val price: Int = 0,
    val isPass: Boolean = false,
    val status: String? = null,
    val serviceStatus: String? = null,
    val direction: String? = null,
    val serviceId: String? = null
)

@Immutable
data class MavTicketReceipt(
    val passengerName: String? = null,
    val birthDate: Long? = null,
    val ticketNumber: String? = null,
    val receiptId: String,
    val items: List<MavTicketItem> = emptyList(),
    val passHolderId: String? = null
)

@Immutable
data class MavOrder(
    val orderId: String,
    val purchaseDate: Long = 0L,
    val deliveryMode: String? = null,
    val passHolder: MavPassHolderSummary? = null,
    val receipts: List<MavTicketReceipt> = emptyList()
) {
    val allItems: List<MavTicketItem> get() = receipts.flatMap { it.items }
    val isValidNow: Boolean
        get() {
            val nowMs = System.currentTimeMillis()
            return allItems.any { item ->
                (item.validTo == 0L || item.validTo >= nowMs)
            }
        }
}

@Immutable
data class MavPassHolderSummary(
    val hptId: String? = null,
    val id: String? = null,
    val version: Int = 0
)

@Immutable
data class MavPassHolder(
    val id: String,
    val hptId: String? = null,
    val photoBase64: String? = null,
    val status: String? = null,
    val validTo: Long = 0L,
    val name: String? = null,
    val birthDate: Long? = null,
    val version: Int = 0,
    val idCardNumber: String? = null
) {
    fun toPhotoBitmap(): Bitmap? {
        val raw = photoBase64 ?: return null
        return runCatching {
            val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

@Immutable
data class MavTicketImage(
    val receiptId: String,
    val ticketNumber: String? = null,
    val kind: String? = null,
    val imageBase64: String
) {
    fun toBitmap(): Bitmap? {
        return runCatching {
            val clean = if (imageBase64.contains(",")) imageBase64.substringAfter(",") else imageBase64
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            if (decoded.size >= 4 && decoded[0] == 0x25.toByte() && decoded[1] == 0x50.toByte() && decoded[2] == 0x44.toByte() && decoded[3] == 0x46.toByte()) {
                renderPdfToBitmap(decoded)
            } else {
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            }
        }.getOrNull()
    }

    private fun renderPdfToBitmap(pdfBytes: ByteArray): Bitmap? {
        return runCatching {
            val tempFile = File.createTempFile("mav_pdf_", ".pdf")
            try {
                tempFile.writeBytes(pdfBytes)
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    val scale = 2
                    val width = page.width * scale
                    val height = page.height * scale
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    renderer.close()
                    pfd.close()
                    bitmap
                } else {
                    renderer.close()
                    pfd.close()
                    null
                }
            } finally {
                tempFile.delete()
            }
        }.getOrNull()
    }
}
