package com.iborn.reminder

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases based updater. Set GITHUB_REPOSITORY before building. */
object UpdateManager {
    // Format: owner/repository. Example: myaccount/Reminder
    const val GITHUB_REPOSITORY = "ibornco/reminder3.0"
    private const val PREFS = "reminder_updater"
    private const val KEY_DOWNLOAD_ID = "download_id"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val version: String
    )

    suspend fun checkLatest(context: Context): Release = withContext(Dispatchers.IO) {
        require(!GITHUB_REPOSITORY.startsWith("YOUR_")) {
            "آدرس مخزن GitHub در UpdateManager.kt تنظیم نشده است."
        }
        val url = URL("https://api.github.com/repos/$GITHUB_REPOSITORY/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Reminder-Android-Updater")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("خطا در دریافت Release از GitHub (HTTP $code)")
            val json = JSONObject(response)
            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("فایل APK در Release پیدا نشد.")
            var apkUrl: String? = null
            var apkName = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkName = name
                    break
                }
            }
            val tag = json.optString("tag_name", "")
            val title = json.optString("name", "Reminder $tag")
            val versionPattern = Regex("\\d+(?:\\.\\d+){1,3}")
            val version = listOf(title, apkName, tag).firstNotNullOfOrNull { source ->
                versionPattern.find(source)?.value
            } ?: throw IllegalStateException("شماره نسخه در نام Release یا فایل APK مشخص نیست؛ مثلاً Reminder-3.0.1.apk")
            val resolvedUrl = apkUrl?.takeIf { it.startsWith("https://") }
                ?: throw IllegalStateException("فایل APK با پسوند apk به Release اضافه نشده است.")
            Release(tag, title, json.optString("body", ""), resolvedUrl, version)
        } finally {
            connection.disconnect()
        }
    }

    fun isNewer(current: String?, latest: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(current.orEmpty())
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (right != left) return right > left
        }
        return false
    }

    fun download(context: Context, release: Release): Long {
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Reminder ${release.version}")
            .setDescription("در حال دریافت به‌روزرسانی Reminder")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Reminder-${release.version}.apk")
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    fun downloadedId(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun installDownloaded(context: Context): Boolean {
        val id = downloadedId(context)
        if (id < 0) return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id) ?: return false
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permissionIntent)
            return false
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }
}
