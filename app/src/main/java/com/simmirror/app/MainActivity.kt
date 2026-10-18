package com.simmirror.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MirrorBus {
    var activeHostRole: Boolean = true
    var onHardwarePacketGenerated: ((type: String, payload: String) -> Unit)? = null
}

object CryptoEngine {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private fun deriveKey(pin: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(pin.trim().toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String, pin: String): String {
        if (pin.isBlank()) return plainText
        return try {
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    fun decrypt(rawText: String, pin: String): String {
        if (!rawText.startsWith("ENC:")) return rawText
        if (pin.isBlank()) return ""
        return try {
            val decoded = Base64.decode(rawText.removePrefix("ENC:"), Base64.NO_WRAP)
            val iv = decoded.copyOfRange(0, IV_LENGTH)
            val cipherText = decoded.copyOfRange(IV_LENGTH, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pin), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

class MirrorForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startPersistentNotification()
        acquireLocks()
    }

    private fun startPersistentNotification() {
        val channelId = "sim_mirror_ultra_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "SIM Mirror Hub Core Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SIM Mirror Hub v6.0 Ultra-Pro Active")
            .setContentText("WakeLock & WifiLock engaged. Bridge streaming continuously.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(6001, notification)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SIMMirrorHub::CoreWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SIMMirrorHub::CoreWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MirrorNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !MirrorBus.activeHostRole) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: pkg
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (text.isBlank()) return

        val appLabel = when {
            pkg.contains("whatsapp", true) -> "WhatsApp"
            pkg.contains("telegram", true) || pkg.contains("messenger", true) -> "Telegram/Messenger"
            pkg.contains("bank", true) || pkg.contains("pay", true) || pkg.contains("wallet", true) -> "Banking/Finance"
            pkg.contains("mms", true) || pkg.contains("messaging", true) -> "Messaging"
            else -> pkg.substringAfterLast('.')
        }

        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$stamp] [$appLabel] $title:$text"
        MirrorBus.onHardwarePacketGenerated?.invoke("APP_NOTIFICATION", formatted)
    }
}

class SimHardwareReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (!MirrorBus.activeHostRole) return
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
                val sb = StringBuilder()
                var sender = "Unknown"
                for (msg in messages) {
                    sender = msg.displayOriginatingAddress ?: sender
                    sb.append(msg.displayMessageBody ?: "")
                }
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                MirrorBus.onHardwarePacketGenerated?.invoke(
                    "INCOMING_SMS",
                    "[$stamp] From $sender:$sb"
                )
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Private/Unknown"
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    MirrorBus.onHardwarePacketGenerated?.invoke("INCOMING_CALL", incomingNumber)
                } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    MirrorBus.onHardwarePacketGenerated?.invoke("CALL_ENDED", incomingNumber)
                }
            }
        }
    }
}

class MainActivity : AppCompatActivity() {

    enum class Role { HOST, REMOTE }
    enum class LedState { DISCONNECTED, CONNECTING, CONNECTED }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: SharedPreferences

    private var currentRole = Role.HOST
    private var isArabic = false
    private var isDarkMode = true
    private var selectedTabIndex = 0
    private var ledState = LedState.DISCONNECTED
    private var deviceId = UUID.randomUUID().toString().substring(0, 8)

    private var tcpServerSocket: ServerSocket? = null
    private val connectedClientWriters = CopyOnWriteArrayList<PrintWriter>()
    private var remoteSocket: Socket? = null
    private var remoteWriter: PrintWriter? = null
    private var ntfyEventSourceCall: Call? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    private val hostContactsCache = mutableListOf<Pair<String, String>>()
    private val notificationsVaultList = mutableListOf<String>()
    private val historyVaultList = mutableListOf<String>()

