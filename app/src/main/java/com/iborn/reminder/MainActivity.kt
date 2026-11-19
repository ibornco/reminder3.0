package com.iborn.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.DownloadManager
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private val Mint = Color(0xFFC5F4A9)
private val Lilac = Mint
private val PaleLilac = Color(0xFF20252D)
private val Ink = Color(0xFFEDF3ED)
private val Muted = Color(0xFF8C9B91)
private val AppBlack = Color(0xFF0B100F)
private val CardDark = Color(0xFF19231F)
private val CardDarkAlt = Color(0xFF23322A)
private val JournalPaper = Color(0xFF0B100F)
private val JournalCard = Color(0xFF19231F)
private val JournalInk = Color(0xFFEDF3ED)
private val JournalRed = Color(0xFFF05E5E)
private fun s(en: String, fa: String, english: Boolean) = if (english) en else fa

data class ReminderItem(val id: String, val title: String, val date: String, val time: String, val category: String, val done: Boolean = false, val createdAt: Long = System.currentTimeMillis())
data class BatchItem(val id: String, val product: String, val barcode: String, val quantity: Int, val expiry: String, val category: String)

class MainActivity : ComponentActivity() {
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == UpdateManager.downloadedId(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "دانلود آپدیت کامل شد؛ برای نصب به تنظیمات برنامه بروید.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedEnglish = getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
        val channel = NotificationChannel("reminders", if (selectedEnglish) "Reminder alerts" else "اعلان‌های یادآور", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("store_alerts", if (selectedEnglish) "Store alerts" else "اعلان‌های فروشگاه", NotificationManager.IMPORTANCE_HIGH))
        val storeRole = getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE).getString("role", "")
        scheduleManagerNightly(this, storeRole == "manager")
        if (getSharedPreferences("reminder_store_auth", Context.MODE_PRIVATE).getString("token", "").orEmpty().isNotBlank()) scheduleStoreNotifications(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        setContent { ReminderApp(this) }
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReminderApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("reminder_local", Context.MODE_PRIVATE) }
    var english by remember { mutableStateOf(prefs.getBoolean("english", true)) }
    var tab by remember { mutableIntStateOf(0) }
    var reminders by remember { mutableStateOf(loadReminders(prefs)) }
    var batches by remember { mutableStateOf(loadBatches(prefs)) }
    var productCategories by remember { mutableStateOf(loadCategories(prefs)) }
    var storeOverview by remember {
        mutableStateOf(StoreOverview(
            prefs.getInt("store_task_count", 0), prefs.getInt("store_done_count", 0),
            prefs.getInt("store_shift_count", 0), prefs.getInt("store_batch_count", 0),
            prefs.getInt("store_expiring_count", 0), prefs.getString("store_role", "").orEmpty()
        ))
    }
    var showReminder by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    var reminderCategory by remember { mutableStateOf("Daily") }
    var barcode by remember { mutableStateOf("") }
    val scanner = remember { GmsBarcodeScanning.getClient(context) }
    val colors = darkColorScheme(primary = Mint, secondary = Mint, background = JournalPaper, surface = JournalCard, surfaceVariant = CardDarkAlt, onBackground = JournalInk, onSurface = JournalInk)
    CompositionLocalProvider(LocalLayoutDirection provides if (english) LayoutDirection.Ltr else LayoutDirection.Rtl) {
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { BottomBar(tab, english) { tab = it } },
            floatingActionButton = {
                if (tab in setOf(2, 3, 4, 8)) FloatingActionButton(
                    onClick = { reminderCategory = when (tab) { 2 -> "Daily"; 3 -> "Installments"; 4 -> "Birthdays"; else -> "Miscellaneous" }; showReminder = true },
                    containerColor = Mint, contentColor = Color(0xFF102B27), shape = RoundedCornerShape(20.dp)
                ) { Icon(Icons.Rounded.Add, contentDescription = s("Add", "افزودن", english)) }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF111B18), Color(0xFF0A1110), Color(0xFF101917)))).padding(padding)) {
                ReminderBrandHeader(english) { tab = 1 }
                AnimatedContent(targetState = tab, transitionSpec = { (fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 18 }) togetherWith (fadeOut(tween(110)) + slideOutHorizontally(tween(110)) { -it / 22 }) }, label = "tab-transition") { page ->
                    when (page) {
                        0 -> HomeScreen(reminders, batches, storeOverview, english, { tab = 1 }, { tab = 5 })
                        1 -> PersonalHubScreen(reminders, batches, english, { tab = 2 }, { tab = 3 }, { tab = 4 }, { tab = 8 }, { tab = 9 })
                        2 -> ReminderList(reminders.filter { it.category == "Daily" }, english, { reminderCategory = "Daily"; showReminder = true }, { item -> val updated = item.copy(done = !item.done); reminders = reminders.map { if (it.id == item.id) updated else it }; if (updated.done) cancelReminder(context, item) else scheduleReminder(context, updated); saveReminders(prefs, reminders) }, { item -> cancelReminder(context, item); reminders = reminders.filterNot { it.id == item.id }; saveReminders(prefs, reminders) })
                        3 -> SimpleCategory(s("Installments", "اقساط", english), s("Payments and due dates", "پرداخت‌ها و سررسیدها", english), reminders.filter { it.category == "Installments" }, english, { reminderCategory = "Installments"; showReminder = true }, { item -> cancelReminder(context, item); reminders = reminders.filterNot { it.id == item.id }; saveReminders(prefs, reminders) })
                        4 -> SimpleCategory(s("Birthdays", "تولدها", english), s("Important dates", "تاریخ‌های مهم", english), reminders.filter { it.category == "Birthdays" }, english, { reminderCategory = "Birthdays"; showReminder = true }, { item -> cancelReminder(context, item); reminders = reminders.filterNot { it.id == item.id }; saveReminders(prefs, reminders) })
                        5 -> Spacer(Modifier.height(0.dp))
                        6 -> ProfileScreen(context, english)
                        7 -> SettingsScreen(context, english) { selected ->
                            english = selected
                            prefs.edit().putBoolean("english", selected).apply()
                            val channelName = if (selected) "Reminder alerts" else "اعلان‌های یادآور"
                            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("reminders", channelName, NotificationManager.IMPORTANCE_HIGH))
                        }
                        8 -> SimpleCategory(s("Miscellaneous", "متفرقه", english), s("Other reminders and notes", "یادآورها و یادداشت‌های دیگر", english), reminders.filter { it.category == "Miscellaneous" }, english, { reminderCategory = "Miscellaneous"; showReminder = true }, { item -> cancelReminder(context, item); reminders = reminders.filterNot { it.id == item.id }; saveReminders(prefs, reminders) })
                        9 -> ExpiryScreen(batches, productCategories, english, { showBatch = true }, { item -> cancelExpiry(context, item); batches = batches.filterNot { it.id == item.id }; saveBatches(prefs, batches) })
                    }
                }
                StorePortal(context, english, visible = tab == 5) { overview ->
                    storeOverview = overview
                    prefs.edit()
                        .putInt("store_task_count", overview.taskCount)
                        .putInt("store_done_count", overview.doneCount)
                        .putInt("store_shift_count", overview.shiftCount)
                        .putInt("store_batch_count", overview.batchCount)
                        .putInt("store_expiring_count", overview.expiringCount)
                        .putString("store_role", overview.role)
                        .apply()
                }
            }
        }
        if (showReminder) ReminderDialog(reminderCategory, english, { showReminder = false }) { title, date, time, category ->
            val item = ReminderItem(UUID.randomUUID().toString(), title, date, time, category)
            reminders = reminders + item; saveReminders(prefs, reminders); scheduleReminder(context, item); showReminder = false
        }
        if (showBatch) BatchDialog(barcode, productCategories, english, onScan = { scanner.startScan().addOnSuccessListener { barcode = it.rawValue ?: "" } }, onDismiss = { showBatch = false }, onSave = { product, code, qty, expiry, category ->
            batches = batches + BatchItem(UUID.randomUUID().toString(), product, code, qty, expiry, category); saveBatches(prefs, batches); scheduleExpiry(context, batches.last().id, product, expiry); if (!productCategories.contains(category)) { productCategories = productCategories + category; saveCategories(prefs, productCategories) }; showBatch = false; barcode = ""
        })
    }
    }
}

