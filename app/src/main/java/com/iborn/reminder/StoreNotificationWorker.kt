package com.iborn.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val STORE_API = "https://iborn.pythonanywhere.com/api/v1"

fun scheduleStoreNotifications(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val periodic = PeriodicWorkRequestBuilder<StoreNotificationWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "reminder-store-notifications", ExistingPeriodicWorkPolicy.UPDATE, periodic
    )
    val immediate = OneTimeWorkRequestBuilder<StoreNotificationWorker>().setConstraints(constraints).build()
    WorkManager.getInstance(context).enqueueUniqueWork("reminder-store-notifications-now", ExistingWorkPolicy.KEEP, immediate)
}

class StoreNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val authPrefs = applicationContext.getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE)
    private val profilePrefs = applicationContext.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE)
    private val workerPrefs = applicationContext.getSharedPreferences("reminder_store_notifications", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = authPrefs.getString("token", "").orEmpty()
        val role = profilePrefs.getString("role", "").orEmpty()
        val userId = profilePrefs.getInt("id", 0)
        if (token.isBlank() || role.isBlank() || userId == 0) return@withContext Result.success()
        try {
            val previousUser = workerPrefs.getInt("notification_user_id", 0)
            if (previousUser != userId) workerPrefs.edit().remove("seen_task_ids").putInt("notification_user_id", userId).apply()
            // Retry the durable local queue even when the app UI is not open.
            StoreApiClient(applicationContext).flushPending()
            val today = LocalDate.now()
            val tasks = request("/tasks?from=$today&to=${today.plusMonths(12)}", token).optJSONArray("tasks") ?: JSONArray()
            notifyNewTasks(tasks, role)
            if (role == "staff") {
                val products = request("/products", token).optJSONArray("products") ?: JSONArray()
                val shifts = request("/shifts?from=$today&to=$today", token).optJSONArray("shifts") ?: JSONArray()
                val morningMember = (0 until shifts.length()).any { i ->
                    val shift = shifts.getJSONObject(i)
                    val type = shift.optString("shift_type")
                    if (type != "morning" && type != "full_day") false else {
                        val members = shift.optJSONArray("members") ?: JSONArray()
                        (0 until members.length()).any { j -> members.getJSONObject(j).optInt("id") == userId }
                    }
                }
                notifyExpiringProducts(products, morningMember, today, userId)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun request(path: String, token: String): JSONObject {
        val connection = (URL(STORE_API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Store API HTTP $code")
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }

    private fun notifyNewTasks(tasks: JSONArray, role: String) {
        if (role != "staff") return
        val current = (0 until tasks.length()).map { tasks.getJSONObject(it).optInt("id") }.filter { it > 0 }.toSet()
        val stored = workerPrefs.getString("seen_task_ids", null)
        if (stored == null) {
            val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
            val recent = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { task ->
                val created = runCatching { LocalDateTime.parse(task.optString("created_at").replace(" ", "T")) }.getOrNull()
                created != null && Duration.between(created, nowUtc).toMinutes() in 0L..20L
            }
            recent.forEach { task ->
                val english = applicationContext.getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
                val title = task.optString("title", "وظیفه جدید")
                postNotification(410000 + task.optInt("id"), if (english) "New task assigned" else "وظیفه جدید برای شما", if (english) title else "وظیفه «$title» به برنامه شما اضافه شد.")
            }
            workerPrefs.edit().putString("seen_task_ids", JSONArray(current.toList()).toString()).apply()
            return
        }
        val old = runCatching { JSONArray(stored) }.getOrDefault(JSONArray())
        val seen = (0 until old.length()).map { old.optInt(it) }.toSet()
        val fresh = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { it.optInt("id") in (current - seen) }
        fresh.forEach { task ->
            val title = task.optString("title", "وظیفه جدید")
            val english = applicationContext.getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
            postNotification(
                410000 + task.optInt("id"),
                if (english) "New task assigned" else "وظیفه جدید برای شما",
                if (english) title else "وظیفه «$title» به برنامه شما اضافه شد."
            )
        }
        workerPrefs.edit().putString("seen_task_ids", JSONArray(current.toList()).toString()).apply()
    }

    private fun notifyExpiringProducts(products: JSONArray, morningMember: Boolean, today: LocalDate, userId: Int) {
        val english = applicationContext.getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
        for (i in 0 until products.length()) {
            val product = products.getJSONObject(i)
            val batchId = product.optInt("batch_id", 0)
            if (batchId <= 0) continue
            val expiry = runCatching { LocalDate.parse(product.optString("expiry_date")) }.getOrNull() ?: continue
            val days = ChronoUnit.DAYS.between(today, expiry)
            if (days !in 0L..7L) continue
            val quantity = product.optInt("quantity")
            val name = product.optString("name", "محصول")
            val text = if (english) "$name · $quantity units · $days days left" else "$name · تعداد ثبت‌شده: $quantity · $days روز تا انقضا"
            val generalKey = "expiry_general_${userId}_${batchId}_${today}"
            val previousGeneralQty = workerPrefs.getInt("${generalKey}_qty", -1)
            if (!workerPrefs.getBoolean(generalKey, false) || previousGeneralQty != quantity) {
                postNotification(420000 + batchId, if (english) "Product expiry alert" else "هشدار تاریخ انقضا", text, silentUpdate = previousGeneralQty >= 0)
                workerPrefs.edit().putBoolean(generalKey, true).putInt("${generalKey}_qty", quantity).apply()
            }
            if (morningMember && days <= 3) {
                val morningKey = "expiry_morning_${userId}_${batchId}_${today}"
                val previousMorningQty = workerPrefs.getInt("${morningKey}_qty", -1)
                if (!workerPrefs.getBoolean(morningKey, false) || previousMorningQty != quantity) {
                    val body = if (english) "$text\nPlease verify the physical stock and update its quantity in the app." else "$text\nلطفاً موجودی واقعی را بررسی و تعداد را در برنامه به‌روزرسانی کنید."
                    postNotification(430000 + batchId, if (english) "Morning shift · critical product" else "شیفت صبح · محصول بحرانی", body, silentUpdate = previousMorningQty >= 0)
                    workerPrefs.edit().putBoolean(morningKey, true).putInt("${morningKey}_qty", quantity).apply()
                }
            }
        }
    }

    private fun postNotification(id: Int, title: String, body: String, silentUpdate: Boolean = false) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("store_alerts") == null) {
            val english = applicationContext.getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
            manager.createNotificationChannel(NotificationChannel("store_alerts", if (english) "Store alerts" else "اعلان‌های فروشگاه", NotificationManager.IMPORTANCE_HIGH))
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(applicationContext, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "store_alerts")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(android.graphics.Color.rgb(125, 249, 213))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(silentUpdate)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}