    private lateinit var rootContainer: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var ledIndicatorView: View
    private lateinit var tvLedStatusLabel: TextView
    private lateinit var btnLangToggle: TextView
    private lateinit var btnThemeToggle: TextView
    private lateinit var tabBarLayout: LinearLayout
    private val tabButtons = mutableListOf<TextView>()
    private val tabContainers = mutableListOf<ScrollView>()

    private lateinit var etRoomTopic: EditText
    private lateinit var etHostIpInput: EditText
    private lateinit var etSecretPin: EditText
    private lateinit var tvLocalIpDisplay: TextView
    private lateinit var tvConnectionConsole: TextView

    private lateinit var etDialNumber: EditText
    private lateinit var tvCallLogsBox: TextView

    private lateinit var etTargetSmsUssdNumber: EditText
    private lateinit var etSmsMessageBody: EditText
    private lateinit var tvSmsUssdResultBox: TextView

    private lateinit var tvNotificationsBox: TextView
    private lateinit var tvHistoryVaultBox: TextView

    private var floatingOverlayView: View? = null
    private lateinit var inAppCallBanner: LinearLayout
    private lateinit var tvIncomingCallerId: TextView

    private lateinit var localContactPickerLauncher: ActivityResultLauncher<Intent>

    private val tabColors = intArrayOf(
        Color.parseColor("#0EA5E9"),
        Color.parseColor("#10B981"),
        Color.parseColor("#8B5CF6"),
        Color.parseColor("#EC4899"),
        Color.parseColor("#F59E0B")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sim_mirror_v6_prefs", Context.MODE_PRIVATE)
        isArabic = prefs.getBoolean("is_arabic", false)
        isDarkMode = prefs.getBoolean("is_dark", true)
        currentRole = if (prefs.getBoolean("is_host", true)) Role.HOST else Role.REMOTE
        MirrorBus.activeHostRole = (currentRole == Role.HOST)

        loadPersistedVaults()
        registerContactPicker()
        requestRuntimePermissions()

        MirrorBus.onHardwarePacketGenerated = { type, payload ->
            mainScope.launch {
                appendVaultEntry("[$type]$payload")
                if (type == "APP_NOTIFICATION") {
                    notificationsVaultList.add(0, payload)
                    refreshNotificationsUI()
                }
                transmitPacket(type, payload)
            }
        }

        ContextCompat.startForegroundService(this, Intent(this, MirrorForegroundService::class.java))

        buildMasterUI()
        applyThemeAndLanguage()
    }

    private fun tr(en: String, ar: String): String = if (isArabic) ar else en

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun darkenColor(color: Int, factor: Float = 0.68f): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun create3DDrawable(faceColor: Int, cornerRadiusDp: Int = 12, bevelHeightDp: Int = 5): LayerDrawable {
        val radius = dp(cornerRadiusDp).toFloat()
        val bevel = dp(bevelHeightDp)

        val shadowLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(darkenColor(faceColor, 0.55f))
        }