@Composable private fun ReminderBrandHeader(english: Boolean, openPersonal: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.Transparent).padding(horizontal = 21.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REMINDER", color = JournalInk, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(".", color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Text(s("Smart daily management", "مدیریت هوشمند کارهای روزانه", english), color = Muted, fontSize = 9.sp)
        }
        Surface(
            shape = RoundedCornerShape(15.dp), color = Color(0xFF294031),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B896B)),
            shadowElevation = 7.dp, modifier = Modifier.size(43.dp).clickable { openPersonal() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable private fun Header(english: Boolean, openPersonal: () -> Unit) {
    val today = remember { SimpleDateFormat("EEEE, MMM dd", Locale.ENGLISH).format(Calendar.getInstance().time) }
    Row(
        Modifier.fillMaxWidth().background(JournalPaper).padding(horizontal = 21.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s("Good day.", "روز خوبی داشته باشی.", english), color = JournalInk, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text(if (english) today else todayJalali(), color = Muted, fontSize = 12.sp)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = JournalCard, modifier = Modifier.size(46.dp).border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(16.dp)).clickable { openPersonal() }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GridView, "Personal collections", tint = JournalInk, modifier = Modifier.size(21.dp)) }
        }
    }
}

@Composable private fun PersonalHubScreen(
    reminders: List<ReminderItem>, batches: List<BatchItem>, english: Boolean,
    openDaily: () -> Unit, openInstallments: () -> Unit, openBirthdays: () -> Unit, openMisc: () -> Unit, openExpiry: () -> Unit
) {
    val reminderStats = remember(reminders) {
        reminders.fold(intArrayOf(0, 0, 0, 0, 0)) { counts, item ->
            if (item.done) counts[1]++ else {
                counts[0]++
                when (item.category) {
                    "Daily" -> counts[2]++
                    "Installments" -> counts[3]++
                    "Birthdays" -> counts[4]++
                }
            }
            counts
        }
    }
    val pending = reminderStats[0]
    val completed = reminderStats[1]
    val expiring = remember(batches) { batches.count { daysUntil(it.expiry) in 0..7 } }
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(JournalPaper).padding(horizontal = 19.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            AnimatedVisibility(contentVisible, enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 7 }) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(s("PERSONAL SPACE", "فضای شخصی", english), color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                Text(s("Your life, in order.", "زندگی‌ات، مرتب و منظم.", english), color = JournalInk, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
                Text(s("Private reminders and important dates, saved on this device.", "یادآورها و تاریخ‌های مهمت، فقط روی همین دستگاه ذخیره می‌شوند.", english), color = Muted, fontSize = 12.sp)
            }
            }
        }
        item {
            AnimatedVisibility(contentVisible, enter = fadeIn(tween(360)) + slideInVertically(tween(360)) { it / 9 }) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PersonalMetric("$pending", "TO DO", Icons.Rounded.NotificationsNone, Modifier.weight(1f), english)
                PersonalMetric("$completed", "COMPLETED", Icons.Rounded.CheckCircleOutline, Modifier.weight(1f), english)
                PersonalMetric("$expiring", "EXPIRING", Icons.Rounded.Inventory2, Modifier.weight(1f), english)
            }
            }
        }
        item { Text(s("YOUR COLLECTIONS", "دسته‌بندی‌های شما", english), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 3.dp)) }
        item { AnimatedVisibility(contentVisible, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { PersonalCategoryCard("Daily reminders", "Tasks, plans and everyday notes", reminderStats[2], Icons.Rounded.Event, Mint, openDaily, english) } }
        item { AnimatedVisibility(contentVisible, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { PersonalCategoryCard("Installments", "Payments and upcoming due dates", reminderStats[3], Icons.Rounded.Event, Color(0xFF9DABFF), openInstallments, english) } }
        item { AnimatedVisibility(contentVisible, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { PersonalCategoryCard("Birthdays", "People and dates worth remembering", reminderStats[4], Icons.Rounded.Cake, Color(0xFFFFB5C8), openBirthdays, english) } }
        item { AnimatedVisibility(contentVisible, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { PersonalCategoryCard("Miscellaneous", "Everything else you want to keep close", reminders.count { it.category == "Miscellaneous" && !it.done }, Icons.Rounded.EditNote, Color(0xFFFFC58A), openMisc, english) } }
        item { AnimatedVisibility(contentVisible, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { PersonalCategoryCard("Personal expiry", "Products and their expiry dates", batches.size, Icons.Rounded.Inventory2, Mint, openExpiry, english) } }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = JournalCard, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Mint, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("Private by design", "حریم خصوصی از ابتدا", english), color = JournalInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(s("Personal data stays on this device and is not sent to the Store server.", "اطلاعات شخصی روی همین دستگاه می‌ماند و به سرور فروشگاه ارسال نمی‌شود.", english), color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun PersonalMetric(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, english: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(19.dp), color = JournalCard) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(18.dp))
            AnimatedContent(targetState = value, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "personal-count") { shown ->
                Text(shown, color = JournalInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(s(label, when(label) { "TO DO" -> "در انتظار"; "COMPLETED" -> "انجام‌شده"; else -> "نزدیک انقضا" }, english), color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp, maxLines = 1)
        }
    }
}

@Composable private fun PersonalCategoryCard(title: String, detail: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit, english: Boolean) {
    Surface(shape = RoundedCornerShape(22.dp), color = JournalCard, shadowElevation = 6.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .045f)), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(47.dp).background(accent.copy(alpha = .13f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(title, when(title) { "Daily reminders" -> "یادآورهای روزانه"; "Installments" -> "اقساط"; "Birthdays" -> "تولدها"; "Personal expiry" -> "انقضای شخصی"; else -> "متفرقه" }, english), color = JournalInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(s(detail, when(title) { "Daily reminders" -> "کارها، برنامه‌ها و یادداشت‌های روزمره"; "Installments" -> "پرداخت‌ها و سررسیدهای پیش‌رو"; "Birthdays" -> "افراد و تاریخ‌های مهم"; "Personal expiry" -> "محصولات و تاریخ انقضای آن‌ها"; else -> "هر چیز دیگری که می‌خواهی نگه داری" }, english), color = Muted, fontSize = 10.sp, maxLines = 2)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .12f)) {
                Text(count.toString(), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.padding(start = 5.dp).size(18.dp))
        }
    }
}

@Composable private fun HomeScreen(
    reminders: List<ReminderItem>,
    batches: List<BatchItem>,
    storeOverview: StoreOverview,
    english: Boolean,
    goReminders: () -> Unit,
    goExpiry: () -> Unit
) {
    val personalOverview = remember(reminders, batches) {
        intArrayOf(reminders.count { !it.done }, reminders.count { it.done }, batches.count { daysUntil(it.expiry) in 0..7 })
    }
    val pendingPersonal = personalOverview[0]
    val completedPersonal = personalOverview[1]
    val personalExpiringLots = personalOverview[2]
    val pendingStore = (storeOverview.taskCount - storeOverview.doneCount).coerceAtLeast(0)
    val today = remember { SimpleDateFormat("EEEE, dd MMM", Locale.ENGLISH).format(Calendar.getInstance().time) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(JournalPaper).padding(horizontal = 19.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(s("OVERVIEW", "نمای کلی", english), color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                Text(s("Everything at a glance.", "همه‌چیز، یک‌جا.", english), color = JournalInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(if (english) today else todayJalali(), color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(25.dp), color = JournalCard, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .05f)), modifier = Modifier.fillMaxWidth().clickable { goReminders() }) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).background(Color(0xFF203B37), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PersonOutline, null, tint = Mint, modifier = Modifier.size(22.dp)) }
                        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s("PERSONAL", "شخصی", english), color = JournalInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(s("Your reminders and important dates", "یادآورها و تاریخ‌های مهم شما", english), color = Muted, fontSize = 10.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric(pendingPersonal.toString(), s("PENDING", "در انتظار", english), Modifier.weight(1f), Mint)
                        OverviewMetric(completedPersonal.toString(), s("DONE", "انجام‌شده", english), Modifier.weight(1f), Color(0xFF9DABFF))
                        OverviewMetric(personalExpiringLots.toString(), s("EXPIRY", "انقضا", english), Modifier.weight(1f), Color(0xFFFFC58A))
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(25.dp), color = JournalCard, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .05f)), modifier = Modifier.fillMaxWidth().clickable { goExpiry() }) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).background(Color(0xFF203B37), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Storefront, null, tint = Mint, modifier = Modifier.size(22.dp)) }
                        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s("STORE", "فروشگاه", english), color = JournalInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (storeOverview.role.isBlank()) s("Store workspace", "فضای کاری فروشگاه", english) else if (storeOverview.role == "manager") s("Manager account", "حساب مدیر", english) else s("Staff account", "حساب پرسنل", english), color = Muted, fontSize = 10.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric(pendingStore.toString(), s("OPEN TASKS", "وظایف باز", english), Modifier.weight(1f), Mint)
                        OverviewMetric(storeOverview.shiftCount.toString(), s("SHIFTS", "شیفت‌ها", english), Modifier.weight(1f), Color(0xFF9DABFF))
                        OverviewMetric(storeOverview.expiringCount.toString(), s("EXPIRING", "نزدیک انقضا", english), Modifier.weight(1f), Color(0xFFFFA477))
                    }
                    Text(s("${storeOverview.batchCount} expiry lots · ${storeOverview.doneCount} completed tasks", "${storeOverview.batchCount} سری انقضا · ${storeOverview.doneCount} وظیفه انجام‌شده", english), color = Muted, fontSize = 10.sp)
                }
            }
        }

    }
}

