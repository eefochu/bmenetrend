package hu.app.bmenetrend.mav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Immutable
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
data class MavSession(
    val email: String,
    val token: String,
    val uaid: String,
    val expiresAt: Long = 0L,
    val user: MavUser? = null
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
    val items: List<MavTicketItem> = emptyList()
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
)

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
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        }.getOrNull()
    }
}
