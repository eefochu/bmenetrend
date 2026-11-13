package hu.app.bmenetrend.mav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MavCacheStore(
    private val cacheFile: File
) {
    constructor(context: Context) : this(
        File(context.filesDir, CACHE_FILE_NAME)
    )

    private val userPhotoFile = File(cacheFile.parentFile ?: File("."), USER_PHOTO_FILE_NAME)
    private val lock = Any()

    fun saveUser(user: MavUser) {
        synchronized(lock) {
            val root = loadOrCreateRoot()
            val userObj = JSONObject().apply {
                put("email", user.email)
                put("firstName", user.firstName)
                put("lastName", user.lastName)
                put("regId", user.regId)
                put("photoBase64", user.photoBase64.orEmpty())
            }
            root.put("cachedUser", userObj)
            writeRoot(root)

            if (!user.photoBase64.isNullOrBlank()) {
                runCatching {
                    userPhotoFile.parentFile?.mkdirs()
                    userPhotoFile.writeText(user.photoBase64, Charsets.UTF_8)
                }
            }
        }
    }

    fun getUser(): MavUser? {
        synchronized(lock) {
            var photo = runCatching {
                if (userPhotoFile.exists()) userPhotoFile.readText().ifBlank { null } else null
            }.getOrNull()

            if (!cacheFile.exists()) {
                if (photo != null) {
                    return MavUser(email = "", photoBase64 = photo)
                }
                return null
            }
            val root = readRoot() ?: return null
            val userObj = root.optJSONObject("cachedUser") ?: return null
            val email = userObj.optString("email", "")
            if (email.isBlank()) return null
            if (photo == null) {
                photo = userObj.optString("photoBase64", "").ifBlank { null }
            }
            return MavUser(
                email = email,
                firstName = userObj.optString("firstName", ""),
                lastName = userObj.optString("lastName", ""),
                regId = userObj.optString("regId", ""),
                photoBase64 = photo
            )
        }
    }

    fun saveOrders(active: List<MavOrder>, past: List<MavOrder>, syncTime: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val root = loadOrCreateRoot()
            root.put("lastSyncTime", syncTime)

            val activeArr = JSONArray()
            for (order in active) {
                activeArr.put(orderToJson(order))
            }
            root.put("activeOrders", activeArr)

            val pastArr = JSONArray()
            for (order in past) {
                pastArr.put(orderToJson(order))
            }
            root.put("pastOrders", pastArr)

            writeRoot(root)
        }
    }

    fun getOrders(): Pair<List<MavOrder>, List<MavOrder>>? {
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val activeArr = root.optJSONArray("activeOrders") ?: return null
            val pastArr = root.optJSONArray("pastOrders") ?: JSONArray()

            val active = mutableListOf<MavOrder>()
            for (i in 0 until activeArr.length()) {
                val obj = activeArr.optJSONObject(i) ?: continue
                jsonToOrder(obj)?.let { active.add(it) }
            }

            val past = mutableListOf<MavOrder>()
            for (i in 0 until pastArr.length()) {
                val obj = pastArr.optJSONObject(i) ?: continue
                jsonToOrder(obj)?.let { past.add(it) }
            }

            return Pair(active, past)
        }
    }

    fun getLastSyncTime(): Long {
        synchronized(lock) {
            if (!cacheFile.exists()) return 0L
            val root = readRoot() ?: return 0L
            return root.optLong("lastSyncTime", 0L)
        }
    }

    fun saveTicketImage(receiptId: String, image: MavTicketImage) {
        if (receiptId.isBlank()) return
        if (image.kind.equals("Certificate", ignoreCase = true) || image.imageBase64.startsWith("JVBER")) {
            return
        }
        synchronized(lock) {
            val root = loadOrCreateRoot()
            val imagesObj = root.optJSONObject("ticketImages") ?: JSONObject().also { root.put("ticketImages", it) }
            val itemObj = JSONObject().apply {
                put("receiptId", image.receiptId)
                put("ticketNumber", image.ticketNumber.orEmpty())
                put("kind", image.kind.orEmpty())
                put("imageBase64", image.imageBase64)
            }
            imagesObj.put(receiptId, itemObj)
            if (image.receiptId.isNotBlank()) {
                imagesObj.put(image.receiptId, itemObj)
            }
            if (!image.ticketNumber.isNullOrBlank()) {
                imagesObj.put(image.ticketNumber, itemObj)
            }
            if (receiptId.contains("#")) {
                imagesObj.put(receiptId.substringAfterLast("#"), itemObj)
            }
            if (image.receiptId.contains("#")) {
                imagesObj.put(image.receiptId.substringAfterLast("#"), itemObj)
            }
            writeRoot(root)
        }
    }

    fun getTicketImage(receiptId: String): MavTicketImage? {
        if (receiptId.isBlank()) return null
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val imagesObj = root.optJSONObject("ticketImages") ?: return null

            var itemObj = imagesObj.optJSONObject(receiptId)
            if (itemObj == null && receiptId.contains("#")) {
                itemObj = imagesObj.optJSONObject(receiptId.substringAfterLast("#"))
            }
            if (itemObj == null) {
                val keys = imagesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val cand = imagesObj.optJSONObject(k) ?: continue
                    val candReceiptId = cand.optString("receiptId", "")
                    val candTicketNumber = cand.optString("ticketNumber", "")
                    if (k == receiptId ||
                        candReceiptId == receiptId ||
                        candTicketNumber == receiptId ||
                        (candReceiptId.isNotBlank() && receiptId.contains(candReceiptId)) ||
                        (receiptId.isNotBlank() && candReceiptId.contains(receiptId)) ||
                        (candReceiptId.contains("#") && candReceiptId.substringAfterLast("#") == receiptId) ||
                        (receiptId.contains("#") && receiptId.substringAfterLast("#") == candReceiptId)
                    ) {
                        itemObj = cand
                        break
                    }
                }
            }
            if (itemObj == null) return null

            val kind = itemObj.optString("kind", "")
            val imgBase64 = itemObj.optString("imageBase64", "")
            if (imgBase64.isBlank() || kind.equals("Certificate", ignoreCase = true) || imgBase64.startsWith("JVBER")) {
                if (kind.equals("Certificate", ignoreCase = true) || imgBase64.startsWith("JVBER")) {
                    imagesObj.remove(receiptId)
                    writeRoot(root)
                }
                return null
            }
            return MavTicketImage(
                receiptId = itemObj.optString("receiptId", receiptId),
                ticketNumber = itemObj.optString("ticketNumber", "").ifBlank { null },
                kind = itemObj.optString("kind", "").ifBlank { null },
                imageBase64 = imgBase64
            )
        }
    }

    fun savePassHolder(id: String, passHolder: MavPassHolder) {
        if (id.isBlank()) return
        synchronized(lock) {
            val root = loadOrCreateRoot()
            val holdersObj = root.optJSONObject("passHolders") ?: JSONObject().also { root.put("passHolders", it) }
            val holderJson = JSONObject().apply {
                put("id", passHolder.id)
                put("hptId", passHolder.hptId.orEmpty())
                put("photoBase64", passHolder.photoBase64.orEmpty())
                put("status", passHolder.status.orEmpty())
                put("validTo", passHolder.validTo)
                put("name", passHolder.name.orEmpty())
                put("birthDate", passHolder.birthDate ?: 0L)
                put("version", passHolder.version)
                put("idCardNumber", passHolder.idCardNumber.orEmpty())
            }
            holdersObj.put(id, holderJson)
            if (passHolder.id.isNotBlank()) {
                holdersObj.put(passHolder.id, holderJson)
            }
            if (!passHolder.hptId.isNullOrBlank()) {
                holdersObj.put(passHolder.hptId, holderJson)
            }
            if (id.contains("#")) {
                holdersObj.put(id.substringAfterLast("#"), holderJson)
            }
            writeRoot(root)
        }
    }

    fun getPassHolder(id: String): MavPassHolder? {
        if (id.isBlank()) return null
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val holdersObj = root.optJSONObject("passHolders") ?: return null

            var obj = holdersObj.optJSONObject(id)
            if (obj == null && id.contains("#")) {
                obj = holdersObj.optJSONObject(id.substringAfterLast("#"))
            }
            if (obj == null) {
                val keys = holdersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val cand = holdersObj.optJSONObject(k) ?: continue
                    val candId = cand.optString("id", "")
                    val candHptId = cand.optString("hptId", "")
                    if (k == id || candId == id || candHptId == id ||
                        (candId.isNotBlank() && id.contains(candId)) ||
                        (id.isNotBlank() && candId.contains(id))
                    ) {
                        obj = cand
                        break
                    }
                }
            }
            if (obj == null) return null

            val bDate = obj.optLong("birthDate", 0L).let { if (it > 0) it else null }
            return MavPassHolder(
                id = obj.optString("id", id),
                hptId = obj.optString("hptId", "").ifBlank { null },
                photoBase64 = obj.optString("photoBase64", "").ifBlank { null },
                status = obj.optString("status", "").ifBlank { null },
                validTo = obj.optLong("validTo", 0L),
                name = obj.optString("name", "").ifBlank { null },
                birthDate = bDate,
                version = obj.optInt("version", 0),
                idCardNumber = obj.optString("idCardNumber", "").ifBlank { null }
            )
        }
    }

    fun savePassengers(passengers: List<MavPassenger>) {
        synchronized(lock) {
            val root = loadOrCreateRoot()
            val arr = JSONArray()
            for (p in passengers) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("birthDate", p.birthDate ?: 0L)
                    put("birthDateString", p.birthDateString.orEmpty())
                    put("photoBase64", p.photoBase64.orEmpty())
                    put("isDefault", p.isDefault)
                    put("businessId", p.businessId)
                }
                arr.put(obj)
            }
            root.put("cachedPassengers", arr)
            writeRoot(root)
        }
    }

    fun getPassengers(): List<MavPassenger> {
        synchronized(lock) {
            if (!cacheFile.exists()) return emptyList()
            val root = readRoot() ?: return emptyList()
            val arr = root.optJSONArray("cachedPassengers") ?: return emptyList()
            val list = mutableListOf<MavPassenger>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val bDate = obj.optLong("birthDate", 0L).let { if (it > 0) it else null }
                list.add(
                    MavPassenger(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        birthDate = bDate,
                        birthDateString = obj.optString("birthDateString", "").ifBlank { null },
                        photoBase64 = obj.optString("photoBase64", "").ifBlank { null },
                        isDefault = obj.optBoolean("isDefault", false),
                        businessId = obj.optString("businessId", "")
                    )
                )
            }
            return list
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching {
                if (cacheFile.exists()) cacheFile.delete()
                if (userPhotoFile.exists()) userPhotoFile.delete()
            }
        }
    }

    private fun loadOrCreateRoot(): JSONObject {
        return readRoot() ?: JSONObject()
    }

    private fun readRoot(): JSONObject? {
        return try {
            if (!cacheFile.exists()) return null
            val content = cacheFile.readText()
            if (content.isBlank()) null else JSONObject(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeRoot(root: JSONObject) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(root.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Ignore disk write errors
        }
    }

    private fun orderToJson(order: MavOrder): JSONObject {
        return JSONObject().apply {
            put("orderId", order.orderId)
            put("purchaseDate", order.purchaseDate)
            put("deliveryMode", order.deliveryMode.orEmpty())
            order.passHolder?.let { ph ->
                put("passHolder", JSONObject().apply {
                    put("hptId", ph.hptId)
                    put("id", ph.id)
                    put("version", ph.version)
                })
            }
            val recArr = JSONArray()
            for (rec in order.receipts) {
                recArr.put(receiptToJson(rec))
            }
            put("receipts", recArr)
        }
    }

    private fun jsonToOrder(obj: JSONObject): MavOrder? {
        val orderId = obj.optString("orderId", "")
        if (orderId.isBlank()) return null
        val purchaseDate = obj.optLong("purchaseDate", 0L)
        val deliveryMode = obj.optString("deliveryMode", "").ifBlank { null }

        val phObj = obj.optJSONObject("passHolder")
        val passHolder = phObj?.let {
            MavPassHolderSummary(
                hptId = it.optString("hptId", ""),
                id = it.optString("id", ""),
                version = it.optInt("version", 0)
            )
        }

        val recArr = obj.optJSONArray("receipts") ?: JSONArray()
        val receipts = mutableListOf<MavTicketReceipt>()
        for (i in 0 until recArr.length()) {
            val recObj = recArr.optJSONObject(i) ?: continue
            jsonToReceipt(recObj)?.let { receipts.add(it) }
        }

        return MavOrder(
            orderId = orderId,
            purchaseDate = purchaseDate,
            deliveryMode = deliveryMode,
            passHolder = passHolder,
            receipts = receipts
        )
    }

    private fun receiptToJson(receipt: MavTicketReceipt): JSONObject {
        return JSONObject().apply {
            put("passengerName", receipt.passengerName.orEmpty())
            put("birthDate", receipt.birthDate ?: 0L)
            put("ticketNumber", receipt.ticketNumber.orEmpty())
            put("receiptId", receipt.receiptId)
            put("passHolderId", receipt.passHolderId.orEmpty())
            val itemsArr = JSONArray()
            for (item in receipt.items) {
                itemsArr.put(itemToJson(item))
            }
            put("items", itemsArr)
        }
    }

    private fun jsonToReceipt(obj: JSONObject): MavTicketReceipt? {
        val receiptId = obj.optString("receiptId", "")
        if (receiptId.isBlank()) return null
        val passengerName = obj.optString("passengerName", "").ifBlank { null }
        val birthDate = obj.optLong("birthDate", 0L).let { if (it > 0) it else null }
        val ticketNumber = obj.optString("ticketNumber", "").ifBlank { null }
        val passHolderId = obj.optString("passHolderId", "").ifBlank { null }

        val itemsArr = obj.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<MavTicketItem>()
        for (i in 0 until itemsArr.length()) {
            val itemObj = itemsArr.optJSONObject(i) ?: continue
            jsonToItem(itemObj)?.let { items.add(it) }
        }

        return MavTicketReceipt(
            passengerName = passengerName,
            birthDate = birthDate,
            ticketNumber = ticketNumber,
            receiptId = receiptId,
            items = items,
            passHolderId = passHolderId
        )
    }

    private fun itemToJson(item: MavTicketItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("discount", item.discount.orEmpty())
            put("validFrom", item.validFrom)
            put("validTo", item.validTo)
            put("price", item.price)
            put("isPass", item.isPass)
            put("status", item.status.orEmpty())
            put("serviceStatus", item.serviceStatus.orEmpty())
            put("direction", item.direction.orEmpty())
            put("serviceId", item.serviceId.orEmpty())
        }
    }

    private fun jsonToItem(obj: JSONObject): MavTicketItem? {
        val id = obj.optString("id", "")
        val name = obj.optString("name", "")
        if (id.isBlank() && name.isBlank()) return null
        return MavTicketItem(
            id = id,
            name = name,
            discount = obj.optString("discount", "").ifBlank { null },
            validFrom = obj.optLong("validFrom", 0L),
            validTo = obj.optLong("validTo", 0L),
            price = obj.optInt("price", 0),
            isPass = obj.optBoolean("isPass", false),
            status = obj.optString("status", "").ifBlank { null },
            serviceStatus = obj.optString("serviceStatus", "").ifBlank { null },
            direction = obj.optString("direction", "").ifBlank { null },
            serviceId = obj.optString("serviceId", "").ifBlank { null }
        )
    }

    companion object {
        const val CACHE_FILE_NAME = "mav_tickets_cache.json"
        const val USER_PHOTO_FILE_NAME = "mav_user_photo.b64"
    }
}
