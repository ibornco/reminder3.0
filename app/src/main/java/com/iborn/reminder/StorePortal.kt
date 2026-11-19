package com.iborn.reminder

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.IOException
import java.util.UUID
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64
import java.net.URL
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import android.content.res.Configuration

private const val STORE_BASE_URL = "https://iborn.pythonanywhere.com/api/v1"

private fun loadStoreProfile(context: Context): StoreProfile? {
    val prefs = context.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE)
    val name = prefs.getString("display_name", null) ?: return null
    val username = prefs.getString("username", null) ?: return null
    val role = prefs.getString("role", null) ?: return null
    return StoreProfile(prefs.getInt("id", 0), username, name, role)
}

private fun persistStoreProfile(context: Context, profile: StoreProfile?) {
    val prefs = context.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE)
    if (profile == null) prefs.edit().clear().apply() else prefs.edit()
        .putString("display_name", profile.displayName)
        .putString("username", profile.username)
        .putString("role", profile.role)
        .putInt("id", profile.id)
        .apply()
}

private val StoreMint = Color(0xFFC5F4A9)
private val StoreInk = Color(0xFFEDF3ED)
private val StoreMuted = Color(0xFF8C9B91)
private val StoreBlack = Color(0xFF0B100F)
private val StoreCard = Color(0xFF19231F)

private val LocalStoreEnglish = staticCompositionLocalOf { true }

private fun displayStoreDate(raw: String, english: Boolean): String {
    if (english) return raw
    val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return raw
    val gy = date.year
    val gm = date.monthValue
    val gd = date.dayOfMonth
    val monthDays = intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
    val gy2 = if (gm > 2) gy + 1 else gy
    var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + monthDays[gm - 1]
    var jy = -1595 + 33 * (days / 12053)
    days %= 12053
    jy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
    val jm: Int
    val jd: Int
    if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 }
    else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
    return "%04d/%02d/%02d".format(jy, jm, jd).map { ch -> if (ch in '0'..'9') ('۰'.code + (ch - '0')).toChar() else ch }.joinToString("")
}

@Composable private fun storeText(value: String): String {
    if (LocalStoreEnglish.current) return value
    val translations = mapOf(
        "STORE WORKSPACE" to "محیط کاری فروشگاه", "Shared workspace" to "فضای کاری مشترک", "Sign in to Store" to "ورود به فروشگاه", "Your shared team workspace" to "فضای مشترک تیم شما",
        "Username" to "نام کاربری", "Password" to "رمز عبور", "Connecting…" to "در حال اتصال…", "Sign in" to "ورود", "Login failed" to "ورود ناموفق بود",
        "Sign in once. This device keeps your session until you log out or access is revoked." to "یک‌بار وارد شوید؛ نشست شما تا زمان خروج یا لغو دسترسی روی این دستگاه باقی می‌ماند.",
        "ROLE" to "نقش", "MANAGER" to "مدیر", "STAFF" to "کارمند", "TASKS" to "وظایف", "DONE" to "انجام‌شده", "Tasks" to "وظایف", "Staff" to "پرسنل", "Shifts" to "شیفت‌ها", "Products" to "محصولات", "My tasks" to "وظایف من", "Schedule" to "برنامه شیفت",
        "Refresh" to "بروزرسانی", "Full day" to "تمام‌روز", "Online" to "متصل", "Offline" to "آفلاین", "No internet connection. Check your internet." to "اینترنت وصل نیست؛ اتصال اینترنت را بررسی کنید.", "VPN may be off or the server route is unavailable. Turn on your VPN and retry." to "احتمالاً فیلترشکن خاموش است یا مسیر سرور در دسترس نیست؛ فیلترشکن را روشن و دوباره تلاش کنید.", "The server is not responding. Your changes are saved on this device." to "سرور پاسخ نمی‌دهد؛ تغییرات شما روی گوشی ذخیره شده‌اند.", "changes waiting to sync" to "تغییر در انتظار همگام‌سازی", "CRITICAL" to "بحرانی", "PRODUCTS" to "محصولات", "BATCHES" to "سری‌ها", "Connection restored; syncing your changes." to "اتصال برقرار شد؛ تغییرات در حال همگام‌سازی هستند.", "Log out" to "خروج", "Choose date" to "انتخاب تاریخ", "Select" to "انتخاب", "Cancel" to "انصراف",
        "Add staff member" to "افزودن پرسنل", "Create staff account" to "ایجاد حساب پرسنل", "Full name" to "نام و نام خانوادگی", "Temporary password" to "رمز عبور موقت", "Staff members" to "اعضای پرسنل", "Disable" to "غیرفعال‌کردن", "Enable" to "فعال‌کردن", "Create failed" to "ایجاد اطلاعات ناموفق بود", "Update failed" to "بروزرسانی ناموفق بود", "Create failed" to "ایجاد ناموفق بود",
        "Schedule a shift" to "ثبت شیفت", "Shift date" to "تاریخ شیفت", "Morning" to "صبح", "Evening" to "عصر", "Select staff" to "انتخاب پرسنل", "Save schedule" to "ذخیره برنامه", "Upcoming shifts" to "شیفت‌های آینده", "My schedule & colleagues" to "برنامه و همکاران من", "No staff assigned" to "پرسنلی تعیین نشده است",
        "Create task" to "ایجاد وظیفه", "Task title" to "عنوان وظیفه", "Task date" to "تاریخ وظیفه", "Shared shift task" to "وظیفه مشترک شیفت", "Save task changes" to "ذخیره تغییرات وظیفه", "Assigned tasks are visible only to that employee and managers." to "وظایف اختصاصی فقط برای همان کارمند و مدیران قابل مشاهده است.", "Store tasks" to "وظایف فروشگاه", "Edit" to "ویرایش", "Delete" to "حذف", "Shared" to "مشترک", "Completed by" to "انجام‌شده توسط", "of" to "از", "assigned staff" to "پرسنل تعیین‌شده",
        "Add product / expiry batch" to "افزودن محصول / سری انقضا", "Product name" to "نام محصول", "Category" to "دسته‌بندی", "Barcode (optional)" to "بارکد (اختیاری)", "Expiry date" to "تاریخ انقضا", "Quantity" to "تعداد", "Save product" to "ذخیره محصول", "Barcode is optional. Each product can have several batches with separate expiry dates." to "ثبت بارکد اختیاری است. هر محصول می‌تواند چند سری با تاریخ انقضای جداگانه داشته باشد.", "Add another expiry batch" to "افزودن سری انقضای دیگر", "Choose existing product" to "انتخاب محصول موجود", "Batch expiry date" to "تاریخ انقضای سری", "Batch quantity" to "تعداد این سری", "Add batch" to "افزودن سری", "Expiry inventory" to "موجودی و انقضای محصولات", "Uncategorized" to "بدون دسته‌بندی", "Qty" to "تعداد", "Expiry:" to "انقضا:", "Barcode:" to "بارکد:",
        "Could not load Store data" to "بارگذاری اطلاعات فروشگاه انجام نشد", "unauthorized" to "نشست معتبر نیست؛ دوباره وارد شوید", "forbidden" to "اجازه انجام این عملیات را ندارید", "invalid_credentials" to "نام کاربری یا رمز عبور اشتباه است", "username_name_and_password_required_min_6_password" to "نام کاربری، نام و رمز عبور حداقل ۶ حرفی الزامی است", "username_exists" to "این نام کاربری قبلاً ثبت شده است", "password_min_6" to "رمز عبور باید حداقل ۶ حرف باشد", "not_found" to "مورد درخواستی پیدا نشد", "display_name_required" to "نام نمایشی الزامی است", "invalid_date_range" to "بازه تاریخ نامعتبر است", "invalid_date_shift_or_staff_list" to "تاریخ، نوع شیفت یا فهرست پرسنل نامعتبر است", "invalid_staff_id" to "شناسه پرسنل نامعتبر است", "invalid_task" to "اطلاعات وظیفه نامعتبر است", "invalid_assignee" to "مسئول وظیفه نامعتبر است", "invalid_quantity" to "تعداد باید عددی معتبر و مثبت باشد", "invalid_product_or_batch" to "محصول یا سری انتخاب‌شده معتبر نیست", "invalid_batch" to "سری محصول معتبر نیست", "name_required" to "نام محصول الزامی است", "Save failed" to "ذخیره‌سازی ناموفق بود", "Batch save failed" to "ذخیره سری ناموفق بود", "Request failed" to "درخواست ناموفق بود", "Server returned a non-JSON response" to "سرور پاسخ معتبر ارسال نکرد", "Server returned an empty response" to "پاسخ سرور خالی است"
    )
    if (translations.containsKey(value)) return translations.getValue(value)
    if (value == "Create failed") return "ایجاد اطلاعات ناموفق بود"
    if (value == "Update failed") return "بروزرسانی ناموفق بود"
    if (value == "Save failed") return "ذخیره‌سازی ناموفق بود"
    if (value == "Batch save failed") return "ذخیره سری ناموفق بود"
    if (value == "Edit failed") return "ویرایش ناموفق بود"
    if (value.startsWith("Server returned a non-JSON response")) return "پاسخ سرور معتبر نیست؛ کد خطا: " + value.substringAfter("HTTP ", "")
    if (value.startsWith("Server returned an empty response")) return "پاسخ سرور خالی است؛ گزارش‌های API را بررسی کنید."
    if (value.startsWith("Request failed")) return "درخواست ناموفق بود؛ " + value.substringAfter("Request failed", "")
    if (value.startsWith("Completed by ")) return value.replace("Completed by ", "انجام‌شده توسط ").replace(" of ", " از ").replace(" assigned staff", " نفر پرسنل")
    return value
}