@Composable private fun OverviewMetric(value: String, label: String, modifier: Modifier, tint: Color) {
    Column(modifier.background(Color(0xFF20252D), RoundedCornerShape(14.dp)).padding(vertical = 10.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = tint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable private fun MintStat(value: String, label: String, modifier: Modifier = Modifier, english: Boolean) {
    Column(modifier.background(Color(0xFF69EBC8), RoundedCornerShape(12.dp)).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color(0xFF102B27), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(s(label, when(label) { "PENDING" -> "در انتظار"; "DONE" -> "انجام‌شده"; "PERSONAL EXPIRY" -> "انقضای شخصی"; else -> "وظایف فروشگاه" }, english), color = Color(0xFF315C53), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
    }
}

@Composable private fun DarkTaskCard(title: String, value: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, english: Boolean) {
    val bg = if (active) Color(0xFF263D3A) else JournalCard
    val fg = JournalInk
    Surface(shape = RoundedCornerShape(22.dp), color = bg, modifier = modifier.heightIn(min = 146.dp).clickable { onClick() }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(21.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.NotificationsNone, null, tint = if (active) Mint else Muted, modifier = Modifier.size(17.dp))
            }
            Text(s(title, when(title) { "NEAR EXPIRY" -> "نزدیک انقضا"; "WORK DEADLINE" -> "سررسید کاری"; "BIRTHDAYS" -> "تولدها"; "QUICK NOTE" -> "یادداشت سریع"; else -> "فضای کاری فروشگاه" }, english), color = if (active) Mint else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Text(value, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(s(detail, when(detail) { "batch within 7 days" -> "یک سری تا ۷ روز آینده"; "batches within 7 days" -> "سری‌ها تا ۷ روز آینده"; "All clear" -> "همه‌چیز مرتب است"; "No pending deadline" -> "سررسید معوقی نیست"; "upcoming dates" -> "تاریخ‌های پیش‌رو"; "Capture an idea" -> "ایده‌ای را ثبت کن"; "Add a personal reminder" -> "یک یادآور شخصی اضافه کن"; "Connect your team account" -> "حساب تیم را متصل کن"; s("Tasks · shifts · products · expiry", "وظایف · شیفت‌ها · محصولات · انقضا", english) -> "وظایف · شیفت‌ها · محصولات · انقضا"; else -> detail }, english), color = if (active) Mint else Muted, fontSize = 10.sp, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (active) s("NEEDS ATTENTION", "نیازمند توجه", english) else s("UP TO DATE", "به‌روز", english), color = if (active) Mint else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowOutward, null, tint = if (active) Mint else Muted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable private fun JournalCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = JournalCard, shadowElevation = 7.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .045f))) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable private fun JournalPill(value: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0xFFF0E0D2), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = JournalRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable private fun JournalMiniCard(title: String, value: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit, warning: Boolean) {
    JournalCard(modifier.clickable { onClick() }) {
        Icon(icon, null, tint = JournalRed, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(9.dp))
        Text(title, color = JournalRed, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, maxLines = 1)
        Spacer(Modifier.height(7.dp))
        Text(value, color = JournalInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = if (warning) JournalRed else Muted, fontSize = 10.sp, fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
    }
}

@Composable private fun ProfileScreen(context: Context, english: Boolean) {
    val storeProfile = remember { context.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE) }
    val storeApi = remember { StoreApiClient(context) }
    val scope = rememberCoroutineScope()
    var storeAccountActive by remember { mutableStateOf(!storeProfile.getString("username", null).isNullOrBlank()) }
    var loggingOut by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(storeProfile.getString("display_name", null)) }
    var username by remember { mutableStateOf(storeProfile.getString("username", null)) }
    var role by remember { mutableStateOf(storeProfile.getString("role", null)) }
    val overviewPrefs = context.getSharedPreferences("reminder_local", Context.MODE_PRIVATE)
    val taskCount = overviewPrefs.getInt("store_task_count", 0)
    val doneCount = overviewPrefs.getInt("store_done_count", 0)
    Column(Modifier.fillMaxSize().background(JournalPaper).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(s("PROFILE", "پروفایل", english), color = Mint, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
        Text(s("Your account", "حساب کاربری", english), color = JournalInk, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        JournalCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).background(Color(0xFF203B37), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = Mint, modifier = Modifier.size(29.dp))
                }
                Column(Modifier.padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayName ?: s("Reminder user", "کاربر یادآور", english), color = JournalInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (!username.isNullOrBlank()) "@$username" else s("Personal workspace", "فضای شخصی", english), color = Muted, fontSize = 11.sp)
                    Text(if (role == "manager") s("STORE MANAGER", "مدیر فروشگاه", english) else if (role == "staff") s("STORE STAFF", "پرسنل فروشگاه", english) else s("LOCAL PROFILE", "حساب محلی", english), color = Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = Mint)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(s("Privacy", "حریم خصوصی", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                    Text(s("Personal reminders stay on this device.", "یادآورهای شخصی روی همین دستگاه باقی می‌مانند.", english), color = Muted, fontSize = 11.sp)
                }
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(s("ACCOUNT DETAILS", "جزئیات حساب", english), color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("Account status", "وضعیت حساب", english), color = Muted, fontSize = 10.sp)
                        Text(if (storeAccountActive) s("Connected", "متصل", english) else s("Local profile", "حساب محلی", english), color = JournalInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF203B37)) {
                        Text(if (storeAccountActive) s("CONNECTED", "متصل", english) else s("LOCAL", "محلی", english), color = Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .06f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("Access level", "سطح دسترسی", english), color = Muted, fontSize = 10.sp)
                        Text(if (role == "manager") s("Manager", "مدیر", english) else if (role == "staff") s("Staff", "پرسنل", english) else s("Personal", "شخصی", english), color = JournalInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("App version", "نسخه برنامه", english), color = Muted, fontSize = 10.sp)
                        Text("2.9.3", color = JournalInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(if (displayName != null) s("Store account information is synced from your active Store session.", "اطلاعات حساب فروشگاه از نشست فعال شما دریافت می‌شود.", english) else s("Connect your Store account to display your name and role here.", "برای نمایش نام و نقش، وارد حساب فروشگاه شوید.", english), color = Muted, fontSize = 11.sp)
        if (storeAccountActive) {
            JournalCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s("STORE ACCOUNT", "حساب فروشگاه", english), color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileStat(if (role == "manager") "MANAGER" else "STAFF", s("ROLE", "نقش", english), Modifier.weight(1f))
                        ProfileStat(taskCount.toString(), s("TASKS", "وظایف", english), Modifier.weight(1f))
                        ProfileStat(doneCount.toString(), s("DONE", "انجام‌شده", english), Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                loggingOut = true
                                runCatching { storeApi.logout() }
                                storeProfile.edit().clear().apply()
                                displayName = null
                                username = null
                                role = null
                                scheduleManagerNightly(context, false)
                                storeAccountActive = false
                                loggingOut = false
                            }
                        },
                        enabled = !loggingOut,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A252A), contentColor = Color(0xFFFFA6A6))
                    ) {
                        Text(s(if (loggingOut) "Signing out…" else "Log out of Store", if (loggingOut) "در حال خروج…" else "خروج از حساب فروشگاه", english), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF20232C), RoundedCornerShape(12.dp)).padding(vertical = 10.dp, horizontal = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = JournalInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable private fun SettingsScreen(context: Context, english: Boolean, onLanguageChange: (Boolean) -> Unit) {
    var updateStatus by remember { mutableStateOf("") }
    var latestRelease by remember { mutableStateOf<UpdateManager.Release?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    val currentVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "3.0.0" }
    Column(Modifier.fillMaxSize().background(JournalPaper).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(s("SETTINGS", "تنظیمات", english), color = Mint, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(s("Make it yours.", "برنامه را مطابق سلیقه‌ات تنظیم کن.", english), color = JournalInk, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        JournalCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Language, null, tint = Mint) }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("Language", "زبان برنامه", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                        Text(s("Choose your preferred language", "زبان موردنظر خود را انتخاب کن", english), color = Muted, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF20232C), RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(true to "English", false to "فارسی").forEach { (isEnglish, label) ->
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onLanguageChange(isEnglish) },
                            shape = RoundedCornerShape(11.dp),
                            color = if (english == isEnglish) Mint else Color.Transparent
                        ) {
                            Text(label, modifier = Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center,
                                color = if (english == isEnglish) Color(0xFF102B27) else JournalInk,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SystemUpdate, null, tint = Mint) }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("App updates", "به‌روزرسانی برنامه", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                        Text(s("Current version: $currentVersion", "نسخه نصب‌شده: $currentVersion", english), color = Muted, fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = {
                        if (!checkingUpdate) {
                            checkingUpdate = true
                            updateStatus = s("Checking GitHub Releases…", "در حال بررسی نسخه‌های GitHub…", english)
                            updateScope.launch {
                                try {
                                    val release = UpdateManager.checkLatest(context)
                                    latestRelease = release
                                    updateStatus = if (UpdateManager.isNewer(currentVersion, release.version))
                                        s("A new version is available: ${release.version}", "نسخه جدید ${release.version} موجود است.", english)
                                    else s("You are using the latest version.", "برنامه شما به‌روز است.", english)
                                } catch (e: Exception) {
                                    updateStatus = e.message ?: s("Update check failed.", "بررسی به‌روزرسانی ناموفق بود.", english)
                                } finally { checkingUpdate = false }
                            }
                        }
                    }, enabled = !checkingUpdate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF102B27))
                ) {
                    if (checkingUpdate) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(s("Check for updates", "بررسی به‌روزرسانی", english), fontWeight = FontWeight.Bold)
                }
                latestRelease?.let { release ->
                    if (UpdateManager.isNewer(currentVersion, release.version)) {
                        Text(release.title, color = JournalInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (release.notes.isNotBlank()) Text(release.notes.take(900), color = Muted, fontSize = 11.sp)
                        Button(onClick = {
                            try {
                                UpdateManager.download(context, release)
                                updateStatus = s("Download started. You can continue using the app.", "دانلود شروع شد؛ می‌توانی به کار با برنامه ادامه بدهی.", english)
                            } catch (e: Exception) { updateStatus = e.message ?: "Download failed" }
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CardDarkAlt, contentColor = Mint)) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text(s("Download update", "دانلود نسخه جدید", english))
                        }
                    }
                }
                if (UpdateManager.downloadedId(context) >= 0) {
                    OutlinedButton(onClick = {
                        val started = UpdateManager.installDownloaded(context)
                        if (!started) updateStatus = s("Allow installs from this app in Android settings, then tap Install again.", "مجوز نصب از این برنامه را در تنظیمات اندروید فعال کن و دوباره نصب را بزن.", english)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.InstallMobile, null); Spacer(Modifier.width(8.dp)); Text(s("Install downloaded update", "نصب آپدیت دانلودشده", english))
                    }
                }
                if (updateStatus.isNotBlank()) Text(updateStatus, color = Muted, fontSize = 11.sp)
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.NotificationsActive, null, tint = Mint) }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(s("Notifications", "اعلان‌ها", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                    Text(s("Reminder alerts are handled by Android.", "اعلان یادآورها توسط اندروید مدیریت می‌شود.", english), color = Muted, fontSize = 11.sp)
                }
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF203B37)) {
                    Text(s("ON", "روشن", english), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Storage, null, tint = Mint) }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(s("Local storage", "ذخیره‌سازی محلی", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                    Text(s("Your personal data remains on this phone.", "اطلاعات شخصی فقط روی همین گوشی ذخیره می‌شود.", english), color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.CheckCircle, null, tint = Mint)
            }
        }
        JournalCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Mint.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Palette, null, tint = Mint) }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(s("Visual style", "ظاهر برنامه", english), color = JournalInk, fontWeight = FontWeight.SemiBold)
                    Text(s("Dark Slate · Mint", "اسلیت تیره · نعنایی", english), color = Muted, fontSize = 11.sp)
                }
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF203B37)) { Text(s("ACTIVE", "فعال", english), color = Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
            }
        }
        Text(s("Reminder 3.0.0 · Designed for focus", "یادآور ۳.۰.۰ · طراحی‌شده برای تمرکز", english), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable private fun OverviewStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = Lilac, modifier = Modifier.size(17.dp))
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = Muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable private fun DarkEmptyState(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Lilac.copy(alpha = .12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.EventAvailable, null, tint = Lilac, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable private fun HomeSectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            Text(action, color = Lilac, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, null, tint = Lilac, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable private fun HomeReminderRow(item: ReminderItem, english: Boolean) {
    Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Lilac.copy(alpha = .13f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.EventNote, null, tint = Lilac, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text("${item.date}  •  ${categoryLabel(item.category, english)}", color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                Text(item.time, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = Lilac, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun HomeBatchRow(item: BatchItem, english: Boolean) {
    val days = daysUntil(item.expiry)
    val accent = if (days < 0) Color(0xFFFF6B6B) else if (days <= 7) Lilac else Color(0xFFB8B8B8)
    Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Inventory2, null, tint = accent, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.product, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text("${item.quantity} ${s("units", "عدد", english)}  •  ${item.expiry}", color = Muted, fontSize = 10.sp)
            }
            Text(if (days < 0) s("Expired", "منقضی", english) else if (days == 0) s("Today", "امروز", english) else "$days ${s("days", "روز", english)}", color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

@Composable private fun ReminderList(list: List<ReminderItem>, english: Boolean, add: () -> Unit, toggle: (ReminderItem) -> Unit, delete: (ReminderItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) { SectionTitle(s("Daily reminders", "یادآورهای روزانه", english), s("Tasks and appointments", "کارها و قرارهای مهمت", english), add); if (list.isEmpty()) EmptyState(s("Your list is empty", "لیستت خالیه", english), s("Add your first reminder", "اولین یادآورت رو ثبت کن", english)) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) { items(list, key = { it.id }) { ReminderRow(it, english, { toggle(it) }, { delete(it) }) } } }
}

@Composable private fun SimpleCategory(title: String, subtitle: String, list: List<ReminderItem>, english: Boolean, add: () -> Unit, delete: (ReminderItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) { SectionTitle(title, subtitle, add); if (list.isEmpty()) EmptyState(s("Nothing here yet", "هنوز موردی ثبت نشده", english), s("Tap + to add an item", "برای اضافه‌کردن روی + بزن", english)) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(list, key = { it.id }) { ReminderRow(it, english, {}, { delete(it) }) } } }
}

@Composable private fun ExpiryScreen(batches: List<BatchItem>, categories: List<String>, english: Boolean, add: () -> Unit, delete: (BatchItem) -> Unit) {
    val grouped = remember(batches) { batches.groupBy { it.category }.mapValues { (_, lots) -> lots.sortedBy { daysUntil(it.expiry) } } }
    val soonCount = remember(batches) { batches.count { daysUntil(it.expiry) in 0..7 } }
    val expiredCount = remember(batches) { batches.count { daysUntil(it.expiry) < 0 } }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        SectionTitle(s("Product expiry", "انقضای محصولات", english), s("Track multiple batches for each product", "برای هر محصول چند سری ثبت کن", english), add)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 12.dp)) { MiniInfo(s("Batches", "کل سری‌ها", english), batches.size.toString(), Modifier.weight(1f)); MiniInfo(s("Soon", "نزدیک انقضا", english), soonCount.toString(), Modifier.weight(1f)); MiniInfo(s("Expired", "منقضی‌شده", english), expiredCount.toString(), Modifier.weight(1f)) }
        if (batches.isEmpty()) EmptyState(s("No products added", "هنوز محصولی ثبت نکردی", english), s("Add manually or scan a barcode", "محصول رو دستی وارد کن یا بارکدش رو اسکن کن", english)) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            grouped.forEach { (category, lots) -> item { Text(category, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Lilac, modifier = Modifier.padding(top = 9.dp, bottom = 2.dp)) }; items(lots, key = { it.id }) { BatchRow(it, english) { delete(it) } } }
        }
    }
}

@Composable private fun MiniInfo(label: String, value: String, modifier: Modifier = Modifier) { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) { Column(Modifier.padding(11.dp)) { Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Lilac); Text(label, fontSize = 10.sp, color = Muted) } } }
@Composable private fun SectionTitle(title: String, subtitle: String, add: () -> Unit) { Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text(subtitle, fontSize = 11.sp, color = Muted) }; Surface(shape = CircleShape, color = Lilac, modifier = Modifier.size(42.dp).clickable { add() }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White) } } } }

