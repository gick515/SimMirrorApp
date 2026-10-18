package com.simmirror.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

object MirrorBridge {
    var onLogEvent: ((String) -> Unit)? = null
    private var tcpWriter: PrintWriter? = null
    private var cloudWebSocket: WebSocket? = null
    var secretRoomCode: String = "Shalapy_Room_2026"
    var isHostMode: Boolean = true
    var isEnglishLanguage: Boolean = true
    var isDarkMode: Boolean = true
    var encryptionPin: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedIds = Collections.synchronizedSet(LinkedHashSet<String>())

    fun isDuplicatePacket(id: String): Boolean {
        if (id.isEmpty()) return false
        synchronized(processedIds) {
            if (processedIds.contains(id)) return true
            processedIds.add(id)
            if (processedIds.size > 200) {
                val it = processedIds.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            return false
        }
    }

    fun attachTcpWriter(writer: PrintWriter?) { tcpWriter = writer }
    fun attachCloudSocket(ws: WebSocket?) { cloudWebSocket = ws }

    fun sendPacket(type: String, number: String = "", body: String = "", extra: String = "") {
        scope.launch {
            try {
                val packetId = UUID.randomUUID().toString().substring(0, 8)
                processedIds.add(packetId)

                val json = JSONObject().apply {
                    put("id", packetId)
                    put("room", secretRoomCode)
                    put("senderRole", if (isHostMode) "HOST" else "CLIENT")
                    put("type", type)
                    put("number", number)
                    put("body", body)
                    put("extra", extra)
                    put("ts", System.currentTimeMillis())
                }
                val payload = json.toString()
                tcpWriter?.println(payload)
                cloudWebSocket?.send(payload)

                val cleanRoom = secretRoomCode.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                val url = URL("https://ntfy.sh/SimMirrorHub_$cleanRoom")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var udpRunning = false
    private var activeRingtone: Ringtone? = null
    private var selectedSimSlot = 0
    private var lastCallerNumber = ""
    private var ntfyStreamJob: Job? = null

    private lateinit var topStatusBanner: TextView
    private lateinit var batteryBanner: TextView
    private lateinit var logText: TextView
    private lateinit var ipInput: EditText
    private lateinit var cloudRoomInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var targetNumberInput: EditText
    private lateinit var smsBodyInput: EditText
    private lateinit var forwardNumberInput: EditText
    private lateinit var callHistoryText: TextView
    private lateinit var smsInboxText: TextView
    private lateinit var otpBanner: TextView
    private lateinit var permanentLogText: TextView

    private lateinit var tabConnectLayout: LinearLayout
    private lateinit var tabCallsLayout: LinearLayout
    private lateinit var tabSmsLayout: LinearLayout
    private lateinit var tabHistoryLayout: LinearLayout

    private val permanentStorageList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        createNotificationChannel()
        buildMasterInterface()
        setupBridgeListeners()
        registerCallAndBatteryMonitors()
        startForegroundKeepAlive()
    }

    private fun buildMasterInterface() {
        val bgDark = Color.parseColor("#0F172A")
        val bgLight = Color.parseColor("#F8FAFC")
        val panelDark = Color.parseColor("#1E293B")
        val panelLight = Color.parseColor("#FFFFFF")
        val textDark = Color.parseColor("#E2E8F0")
        val textLight = Color.parseColor("#1E293B")

        val currentBg = if (MirrorBridge.isDarkMode) bgDark else bgLight
        val currentPanel = if (MirrorBridge.isDarkMode) panelDark else panelLight
        val currentText = if (MirrorBridge.isDarkMode) textDark else textLight

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentBg)
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentPanel)
            setPadding(30, 35, 30, 20)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = if (MirrorBridge.isEnglishLanguage) "SIM Mirror Hub v5.0 Master" else "سيم ميرور برو v5.0"
            textSize = 20f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)

        val topControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val langBtn = createStyledButton(if (MirrorBridge.isEnglishLanguage) "AR" else "EN", "#475569") {
            MirrorBridge.isEnglishLanguage = !MirrorBridge.isEnglishLanguage
            recreate()
        }.apply { layoutParams = LinearLayout.LayoutParams(90, 75).apply { rightMargin = 8 } }

        val themeBtn = createStyledButton(if (MirrorBridge.isDarkMode) "☀️" else "🌙", "#475569") {
            MirrorBridge.isDarkMode = !MirrorBridge.isDarkMode
            recreate()
        }.apply { layoutParams = LinearLayout.LayoutParams(90, 75) }

        topControls.addView(langBtn)
        topControls.addView(themeBtn)
        titleRow.addView(topControls)
        headerBox.addView(titleRow)

        topStatusBanner = TextView(this).apply {
            text = if (MirrorBridge.isEnglishLanguage) "🟢 Status: Online | IP: ${getLocalIpAddress()}" else "🟢 الحالة: متصل | IP: ${getLocalIpAddress()}"
            textSize = 12f
            setTextColor(currentText)
            setPadding(0, 8, 0, 4)
        }
        headerBox.addView(topStatusBanner)

        batteryBanner = TextView(this).apply {
            text = if (MirrorBridge.isEnglishLanguage) "🔋 Host Battery: --%" else "🔋 بطارية الأساسي: --%"
            textSize = 11f
            setTextColor(Color.parseColor("#34D399"))
        }
        headerBox.addView(batteryBanner)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(0, 15, 0, 0)
        }