enum class StoreConnectionState { CHECKING, ONLINE, NO_INTERNET, VPN_OR_ROUTE, SERVER_ERROR }

data class StoreProfile(val id: Int, val username: String, val displayName: String, val role: String)
data class StoreTask(val id: Int, val title: String, val shift: String, val date: String, val assignedName: String?, val done: Boolean, val doneCount: Int = 0, val memberCount: Int = 0)
data class StoreStaff(val id: Int, val username: String, val name: String, val active: Boolean)
data class StoreShift(val id: Int, val date: String, val type: String, val start: String, val end: String, val members: List<String>, val memberIds: List<Int> = emptyList())
data class StoreProduct(val id: Int, val batchId: Int, val name: String, val category: String, val barcode: String, val expiry: String, val quantity: Int, val checkedQuantity: Int? = null, val checkedAt: String = "", val checkedBy: String = "")
data class StoreOverview(val taskCount: Int = 0, val doneCount: Int = 0, val shiftCount: Int = 0, val batchCount: Int = 0, val expiringCount: Int = 0, val role: String = "")

class StoreHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

class StoreApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE)
    private val cache = appContext.getSharedPreferences("reminder_store_cache", Context.MODE_PRIVATE)
    private val queueLock = Any()
    private fun queuePrefsKey(): String {
        val userId = appContext.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE).getInt("id", 0)
        return "pending_store_ops_$userId"
    }
    var token: String
        get() = prefs.getString("token", "") ?: ""
        private set(value) { prefs.edit().putString("token", value).apply() }

    private fun cacheKey(path: String): String {
        val userId = appContext.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE).getInt("id", 0)
        return "get:$userId:" + path.substringBefore("?")
    }
    private fun cached(path: String): JSONObject? = cache.getString(cacheKey(path), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun saveCache(path: String, value: JSONObject) { cache.edit().putString(cacheKey(path), value.toString()).apply() }

    private fun pendingQueue(): JSONArray = synchronized(queueLock) {
        runCatching { JSONArray(prefs.getString(queuePrefsKey(), "[]")) }.getOrDefault(JSONArray())
    }
    fun pendingCount(): Int = pendingQueue().length()
    private fun enqueue(method: String, path: String, body: JSONObject?, key: String) = synchronized(queueLock) {
        val q = pendingQueue()
        val op = JSONObject().put("method", method).put("path", path).put("body", body ?: JSONObject.NULL).put("key", key)
        if ((method == "POST" && (path == "/tasks" || path == "/products" || path == "/staff" || path.endsWith("/batches"))) || (method == "PUT" && path == "/shifts")) {
            val localId = -((key.hashCode().toLong().let { kotlin.math.abs(it) } % 1000000000L).toInt() + 1)
            op.put("local_id", localId)
        }
        q.put(op)
        prefs.edit().putString(queuePrefsKey(), q.toString()).apply()
    }
    private fun pendingOperations(): List<JSONObject> {
        val q = pendingQueue()
        return (0 until q.length()).mapNotNull { runCatching { q.getJSONObject(it) }.getOrNull() }
    }
    private fun resolveLocalId(id: Int): Int = if (id < 0) prefs.getInt("server_id_$id", id) else id
    private fun resolveBody(source: JSONObject?): JSONObject? {
        if (source == null) return null
        val body = JSONObject(source.toString())
        if (body.has("assigned_user_id") && !body.isNull("assigned_user_id")) body.put("assigned_user_id", resolveLocalId(body.optInt("assigned_user_id")))
        if (body.has("staff_ids")) {
            val old = body.optJSONArray("staff_ids") ?: JSONArray(); val mapped = JSONArray()
            for (i in 0 until old.length()) mapped.put(resolveLocalId(old.optInt(i)))
            body.put("staff_ids", mapped)
        }
        return body
    }
    private fun saveQueue(q: JSONArray) = synchronized(queueLock) { prefs.edit().putString(queuePrefsKey(), q.toString()).apply() }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, queueOnOffline: Boolean = true, idempotencyKey: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val mutating = method in setOf("POST", "PUT", "PATCH", "DELETE")
        val key = if (mutating) (idempotencyKey ?: UUID.randomUUID().toString()) else null
        try {
            val connection = (URL(STORE_BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (key != null) setRequestProperty("X-Idempotency-Key", key)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = try {
                    if (raw.isBlank()) JSONObject() else JSONObject(raw)
                } catch (_: Exception) {
                    JSONObject().put("error", "Server returned a non-JSON response (HTTP $code)")
                }
                if (code !in 200..299) {
                    if (method == "GET" && code >= 500) cached(path)?.let { return@withContext it }
                    if (mutating && queueOnOffline && code in 502..504) {
                        enqueue(method, path, body, key!!)
                        return@withContext JSONObject().put("ok", true).put("queued", true).put("idempotency_key", key)
                    }
                    val detail = result.optString("error").ifBlank { "Request failed (HTTP $code)" }
                    throw StoreHttpException(code, detail)
                }
                if (raw.isBlank()) throw IOException("Server returned an empty response (HTTP $code)")
                if (method == "GET") saveCache(path, result)
                result
            } finally { connection.disconnect() }
        } catch (e: IOException) {
            if (method == "GET") cached(path) ?: throw e
            else if (mutating && queueOnOffline) {
                enqueue(method, path, body, key!!)
                JSONObject().put("ok", true).put("queued", true).put("idempotency_key", key)
            } else throw e
        }
    }

    suspend fun probeConnection(): StoreConnectionState = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$STORE_BASE_URL/health").openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000; requestMethod = "GET" }
            try {
                val code = c.responseCode
                if (code in 200..299) StoreConnectionState.ONLINE
                else {
                    // The health route can be unavailable or misconfigured while authenticated API routes still work.
                    runCatching { call("GET", "/products", queueOnOffline = false) }
                        .fold({ StoreConnectionState.ONLINE }, { StoreConnectionState.SERVER_ERROR })
                }
            } finally { c.disconnect() }
        } catch (_: Exception) {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) StoreConnectionState.NO_INTERNET
            else if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) StoreConnectionState.VPN_OR_ROUTE
            else StoreConnectionState.SERVER_ERROR
        }
    }

    suspend fun flushPending(): Int = withContext(Dispatchers.IO) {
        var q = pendingQueue()
        var sent = 0
        while (q.length() > 0) {
            val op = q.getJSONObject(0)
            var path = op.getString("path")
            val originalBody = op.optJSONObject("body")
            val body = resolveBody(originalBody)
            // Replace temporary IDs in URL path after earlier create operations have succeeded.
            for ((key, value) in prefs.all) {
                if (key.startsWith("server_id_") && value is Int) {
                    val local = key.removePrefix("server_id_")
                    path = path.replace("/$local/", "/$value/").let { if (it.endsWith("/$local")) it.dropLast(local.length) + value else it }
                }
            }
            try {
                val result = call(op.getString("method"), path, body, queueOnOffline = false, idempotencyKey = op.getString("key"))
                val localId = op.optInt("local_id", 0)
                if (localId < 0) {
                    val serverId = when {
                        result.has("id") -> result.optInt("id")
                        result.has("batch_id") -> result.optInt("batch_id")
                        else -> 0
                    }
                    if (serverId > 0) prefs.edit().putInt("server_id_$localId", serverId).apply()
                }
                val next = JSONArray()
                for (i in 1 until q.length()) next.put(q.get(i))
                q = next
                saveQueue(q)
                sent++
            } catch (_: Exception) { break }
        }
        sent
    }


    suspend fun login(username: String, password: String): StoreProfile {
        val result = call("POST", "/auth/login", JSONObject().put("username", username).put("password", password), queueOnOffline = false)
        token = result.getString("token")
        return result.getJSONObject("profile").toProfile()
    }
    suspend fun profile(): StoreProfile = call("GET", "/me").getJSONObject("profile").toProfile()
    private fun JSONObject.toProfile() = StoreProfile(getInt("id"), getString("username"), getString("display_name"), getString("role"))

    suspend fun tasks(): List<StoreTask> {
        val from = LocalDate.now().toString(); val to = LocalDate.now().plusMonths(12).toString()
        val a = call("GET", "/tasks?from=$from&to=$to").optJSONArray("tasks") ?: JSONArray()
        val result = (0 until a.length()).map { i -> a.getJSONObject(i).let { x ->
            StoreTask(x.getInt("id"), x.getString("title"), x.getString("shift_type"), x.getString("task_date"),
                x.optString("assigned_name").takeIf { it != "null" && it.isNotBlank() }, x.optInt("done") == 1,
                x.optInt("done_count"), x.optInt("member_count"))
        } }.toMutableList()
        pendingOperations().forEach { op ->
            val path = op.optString("path"); val method = op.optString("method"); val body = op.optJSONObject("body")
            if (path == "/tasks" && method == "POST" && body != null) {
                val localId = op.optInt("local_id")
                val assignedId = body.optInt("assigned_user_id", 0)
                val assignedName = if (assignedId != 0) staff().firstOrNull { it.id == assignedId }?.name else null
                result.removeAll { it.id == localId }
                result.add(StoreTask(localId, body.optString("title"), body.optString("shift_type"), body.optString("date"), assignedName, false))
            } else if (path.startsWith("/tasks/") && path.endsWith("/complete") && method == "POST" && body != null) {
                val id = path.removePrefix("/tasks/").substringBefore("/").toIntOrNull() ?: 0
                val index = result.indexOfFirst { it.id == id }
                if (index >= 0) result[index] = result[index].copy(done = body.optBoolean("done", true))
            } else if (path.startsWith("/tasks/") && method == "DELETE") {
                val id = path.substringAfter("/tasks/").toIntOrNull() ?: 0
                result.removeAll { it.id == id }
            } else if (path.startsWith("/tasks/") && method == "PATCH" && body != null) {
                val id = path.substringAfter("/tasks/").toIntOrNull() ?: 0
                val index = result.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val assignedId = if (body.isNull("assigned_user_id")) 0 else body.optInt("assigned_user_id", 0)
                    val assignedName = if (assignedId != 0) staff().firstOrNull { it.id == assignedId }?.name else null
                    result[index] = result[index].copy(title=body.optString("title",result[index].title),date=body.optString("date",result[index].date),shift=body.optString("shift_type",result[index].shift),assignedName=assignedName)
                }
            }
        }
        return result.sortedWith(compareBy<StoreTask> { it.date }.thenBy { it.shift })
    }
    suspend fun complete(id: Int, done: Boolean) { call("POST", "/tasks/$id/complete", JSONObject().put("done", done)) }
    suspend fun staff(): List<StoreStaff> {
        val a = call("GET", "/staff").optJSONArray("staff") ?: JSONArray()
        val result = (0 until a.length()).map { a.getJSONObject(it).let { x -> StoreStaff(x.getInt("id"), x.getString("username"), x.getString("display_name"), x.optInt("active") == 1) } }.toMutableList()
        pendingOperations().forEach { op ->
            val path=op.optString("path"); val body=op.optJSONObject("body")
            if(path=="/staff" && op.optString("method")=="POST" && body!=null) result.add(StoreStaff(op.optInt("local_id"),body.optString("username"),body.optString("display_name"),true))
            if(path.startsWith("/staff/") && op.optString("method")=="PATCH" && body!=null) {
                val id=path.substringAfter("/staff/").toIntOrNull() ?: 0
                val index=result.indexOfFirst{it.id==id}; if(index>=0) result[index]=result[index].copy(active=body.optBoolean("active",result[index].active))
            }
        }
        return result
    }
    private fun passwordHash(password: String): String {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), 600000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val hex = bytes.joinToString("") { "%02x".format(it) }
        spec.clearPassword()
        return "pbkdf2:sha256:600000\$${salt}\$${hex}"
    }
    suspend fun createStaff(username: String, name: String, password: String) {
        val hash = withContext(Dispatchers.Default) { passwordHash(password) }
        call("POST", "/staff", JSONObject().put("username", username).put("display_name", name).put("password_hash", hash))
    }
    suspend fun setStaffActive(id: Int, active: Boolean) {
        call("PATCH", "/staff/$id", JSONObject().put("active", active))
    }
    suspend fun shifts(): List<StoreShift> {
        val from = LocalDate.now().toString(); val to = LocalDate.now().plusMonths(12).toString()
        val a = call("GET", "/shifts?from=$from&to=$to").optJSONArray("shifts") ?: JSONArray()
        val result = (0 until a.length()).map { a.getJSONObject(it).let { x ->
            val members = x.optJSONArray("members") ?: JSONArray()
            StoreShift(x.getInt("id"), x.getString("shift_date"), x.getString("shift_type"), x.optString("start_time"), x.optString("end_time"),
                (0 until members.length()).map { members.getJSONObject(it).getString("display_name") },
                (0 until members.length()).map { members.getJSONObject(it).optInt("id") })
        } }.toMutableList()
        val staffList = runCatching { staff() }.getOrDefault(emptyList())
        pendingOperations().filter { it.optString("method")=="PUT" && it.optString("path")=="/shifts" }.forEach { op ->
            val body=op.optJSONObject("body") ?: return@forEach
            val day=body.optString("date"); val type=body.optString("shift_type")
            result.removeAll { it.date==day && it.type==type }
            val ids=body.optJSONArray("staff_ids") ?: JSONArray()
            val names=(0 until ids.length()).mapNotNull { idIndex -> staffList.firstOrNull { it.id==ids.optInt(idIndex) }?.name }
            result.add(StoreShift(op.optInt("local_id"),day,type,"","",names,(0 until ids.length()).map { ids.optInt(it) }))
        }
        pendingOperations().filter { it.optString("method")=="DELETE" && it.optString("path").startsWith("/shifts/") }.forEach { op ->
            val id=op.optString("path").substringAfter("/shifts/").toIntOrNull() ?: 0
            result.removeAll { it.id==id }
        }
        return result.sortedWith(compareBy<StoreShift>{it.date}.thenBy{it.type})
    }
    suspend fun saveShift(date: String, type: String, ids: Set<Int>) {
        val a = JSONArray(); ids.forEach { a.put(it) }
        call("PUT", "/shifts", JSONObject().put("date", date).put("shift_type", type).put("staff_ids", a))
    }
    suspend fun deleteShift(id: Int) { call("DELETE", "/shifts/$id") }
    suspend fun createTask(title: String, date: String, type: String, assignedId: Int?) {
        val b = JSONObject().put("title", title).put("date", date).put("shift_type", type)
        if (assignedId != null) b.put("assigned_user_id", assignedId)
        call("POST", "/tasks", b)
    }
    suspend fun updateTask(id: Int, title: String, date: String, type: String, assignedId: Int?) {
        val b = JSONObject().put("title", title).put("date", date).put("shift_type", type)
        if (assignedId != null) b.put("assigned_user_id", assignedId) else b.put("assigned_user_id", JSONObject.NULL)
        call("PATCH", "/tasks/$id", b)
    }
    suspend fun deleteTask(id: Int) { call("DELETE", "/tasks/$id") }
    suspend fun products(): List<StoreProduct> {
        val a = call("GET", "/products").optJSONArray("products") ?: JSONArray()
        val result = (0 until a.length()).map { a.getJSONObject(it).let { x -> StoreProduct(x.getInt("id"), x.optInt("batch_id"), x.getString("name"), x.optString("category"), x.optString("barcode"), x.optString("expiry_date"), x.optInt("quantity"), if (x.isNull("checked_quantity")) null else x.optInt("checked_quantity"), x.optString("checked_at"), x.optString("checked_by")) } }.toMutableList()
        pendingOperations().forEach { op ->
            val path=op.optString("path"); val method=op.optString("method"); val body=op.optJSONObject("body")
            if(path=="/products" && method=="POST" && body!=null) {
                val localId=op.optInt("local_id")
                result.add(StoreProduct(localId,localId,body.optString("name"),body.optString("category"),body.optString("barcode"),body.optString("expiry_date"),body.optInt("quantity")))
            } else if(path.matches(Regex("/products/-?\\d+/batches")) && method=="POST" && body!=null) {
                val productId=path.removePrefix("/products/").substringBefore("/").toIntOrNull() ?: 0
                val parent=result.firstOrNull{it.id==productId}
                if(parent!=null) result.add(StoreProduct(parent.id,op.optInt("local_id"),parent.name,parent.category,parent.barcode,body.optString("expiry_date"),body.optInt("quantity")))
            } else if(path.startsWith("/batches/") && method=="PATCH" && body!=null) {
                val batchId=path.substringAfter("/batches/").toIntOrNull() ?: 0
                val index=result.indexOfFirst{it.batchId==batchId}; if(index>=0) result[index]=result[index].copy(quantity=body.optInt("quantity",result[index].quantity))
            }
        }
        return result.sortedWith(compareBy<StoreProduct>{it.name.lowercase(Locale.ROOT)}.thenBy{it.expiry})
    }
    suspend fun createProduct(name: String, category: String, barcode: String, expiry: String, quantity: Int) {
        call("POST", "/products", JSONObject().put("name", name).put("category", category).put("barcode", barcode).put("expiry_date", expiry).put("quantity", quantity))
    }
    suspend fun createBatch(productId: Int, expiry: String, quantity: Int) {
        call("POST", "/products/$productId/batches", JSONObject().put("expiry_date", expiry).put("quantity", quantity))
    }
    suspend fun updateBatchQuantity(batchId: Int, quantity: Int) {
        call("PATCH", "/batches/$batchId", JSONObject().put("quantity", quantity).put("shift_date", LocalDate.now().toString()))
    }
    suspend fun checkBatchQuantity(batchId: Int, quantity: Int) {
        call("POST", "/batches/$batchId/check", JSONObject().put("counted_quantity", quantity).put("shift_date", LocalDate.now().toString()))
    }
    fun clearLocalSession() { token = "" }
    suspend fun logout() { if (token.isNotBlank()) runCatching { call("POST", "/auth/logout", JSONObject(), queueOnOffline = false) }; clearLocalSession() }
}

