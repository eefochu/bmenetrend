package hu.app.bmenetrend.mav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MavCacheStore(
    private val cacheFile: File
) {
    constructor(context: Context) : this(
        File(context.filesDir, CACHE_FILE_NAME)
    )

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
        }
    }

    fun getUser(): MavUser? {
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val userObj = root.optJSONObject("cachedUser") ?: return null
            val email = userObj.optString("email", "")
            if (email.isBlank()) return null
            return MavUser(
                email = email,
                firstName = userObj.optString("firstName", ""),
                lastName = userObj.optString("lastName", ""),
                regId = userObj.optString("regId", ""),
                photoBase64 = userObj.optString("photoBase64", "").ifBlank { null }
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
            writeRoot(root)
        }
    }

    fun getTicketImage(receiptId: String): MavTicketImage? {
        if (receiptId.isBlank()) return null
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val imagesObj = root.optJSONObject("ticketImages") ?: return null
            val itemObj = imagesObj.optJSONObject(receiptId) ?: return null
            val imgBase64 = itemObj.optString("imageBase64", "")
            if (imgBase64.isBlank()) return null
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
            writeRoot(root)
        }
    }

    fun getPassHolder(id: String): MavPassHolder? {
        if (id.isBlank()) return null
        synchronized(lock) {
            if (!cacheFile.exists()) return null
            val root = readRoot() ?: return null
            val holdersObj = root.optJSONObject("passHolders") ?: return null
            val obj = holdersObj.optJSONObject(id) ?: return null
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
            if (cacheFile.exists()) {
                cacheFile.delete()
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
            val tempFile = File(cacheFile.parentFile ?: return, "${cacheFile.name}.tmp")
            tempFile.writeText(root.toString())
            if (!tempFile.renameTo(cacheFile)) {
                cacheFile.delete()
                tempFile.renameTo(cacheFile)
            }
        } catch (e: IOException) {
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
            items = items
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
    }
}