@Composable private fun ReminderRow(item: ReminderItem, english: Boolean, toggle: () -> Unit, delete: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) { Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = RoundedCornerShape(12.dp), color = PaleLilac, modifier = Modifier.size(42.dp).clickable { toggle() }) { Box(contentAlignment = Alignment.Center) { Icon(if (item.done) Icons.Rounded.CheckCircle else Icons.Rounded.Notifications, null, tint = Lilac) } }; Column(Modifier.weight(1f).padding(horizontal = 11.dp)) { Text(item.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Text("${item.date}  •  ${item.time}  •  ${categoryLabel(item.category, english)}", color = Muted, fontSize = 10.sp) }; IconButton(onClick = delete) { Icon(Icons.Rounded.DeleteOutline, null, tint = Muted) } } }
}
@Composable private fun BatchRow(item: BatchItem, english: Boolean, delete: () -> Unit) { val days = daysUntil(item.expiry); val warning = days < 0 || days <= 7; Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) { Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = RoundedCornerShape(12.dp), color = if (warning) Color(0xFF35251F) else PaleLilac, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Inventory2, null, tint = if (warning) Color(0xFFFF8A65) else Lilac) } }; Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(item.product, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text("${item.quantity} ${s("units", "عدد", english)}  •  ${item.expiry}", fontSize = 11.sp, color = Muted); if (item.barcode.isNotBlank()) Text("${s("Barcode:", "بارکد:", english)} ${item.barcode}", fontSize = 9.sp, color = Muted) }; Text(if (days < 0) s("Expired", "منقضی", english) else if (days == 0) s("Today", "امروز", english) else "$days ${s("days", "روز", english)}", color = if (warning) Color(0xFFFF8A65) else Lilac, fontWeight = FontWeight.Bold, fontSize = 10.sp); IconButton(onClick = delete) { Icon(Icons.Rounded.DeleteOutline, null, tint = Muted) } } } }