@Composable
fun StorePortal(context: Context, english: Boolean = true, visible: Boolean = true, onOverview: (StoreOverview) -> Unit = {}) {
    val api = remember { StoreApiClient(context) }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(loadStoreProfile(context)) }
    var tasks by remember { mutableStateOf<List<StoreTask>>(emptyList()) }
    var staff by remember { mutableStateOf<List<StoreStaff>>(emptyList()) }
    var shifts by remember { mutableStateOf<List<StoreShift>>(emptyList()) }
    var products by remember { mutableStateOf<List<StoreProduct>>(emptyList()) }
    var page by remember { mutableStateOf("Tasks") }
    var error by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    var introVisible by remember { mutableStateOf(false) }; var dataReady by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf(StoreConnectionState.CHECKING) }
    var pendingChanges by remember { mutableIntStateOf(api.pendingCount()) }

    fun refresh(showLoading: Boolean = true) {
        scope.launch {
            if (showLoading) { loading = true; dataReady = false }; error = ""
            try {
                coroutineScope {
                    val manager = profile?.role == "manager"
                    val taskRequest = async { runCatching { api.tasks() } }
                    val shiftRequest = async { runCatching { api.shifts() } }
                    val productRequest = async { runCatching { api.products() } }
                    val staffRequest = if (manager) async { runCatching { api.staff() } } else null
                    val results = mutableListOf<Result<*>>(taskRequest.await(), shiftRequest.await(), productRequest.await())
                    if (staffRequest != null) results.add(staffRequest.await())
                    var successfulLoads = 0
                    taskRequest.await().onSuccess { tasks = it; successfulLoads++ }
                    shiftRequest.await().onSuccess { shifts = it; successfulLoads++ }
                    productRequest.await().onSuccess { products = it; successfulLoads++ }
                    staffRequest?.await()?.onSuccess { staff = it; successfulLoads++ }
                    val authFailure = results.mapNotNull { it.exceptionOrNull() }.filterIsInstance<StoreHttpException>().firstOrNull { it.statusCode == 401 }
                    if (authFailure != null) { api.clearLocalSession(); profile = null; persistStoreProfile(context, null) }
                    if (successfulLoads == 0) throw results.mapNotNull { it.exceptionOrNull() }.firstOrNull() ?: IllegalStateException("Could not load Store data")
                    error = ""
                    // Expiry overview is useful to both managers and staff; permissions
                    // are enforced by the server for product operations, not by hiding counts.
                    val soon = products.count { product ->
                        runCatching { val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(product.expiry)); days in 0L..7L }.getOrDefault(false)
                    }
                    onOverview(StoreOverview(tasks.size, tasks.count { it.done }, shifts.size, products.size, soon, profile?.role.orEmpty()))
                }
            } catch (e: StoreHttpException) {
                error = e.message ?: "Could not load Store data"
                if (e.statusCode == 401) { api.clearLocalSession(); profile = null; persistStoreProfile(context, null) }
            } catch (e: Exception) { error = if (e is IOException) "Vpn khod ra roshn konid" else (e.message ?: "Could not load Store data") }
            finally { if (showLoading) { loading = false; dataReady = true }; pendingChanges = api.pendingCount() }
        }
    }
    val pullRefreshThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val pullRefreshConnection = remember(profile?.id, loading, pullRefreshThreshold) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    pullDistance = when {
                        available.y > 0f -> (pullDistance + available.y).coerceAtMost(pullRefreshThreshold * 1.5f)
                        available.y < 0f -> (pullDistance + available.y).coerceAtLeast(0f)
                        else -> pullDistance
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val shouldRefresh = pullDistance >= pullRefreshThreshold && !loading && profile != null
                pullDistance = 0f
                if (shouldRefresh) refresh()
                return Velocity.Zero
            }
        }
    }

    DisposableEffect(profile?.id, visible) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (profile != null && visible) {
                    scope.launch {
                        connectionState = api.probeConnection()
                        if (connectionState == StoreConnectionState.ONLINE) {
                            if (api.pendingCount() > 0) api.flushPending()
                            pendingChanges = api.pendingCount()
                            refresh(showLoading = false)
                        }
                    }
                }
            }
        }
        if (profile != null && visible) runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    LaunchedEffect(profile?.id, visible) {
        if (profile != null && visible) {
            while (true) {
                // Background polling is deliberately gentle: the app is used a few times a day,
                // while explicit screen entry and user writes still sync immediately.
                connectionState = api.probeConnection()
                if (connectionState == StoreConnectionState.ONLINE && api.pendingCount() > 0) {
                    api.flushPending()
                    pendingChanges = api.pendingCount()
                }
                if (connectionState == StoreConnectionState.ONLINE && !loading) {
                    refresh(showLoading = false)
                }
                pendingChanges = api.pendingCount()
                delay(15 * 60 * 1000L)
            }
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            if (api.token.isBlank()) {
                profile = null
                persistStoreProfile(context, null)
                onOverview(StoreOverview())
                dataReady = true
            } else {
                loading = true
                try {
                    dataReady = false
                    profile = api.profile()
                    persistStoreProfile(context, profile)
                    scheduleManagerNightly(context, profile?.role == "manager")
                    introVisible = !context.getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE).getBoolean("intro_seen", false)
                } catch (e: StoreHttpException) {
                    if (e.statusCode == 401) { api.clearLocalSession(); profile = null; persistStoreProfile(context, null) }
                    error = if (e.statusCode == 401) "unauthorized" else (e.message ?: "Could not load Store data")
                    dataReady = true
                } catch (e: Exception) {
                    error = if (e is IOException) "Vpn khod ra roshn konid" else (e.message ?: "Could not load Store data")
                    dataReady = true
                }
                loading = false
            }
        }
    }
    LaunchedEffect(profile?.id, visible) { if (profile != null && visible && !dataReady) refresh() }
    LaunchedEffect(introVisible) {
        if (introVisible) {
            kotlinx.coroutines.delay(2200)
            while (!dataReady) kotlinx.coroutines.delay(100)
            introVisible = false
            context.getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply()
        }
    }

    if (!visible) { Box(Modifier.size(0.dp)); return }
    if (introVisible) { StoreLoadingVideo(context); return }

    CompositionLocalProvider(LocalStoreEnglish provides english) {
    Column(Modifier.fillMaxSize().background(StoreBlack).nestedScroll(pullRefreshConnection).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(storeText("STORE WORKSPACE"), color = StoreMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text(if (profile == null) storeText("Shared workspace") else profile!!.displayName, color = StoreInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Rounded.Storefront, null, tint = StoreMint, modifier = Modifier.size(30.dp))
        }
        if (profile != null) {
            val indicatorColor = when {
                connectionState == StoreConnectionState.ONLINE && pendingChanges == 0 -> Color(0xFF34D399)
                connectionState == StoreConnectionState.SERVER_ERROR -> Color(0xFFEF4444)
                connectionState == StoreConnectionState.NO_INTERNET || connectionState == StoreConnectionState.VPN_OR_ROUTE || pendingChanges > 0 -> Color(0xFFF59E0B)
                else -> StoreMuted
            }
            val indicatorText = when {
                connectionState == StoreConnectionState.ONLINE && pendingChanges == 0 -> if(LocalStoreEnglish.current) "Online · Synced" else "متصل · همگام‌شده"
                connectionState == StoreConnectionState.ONLINE -> if(LocalStoreEnglish.current) "Online · Syncing $pendingChanges" else "متصل · در حال ارسال $pendingChanges تغییر"
                connectionState == StoreConnectionState.NO_INTERNET -> if(LocalStoreEnglish.current) "No internet" else "اینترنت قطع است"
                connectionState == StoreConnectionState.VPN_OR_ROUTE -> if(LocalStoreEnglish.current) "VPN / route unavailable" else "فیلترشکن یا مسیر سرور در دسترس نیست"
                connectionState == StoreConnectionState.SERVER_ERROR -> if(LocalStoreEnglish.current) "Server error" else "خطای سرور"
                else -> if(LocalStoreEnglish.current) "Checking connection…" else "در حال بررسی اتصال…"
            }
            Row(Modifier.fillMaxWidth().padding(top=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).background(indicatorColor,CircleShape))
                Text(indicatorText,color=indicatorColor,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
            }
        }
        if (profile != null && (connectionState != StoreConnectionState.ONLINE || pendingChanges > 0)) {
            val warning = pendingChanges >= 3
            val bannerColor = when (connectionState) { StoreConnectionState.NO_INTERNET -> Color(0xFFB45309); StoreConnectionState.VPN_OR_ROUTE -> Color(0xFFB45309); StoreConnectionState.SERVER_ERROR -> Color(0xFFB91C1C); StoreConnectionState.ONLINE -> Color(0xFFD97706); else -> Color(0xFF64748B) }
            Card(colors = CardDefaults.cardColors(containerColor = bannerColor.copy(alpha = .18f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Storefront, null, tint = bannerColor)
                        Text(storeText(when(connectionState) { StoreConnectionState.NO_INTERNET -> "No internet connection. Check your internet."; StoreConnectionState.VPN_OR_ROUTE -> "VPN may be off or the server route is unavailable. Turn on your VPN and retry."; StoreConnectionState.SERVER_ERROR -> "The server is not responding. Your changes are saved on this device."; StoreConnectionState.ONLINE -> "Connection restored; syncing your changes."; else -> "Offline" }), color = StoreInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (pendingChanges > 0) Text("$pendingChanges ${storeText("changes waiting to sync")}", color = StoreInk, fontSize = 11.sp)
                    if (warning) Text(if (LocalStoreEnglish.current) "Please reconnect soon so the latest changes reach the shared workspace." else "لطفاً هرچه زودتر اتصال را برقرار کنید تا تغییرات به فضای مشترک ارسال شوند.", color = StoreInk, fontSize = 11.sp)
                }
            }
        }
        if (profile == null) {
            Card(colors = CardDefaults.cardColors(containerColor = StoreCard), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(storeText("Sign in to Store"), color = StoreInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(storeText("Your shared team workspace"), color = StoreMuted, fontSize = 12.sp)
                    StoreField(username, { username = it }, "Username")
                    StoreField(password, { password = it }, "Password", true)
                    Button(onClick = { scope.launch { loading = true; error = ""; try { dataReady = false; profile = api.login(username.trim(), password); persistStoreProfile(context, profile); scheduleManagerNightly(context, profile?.role == "manager"); scheduleStoreNotifications(context); context.getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE).edit().putBoolean("intro_seen", false).apply(); introVisible = true; password = "" } catch (e: Exception) { introVisible = false; dataReady = true; error = if (e is IOException) "Vpn khod ra roshn konid" else (e.message ?: "Login failed") }; loading = false } }, enabled = !loading && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = StoreMint, contentColor = StoreBlack)) { Text(storeText(if (loading) "Connecting…" else "Sign in"), fontWeight = FontWeight.Bold) }
                    if (error.isNotBlank()) Text(storeText(error), color = Color(0xFFFF8A80), fontSize = 12.sp)
                    Text(storeText("Sign in once. This device keeps your session until you log out or access is revoked."), color = StoreMuted, fontSize = 12.sp)
                }
            }
        } else {
            val manager = profile!!.role == "manager"
            val tabs = if (manager) listOf("Tasks", "Staff", "Shifts", "Products") else listOf("My tasks", "Schedule", "Products")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                tabs.forEach { tab -> FilterChip(selected = page == tab, onClick = { page = tab }, label = { Text(storeText(tab), fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StoreMint, selectedLabelColor = StoreBlack)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = StoreMint)
            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(150)) + slideInHorizontally(tween(150)) { it / 14 }) togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(100)) { -it / 18 })
                },
                label = "store-tab-transition"
            ) { activePage ->
                when (activePage) {
                    "Staff" -> ManagerStaff(staff, modifier = Modifier.fillMaxSize(), onCreate = { u, n, p -> scope.launch { runCatching { api.createStaff(u,n,p); staff=api.staff() }.onFailure { error=it.message ?: "Create failed" } } }, onToggle = { id, active -> scope.launch { runCatching { api.setStaffActive(id,active); staff=api.staff() }.onFailure { error=it.message ?: "Update failed" } } })
                    "Shifts", "Schedule" -> ShiftPanel(manager, staff, shifts, modifier = Modifier.fillMaxSize(), onSave = { d,t,ids -> scope.launch { runCatching { api.saveShift(d,t,ids); shifts=api.shifts() }.onFailure { error=it.message ?: "Save failed" } } }, onDelete = { id -> scope.launch { runCatching { api.deleteShift(id); shifts=api.shifts() }.onFailure { error=it.message ?: "Delete failed" } } })
                    "Products" -> ProductPanel(products, modifier = Modifier.fillMaxSize(), canUpdateQuantity = manager || shifts.any { it.date == LocalDate.now().toString() && it.type in setOf("morning", "evening", "full_day") && profile!!.id in it.memberIds }, onCreate = { n,c,b,e,q -> scope.launch { runCatching { api.createProduct(n,c,b,e,q); products=api.products() }.onFailure { error=it.message ?: "Save failed" } } }, onAddBatch = { id,e,q -> scope.launch { runCatching { api.createBatch(id,e,q); products=api.products() }.onFailure { error=it.message ?: "Batch save failed" } } }, onCheckQuantity = { id,q -> scope.launch { runCatching { api.checkBatchQuantity(id,q); products=api.products() }.onFailure { error=it.message ?: "Count could not be saved" } } })
                    else -> TaskPanel(manager, tasks, staff, modifier = Modifier.fillMaxSize(), onComplete = { id, done -> scope.launch { runCatching { api.complete(id,done); tasks=api.tasks() }.onFailure { error=it.message ?: "Update failed" } } }, onCreate = { title,date,type,assignee -> scope.launch { runCatching { api.createTask(title,date,type,assignee); tasks=api.tasks() }.onFailure { error=it.message ?: "Create failed" } } }, onUpdate = { id,title,date,type,assignee -> scope.launch { runCatching { api.updateTask(id,title,date,type,assignee); tasks=api.tasks() }.onFailure { error=it.message ?: "Edit failed" } } }, onDelete = { id -> scope.launch { runCatching { api.deleteTask(id); tasks=api.tasks() }.onFailure { error=it.message ?: "Delete failed" } } })
                }
            }
            if (error.isNotBlank()) Text(storeText(error), color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
    }
    }
}