        val highlightStroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(faceColor)
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }

        return LayerDrawable(arrayOf(shadowLayer, highlightStroke)).apply {
            setLayerInset(0, 0, bevel, 0, 0)
            setLayerInset(1, 0, 0, 0, bevel)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create3DButton(
        textEn: String,
        textAr: String,
        baseColor: Int,
        textSizeSp: Float = 13f,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = tr(textEn, textAr)
            setTextColor(Color.WHITE)
            textSize = textSizeSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(16))
            background = create3DDrawable(baseColor, cornerRadiusDp = 12, bevelHeightDp = 5)
            elevation = dp(4).toFloat()

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationY = dp(4).toFloat()
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.translationY = 0f
                        onClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.translationY = 0f
                    }
                }
                true
            }
        }
    }

    private fun createCardContainer(accentColor: Int): LinearLayout {
        val cardBg = if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF")
        val borderColor = if (isDarkMode) darkenColor(accentColor, 0.85f) else accentColor
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(cardBg)
                setStroke(dp(2), borderColor)
            }
            elevation = dp(6).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, dp(14))
            layoutParams = lp
        }
    }

    private fun createSectionHeaderWithInfo(
        titleEn: String,
        titleAr: String,
        infoEn: String,
        infoAr: String,
        accentColor: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))

            val titleView = TextView(this@MainActivity).apply {
                text = tr(titleEn, titleAr)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val infoBtn = create3DButton("ℹ️ Info", "ℹ️ معلومات", Color.parseColor("#475569"), 11f) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr(titleEn, titleAr))
                    .setMessage(tr(infoEn, infoAr))
                    .setPositiveButton(tr("Got It", "حسناً"), null)
                    .show()
            }.apply {
                setPadding(dp(10), dp(6), dp(10), dp(10))
            }

            addView(titleView)
            addView(infoBtn)
        }
    }

    private fun styleInputEditText(hintEn: String, hintAr: String, defaultText: String = ""): EditText {
        val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val fg = if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val hintColor = if (isDarkMode) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        return EditText(this).apply {
            hint = tr(hintEn, hintAr)
            setText(defaultText)
            setTextColor(fg)
            setHintTextColor(hintColor)
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(bg)
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(10))
            layoutParams = lp
        }
    }

    private fun buildMasterUI() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "SIM Mirror Hub v6.0"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        tvLedStatusLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 1
        }

        titleColumn.addView(appTitle)
        titleColumn.addView(tvLedStatusLabel)

        ledIndicatorView = View(this).apply {
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(10)
                marginStart = dp(6)
            }
        }

        btnLangToggle = create3DButton(
            if (isArabic) "🌐 AR" else "🌐 EN",
            if (isArabic) "🌐 AR" else "🌐 EN",
            Color.parseColor("#2563EB"),
            12f
        ) {
            isArabic = !isArabic
            prefs.edit().putBoolean("is_arabic", isArabic).apply()
            rebuildUIPreservingState()
        }.apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }

        btnThemeToggle = create3DButton(
            if (isDarkMode) "🌙 Dark" else "☀️ Light",
            if (isDarkMode) "🌙 داكن" else "☀️ فاتح",
            Color.parseColor("#4F46E5"),
            12f
        ) {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("is_dark", isDarkMode).apply()
            rebuildUIPreservingState()
        }

        headerBar.addView(titleColumn)
        headerBar.addView(ledIndicatorView)
        headerBar.addView(btnLangToggle)
        headerBar.addView(btnThemeToggle)
        rootContainer.addView(headerBar)

        inAppCallBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#DC2626"))
        }
        tvIncomingCallerId = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnBannerAnswer = create3DButton("🔊 Answer", "🔊 رد ومكبر", Color.parseColor("#16A34A"), 12f) {
            sendRemoteCommand("ANSWER_CALL", "FORCE_SPEAKER")
            hideIncomingCallOverlay()
        }
        val btnBannerDismiss = create3DButton("✖", "✖", Color.parseColor("#7F1D1D"), 12f) {
            hideIncomingCallOverlay()
        }.apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(6)
            layoutParams = lp
        }
        inAppCallBanner.addView(tvIncomingCallerId)
        inAppCallBanner.addView(btnBannerAnswer)
        inAppCallBanner.addView(btnBannerDismiss)
        rootContainer.addView(inAppCallBanner)

        tabBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        val tabTitlesEn = listOf(
            "Connect",
            "Calls & Audio",
            "SMS, USSD & Apps",
            "Notifications Vault",
            "History Vault"
        )
        val tabTitlesAr = listOf(
            "الاتصال",
            "المكالمات",
            "الرسائل و USSD",
            "خزنة الإشعارات",
            "خزنة السجل"
        )

        tabButtons.clear()
        for (i in 0 until 5) {
            val tabBtn = TextView(this).apply {
                text = tr(tabTitlesEn[i], tabTitlesAr[i])
                setTextColor(Color.WHITE)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(10), dp(4), dp(13))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(2), 0, dp(2), 0)
                layoutParams = lp
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    switchTab(i)
                }
            }
            tabButtons.add(tabBtn)
            tabBarLayout.addView(tabBtn)
        }
        rootContainer.addView(tabBarLayout)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tabContainers.clear()
        tabContainers.add(buildTab1Connect())
        tabContainers.add(buildTab2CallsAudio())
        tabContainers.add(buildTab3SmsUssdApps())
        tabContainers.add(buildTab4NotificationsVault())
        tabContainers.add(buildTab5HistoryVault())

        tabContainers.forEach { contentFrame.addView(it) }
        rootContainer.addView(contentFrame)

        setContentView(rootContainer)
        updateLedIndicator(ledState)
        switchTab(selectedTabIndex)
    }

    private fun rebuildUIPreservingState() {
        val savedTopic = if (::etRoomTopic.isInitialized) etRoomTopic.text.toString() else prefs.getString("topic", "simhub_v6_ultra") ?: "simhub_v6_ultra"
        val savedIp = if (::etHostIpInput.isInitialized) etHostIpInput.text.toString() else prefs.getString("host_ip", "192.168.43.1") ?: "192.168.43.1"
        val savedPin = if (::etSecretPin.isInitialized) etSecretPin.text.toString() else prefs.getString("pin", "") ?: ""
        prefs.edit().putString("topic", savedTopic).putString("host_ip", savedIp).putString("pin", savedPin).apply()

        buildMasterUI()
        applyThemeAndLanguage()
    }

    private fun applyThemeAndLanguage() {
        window.decorView.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val rootBg = if (isDarkMode) Color.parseColor("#0B1120") else Color.parseColor("#E2E8F0")
        val headerBg = if (isDarkMode) Color.parseColor("#111827") else Color.parseColor("#FFFFFF")
        rootContainer.setBackgroundColor(rootBg)
        headerBar.setBackgroundColor(headerBg)
        tabBarLayout.setBackgroundColor(headerBg)
        switchTab(selectedTabIndex)
    }

    private fun switchTab(index: Int) {
        selectedTabIndex = index
        for (i in 0 until 5) {
            tabContainers[i].visibility = if (i == index) View.VISIBLE else View.GONE
            val activeColor = tabColors[i]
            val inactiveColor = if (isDarkMode) Color.parseColor("#334155") else Color.parseColor("#64748B")
            tabButtons[i].background = create3DDrawable(
                if (i == index) activeColor else inactiveColor,
                cornerRadiusDp = 10,
                bevelHeightDp = if (i == index) 5 else 3
            )
            tabButtons[i].translationY = if (i == index) 0f else dp(1).toFloat()
        }
    }

    private fun updateLedIndicator(state: LedState) {
        ledState = state
        val (ledColor, statusEn, statusAr) = when (state) {
            LedState.DISCONNECTED -> Triple(
                Color.parseColor("#EF4444"),
                "🔴 Disconnected / Offline",
                "🔴 غير متصل / متوقف"
            )
            LedState.CONNECTING -> Triple(
                Color.parseColor("#F59E0B"),
                "🟠 Connecting / Handshaking...",
                "🟠 جاري الاتصال والمصافحة..."
            )
            LedState.CONNECTED -> Triple(
                Color.parseColor("#10B981"),
                "🟢 Connected & Stable (${currentRole.name})",
                "🟢 متصل ومستقر (${if (currentRole == Role.HOST) "مضيف" else "بعيد"})"
            )
        }

        ledIndicatorView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ledColor)
            setStroke(dp(3), Color.argb(110, 255, 255, 255))
        }
        tvLedStatusLabel.text = tr(statusEn, statusAr)
    }

    private fun buildTab1Connect(): ScrollView {
        val accent = tabColors[0]
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        val roleCard = createCardContainer(accent)
        roleCard.addView(
            createSectionHeaderWithInfo(
                "1. Device Operating Role",
                "1. وضع تشغيل الجهاز",
                "Select HOST on the primary phone holding the physical SIM card. Select REMOTE on secondary phones to control the Host SIM from anywhere.",
                "اختر (الهاتف المضيف) في الهاتف الذي يحتوي على شريحة SIM الفعلية. اختر (الهاتف البعيد) للتحكم في الشريحة عن بعد.",
                accent
            )
        )

        val roleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHostRole = create3DButton(
            "📡 HOST (SIM Phone)",
            "📡 المضيف (هاتف الشريحة)",
            if (currentRole == Role.HOST) accent else Color.parseColor("#475569")
        ) {
            currentRole = Role.HOST
            MirrorBus.activeHostRole = true
            prefs.edit().putBoolean("is_host", true).apply()
            rebuildUIPreservingState()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }

        val btnRemoteRole = create3DButton(
            "📱 REMOTE (Controller)",
            "📱 البعيد (جهاز التحكم)",
            if (currentRole == Role.REMOTE) accent else Color.parseColor("#475569")
        ) {
            currentRole = Role.REMOTE
            MirrorBus.activeHostRole = false
            prefs.edit().putBoolean("is_host", false).apply()
            rebuildUIPreservingState()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        roleRow.addView(btnHostRole)
        roleRow.addView(btnRemoteRole)
        roleCard.addView(roleRow)
        container.addView(roleCard)

        val bridgeCard = createCardContainer(accent)
        bridgeCard.addView(
            createSectionHeaderWithInfo(
                "2. Cloud (Ntfy.sh) & Local Hotspot Bridge",
                "2. جسر السحابة (Ntfy) والشبكة المحلية",
                "Enter an identical Room Topic and optional AES-256 PIN on both phones. Cloud Stream works globally over mobile data; Local TCP works offline via Wi-Fi/Hotspot.",
                "أدخل نفس اسم الغرفة ورمز التشفير AES-256 في الهاتفين. البث السحابي يعمل عبر الإنترنت عالمياً، بينما يعمل TCP محلياً بدون إنترنت.",
                accent
            )
        )

        tvLocalIpDisplay = TextView(this).apply {
            text = tr("Local Wi-Fi/Hotspot IP: ${getLocalIpAddress()} (Port 8989)", "عنوان IP المحلي: ${getLocalIpAddress()} (منفذ 8989)")
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        bridgeCard.addView(tvLocalIpDisplay)

        etRoomTopic = styleInputEditText(
            "Cloud Room Topic (e.g. simhub_v6_ultra)",
            "اسم الغرفة السحابية (مثال simhub_v6_ultra)",
            prefs.getString("topic", "simhub_v6_ultra") ?: "simhub_v6_ultra"
        )
        etHostIpInput = styleInputEditText(
            "Host Local IP for Direct Wi-Fi (e.g. 192.168.43.1)",
            "عنوان IP للهاتف المضيف للشبكة المحلية",
            prefs.getString("host_ip", getLocalIpAddress()) ?: "192.168.43.1"
        )
        etSecretPin = styleInputEditText(
            "Optional AES-256 Secret PIN (Leave empty for none)",
            "رمز التشفير السري AES-256 (اختياري)",
            prefs.getString("pin", "") ?: ""
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        bridgeCard.addView(etRoomTopic)
        bridgeCard.addView(etHostIpInput)
        bridgeCard.addView(etSecretPin)

        val connectBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnStartCloud = create3DButton("☁️ Start Cloud Stream", "☁️ بدء البث السحابي", accent) {
            startCloudBridge()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }

        val btnStartLocal = create3DButton("📶 Start Local Wi-Fi", "📶 بدء الشبكة المحلية", Color.parseColor("#0284C7")) {
            startLocalTcpBridge()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        connectBtnRow.addView(btnStartCloud)
        connectBtnRow.addView(btnStartLocal)
        bridgeCard.addView(connectBtnRow)

        val btnDisconnectAll = create3DButton("🛑 Stop All Bridges", "🛑 إيقاف جميع الجسور", Color.parseColor("#DC2626")) {
            stopAllBridges()
        }.apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        bridgeCard.addView(btnDisconnectAll)

        tvConnectionConsole = TextView(this).apply {
            text = tr("Bridge Console Ready...", "وحدة تحكم الجسر جاهزة...")
            textSize = 12f
            setTextColor(if (isDarkMode) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"))
            setPadding(dp(8), dp(10), dp(8), dp(4))
        }
        bridgeCard.addView(tvConnectionConsole)
        container.addView(bridgeCard)

        scroll.addView(container)
        return scroll
    }

    private fun buildTab2CallsAudio(): ScrollView {
        val accent = tabColors[1]
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        val callCard = createCardContainer(accent)
        callCard.addView(
            createSectionHeaderWithInfo(
                "Remote Dialer & 3-Stage Speaker Force",
                "الاتصال عن بعد وفرض مكبر الصوت الثلاثي",
                "Dial any number through the Host SIM, or answer an incoming Host call with our 3-stage (0.5s, 1.2s, 2.0s) hardware speakerphone enforcer.",
                "اتصل بأي رقم عبر شريحة المضيف، أو رد على المكالمات الواردة مع تفعيل مكبر الصوت تلقائياً عبر 3 مراحل زمنية متتالية بأقصى صوت.",
                accent
            )
        )

        etDialNumber = styleInputEditText("Enter Phone Number to Dial via Host", "أدخل رقم الهاتف للاتصال عبر المضيف")
        etDialNumber.inputType = InputType.TYPE_CLASS_PHONE
        callCard.addView(etDialNumber)

        val dialActionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnDial = create3DButton("📞 Dial on Host", "📞 اتصال من المضيف", accent) {
            val num = etDialNumber.text.toString().trim()
            if (num.isNotEmpty()) {
                if (currentRole == Role.HOST) executeHostDial(num)
                else sendRemoteCommand("DIAL_CALL", num)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }

        val btnAnswerSpeaker = create3DButton("🔊 Answer + Force Speaker", "🔊 رد + مكبر الصوت", Color.parseColor("#059669")) {
            if (currentRole == Role.HOST) answerIncomingCallAndForceSpeaker()
            else sendRemoteCommand("ANSWER_CALL", "FORCE_SPEAKER")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dialActionsRow.addView(btnDial)
        dialActionsRow.addView(btnAnswerSpeaker)
        callCard.addView(dialActionsRow)

        container.addView(callCard)

        val logsCard = createCardContainer(accent)
        logsCard.addView(
            createSectionHeaderWithInfo(
                "Host Call Logs Sync (SYNC_CALL_LOG)",
                "مزامنة سجل مكالمات المضيف",
                "Fetches the last 25 calls directly from the Host SIM phone's CallLog provider and returns them via CALL_LOG_DATA packet.",
                "يجلب آخر 25 مكالمة مباشرة من سجل الهاتف المضيف ويرسلها عبر حزمة CALL_LOG_DATA لعرضها على الهاتف البعيد.",
                accent
            )
        )

        val btnSyncCallLogs = create3DButton(
            "🔄 Sync Host Call Logs Now",
            "🔄 مزامنة سجل مكالمات المضيف الآن",
            accent
        ) {
            if (currentRole == Role.HOST) {
                val hostLogs = readLastCalls()
                tvCallLogsBox.text = hostLogs
                transmitPacket("CALL_LOG_DATA", hostLogs)
            } else {
                tvCallLogsBox.text = tr("Requesting Host Call Logs (SYNC_CALL_LOG)...", "جاري طلب سجل مكالمات المضيف...")
                sendRemoteCommand("SYNC_CALL_LOG", "LAST_25")
            }
        }
        logsCard.addView(btnSyncCallLogs)

        tvCallLogsBox = TextView(this).