        val btnTab1 = createStyledButton(if (MirrorBridge.isEnglishLanguage) "1. Connect" else "1. الربط", "#0284C7") { switchTab(1) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 } }
        val btnTab2 = createStyledButton(if (MirrorBridge.isEnglishLanguage) "2. Calls" else "2. المكالمات", "#10B981") { switchTab(2) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 2; rightMargin = 2 } }
        val btnTab3 = createStyledButton(if (MirrorBridge.isEnglishLanguage) "3. SMS & Apps" else "3. الرسائل", "#8B5CF6") { switchTab(3) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 2; rightMargin = 2 } }
        val btnTab4 = createStyledButton(if (MirrorBridge.isEnglishLanguage) "4. History" else "4. السجل", "#F59E0B") { switchTab(4) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 } }

        tabBar.addView(btnTab1)
        tabBar.addView(btnTab2)
        tabBar.addView(btnTab3)
        tabBar.addView(btnTab4)
        headerBox.addView(tabBar)
        mainContainer.addView(headerBox)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 25, 30, 40)
        }

        tabConnectLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabConnectLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "1. Device Role Selection" else "أولاً: وضع الجهاز", if (MirrorBridge.isEnglishLanguage) "Choose if this phone holds the SIM card (Host) or acts as a remote controller (Remote)." else "حدد ما إذا كان هذا الهاتف يحمل الشريحة (Host) أو جهاز تحكم عن بُعد (Remote)."))
        
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnHost = createStyledButton(if (MirrorBridge.isEnglishLanguage) "SIM Host Mode" else "وضع الأساسي (Host)", "#0284C7") { startHostMode() }
        btnHost.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 }
        modeRow.addView(btnHost)

        val btnRemote = createStyledButton(if (MirrorBridge.isEnglishLanguage) "Remote Client" else "وضع الفرعي (Remote)", "#6366F1") {
            MirrorBridge.isHostMode = false
            topStatusBanner.text = if (MirrorBridge.isEnglishLanguage) "🟣 Remote Mode Active" else "🟣 وضع الفرعي (Remote)"
            appendLog("Switched to Remote Client mode.")
        }
        btnRemote.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 6 }
        modeRow.addView(btnRemote)
        tabConnectLayout.addView(modeRow)

        tabConnectLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "2. Secure Cloud Bridge" else "ثانياً: السحابة الآمنة", if (MirrorBridge.isEnglishLanguage) "Enables continuous connection anywhere without API keys." else "تفعيل الاتصال السحابي الدائم."))
        cloudRoomInput = createStyledInput("Room Code (e.g. Shalapy_Room_2026)")
        cloudRoomInput.setText("Shalapy_Room_2026")
        tabConnectLayout.addView(cloudRoomInput)

        pinInput = createStyledInput("Optional Encryption PIN (AES)")
        tabConnectLayout.addView(pinInput)

        tabConnectLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "☁️ Connect Secure Cloud" else "☁️ تفعيل السحابة الآمنة", "#8B5CF6") {
            MirrorBridge.secretRoomCode = cloudRoomInput.text.toString().trim().ifEmpty { "Shalapy_Room_2026" }
            MirrorBridge.encryptionPin = pinInput.text.toString().trim()
            startUltraStableCloudBridge()
        })
        tabConnectLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "⚡ Disable Battery Optimization" else "⚡ استثناء التطبيق من توفير الطاقة", "#B45309") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        })
        tabConnectLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔔 Enable Appear on Top Permission" else "🔔 السماح بالظهور فوق التطبيقات", "#0284C7") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        })

        tabConnectLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "3. Local Wi-Fi / Hotspot" else "ثالثاً: الربط المحلي عبر Wi-Fi", if (MirrorBridge.isEnglishLanguage) "Auto-discovers host on the same Wi-Fi router or hotspot." else "اكتشاف تلقائي للهاتف الأساسي."))
        tabConnectLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔍 Auto-Discover Host" else "🔍 بحث تلقائي عن الأساسي", "#10B981") { discoverHostAutomatically() })
        ipInput = createStyledInput("Enter Host IP manually")
        tabConnectLayout.addView(ipInput)
        tabConnectLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "Connect via IP" else "ربط يدوي بالـ IP", "#3B82F6") { connectToHostTcp(ipInput.text.toString().trim()) })

        contentRoot.addView(tabConnectLayout)

        tabCallsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        tabCallsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Call Control & Contacts" else "التحكم في المكالمات وجهات الاتصال", if (MirrorBridge.isEnglishLanguage) "Make, answer, and manage calls." else "إجراء والرد وإدارة المكالمات."))
        
        targetNumberInput = createStyledInput("Target Phone Number")
        tabCallsLayout.addView(targetNumberInput)

        val contactRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnLocalC = createStyledButton(if (MirrorBridge.isEnglishLanguage) "📱 Local Contacts" else "📱 جهات اتصال هذا الموبايل", "#475569") { pickContactFromDevice() }
        btnLocalC.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        contactRow.addView(btnLocalC)

        val btnHostC = createStyledButton(if (MirrorBridge.isEnglishLanguage) "☁️ Host Contacts" else "☁️ جهات اتصال الأساسي", "#334155") { triggerAction("SYNC_CONTACTS", "") }
        btnHostC.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        contactRow.addView(btnHostC)
        tabCallsLayout.addView(contactRow)

        val callRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 8, 0, 0) }
        val btnMakeCall = createStyledButton(if (MirrorBridge.isEnglishLanguage) "📞 Make Call" else "📞 إجراء مكالمة", "#10B981") { triggerAction("MAKE_CALL", targetNumberInput.text.toString().trim()) }
        btnMakeCall.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        callRow1.addView(btnMakeCall)

        val btnAns = createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔊 Answer + Speaker" else "🔊 الرد + سبيكر تلقائي", "#F59E0B") {
            stopRingingAlert()
            triggerAction("ANSWER_CALL", "")
        }
        btnAns.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        callRow1.addView(btnAns)
        tabCallsLayout.addView(callRow1)

        val callRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 8, 0, 0) }
        val btnEnd = createStyledButton(if (MirrorBridge.isEnglishLanguage) "⛔ End Call" else "⛔ رفض المكالمة", "#EF4444") { stopRingingAlert(); triggerAction("END_CALL", "") }
        btnEnd.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        callRow2.addView(btnEnd)

        val btnRejectSms = createStyledButton(if (MirrorBridge.isEnglishLanguage) "💬 Reject + SMS" else "💬 رفض + رسالة مشغول", "#EC4899") {
            stopRingingAlert()
            triggerAction("REJECT_WITH_SMS", lastCallerNumber, "I am busy right now, will call you later.")
        }
        btnRejectSms.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        callRow2.addView(btnRejectSms)
        tabCallsLayout.addView(callRow2)

        tabCallsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Call Forwarding (*21*)" else "تحويل المكالمات الخلوية", ""))
        forwardNumberInput = createStyledInput("Your Remote Phone Number")
        tabCallsLayout.addView(forwardNumberInput)
        val fwdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnFwdOn = createStyledButton(if (MirrorBridge.isEnglishLanguage) "Enable (*21*)" else "تفعيل التحويل (*21*)", "#7C3AED") {
            val num = forwardNumberInput.text.toString().trim()
            if (num.isNotEmpty()) triggerAction("MAKE_CALL", "*21*$num#")
        }
        btnFwdOn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        fwdRow.addView(btnFwdOn)

        val btnFwdOff = createStyledButton(if (MirrorBridge.isEnglishLanguage) "Disable (##21#)" else "إلغاء التحويل (##21#)", "#BE123C") { triggerAction("MAKE_CALL", "##21#") }
        btnFwdOff.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        fwdRow.addView(btnFwdOff)
        tabCallsLayout.addView(fwdRow)

        tabCallsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Call History Log" else "سجل المكالمات", ""))
        tabCallsLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔄 Sync Call Logs" else "🔄 جلب سجل المكالمات", "#0284C7") { triggerAction("SYNC_CALL_LOG", "") })
        callHistoryText = TextView(this).apply {
            text = "Call logs..."
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(currentText)
            setBackgroundColor(currentPanel)
            setPadding(20, 20, 20, 20)
        }
        tabCallsLayout.addView(callHistoryText)
        contentRoot.addView(tabCallsLayout)

        tabSmsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        otpBanner = TextView(this).apply {
            text = if (MirrorBridge.isEnglishLanguage) "🔐 Smart OTP Auto-Copy Active" else "🔐 النسخ التلقائي للـ OTP مفعل"
            textSize = 13f
            setTextColor(Color.parseColor("#FDE047"))
            setBackgroundColor(Color.parseColor("#312E81"))
            setPadding(20, 20, 20, 20)
        }
        tabSmsLayout.addView(otpBanner)

        tabSmsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "USSD Code Runner (*100#)" else "أكواد الشبكة (USSD)", ""))
        val ussdInput = createStyledInput("USSD Code (e.g. *100#)")
        tabSmsLayout.addView(ussdInput)
        tabSmsLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "⚡ Run USSD Code" else "⚡ تنفيذ كود USSD", "#D97706") {
            val code = ussdInput.text.toString().trim()
            if (code.isNotEmpty()) triggerAction("RUN_USSD", code)
        })

        tabSmsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Send SMS & Choose SIM" else "إرسال SMS واختيار الشريحة", ""))
        val simChoiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnSim1 = createStyledButton("SIM 1", "#0284C7") {
            selectedSimSlot = 0
            triggerAction("SELECT_SIM", "0")
            Toast.makeText(this, "Selected SIM 1", Toast.LENGTH_SHORT).show()
        }
        btnSim1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        simChoiceRow.addView(btnSim1)

        val btnSim2 = createStyledButton("SIM 2", "#0EA5E9") {
            selectedSimSlot = 1
            triggerAction("SELECT_SIM", "1")
            Toast.makeText(this, "Selected SIM 2", Toast.LENGTH_SHORT).show()
        }
        btnSim2.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        simChoiceRow.addView(btnSim2)
        tabSmsLayout.addView(simChoiceRow)

        smsBodyInput = createStyledInput("Message body...")
        tabSmsLayout.addView(smsBodyInput)
        tabSmsLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "📩 Send SMS" else "📩 إرسال الرسالة", "#10B981") {
            val target = targetNumberInput.text.toString().trim()
            val msg = smsBodyInput.text.toString().trim()
            if (target.isNotEmpty() && msg.isNotEmpty()) {
                triggerAction("SEND_SMS", target, msg)
                smsBodyInput.setText("")
            }
        })

        tabSmsLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "SMS Inbox & Notifications" else "صندوق الوارد والإشعارات", ""))
        tabSmsLayout.addView(createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔄 Sync SMS Inbox" else "🔄 جلب صندوق الوارد", "#0284C7") { triggerAction("SYNC_SMS_INBOX", "") })
        smsInboxText = TextView(this).apply {
            text = "Inbox & Notifications..."
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(currentText)
            setBackgroundColor(currentPanel)
            setPadding(20, 20, 20, 20)
        }
        tabSmsLayout.addView(smsInboxText)
        contentRoot.addView(tabSmsLayout)

        tabHistoryLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        tabHistoryLayout.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Persistent History Vault" else "السجل المحفوظ الدائم", ""))

        val histRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnClearH = createStyledButton(if (MirrorBridge.isEnglishLanguage) "🗑️ Clear (Keep Last 15)" else "🗑️ حذف مع حماية آخر 15", "#DC2626") { clearPermanentHistoryKeepLast15() }
        btnClearH.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 }
        histRow.addView(btnClearH)

        val btnRefH = createStyledButton(if (MirrorBridge.isEnglishLanguage) "🔄 Refresh" else "🔄 تحديث العرض", "#2563EB") { refreshPermanentHistoryUI() }
        btnRefH.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 4 }
        histRow.addView(btnRefH)
        tabHistoryLayout.addView(histRow)

        permanentLogText = TextView(this).apply {
            text = if (MirrorBridge.isEnglishLanguage) "No saved history yet." else "لا توجد عناصر محفوظة في السجل بعد."
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(currentText)
            setBackgroundColor(currentPanel)
            setPadding(20, 20, 20, 20)
        }
        tabHistoryLayout.addView(permanentLogText)
        contentRoot.addView(tabHistoryLayout)

        contentRoot.addView(createSectionHeader(if (MirrorBridge.isEnglishLanguage) "Real-time System Log" else "سجل النظام اللحظي", ""))
        logText = TextView(this).apply {
            text = "System is running...\n"
            textSize = 11f
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(currentPanel)
            setPadding(20, 20, 20, 20)
        }
        contentRoot.addView(logText)

        scroll.addView(contentRoot)
        mainContainer.addView(scroll)
        setContentView(mainContainer)
    }

    private fun switchTab(tabIndex: Int) {
        tabConnectLayout.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        tabCallsLayout.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        tabSmsLayout.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        tabHistoryLayout.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE
        if (tabIndex == 4) refreshPermanentHistoryUI()
    }

    private fun pickContactFromDevice() {
        try {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            startActivityForResult(intent, 200)
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open contacts", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val cursor = contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val num = it.getString(0) ?: ""
                        targetNumberInput.setText(num)
                        Toast.makeText(this, "Selected: $num", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun addEntryToPermanentStorage(entry: String) {
        permanentStorageList.add(0, "[${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())}] $entry")
        refreshPermanentHistoryUI()
    }

    private fun clearPermanentHistoryKeepLast15() {
        if (permanentStorageList.size > 15) {
            val last15 = permanentStorageList.takeLast(15)
            permanentStorageList.clear()
            permanentStorageList.addAll(last15)
            Toast.makeText(this, "Cleared successfully (Last 15 preserved)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Items are 15 or less, all preserved.", Toast.LENGTH_SHORT).show()
        }
        refreshPermanentHistoryUI()
    }

    private fun refreshPermanentHistoryUI() {
        if (permanentStorageList.isEmpty()) {
            permanentLogText.text = if (MirrorBridge.isEnglishLanguage) "History storage is empty." else "السجل المحفوظ فارغ حالياً."
        } else {
            permanentLogText.text = permanentStorageList.joinToString("\n────────────────────\n")
        }
    }

    private fun startUltraStableCloudBridge() {
        val cleanRoom = MirrorBridge.secretRoomCode.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        topStatusBanner.text = "☁️ Cloud Active | Room: $cleanRoom"
        appendLog("☁️ Cloud continuous stream started...")

        ntfyStreamJob?.cancel()
        ntfyStreamJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("https://ntfy.sh/SimMirrorHub_$cleanRoom/json")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 0
                    }
                    if (conn.responseCode == 200) {
                        withContext(Dispatchers.Main) {
                            appendLog("✅ Connected to cloud stream stably!")
                            if (MirrorBridge.isHostMode) sendBatteryStatusToClient()
                        }
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                        var line: String? = reader.readLine()
                        while (isActive && line != null) {
                            val raw = line.trim()
                            if (raw.isNotEmpty()) {
                                val eventJson = JSONObject(raw)
                                if (eventJson.optString("event") == "message") {
                                    processCloudPayloadString(eventJson.optString("message"))
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (e: Exception) {
                    delay(4000)
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun processCloudPayloadString(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("room") == MirrorBridge.secretRoomCode) {
                val myRole = if (MirrorBridge.isHostMode) "HOST" else "CLIENT"
                if (json.optString("senderRole") != myRole) {
                    val packetId = json.optString("id")
                    if (!MirrorBridge.isDuplicatePacket(packetId)) {
                        scope.launch { handleIncomingJson(json) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun startHostMode() {
        MirrorBridge.isHostMode = true
        topStatusBanner.text = "🟢 SIM Host | IP: ${getLocalIpAddress()}"
        appendLog("Host mode activated.")
        startUdpBeaconResponder()
        sendBatteryStatusToClient()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(8989)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    withContext(Dispatchers.Main) {
                        appendLog("✅ Local client connected: ${client.inetAddress.hostAddress}")
                        sendBatteryStatusToClient()
                    }
                    listenToTcpSocket(client)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startUdpBeaconResponder() {
        if (udpRunning) return
        udpRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val udpSocket = DatagramSocket(8990, InetAddress.getByName("0.0.0.0")).apply { broadcast = true }
                val buf = ByteArray(256)
                while (udpRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg == "DISCOVER_SIM_HOST" && MirrorBridge.isHostMode) {
                        val reply = "SIM_HOST_HERE:${getLocalIpAddress()}".toByteArray()
                        udpSocket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } catch (_: Exception) { udpRunning = false }
        }
    }

    private fun discoverHostAutomatically() {
        MirrorBridge.isHostMode = false
        appendLog("🔍 Scanning local network for Host...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket().apply { broadcast = true; soTimeout = 4000 }
                val data = "DISCOVER_SIM_HOST".toByteArray()
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 8990))
                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val reply = String(response.data, 0, response.length)
                socket.close()
                if (reply.startsWith("SIM_HOST_HERE:")) {
                    val foundIp = reply.substringAfter("SIM_HOST_HERE:")
                    withContext(Dispatchers.Main) {
                        ipInput.setText(foundIp)
                        appendLog("🎯 Host found at: $foundIp")
                        connectToHostTcp(foundIp)
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { appendLog("⚠️ Local discovery failed, use Cloud Bridge.") }
            }
        }
    }

    private fun connectToHostTcp(hostIp: String) {
        if (hostIp.isEmpty()) return
        MirrorBridge.isHostMode = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket?.close()
                val socket = Socket(hostIp, 8989)
                clientSocket = socket
                withContext(Dispatchers.Main) { topStatusBanner.text = "🟣 Connected locally ($hostIp)" }
                listenToTcpSocket(socket)
            } catch (e: Exception) { withContext(Dispatchers.Main) { appendLog("❌ Connection failed: ${e.message}") } }
        }
    }

    private suspend fun listenToTcpSocket(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val json = JSONObject(line)
                if (!MirrorBridge.isDuplicatePacket(json.optString("id"))) {
                    withContext(Dispatchers.Main) { handleIncomingJson(json) }
                }
                line = reader.readLine()
            }
        } catch (_: Exception) {}
    }

    private fun triggerAction(type: String, number: String, body: String = "") {
        if (MirrorBridge.isHostMode) {
            executeHostHardwareCommand(type, number, body)
        } else {
            MirrorBridge.sendPacket(type, number, body)
            appendLog("🚀 Command sent ($type)...")
        }
    }

    private fun handleIncomingJson(json: JSONObject) {
        val type = json.optString("type")
        val number = json.optString("number")
        val body = json.optString("body")
        val extra = json.optString("extra")

        when (type) {
            "INCOMING_SMS" -> {
                val txt = "📩 SMS from [$extra ($number)]:\n$body"
                appendLog(txt)
                addEntryToPermanentStorage(txt)
                showPopUpNotification("New SMS from $extra", body)
                checkAndAutoCopyOtp(body)
            }
            "INCOMING_CALL" -> {
                lastCallerNumber = number
                targetNumberInput.setText(number)
                val txt = "📞 Incoming call: $extra ($number)"
                appendLog(txt)
                addEntryToPermanentStorage(txt)
                showPopUpNotification("📞 Incoming Call", "Caller: $extra ($number)")
                startRingingAlert()
            }
            "BATTERY_STATUS" -> { batteryBanner.text = "🔋 Host Battery: $number% ($body)" }
            "CALL_LOG_DATA" -> {
                callHistoryText.text = body
                addEntryToPermanentStorage("Call Log Sync:\n$body")
                appendLog("✅ Call history synced.")
            }
            "SMS_INBOX_DATA" -> {
                smsInboxText.text = body
                addEntryToPermanentStorage("SMS Inbox Sync:\n$body")
                appendLog("✅ SMS inbox synced.")
            }
            "CONTACTS_DATA" -> {
                callHistoryText.text = "Host Contacts:\n$body"
                appendLog("✅ Host contacts synced.")
            }
            "NOTIFICATION_MIRROR" -> {
                val txt = "🔔 App Notification [$number]: $body"
                appendLog(txt)
                addEntryToPermanentStorage(txt)
                showPopUpNotification("Notification: $number", body)
            }
            "USSD_RESPONSE" -> {
                val txt = "⚡ USSD Response: $body"
                appendLog(txt)
                addEntryToPermanentStorage(txt)
                Toast.makeText(this, body, Toast.LENGTH_SHORT).show()
            }
            "SEND_SMS", "MAKE_CALL", "ANSWER_CALL", "END_CALL", "REJECT_WITH_SMS", "SYNC_CALL_LOG", "SYNC_SMS_INBOX", "SYNC_CONTACTS", "RUN_USSD" -> {
                executeHostHardwareCommand(type, number, body)
            }
        }
    }

    private fun checkAndAutoCopyOtp(msg: String) {
        val matcher = Pattern.compile("\\b(\\d{4,8})\\b").matcher(msg)
        if (matcher.find()) {
            val code = matcher.group(1) ?: return
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OTP", code))
            otpBanner.text = "🔐 OTP Copied: $code"
            Toast.makeText(this, "OTP Copied: $code", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeHostHardwareCommand(type: String, number: String, body: String) {
        try {
            when (type) {
                "SEND_SMS" -> getSmsManagerForSlot(selectedSimSlot).sendTextMessage(number, null, body, null, null)
                "MAKE_CALL" -> {
                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                }
                "ANSWER_CALL" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
                    }
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val speaker = am.availableCommunicationDevices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) am.setCommunicationDevice(speaker)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        am.isSpeakerphoneOn = true
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
                    }, 600)
                    Handler(Looper.getMainLooper()).postDelayed({
                        am.isSpeakerphoneOn = true
                    }, 1500)
                    appendLog("🔊 Answered and speakerphone forced successfully.")
                }
                "END_CALL" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
                    }
                }
                "REJECT_WITH_SMS" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
                    }
                    if (number.isNotEmpty()) {
                        getSmsManagerForSlot(selectedSimSlot).sendTextMessage(number, null, body, null, null)
                    }
                }
                "SYNC_CALL_LOG" -> {
                    val logs = readLastCalls()
                    if (MirrorBridge.isHostMode) callHistoryText.text = logs
                    MirrorBridge.sendPacket("CALL_LOG_DATA", "", logs)
                }
                "SYNC_SMS_INBOX" -> {
                    val smsList = readLastSms()
                    if (MirrorBridge.isHostMode) smsInboxText.text = smsList
                    MirrorBridge.sendPacket("SMS_INBOX_DATA", "", smsList)
                }
                "SYNC_CONTACTS" -> {
                    val contacts = readAllContacts()
                    MirrorBridge.sendPacket("CONTACTS_DATA", "", contacts)
                }
                "RUN_USSD" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val tm = (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                        tm.sendUssdRequest(number, object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager?, request: String?, response: CharSequence?) {
                                MirrorBridge.sendPacket("USSD_RESPONSE", "", response.toString())
                            }
                            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager?, request: String?, failureCode: Int) {
                                MirrorBridge.sendPacket("USSD_RESPONSE", "", "USSD Failed: $failureCode")
                            }
                        }, Handler(Looper.getMainLooper()))
                    }
                }
            }
        } catch (e: Exception) { appendLog("⚠️ Error: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun getSmsManagerForSlot(slot: Int): SmsManager {
        return try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subs = sm.activeSubscriptionInfoList
            val subId = if (subs != null && subs.size > slot) subs[slot].subscriptionId else SubscriptionManager.getDefaultSmsSubscriptionId()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        }
    }

    private fun lookupContactName(num: String): String {
        if (num.isEmpty()) return "Unknown"
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
            num
        } catch (_: Exception) { num }
    }

    @SuppressLint("Range")
    private fun readAllContacts(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)?.use {
                var c = 0
                while (it.moveToNext() && c < 50) {
                    val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                    val num = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    sb.append("$name: $num\n")
                    c++
                }
            }
        } catch (_: Exception) { return "Error reading contacts" }
        return if (sb.isEmpty()) "No contacts found." else sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun readLastCalls(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")?.use {
                var c = 0
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                while (it.moveToNext() && c < 15) {
                    val num = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val type = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                        CallLog.Calls.INCOMING_TYPE -> "📥 Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "📤 Outgoing"
                        else -> "❌ Missed"
                    }
                    sb.append("$type | ${lookupContactName(num)} ($num) - ${sdf.format(Date(it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))))}\n")
                    c++
                }
            }
        } catch (_: Exception) { return "Error reading call logs" }
        return if (sb.isEmpty()) "No calls recorded." else sb.toString()
    }

    private fun readLastSms(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, "${Telephony.Sms.DATE} DESC")?.use {
                var c = 0
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                while (it.moveToNext() && c < 15) {
                    val addr = it.getString(0) ?: ""
                    sb.append("📩 [${lookupContactName(addr)} - ${sdf.format(Date(it.getLong(2)))}]:\n${it.getString(1)}\n────────\n")
                    c++
                }
            }
        } catch (_: Exception) { return "Error reading SMS inbox" }
        return if (sb.isEmpty()) "Inbox is empty." else sb.toString()
    }

    private fun startRingingAlert() {
        try {
            stopRingingAlert()
            activeRingtone = RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            activeRingtone?.play()
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun stopRingingAlert() {
        try { activeRingtone?.stop() } catch (_: Exception) {}
    }

    private fun registerCallAndBatteryMonitors() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING && MirrorBridge.isHostMode) {
                            val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                            MirrorBridge.sendPacket("INCOMING_CALL", num, "", lookupContactName(num))
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        if (MirrorBridge.isHostMode) sendBatteryStatusToClient()
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
    }

    private fun sendBatteryStatusToClient() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryBanner.text = if (MirrorBridge.isEnglishLanguage) "🔋 Host Battery: $pct%" else "🔋 بطارية الأساسي: $pct%"
        MirrorBridge.sendPacket("BATTERY_STATUS", pct.toString(), if (bm.isCharging) "Charging" else "On Battery")
    }

    private fun setupBridgeListeners() {
        MirrorBridge.onLogEvent = { msg -> scope.launch { appendLog(msg) } }
    }

    private fun startForegroundKeepAlive() {
        try {
            val intent = Intent(this, MirrorBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("SIM_MIRROR_ALERTS", "System Alerts", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun showPopUpNotification(title: String, msg: String) {
        try {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, "SIM_MIRROR_ALERTS")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(System.currentTimeMillis().toInt(), notif)
        } catch (_: Exception) {}
    }

    private fun getLocalIpAddress(): String {
        return try {
            val ip = Formatter.formatIpAddress((applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress)
            if (ip == "0.0.0.0") "192.168.43.1" else ip
        } catch (_: Exception) { "192.168.43.1" }
    }

    private fun appendLog(msg: String) {
        logText.text = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg\n${logText.text}"
    }

    private fun createSectionHeader(title: String, infoDesc: String = ""): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 8)
        }
        val tv = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tv)

        if (infoDesc.isNotEmpty()) {
            val infoBtn = Button(this).apply {
                text = "i"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#475569"))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Option Info")
                        .setMessage(infoDesc)
                        .setPositiveButton("OK", null)
                        .show()
                }
                layoutParams = LinearLayout.LayoutParams(60, 60)
            }
            row.addView(infoBtn)
        }
        return row
    }

    private fun createStyledInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(20, 18, 20, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8; bottomMargin = 8 }
        }
    }

    private fun createStyledButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setOnClickListener { onClick() }
        }
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 102)
    }
}

class MirrorBackgroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override()->Unit {
        super.onCreate()
    }
    
    override fun onCreate() {
        super.onCreate()
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimMirror::KeepAlive")
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, "SIM_MIRROR_ALERTS")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("SIM Mirror Master Active")
            .setContentText("Background Cloud & Notifications Stream Connected")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