@Composable private fun StoreLoadingVideo(context: Context) {
    Box(Modifier.fillMaxSize().background(StoreBlack), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    val videoResourceId = viewContext.resources.getIdentifier("loading_intro", "raw", viewContext.packageName)
                    if (videoResourceId != 0) {
                        setVideoURI(Uri.parse("android.resource://${viewContext.packageName}/$videoResourceId"))
                        setOnPreparedListener { player -> player.isLooping = true; start() }
                    }
                }
            },
            update = { if (!it.isPlaying) it.start() },
            onRelease = { it.stopPlayback() }
        )
    }
}

@Composable private fun StoreField(value: String, onChange: (String)->Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(storeText(label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = StoreMint, unfocusedBorderColor = Color(0xFF454B5B),
            focusedLabelColor = StoreMint, cursorColor = StoreMint,
            focusedTextColor = StoreInk, unfocusedTextColor = StoreInk
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StoreDatePicker(value: String, onChange: (String) -> Unit, label: String) {
    var dialogOpen by remember { mutableStateOf(false) }
    val baseConfiguration = LocalConfiguration.current
    // Read CompositionLocal while in the composable scope; remember's calculation is not composable.
    val isEnglish = LocalStoreEnglish.current
    val pickerConfiguration = remember(baseConfiguration, isEnglish) {
        Configuration(baseConfiguration).apply {
            setLocale(if (isEnglish) Locale.ENGLISH else Locale("fa", "IR"))
        }
    }
    OutlinedButton(
        onClick = { dialogOpen = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreInk),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF454B5B))
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(storeText(label), color = StoreMuted, fontSize = 10.sp)
                Text(displayStoreDate(value, LocalStoreEnglish.current), color = StoreInk, fontSize = 14.sp)
            }
            Text(storeText("Choose date"), color = StoreMint, fontSize = 11.sp)
        }
    }

    if (dialogOpen) {
        val currentDate = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = currentDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { dialogOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    dialogOpen = false
                }) { Text(storeText("Select"), color = StoreMint) }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) { Text(storeText("Cancel"), color = StoreMuted) }
            }
        ) {
            CompositionLocalProvider(LocalConfiguration provides pickerConfiguration) { DatePicker(state = pickerState, showModeToggle = false) }
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors=CardDefaults.cardColors(containerColor=StoreCard), shape=RoundedCornerShape(20.dp), elevation=CardDefaults.cardElevation(defaultElevation = 5.dp)) { Column(Modifier.padding(11.dp)) { Text(storeText(label),color=StoreMuted,fontSize=9.sp); Text(storeText(value),color=StoreMint,fontSize=15.sp,fontWeight=FontWeight.Bold) } }
}