@Composable private fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally) { Surface(shape = CircleShape, color = PaleLilac, modifier = Modifier.size(62.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, tint = Lilac, modifier = Modifier.size(28.dp)) } }; Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground); Text(subtitle, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center) } }

@Composable private fun BottomBar(selected: Int, english: Boolean, onSelect: (Int) -> Unit) {
    val selectedNav = when (selected) {
        6 -> 0
        1, 2, 3, 4, 8, 9 -> 1
        0 -> 2
        5 -> 3
        else -> 4
    }
    // RTL order from right to left: Profile | Personal | Home | Store | Settings
    val navItems = listOf(
        Triple("Profile", Icons.Rounded.AccountCircle, 6),
        Triple("Personal", Icons.Rounded.PersonOutline, 1),
        Triple("Home", Icons.Rounded.Home, 0),
        Triple("Store", Icons.Rounded.ShoppingBag, 5),
        Triple("Settings", Icons.Rounded.Settings, 7)
    )
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = Color(0xFF19231F),
        shadowElevation = 17.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4B6051).copy(alpha = .72f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            navItems.forEachIndexed { index, item ->
                val active = selectedNav == index
                val scale by animateFloatAsState(if (active) 1.08f else 1f, spring(dampingRatio = .62f, stiffness = 420f), label = "nav-scale-$index")
                Column(
                    Modifier.weight(1f)
                        .background(if (active) Brush.linearGradient(listOf(Color(0xFF3B5740), Color(0xFF1A2B20))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), RoundedCornerShape(16.dp))
                        .clickable { onSelect(item.third) }.padding(vertical = 7.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (index == 2) {
                        Box(Modifier.size(42.dp).background(if (active) Mint else Color(0xFF343A49), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(item.second, null, tint = if (active) Color(0xFF102B27) else Muted, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        Icon(item.second, null, tint = if (active) Mint else Muted, modifier = Modifier.size(22.dp))
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = active,
                        enter = fadeIn(tween(130)) + androidx.compose.animation.expandHorizontally(tween(130)),
                        exit = fadeOut(tween(90)) + androidx.compose.animation.shrinkHorizontally(tween(90))
                    ) {
                        Text(s(item.first, when(item.first) { "Store" -> "فروشگاه"; "Personal" -> "شخصی"; "Home" -> "خانه"; "Settings" -> "تنظیمات"; else -> "پروفایل" }, english), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.size(width = if (active) 13.dp else 4.dp, height = 3.dp).background(if (active) Mint else Color.Transparent, RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable private fun ReminderDialog(initialCategory: String, english: Boolean, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf(todayJalali()) }; var time by remember { mutableStateOf("09:00") }; var category by remember { mutableStateOf(initialCategory) }; var picker by remember { mutableStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(s("New reminder", "یادآور جدید", english), fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text(s("Title", "عنوان یادآور", english)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { picker = 1 }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("$date") }
        OutlinedButton(onClick = { picker = 2 }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(8.dp)); Text(time) }
        Text(s("Category", "دسته‌بندی", english), color = Muted, fontSize = 12.sp); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Daily", "Installments", "Birthdays", "Miscellaneous").forEach { FilterChip(category == it, { category = it }, label = { Text(categoryLabel(it, english), fontSize = 10.sp) }) } }
    } }, confirmButton = { TextButton(onClick = { if (title.isNotBlank() && validJalali(date)) onSave(title, date, time, category) }) { Text(s("Save", "ذخیره", english), color = Lilac) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(s("Cancel", "انصراف", english)) } })
    if (picker != 0) WheelPickerDialog(picker == 1, date, time, english, { date = it; picker = 0 }, { time = it; picker = 0 }, { picker = 0 })
}

@Composable private fun WheelPickerDialog(isDate: Boolean, date: String, time: String, english: Boolean, onDate: (String) -> Unit, onTime: (String) -> Unit, onDismiss: () -> Unit) {
    val dp = date.split('/'); val tp = time.split(':'); var y by remember { mutableIntStateOf(dp.getOrNull(0)?.toIntOrNull() ?: 1405) }; var m by remember { mutableIntStateOf(dp.getOrNull(1)?.toIntOrNull() ?: 9) }; var d by remember { mutableIntStateOf(dp.getOrNull(2)?.toIntOrNull() ?: 1) }; var h by remember { mutableIntStateOf(tp.getOrNull(0)?.toIntOrNull() ?: 9) }; var min by remember { mutableIntStateOf(tp.getOrNull(1)?.toIntOrNull() ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isDate) s("Choose date", "انتخاب تاریخ", english) else s("Choose time", "انتخاب ساعت", english)) }, text = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (isDate) { Wheel(y, 1400, 1450) { y = it }; Wheel(m, 1, 12) { m = it; d = d.coerceAtMost(if (m <= 6) 31 else 30) }; Wheel(d, 1, if (m <= 6) 31 else 30) { d = it } }
            else { Wheel(h, 0, 23) { h = it }; Text(":", fontSize = 22.sp, fontWeight = FontWeight.Bold); Wheel(min, 0, 59) { min = it } }
        }
    }, confirmButton = { TextButton(onClick = { if (isDate) onDate("%04d/%02d/%02d".format(y,m,d)) else onTime("%02d:%02d".format(h,min)) }) { Text(s("Done", "تأیید", english), color = Lilac) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(s("Cancel", "انصراف", english)) } })
}
@Composable private fun Wheel(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) { AndroidView(factory = { ctx -> NumberPicker(ctx).apply { minValue = min; maxValue = max; this.value = value; wrapSelectorWheel = true; descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS; setOnValueChangedListener { _, _, new -> onChange(new) } } }, update = { if (it.value != value) it.value = value }, modifier = Modifier.width(76.dp).height(145.dp)) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun BatchDialog(initialBarcode: String, categories: List<String>, english: Boolean, onScan: () -> Unit, onDismiss: () -> Unit, onSave: (String, String, Int, String, String) -> Unit) {
    var product by remember { mutableStateOf("") }; var code by remember { mutableStateOf(initialBarcode) }; var qty by remember { mutableStateOf("1") }; var expiry by remember { mutableStateOf(todayJalali()) }; var category by remember { mutableStateOf(categories.firstOrNull() ?: "General") }; var newCategory by remember { mutableStateOf("") }; var picker by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(s("Add product batch", "ثبت سری محصول", english), fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedTextField(product, { product = it }, label = { Text(s("Product name", "نام محصول", english)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text(s("Barcode (optional)", "بارکد (اختیاری)", english)) }, singleLine = true, trailingIcon = { IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, s("Scan barcode", "اسکن بارکد", english), tint = Lilac) } })
        OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text(s("Quantity", "تعداد", english)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text(s("Product category", "دسته‌بندی محصول", english), color = Muted, fontSize = 12.sp)
        if (categories.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { categories.forEach { FilterChip(category == it, { category = it }, label = { Text(it, fontSize = 10.sp) }) } }
        OutlinedTextField(newCategory, { newCategory = it; if (it.isNotBlank()) category = it.trim() }, label = { Text(s("Add a new category", "دسته‌بندی جدید", english)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("${s("Expiry date", "تاریخ انقضا", english)}: $expiry") }
        Text(s("Each batch keeps its own expiry date. Categories are saved for next time.", "هر سری تاریخ انقضای مستقل دارد و دسته‌بندی برای دفعات بعد ذخیره می‌شود.", english), color = Muted, fontSize = 10.sp)
    } }, confirmButton = { TextButton(onClick = { val count = qty.toIntOrNull() ?: 0; val cat = category.trim().ifBlank { "General" }; if (product.isNotBlank() && count > 0 && validJalali(expiry)) onSave(product.trim(), code, count, expiry, cat) }) { Text(s("Save batch", "ثبت سری", english), color = Lilac) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(s("Cancel", "انصراف", english)) } })
    if (picker) WheelPickerDialog(true, expiry, "09:00", english, { expiry = it; picker = false }, {}, { picker = false })
}

private fun categoryLabel(category: String, english: Boolean) = when (category) { "Daily" -> s("Daily", "روزانه", english); "Installments" -> s("Installments", "اقساط", english); "Birthdays" -> s("Birthdays", "تولدها", english); "Miscellaneous" -> s("Miscellaneous", "متفرقه", english); else -> category }
private fun saveReminders(p: android.content.SharedPreferences, list: List<ReminderItem>) { val a=JSONArray(); list.forEach { a.put(JSONObject().put("id",it.id).put("title",it.title).put("date",it.date).put("time",it.time).put("category",it.category).put("done",it.done).put("createdAt",it.createdAt)) }; p.edit().putString("reminders",a.toString()).apply() }
private fun loadReminders(p: android.content.SharedPreferences): List<ReminderItem> = try { val a=JSONArray(p.getString("reminders","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); ReminderItem(o.optString("id"),o.optString("title"),o.optString("date"),o.optString("time"),o.optString("category"),o.optBoolean("done"),o.optLong("createdAt", 0L)) } } catch (_:Exception) { emptyList() }
private fun saveBatches(p: android.content.SharedPreferences, list: List<BatchItem>) { val a=JSONArray(); list.forEach { a.put(JSONObject().put("id",it.id).put("product",it.product).put("barcode",it.barcode).put("quantity",it.quantity).put("expiry",it.expiry).put("category",it.category)) }; p.edit().putString("batches",a.toString()).apply() }
private fun loadBatches(p: android.content.SharedPreferences): List<BatchItem> = try { val a=JSONArray(p.getString("batches","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); BatchItem(o.optString("id"),o.optString("product"),o.optString("barcode"),o.optInt("quantity"),o.optString("expiry"),o.optString("category","General")) } } catch (_:Exception) { emptyList() }
private fun saveCategories(p: android.content.SharedPreferences, list: List<String>) { p.edit().putString("product_categories", JSONArray(list).toString()).apply() }
private fun loadCategories(p: android.content.SharedPreferences): List<String> = try { val a=JSONArray(p.getString("product_categories","[]")); (0 until a.length()).map { a.getString(it) } } catch (_:Exception) { emptyList() }
private fun todayJalali(): String {
    val today = LocalDate.now()
    var jalaliYear = today.year - 621
    fun yearStart(year: Int): LocalDate {
        val g = jalaliToGregorian(year, 1, 1)
        return LocalDate.of(g[0], g[1], g[2])
    }
    while (today < yearStart(jalaliYear)) jalaliYear--
    while (today >= yearStart(jalaliYear + 1)) jalaliYear++
    val dayOfYear = ChronoUnit.DAYS.between(yearStart(jalaliYear), today).toInt()
    val month: Int
    val day: Int
    if (dayOfYear < 186) {
        month = dayOfYear / 31 + 1
        day = dayOfYear % 31 + 1
    } else {
        val remainder = dayOfYear - 186
        month = remainder / 30 + 7
        day = remainder % 30 + 1
    }
    return "%04d/%02d/%02d".format(jalaliYear, month, day)
}
private fun validJalali(s:String):Boolean {
    val p=s.split('/'); if(p.size!=3)return false
    val y=p[0].toIntOrNull()?:return false; val m=p[1].toIntOrNull()?:return false; val d=p[2].toIntOrNull()?:return false
    if (y !in 1300..1500 || m !in 1..12) return false
    val maxDay = when { m <= 6 -> 31; m <= 11 -> 30; else -> {
        val start = jalaliToGregorian(y, 1, 1); val next = jalaliToGregorian(y + 1, 1, 1)
        val days = ChronoUnit.DAYS.between(LocalDate.of(start[0], start[1], start[2]), LocalDate.of(next[0], next[1], next[2]))
        if (days == 366L) 30 else 29
    } }
    return d in 1..maxDay
}
private fun daysUntil(j:String):Int = try { val p=j.split('/'); val g=jalaliToGregorian(p[0].toInt(),p[1].toInt(),p[2].toInt()); val target=Calendar.getInstance().apply { set(g[0],g[1]-1,g[2],0,0,0);set(Calendar.MILLISECOND,0) }; val today=Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }; ((target.timeInMillis-today.timeInMillis)/86400000L).toInt() } catch (_:Exception) { 9999 }
private fun jalaliToGregorian(jy:Int,jm:Int,jd:Int):IntArray { var year=jy+1595; var days=-355668+365*year+(year/33)*8+(year%33+3)/4+jd+(if(jm<7)(jm-1)*31 else (jm-7)*30+186); var gy=400*(days/146097); days%=146097; if(days>36524){gy+=100*((--days)/36524);days%=36524;if(days>=365)days++}; gy+=4*(days/1461); days%=1461; if(days>365){gy+=(days-1)/365;days=(days-1)%365}; var gd=days+1; val leap=(gy%4==0&&gy%100!=0)||gy%400==0; val md=intArrayOf(31,if(leap)29 else 28,31,30,31,30,31,31,30,31,30,31); var gm=0; while(gm<12&&gd>md[gm]){gd-=md[gm];gm++}; return intArrayOf(gy,gm+1,gd) }
private fun cancelReminder(c:Context,i:ReminderItem) { cancelAlarm(c, i.id.hashCode()) }
private fun cancelExpiry(c:Context, item:BatchItem) { val base = item.id.hashCode(); cancelAlarm(c, base - 7); cancelAlarm(c, base - 1); cancelAlarm(c, base); val legacy = item.product.hashCode() + item.expiry.hashCode(); cancelAlarm(c, legacy - 7); cancelAlarm(c, legacy - 1); cancelAlarm(c, legacy) }
private fun cancelAlarm(c:Context,id:Int) { val intent = Intent(c, ReminderReceiver::class.java); val pi = PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return; (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi); pi.cancel() }
private fun scheduleReminder(c:Context,i:ReminderItem) { try { val p=i.date.split('/'); val g=jalaliToGregorian(p[0].toInt(),p[1].toInt(),p[2].toInt()); val t=i.time.split(':'); val cal=Calendar.getInstance().apply { set(g[0],g[1]-1,g[2],t[0].toInt(),t[1].toInt(),0);set(Calendar.MILLISECOND,0) }; schedule(c,i.id.hashCode(),i.title,cal.timeInMillis) } catch (_:Exception) {} }
private fun scheduleExpiry(c:Context,batchId:String,product:String,expiry:String) { try { val english=c.getSharedPreferences("reminder_local",Context.MODE_PRIVATE).getBoolean("english",true); val prefix=if(english) "Reminder" else "یادآور"; val p=expiry.split('/'); val g=jalaliToGregorian(p[0].toInt(),p[1].toInt(),p[2].toInt()); val cal=Calendar.getInstance().apply { set(g[0],g[1]-1,g[2],9,0,0);set(Calendar.MILLISECOND,0) }; val base=batchId.hashCode(); schedule(c,base-7,if(english) "$prefix: $product expires in 7 days" else "$prefix: تاریخ انقضای $product تا ۷ روز دیگر",cal.timeInMillis-7*86400000L); schedule(c,base-1,if(english) "$prefix: $product expires tomorrow" else "$prefix: تاریخ انقضای $product فرداست",cal.timeInMillis-86400000L); schedule(c,base,if(english) "Product expiry: $product" else "تاریخ انقضای محصول: $product",cal.timeInMillis) } catch (_:Exception) {} }
private fun schedule(c:Context,id:Int,title:String,at:Long) { if(at<System.currentTimeMillis())return; val i=Intent(c,ReminderReceiver::class.java).putExtra("title",title); val pi=PendingIntent.getBroadcast(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val am=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager; if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) }
private const val MANAGER_NIGHTLY_REQUEST = 29130
fun scheduleManagerNightly(context: Context, enabled: Boolean) {
    val intent = Intent(context, ManagerNightlyReceiver::class.java)
    val pending = PendingIntent.getBroadcast(context, MANAGER_NIGHTLY_REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (!enabled) { alarms.cancel(pending); pending.cancel(); return }
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1) }
    if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
}

class ManagerNightlyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val role = context.getSharedPreferences("reminder_store_profile", Context.MODE_PRIVATE).getString("role", "")
        if (role != "manager") return
        val english = context.getSharedPreferences("reminder_local", Context.MODE_PRIVATE).getBoolean("english", true)
        val title = if (english) "🌙 Get tomorrow ready" else "🌙 وقت برنامه‌ریزی فرداست!"
        val body = if (english) "Register tomorrow’s work-shift tasks so everyone starts with a clear plan." else "وظایف شیفت‌های کاری فردا را ثبت کنید تا کارکنان با برنامه مشخص وارد شیفت شوند."
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(context, MANAGER_NIGHTLY_REQUEST, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "store_alerts")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(android.graphics.Color.rgb(125, 249, 213))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) manager.notify(MANAGER_NIGHTLY_REQUEST, notification)
        scheduleManagerNightly(context, true)
    }
}

class ReminderReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
    val title=i.getStringExtra("title")?:"Reminder"
    val english=c.getSharedPreferences("reminder_local",Context.MODE_PRIVATE).getBoolean("english",true)
    val openApp=Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val content=PendingIntent.getActivity(c,title.hashCode(),openApp,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val n=NotificationCompat.Builder(c,"reminders").setSmallIcon(R.drawable.ic_notification).setContentTitle(if(english) "✨ Reminder" else "✨ یادآور").setContentText(title).setStyle(NotificationCompat.BigTextStyle().bigText(title)).setColor(android.graphics.Color.rgb(125,249,213)).setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(content).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
    val m=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)m.notify(title.hashCode(),n)
}}
class BootReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action==Intent.ACTION_BOOT_COMPLETED){val p=c.getSharedPreferences("reminder_local",Context.MODE_PRIVATE);loadReminders(p).filter{!it.done}.forEach{scheduleReminder(c,it)};loadBatches(p).forEach{scheduleExpiry(c,it.id,it.product,it.expiry)};val role=c.getSharedPreferences("reminder_store_profile",Context.MODE_PRIVATE).getString("role","");scheduleManagerNightly(c,role=="manager");if(c.getSharedPreferences("reminder_store_auth",Context.MODE_PRIVATE).getString("token","").orEmpty().isNotBlank())scheduleStoreNotifications(c)}}}