@Composable private fun PanelCard(content: @Composable ColumnScope.()->Unit) {
    Card(colors=CardDefaults.cardColors(containerColor=StoreCard), shape=RoundedCornerShape(23.dp), modifier=Modifier.fillMaxWidth(), elevation=CardDefaults.cardElevation(defaultElevation = 6.dp)) { Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(9.dp), content=content) }
}

@Composable private fun ManagerStaff(staff: List<StoreStaff>, modifier: Modifier = Modifier, onCreate: (String,String,String)->Unit, onToggle: (Int,Boolean)->Unit) {
    var username by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { PanelCard {
            Text(storeText("Add staff member"), color=StoreInk, fontWeight=FontWeight.Bold)
            StoreField(username,{username=it},"Username"); StoreField(name,{name=it},"Full name"); StoreField(password,{password=it},"Temporary password",true)
            Button(onClick={ onCreate(username.trim(),name.trim(),password); username="";name="";password="" }, enabled=username.isNotBlank()&&name.isNotBlank()&&password.length>=6, colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)) { Text(storeText("Create staff account")) }
        } }
        item { Text(storeText("Staff members"), color=StoreInk, fontWeight=FontWeight.Bold) }
        items(staff, key = { it.id }) { s -> PanelCard { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(s.name,color=StoreInk,fontWeight=FontWeight.Medium); Text(storeText("@${s.username}"),color=StoreMuted,fontSize=11.sp) }; TextButton(onClick={onToggle(s.id,!s.active)}) { Text(storeText(if(s.active) "Disable" else "Enable"),color=if(s.active) Color(0xFFFF8A80) else StoreMint,fontSize=11.sp) } } } }
    }
}

@Composable private fun ShiftPanel(manager: Boolean, staff: List<StoreStaff>, shifts: List<StoreShift>, modifier: Modifier = Modifier, onSave: (String,String,Set<Int>)->Unit, onDelete: (Int)->Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var type by remember { mutableStateOf("morning") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(date,type,shifts) { selected = shifts.firstOrNull { it.date==date && it.type==type }?.memberIds?.toSet() ?: emptySet() }
    val first = month.withDayOfMonth(1)
    val weekOffset = if (LocalStoreEnglish.current) first.dayOfWeek.value - 1 else (first.dayOfWeek.value + 1) % 7
    val calendarStart = first.minusDays(weekOffset.toLong())
    val calendarDays = remember(calendarStart) { (0 until 42).map { calendarStart.plusDays(it.toLong()) } }
    val shiftsByDate = remember(shifts) { shifts.groupBy { it.date } }
    val selectedDateShifts = remember(shiftsByDate, date) { shiftsByDate[date].orEmpty().ifEmpty { shifts } }
    val activeStaff = remember(staff) { staff.filter { it.active } }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { PanelCard {
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth()) {
                Text(if(LocalStoreEnglish.current) month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) else displayStoreDate(month.toString(), false).substringBeforeLast("/"),color=StoreInk,fontWeight=FontWeight.Bold,fontSize=16.sp)
                Row {
                    TextButton(onClick={month=month.minusMonths(1)}){Text("‹",color=StoreMint,fontSize=22.sp)}
                    TextButton(onClick={month=month.plusMonths(1)}){Text("›",color=StoreMint,fontSize=22.sp)}
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val weekdays = if(LocalStoreEnglish.current) listOf("M","T","W","T","F","S","S") else listOf("ش","ی","د","س","چ","پ","ج")
                weekdays.forEach { day -> Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Text(day,color=StoreMuted,fontSize=10.sp,fontWeight=FontWeight.Bold)} }
            }
            calendarDays.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        val iso = day.toString()
                        val dayShifts = shiftsByDate[iso].orEmpty()
                        val selectedDay = date == iso
                        Box(Modifier.weight(1f).height(43.dp).padding(2.dp).background(if(selectedDay) StoreMint.copy(alpha=.22f) else Color.Transparent,RoundedCornerShape(11.dp)).clickable { date=iso; if(day.month!=month.month) month=day.withDayOfMonth(1) },contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(0.dp)) {
                                Text(if(LocalStoreEnglish.current) day.dayOfMonth.toString() else day.dayOfMonth.toString().map { ch -> if(ch in '0'..'9') ('۰'.code + (ch-'0')).toChar() else ch }.joinToString(""),color=if(day.month==month.month) StoreInk else StoreMuted.copy(alpha=.35f),fontSize=11.sp,fontWeight=if(selectedDay) FontWeight.Bold else FontWeight.Normal)
                                if(dayShifts.isNotEmpty()) Text("•",color=if(dayShifts.any { it.type=="full_day" }) StoreMint else Color(0xFFF6C66A),fontSize=12.sp,lineHeight=10.sp)
                            }
                        }
                    }
                }
            }
            Text((if(LocalStoreEnglish.current) "Selected: " else "تاریخ انتخاب‌شده: ")+displayStoreDate(date,LocalStoreEnglish.current),color=StoreMint,fontSize=11.sp)
        } }
        if(manager) item { PanelCard {
            Text(storeText("Schedule a shift"),color=StoreInk,fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth()) {
                listOf("morning","evening","full_day").forEach { shiftType -> FilterChip(selected=type==shiftType,onClick={type=shiftType},label={Text(storeText(when(shiftType){"morning"->"Morning";"evening"->"Evening";else->"Full day"}),fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=StoreMint,selectedLabelColor=StoreBlack)) }
            }
            Text(storeText("Select staff"),color=StoreMuted,fontSize=12.sp)
            activeStaff.forEach { s -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=s.id in selected,onCheckedChange={selected=if(it) selected+s.id else selected-s.id},colors=CheckboxDefaults.colors(checkedColor=StoreMint)); Text(s.name,color=StoreInk) } }
            Button(onClick={onSave(date,type,selected);selected= emptySet()},enabled=runCatching{LocalDate.parse(date)}.isSuccess,colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)){Text(storeText("Save schedule"))}
        } }
        item { Text(storeText(if(manager) "Upcoming shifts" else "My schedule & colleagues"),color=StoreInk,fontWeight=FontWeight.Bold) }
        items(selectedDateShifts, key={it.id}) { shift -> PanelCard {
            Text("${displayStoreDate(shift.date, LocalStoreEnglish.current)} · ${storeText(when(shift.type){"morning"->"Morning";"evening"->"Evening";else->"Full day"})}",color=StoreMint,fontWeight=FontWeight.SemiBold)
            Text(if(shift.members.isEmpty()) storeText("No staff assigned") else shift.members.joinToString(" · "),color=StoreInk,fontSize=13.sp)
            if(manager) TextButton(onClick={onDelete(shift.id)},enabled=shift.id>0 || shift.id<0){Text(storeText("Delete"),color=Color(0xFFFF8A80),fontSize=11.sp)}
        } }
    }
}

@Composable private fun TaskPanel(manager: Boolean, tasks: List<StoreTask>, staff: List<StoreStaff>, modifier: Modifier = Modifier, onComplete: (Int,Boolean)->Unit, onCreate: (String,String,String,Int?)->Unit, onUpdate: (Int,String,String,String,Int?)->Unit, onDelete: (Int)->Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }; var type by remember { mutableStateOf("morning") }; var assignee by remember { mutableStateOf("Shared shift task") }; var editingId by remember { mutableStateOf<Int?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val editScope = rememberCoroutineScope()
    val activeStaff = remember(staff) { staff.filter { it.active } }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(9.dp)) {
        if(manager) item { PanelCard {
            Text(storeText("Create task"),color=StoreInk,fontWeight=FontWeight.Bold)
            StoreField(title,{title=it},"Task title"); StoreDatePicker(date,{date=it},"Task date")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("morning","evening").forEach{FilterChip(selected=type==it,onClick={type=it},label={Text(storeText(if(it=="morning") "Morning" else "Evening"))})}}
            var expanded by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(storeText(assignee),color=StoreInk)}; DropdownMenu(expanded,{expanded=false}) {
                DropdownMenuItem(text={Text(storeText("Shared shift task"))},onClick={ assignee="Shared shift task"; expanded=false })
                activeStaff.forEach { s -> DropdownMenuItem(text={Text(s.name)},onClick={ assignee="${s.id}:${s.name}"; expanded=false }) }
            } }
            Button(onClick={val id=assignee.substringBefore(":").toIntOrNull(); if(editingId==null) onCreate(title.trim(),date,type,id) else onUpdate(editingId!!,title.trim(),date,type,id); title=""; editingId=null; assignee="Shared shift task"},enabled=title.isNotBlank()&&runCatching{LocalDate.parse(date)}.isSuccess,colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)){Text(storeText(if(editingId==null) "Create task" else "Save task changes"))}
            if (editingId != null) TextButton(onClick = { editingId=null; title=""; assignee="Shared shift task"; date=LocalDate.now().toString() }) { Text(storeText("Cancel"), color=StoreMuted) }
            Text(storeText("Assigned tasks are visible only to that employee and managers."),color=StoreMuted,fontSize=10.sp)
        } }
        item { Text(storeText(if(manager) "Store tasks" else "My tasks"),color=StoreInk,fontWeight=FontWeight.Bold) }
        items(tasks, key = { it.id }) { task -> PanelCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(task.title,color=StoreInk,fontWeight=FontWeight.Medium); Text("${displayStoreDate(task.date, LocalStoreEnglish.current)} · ${storeText(if(task.shift=="morning") "Morning" else if(task.shift=="evening") "Evening" else task.shift)} · ${task.assignedName?.let { storeText(it) } ?: storeText("Shared")}",color=StoreMuted,fontSize=10.sp) }
                Checkbox(checked=task.done,onCheckedChange={onComplete(task.id,it)},enabled=!manager || task.assignedName!=null,colors=CheckboxDefaults.colors(checkedColor=StoreMint))
                if(manager) {
                    TextButton(onClick={title=task.title;date=task.date;type=task.shift;assignee=staff.firstOrNull { it.name==task.assignedName }?.let { "${it.id}:${it.name}" } ?: "Shared shift task";editingId=task.id;editScope.launch { listState.animateScrollToItem(0) }}) { Text(storeText("Edit"),color=StoreMint,fontSize=10.sp) }
                    TextButton(onClick={onDelete(task.id)}){Text(storeText("Delete"),color=Color(0xFFFF8A80),fontSize=10.sp)}
                }
            }
            if(manager && task.assignedName==null) Text(if(LocalStoreEnglish.current) "Completed by ${task.doneCount} of ${task.memberCount} assigned staff" else "${task.memberCount} نفر پرسنل؛ ${task.doneCount} نفر انجام داده‌اند",color=StoreMuted,fontSize=10.sp)
        } }
    }
}

@Composable private fun ProductPanel(products: List<StoreProduct>, modifier: Modifier = Modifier, canUpdateQuantity: Boolean, onCreate: (String,String,String,String,Int)->Unit, onAddBatch: (Int,String,Int)->Unit, onCheckQuantity: (Int,Int)->Unit) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("") }; var barcode by remember { mutableStateOf("") }; var expiry by remember { mutableStateOf(LocalDate.now().plusMonths(1).toString()) }; var quantity by remember { mutableStateOf("1") }
    var batchProductId by remember { mutableStateOf<Int?>(null) }; var batchExpanded by remember { mutableStateOf(false) }; var batchExpiry by remember { mutableStateOf(LocalDate.now().plusMonths(2).toString()) }; var batchQuantity by remember { mutableStateOf("1") }
    var query by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("all") }
    var showProductForm by remember { mutableStateOf(false) }; var showBatchForm by remember { mutableStateOf(false) }
    var countTarget by remember { mutableStateOf<StoreProduct?>(null) }; var countedQuantity by remember { mutableStateOf("") }; var confirmCount by remember { mutableStateOf(false) }
    val groupedProducts = remember(products) { products.groupBy { it.id }.toList() }
    val filteredProducts = remember(groupedProducts, query, filter) {
        groupedProducts.filter { (_, batches) ->
            val first = batches.first()
            val matchesText = query.isBlank() || first.name.contains(query, true) || first.barcode.contains(query, true) || first.category.contains(query, true)
            val matchesStatus = batches.any { batch ->
                val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(batch.expiry)) }.getOrDefault(Long.MAX_VALUE)
                when (filter) { "critical" -> days in 0L..3L; "expired" -> days < 0; "soon" -> days in 4L..14L; else -> true }
            }
            matchesText && matchesStatus
        }.sortedBy { (_, batches) -> batches.minOfOrNull { it.expiry } ?: "9999-12-31" }
    }
    val today = LocalDate.now()
    val criticalBatches = remember(products, today) {
        products.filter { batch ->
            val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(batch.expiry)) }.getOrDefault(Long.MAX_VALUE)
            days < 0L || days in 0L..3L
        }
    }
    val criticalCount = criticalBatches.size
    val needsPhysicalCount = remember(criticalBatches, today) { criticalBatches.filter { it.checkedAt.take(10) != today.toString() } }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("PRODUCTS", products.distinctBy { it.id }.size.toString(), Modifier.weight(1f))
                Metric("BATCHES", products.size.toString(), Modifier.weight(1f))
                Metric("CRITICAL", criticalCount.toString(), Modifier.weight(1f))
            }
        }
        if (needsPhysicalCount.isNotEmpty()) item {
            PanelCard {
                Text(if(LocalStoreEnglish.current) "PHYSICAL STOCK CHECK · ${needsPhysicalCount.size} batches" else "بررسی موجودی واقعی · ${needsPhysicalCount.size} سری محصول", color=Color(0xFFFFB454), fontWeight=FontWeight.Bold, fontSize=12.sp)
                Text(if(LocalStoreEnglish.current) "Please go to the shelf / stockroom, count the actual remaining units, and record the number below." else "لطفاً به قفسه یا انبار بروید، تعداد واقعی باقی‌مانده را بشمارید و ثبت کنید.", color=StoreMuted, fontSize=11.sp)
                needsPhysicalCount.take(8).forEach { batch ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(batch.name, color=StoreInk, fontWeight=FontWeight.SemiBold, fontSize=12.sp)
                            Text("${displayStoreDate(batch.expiry, LocalStoreEnglish.current)} · ${if(LocalStoreEnglish.current) "system count" else "تعداد سیستمی"}: ${batch.quantity}", color=StoreMuted, fontSize=10.sp)
                        }
                        TextButton(onClick={countTarget=batch;countedQuantity=batch.quantity.toString()}, enabled=canUpdateQuantity) { Text(if(LocalStoreEnglish.current) "Count stock" else "ثبت شمارش", color=StoreMint) }
                    }
                }
            }
        }
        item {
            OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text(if(LocalStoreEnglish.current) "Search name, category or barcode" else "جست‌وجوی نام، دسته یا بارکد")},shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=StoreInk,unfocusedTextColor=StoreInk,focusedBorderColor=StoreMint,unfocusedBorderColor=StoreMuted.copy(alpha=.4f),cursorColor=StoreMint))
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                listOf("all","critical","soon","expired").forEach { key ->
                    FilterChip(selected=filter==key,onClick={filter=key},label={Text(when(key){"all"->if(LocalStoreEnglish.current) "All" else "همه";"critical"->if(LocalStoreEnglish.current) "Critical" else "بحرانی";"soon"->if(LocalStoreEnglish.current) "Soon" else "نزدیک";else->if(LocalStoreEnglish.current) "Expired" else "منقضی"},fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=StoreMint,selectedLabelColor=StoreBlack))
                }
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                Button(onClick={showProductForm=!showProductForm;showBatchForm=false},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)){Text(if(LocalStoreEnglish.current) "+ New product" else "+ محصول جدید")}
                OutlinedButton(onClick={showBatchForm=!showBatchForm;showProductForm=false},modifier=Modifier.weight(1f)){Text(if(LocalStoreEnglish.current) "+ New batch" else "+ سری جدید",color=StoreMint)}
            }
        }
        if(showProductForm) item { PanelCard {
            Text(storeText("Add product / expiry batch"),color=StoreInk,fontWeight=FontWeight.Bold)
            StoreField(name,{name=it},"Product name"); StoreField(category,{category=it},"Category"); StoreField(barcode,{barcode=it},"Barcode (optional)"); StoreDatePicker(expiry,{expiry=it},"Expiry date"); StoreField(quantity,{quantity=it.filter(Char::isDigit)},"Quantity")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onCreate(name.trim(),category.trim(),barcode.trim(),expiry,quantity.toIntOrNull()?:0);name="";barcode="";showProductForm=false},enabled=name.isNotBlank()&&runCatching{LocalDate.parse(expiry)}.isSuccess&&quantity.toIntOrNull()!=null,colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)){Text(storeText("Save product"))}
                TextButton(onClick={showProductForm=false}){Text(storeText("Cancel"),color=StoreMuted)}
            }
            Text(storeText("Barcode is optional. Each product can have several batches with separate expiry dates."),color=StoreMuted,fontSize=10.sp)
        } }
        if(showBatchForm) item { PanelCard {
            Text(storeText("Add another expiry batch"),color=StoreInk,fontWeight=FontWeight.Bold)
            Box {
                OutlinedButton(onClick={batchExpanded=true},modifier=Modifier.fillMaxWidth(),enabled=products.isNotEmpty()){Text(products.firstOrNull { it.id==batchProductId }?.name ?: storeText("Choose existing product"),color=StoreInk)}
                DropdownMenu(expanded=batchExpanded,onDismissRequest={batchExpanded=false}) { products.distinctBy { it.id }.forEach { product -> DropdownMenuItem(text={Text(product.name)},onClick={batchProductId=product.id;batchExpanded=false}) } }
            }
            StoreDatePicker(batchExpiry,{batchExpiry=it},"Batch expiry date")
            StoreField(batchQuantity,{batchQuantity=it.filter(Char::isDigit)},"Batch quantity")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onAddBatch(batchProductId!!,batchExpiry,batchQuantity.toIntOrNull()?:0);batchProductId=null;showBatchForm=false},enabled=batchProductId!=null&&runCatching{LocalDate.parse(batchExpiry)}.isSuccess&&batchQuantity.toIntOrNull()!=null,colors=ButtonDefaults.buttonColors(containerColor=StoreMint,contentColor=StoreBlack)){Text(storeText("Add batch"))}
                TextButton(onClick={showBatchForm=false}){Text(storeText("Cancel"),color=StoreMuted)}
            }
        } }
        item { Text(if(LocalStoreEnglish.current) "EXPIRY INVENTORY · ${filteredProducts.size}" else "موجودی و انقضا · ${filteredProducts.size}",color=StoreInk,fontWeight=FontWeight.Bold) }
        if(filteredProducts.isEmpty()) item { PanelCard { Text(if(LocalStoreEnglish.current) "No matching products" else "محصولی با این مشخصات پیدا نشد",color=StoreMuted) } }
        items(filteredProducts, key = { it.first }) { (_, batches) ->
            val sortedBatches = remember(batches) { batches.sortedBy { it.expiry } }
            val first = sortedBatches.first()
            PanelCard {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(first.name,color=StoreInk,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                        Text(first.category.ifBlank { storeText("Uncategorized") },color=StoreMuted,fontSize=11.sp)
                    }
                    Text("${batches.sumOf { it.quantity }} ${storeText("Qty")}",color=StoreMint,fontWeight=FontWeight.Bold)
                }
                if(first.barcode.isNotBlank()) Text("${storeText("Barcode:")} ${first.barcode}",color=StoreMuted,fontSize=10.sp)
                sortedBatches.forEach { batch ->
                    val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(batch.expiry)) }.getOrDefault(Long.MAX_VALUE)
                    val statusColor = when { days < 0L -> Color(0xFFFF6B6B); days <= 3L -> Color(0xFFFFB454); days <= 14L -> Color(0xFFF6D365); else -> StoreMint }
                    Surface(color=statusColor.copy(alpha=.10f),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${storeText("Expiry:")} ${displayStoreDate(batch.expiry, LocalStoreEnglish.current)}",color=StoreInk,fontSize=11.sp)
                                Text(when { days < 0L -> if(LocalStoreEnglish.current) "Expired" else "منقضی شده"; days <= 3L -> if(LocalStoreEnglish.current) "Critical · $days days left" else "بحرانی · $days روز باقی‌مانده"; days <= 14L -> if(LocalStoreEnglish.current) "$days days left" else "$days روز باقی‌مانده"; else -> if(LocalStoreEnglish.current) "Normal" else "عادی" },color=statusColor,fontSize=10.sp)
                            }
                            Column(horizontalAlignment=Alignment.End, verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text("${batch.quantity}",color=StoreInk,fontWeight=FontWeight.Bold)
                                if (canUpdateQuantity) {
                                    TextButton(
                                        onClick={ countTarget=batch; countedQuantity=batch.quantity.toString(); confirmCount=false },
                                        enabled=batch.batchId!=0,
                                        contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)
                                    ) { Text(if(LocalStoreEnglish.current) "Edit stock" else "اصلاح موجودی",color=StoreMint,fontSize=10.sp) }
                                }
                            }
                        }
                    }
                    if (batch.checkedAt.isNotBlank()) Text(
                        (if(LocalStoreEnglish.current) "Last physical count: " else "آخرین شمارش واقعی: ") + "${batch.checkedQuantity ?: batch.quantity} · ${batch.checkedBy}",
                        color=StoreMuted, fontSize=9.sp
                    )
                }
            }
        }
    }
    if (countTarget != null && !confirmCount) {
        AlertDialog(
            onDismissRequest = { countTarget = null; confirmCount=false },
            title = { Text(if(LocalStoreEnglish.current) "Record / edit stock" else "ثبت یا اصلاح موجودی", color=StoreInk) },
            text = {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(countTarget!!.name, color=StoreMuted)
                    Text(if(LocalStoreEnglish.current) "Count the actual remaining units on the shelf and in the stockroom." else "تعداد واقعی باقی‌مانده در قفسه و انبار را بشمارید.", color=StoreMuted, fontSize=12.sp)
                    OutlinedTextField(value=countedQuantity, onValueChange={countedQuantity=it.filter(Char::isDigit)}, label={Text(if(LocalStoreEnglish.current) "Actual quantity" else "تعداد واقعی")}, singleLine=true)
                }
            },
            confirmButton = { TextButton(onClick={ if(countedQuantity.toIntOrNull()!=null) confirmCount=true }, enabled=countedQuantity.toIntOrNull()!=null) { Text(if(LocalStoreEnglish.current) "Continue" else "ادامه", color=StoreMint) } },
            dismissButton = { TextButton(onClick={countTarget=null;confirmCount=false}) { Text(if(LocalStoreEnglish.current) "Cancel" else "انصراف", color=StoreMuted) } },
            containerColor=StoreCard
        )
    }
    if (confirmCount && countTarget != null) {
        val target = countTarget!!
        val newQuantity = countedQuantity.toIntOrNull()
        AlertDialog(
            onDismissRequest = { confirmCount=false },
            title = { Text(if(LocalStoreEnglish.current) "Confirm stock change" else "تأیید تغییر موجودی", color=StoreInk) },
            text = {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(target.name, color=StoreMuted)
                    Text(if(LocalStoreEnglish.current) "Previous quantity: ${target.quantity}" else "تعداد قبلی: ${target.quantity}", color=StoreInk)
                    Text(if(LocalStoreEnglish.current) "New counted quantity: ${newQuantity ?: target.quantity}" else "تعداد جدید: ${newQuantity ?: target.quantity}", color=StoreMint, fontWeight=FontWeight.Bold)
                    Text(if(LocalStoreEnglish.current) "Only confirm after physically checking the shelf / stockroom." else "فقط پس از بررسی واقعی قفسه و انبار، این تغییر را تأیید کنید.", color=StoreMuted, fontSize=11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick={
                    if(newQuantity != null) onCheckQuantity(target.batchId,newQuantity)
                    confirmCount=false; countTarget=null
                }, enabled=newQuantity!=null) { Text(if(LocalStoreEnglish.current) "Confirm and save" else "تأیید و ثبت", color=StoreMint) }
            },
            dismissButton = { TextButton(onClick={confirmCount=false}) { Text(if(LocalStoreEnglish.current) "Back" else "بازگشت", color=StoreMuted) } },
            containerColor=StoreCard
        )
    }
}
