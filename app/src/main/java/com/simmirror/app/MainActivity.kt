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
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object MirrorBridge {
    var onUiUpdate: ((JSONObject) -> Unit)? = null
    var onLogEvent: ((String) -> Unit)? = null
    private var tcpWriter: PrintWriter? = null
    private var cloudWebSocket: WebSocket? = null
    var secretRoomCode: String = "MySecretRoom99"
    var isHostMode: Boolean = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun attachTcpWriter(writer: PrintWriter?) {
        tcpWriter = writer
    }

    fun attachCloudSocket(ws: WebSocket?) {
        cloudWebSocket = ws
    }

    fun sendPacket(type: String, number: String = "", body: String = "", extra: String = "") {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("room", secretRoomCode)
                    put("senderRole", if (isHostMode) "HOST" else "CLIENT")
                    put("type", type)
                    put("number", number)
                    put("body", body)
                    put("extra", extra)
                }
                val payload = json.toString()
                tcpWriter?.println(payload)
                cloudWebSocket?.send(payload)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
    private var isCloudConnected = false

    private lateinit var topStatusBanner: TextView
    private lateinit var batteryBanner: TextView
    private lateinit var logText: TextView
    private lateinit var ipInput: EditText
    private lateinit var cloudRoomInput: EditText
    private lateinit var targetNumberInput: EditText
    private lateinit var smsBodyInput: EditText
    private lateinit var forwardNumberInput: EditText
    private lateinit var callHistoryText: TextView
    private lateinit var smsInboxText: TextView
    private lateinit var otpBanner: TextView

    private lateinit var tabConnectLayout: LinearLayout
    private lateinit var tabCallsLayout: LinearLayout
    private lateinit var tabSmsLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        createNotificationChannel()
        buildTabbedInterface()
        setupBridgeListeners()
        registerCallAndBatteryMonitors()
        startForegroundKeepAlive()
    }

    private fun buildTabbedInterface() {
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(35, 45, 35, 25)
        }

        val title = TextView(this).apply {
            text = "SIM Mirror Hub v2.0 Pro"
            textSize = 21f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(title)

        topStatusBanner = TextView(this).apply {
            text = "🟢 IP الجهاز: ${getLocalIpAddress()} | اختر وضع التشغيل"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 5)
        }
        headerBox.addView(topStatusBanner)

        batteryBanner = TextView(this).apply {
            text = "🔋 بطارية الأساسي: --% | الشريحة النشطة: SIM 1"
            textSize = 12f
            setTextColor(Color.parseColor("#34D399"))
            gravity = Gravity.CENTER
        }
        headerBox.addView(batteryBanner)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 20, 0, 0)
        }

        val btnTab1 = createStyledButton("1. الربط والسحابة", "#0284C7") { switchTab(1) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 } }
        val btnTab2 = createStyledButton("2. المكالمات", "#4F46E5") { switchTab(2) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 3; rightMargin = 3 } }
        val btnTab3 = createStyledButton("3. الرسائل و OTP", "#059669") { switchTab(3) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 6 } }

        tabBar.addView(btnTab1)
        tabBar.addView(btnTab2)
        tabBar.addView(btnTab3)
        headerBox.addView(tabBar)
        mainContainer.addView(headerBox)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 30, 35, 50)
        }

        tabConnectLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tabConnectLayout.addView(createSectionHeader("أولاً: تحديد وضع هذا الموبايل"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        modeRow.addView(createStyledButton("وضع الأساسي (SIM Host)", "#0284C7") {
            startHostMode()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        modeRow.addView(createStyledButton("وضع الفرعي (Remote)", "#6366F1") {
            MirrorBridge.isHostMode = false
            topStatusBanner.text = "🟣 الوضع: هاتف فرعي (Remote) | جاهز للاتصال"
            appendLog("تم التبديل لوضع الهاتف الفرعي.")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabConnectLayout.addView(modeRow)

        tabConnectLayout.addView(createSectionHeader("ثانياً: الربط الأوفلاين (Wi-Fi / Hotspot)"))
        tabConnectLayout.addView(createStyledButton("🔍 بحث وربط تلقائي بالهاتف الأساسي (بدون كتابة IP)", "#10B981") {
            discoverHostAutomatically()
        })

        ipInput = createStyledInput("أو أدخل IP الأساسي يدوياً (مثال: 192.168.0.196)")
        tabConnectLayout.addView(ipInput)
        tabConnectLayout.addView(createStyledButton("ربط يدوي عبر الـ IP", "#3B82F6") {
            connectToHostTcp(ipInput.text.toString().trim())
        })

        tabConnectLayout.addView(createSectionHeader("ثالثاً: الربط السحابي عبر الإنترنت (لو الموبايل في البيت)"))
        cloudRoomInput = createStyledInput("اكتب كود غرفة سري خاص بك في الجهازين (مثال: Shalapy_Room_2026)")
        cloudRoomInput.setText("Shalapy_Room_2026")
        tabConnectLayout.addView(cloudRoomInput)

        tabConnectLayout.addView(createStyledButton("☁️ تفعيل الربط السحابي (يعمل من أي مكان 4G/5G/Wi-Fi)", "#8B5CF6") {
            MirrorBridge.secretRoomCode = cloudRoomInput.text.toString().trim().ifEmpty { "Shalapy_Room_2026" }
            connectCloudBridge()
        })

        tabConnectLayout.addView(createSectionHeader("رابعاً: اختيار الشريحة (للهواتف بشريحتين Dual SIM)"))
        val simRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        simRow.addView(createStyledButton("تفعيل SIM 1", "#0EA5E9") {
            selectedSimSlot = 0
            triggerAction("SELECT_SIM", "0")
            batteryBanner.text = "🔋 الشريحة المختارة حالياً: SIM 1"
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        simRow.addView(createStyledButton("تفعيل SIM 2", "#14B8A6") {
            selectedSimSlot = 1
            triggerAction("SELECT_SIM", "1")
            batteryBanner.text = "🔋 الشريحة المختارة حالياً: SIM 2"
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabConnectLayout.addView(simRow)

        contentRoot.addView(tabConnectLayout)

        tabCallsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        tabCallsLayout.addView(createSectionHeader("لوحة الاتصال والتحكم في المكالمات الجارية"))
        targetNumberInput = createStyledInput("رقم الهاتف للاتصال أو المراسلة (مثال: 07123456789)")
        tabCallsLayout.addView(targetNumberInput)

        val callRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        callRow1.addView(createStyledButton("📞 إجراء مكالمة الآن", "#10B981") {
            triggerAction("MAKE_CALL", targetNumberInput.text.toString().trim())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        callRow1.addView(createStyledButton("🔊 الرد وتفعيل السبيكر", "#F59E0B") {
            stopRingingAlert()
            triggerAction("ANSWER_CALL", "")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabCallsLayout.addView(callRow1)

        val callRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 12, 0, 0) }
        callRow2.addView(createStyledButton("⛔ رفض / إنهاء المكالمة", "#EF4444") {
            stopRingingAlert()
            triggerAction("END_CALL", "")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        callRow2.addView(createStyledButton("💬 رفض + رسالة مشغول", "#EC4899") {
            stopRingingAlert()
            triggerAction("REJECT_WITH_SMS", lastCallerNumber, "أنا مشغول حالياً، سأتصل بك في أقرب وقت.")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabCallsLayout.addView(callRow2)

        val callRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 12, 0, 0) }
        callRow3.addView(createStyledButton("🎧 توجيه الصوت بلوتوث (HFP)", "#6366F1") {
            enableBluetoothAudioRouting()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        callRow3.addView(createStyledButton("🔕 كتم رنين التنبيه", "#64748B") {
            stopRingingAlert()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabCallsLayout.addView(callRow3)

        tabCallsLayout.addView(createSectionHeader("تحويل المكالمات التلقائي عن بُعد (بدون روت)"))
        forwardNumberInput = createStyledInput("رقم هذا الهاتف الفرعي لتحويل المكالمات إليه")
        tabCallsLayout.addView(forwardNumberInput)

        val forwardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        forwardRow.addView(createStyledButton("تفعيل التحويل (*21*)", "#8B5CF6") {
            val num = forwardNumberInput.text.toString().trim()
            if (num.isNotEmpty()) triggerAction("MAKE_CALL", "*21*$num#")
            else appendLog("أدخل رقم التحويل أولاً")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 } })

        forwardRow.addView(createStyledButton("إلغاء التحويل (##21#)", "#F43F5E") {
            triggerAction("MAKE_CALL", "##21#")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 } })
        tabCallsLayout.addView(forwardRow)

        tabCallsLayout.addView(createSectionHeader("سجل المكالمات على الهاتف الأساسي"))
        tabCallsLayout.addView(createStyledButton("🔄 جلب آخر 15 مكالمة من الهاتف الأساسي", "#0284C7") {
            triggerAction("SYNC_CALL_LOG", "")
        })
        callHistoryText = TextView(this).apply {
            text = "اضغط على زر التحديث لعرض سجل مكالمات الهاتف الأساسي..."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 25, 25, 25)
        }
        tabCallsLayout.addView(callHistoryText)

        contentRoot.addView(tabCallsLayout)

        tabSmsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        otpBanner = TextView(this).apply {
            text = "🔐 النسخ التلقائي لأكواد البنوك والتفعيل (OTP) مفعل وجاهز"
            textSize = 14f
            setTextColor(Color.parseColor("#FDE047"))
            setBackgroundColor(Color.parseColor("#312E81"))
            setPadding(25, 25, 25, 25)
        }
        tabSmsLayout.addView(otpBanner)

        tabSmsLayout.addView(createSectionHeader("إرسال رسالة نصية (SMS) من شريحة الأساسي"))
        smsBodyInput = createStyledInput("اكتب نص الرسالة هنا (سيتم إرسالها للرقم المكتوب في خانة الرقم المستهدف)...")
        tabSmsLayout.addView(smsBodyInput)

        tabSmsLayout.addView(createStyledButton("📩 إرسال SMS الآن", "#10B981") {
            val target = targetNumberInput.text.toString().trim()
            val msg = smsBodyInput.text.toString().trim()
            if (target.isNotEmpty() && msg.isNotEmpty()) {
                triggerAction("SEND_SMS", target, msg)
                smsBodyInput.setText("")
            } else {
                appendLog("⚠️ يرجى كتابة الرقم المستهدف (في تبويب المكالمات) ونص الرسالة.")
            }
        })

        tabSmsLayout.addView(createSectionHeader("صندوق الرسائل الواردة على الهاتف الأساسي"))
        tabSmsLayout.addView(createStyledButton("🔄 جلب آخر 15 رسالة SMS من الهاتف الأساسي", "#0284C7") {
            triggerAction("SYNC_SMS_INBOX", "")
        })
        smsInboxText = TextView(this).apply {
            text = "اضغط على زر التحديث لقراءة رسائل الهاتف الأساسي..."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 25, 25, 25)
        }
        tabSmsLayout.addView(smsInboxText)

        contentRoot.addView(tabSmsLayout)

        contentRoot.addView(createSectionHeader("سجل الإشعارات والأحداث المباشر"))
        logText = TextView(this).apply {
            text = "النظام جاهز...\n"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 25, 25, 25)
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
    }

    private fun startHostMode() {
        MirrorBridge.isHostMode = true
        val myIp = getLocalIpAddress()
        topStatusBanner.text = "🟢 وضع الأساسي (Host) | IP: $myIp"
        appendLog("تم تشغيل سيرفر الهاتف الأساسي والبحث التلقائي...")
        startUdpBeaconResponder()
        sendBatteryStatusToClient()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(8989)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    withContext(Dispatchers.Main) {
                        appendLog("✅ ارتبط الهاتف الفرعي محلياً: ${client.inetAddress.hostAddress}")
                        sendBatteryStatusToClient()
                    }
                    listenToTcpSocket(client)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("ملاحظة سيرفر: ${e.message}") }
            }
        }
    }

    private fun startUdpBeaconResponder() {
        if (udpRunning) return
        udpRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val udpSocket = DatagramSocket(8990, InetAddress.getByName("0.0.0.0"))
                udpSocket.broadcast = true
                val buf = ByteArray(256)
                while (udpRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg == "DISCOVER_SIM_HOST" && MirrorBridge.isHostMode) {
                        val reply = "SIM_HOST_HERE:${getLocalIpAddress()}".toByteArray()
                        val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                        udpSocket.send(replyPacket)
                    }
                }
            } catch (e: Exception) {
                udpRunning = false
            }
        }
    }

    private fun discoverHostAutomatically() {
        MirrorBridge.isHostMode = false
        appendLog("🔍 جاري البحث التلقائي عن الهاتف الأساسي على الشبكة...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 4000
                val data = "DISCOVER_SIM_HOST".toByteArray()
                val broadcastPacket = DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 8990)
                socket.send(broadcastPacket)

                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val reply = String(response.data, 0, response.length)
                socket.close()

                if (reply.startsWith("SIM_HOST_HERE:")) {
                    val foundIp = reply.substringAfter("SIM_HOST_HERE:")
                    withContext(Dispatchers.Main) {
                        ipInput.setText(foundIp)
                        appendLog("🎯 تم اكتشاف الهاتف الأساسي تلقائياً على: $foundIp")
                        connectToHostTcp(foundIp)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("⚠️ لم يتم العثور تلقائياً؛ يمكنك كتابة الـ IP يدوياً أو استخدام الربط السحابي.")
                }
            }
        }
    }

    private fun connectToHostTcp(hostIp: String) {
        if (hostIp.isEmpty()) {
            appendLog("أدخل الـ IP أولاً")
            return
        }
        MirrorBridge.isHostMode = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket?.close()
                val socket = Socket(hostIp, 8989)
                clientSocket = socket
                withContext(Dispatchers.Main) {
                    topStatusBanner.text = "🟣 متصل محلياً بالأساسي ($hostIp)"
                    appendLog("✅ تم الاتصال بالهاتف الأساسي عبر Wi-Fi / Hotspot!")
                }
                listenToTcpSocket(socket)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("❌ فشل الاتصال المحلي: ${e.message}") }
            }
        }
    }

    private suspend fun listenToTcpSocket(socket: Socket) {
        val writer = PrintWriter(socket.getOutputStream(), true)
        MirrorBridge.attachTcpWriter(writer)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { payload ->
                    val json = JSONObject(payload)
                    withContext(Dispatchers.Main) { handleIncomingJson(json) }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendLog("انقطع الاتصال المحلي.") }
        }
    }

    private fun connectCloudBridge() {
        val client = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val roomEncoded = Uri.encode(MirrorBridge.secretRoomCode)
        val request = Request.Builder()
            .url("wss://free.blr2.piesocket.com/v3/$roomEncoded?api_key=VCXCEuvhGcBDP7XhiJJUDvR1e1D3eiVjgZ9VRiaV&notify_self=0")
            .build()

        appendLog("☁️ جاري الاتصال بالسحابة الثابتة على الغرفة [${MirrorBridge.secretRoomCode}]...")

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                MirrorBridge.attachCloudSocket(webSocket)
                isCloudConnected = true
                scope.launch {
                    topStatusBanner.text = "☁️ متصل سحابياً بثبات | الغرفة: ${MirrorBridge.secretRoomCode}"
                    appendLog("✅ تم الربط السحابي بنجاح وثبات تام!")
                    if (MirrorBridge.isHostMode) sendBatteryStatusToClient()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("room") == MirrorBridge.secretRoomCode) {
                        val myRole = if (MirrorBridge.isHostMode) "HOST" else "CLIENT"
                        if (json.optString("senderRole") != myRole) {
                            scope.launch { handleIncomingJson(json) }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isCloudConnected = false
                scope.launch {
                    appendLog("⚠️ انقطع الاتصال السحابي، جاري إعادة المحاولة تلقائياً...")
                    delay(5000)
                    if (!isCloudConnected) connectCloudBridge()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isCloudConnected = false
                scope.launch {
                    delay(5000)
                    if (!isCloudConnected) connectCloudBridge()
                }
            }
        })
    }

    private fun triggerAction(type: String, number: String, body: String = "") {
        if (MirrorBridge.isHostMode) {
            executeHostHardwareCommand(type, number, body)
        } else {
            MirrorBridge.sendPacket(type, number, body)
            appendLog("🚀 تم إرسال الأمر ($type) للهاتف الأساسي...")
        }
    }

    private fun handleIncomingJson(json: JSONObject) {
        val type = json.optString("type")
        val number = json.optString("number")
        val body = json.optString("body")
        val extra = json.optString("extra")

        when (type) {
            "INCOMING_SMS" -> {
                appendLog("📩 رسالة واردة من [$extra ($number)]:\n$body")
                showPopUpNotification("رسالة جديدة من $extra", body)
                checkAndAutoCopyOtp(body)
            }
            "INCOMING_CALL" -> {
                lastCallerNumber = number
                targetNumberInput.setText(number)
                appendLog("📞 يتصل الآن: $extra ($number)")
                showPopUpNotification("📞 مكالمة واردة على الأساسي", "المتصل: $extra ($number)")
                startRingingAlert()
            }
            "BATTERY_STATUS" -> {
                batteryBanner.text = "🔋 بطارية الأساسي: $number% ($body) | الشريحة: SIM ${selectedSimSlot + 1}"
                if (extra == "LOW") {
                    showPopUpNotification("⚠️ بطارية الهاتف الأساسي منخفضة!", "وصلت البطارية إلى $number%، يرجى توصيله بالشاحن.")
                }
            }
            "CALL_LOG_DATA" -> {
                callHistoryText.text = body
                appendLog("✅ تم تحديث سجل المكالمات من الهاتف الأساسي.")
            }
            "SMS_INBOX_DATA" -> {
                smsInboxText.text = body
                appendLog("✅ تم تحديث صندوق الرسائل من الهاتف الأساسي.")
            }
            "SELECT_SIM" -> {
                selectedSimSlot = number.toIntOrNull() ?: 0
                appendLog("تم التبديل إلى الشريحة SIM ${selectedSimSlot + 1}")
            }
            "SEND_SMS", "MAKE_CALL", "ANSWER_CALL", "END_CALL", "REJECT_WITH_SMS", "SYNC_CALL_LOG", "SYNC_SMS_INBOX" -> {
                executeHostHardwareCommand(type, number, body)
            }
        }
    }

    private fun checkAndAutoCopyOtp(messageBody: String) {
        val pattern = Pattern.compile("\\b(\\d{4,8})\\b")
        val matcher = pattern.matcher(messageBody)
        if (matcher.find()) {
            val otpCode = matcher.group(1) ?: return
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", otpCode))
            otpBanner.text = "🔐 تم نسخ كود التفعيل تلقائياً: $otpCode (جاهز للصق!)"
            Toast.makeText(this, "تم نسخ الكود تلقائياً: $otpCode", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeHostHardwareCommand(type: String, number: String, body: String) {
        try {
            when (type) {
                "SEND_SMS" -> {
                    val smsManager = getSmsManagerForSlot(selectedSimSlot)
                    smsManager.sendTextMessage(number, null, body, null, null)
                    appendLog("✅ تم إرسال SMS من SIM ${selectedSimSlot + 1} إلى $number")
                }
                "MAKE_CALL" -> {
                    val encoded = Uri.encode(number)
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                    appendLog("⏳ جاري الاتصال بـ $number...")
                }
                "ANSWER_CALL" -> {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.acceptRingingCall()
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                    appendLog("🔊 تم الرد وفتح السبيكر")
                }
                "END_CALL" -> {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.endCall()
                    appendLog("⛔ تم إنهاء/رفض المكالمة")
                }
                "REJECT_WITH_SMS" -> {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.endCall()
                    if (number.isNotEmpty()) {
                        getSmsManagerForSlot(selectedSimSlot).sendTextMessage(number, null, body, null, null)
                        appendLog("💬 تم رفض المكالمة وإرسال رسالة انشغال إلى $number")
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
            }
        } catch (e: Exception) {
            appendLog("⚠️ خطأ تنفيذي: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSmsManagerForSlot(slotIndex: Int): SmsManager {
        return try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subs = subManager.activeSubscriptionInfoList
            val subId = if (subs != null && subs.size > slotIndex) subs[slotIndex].subscriptionId else SubscriptionManager.getDefaultSmsSubscriptionId()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        }
    }

    fun lookupContactName(phoneNumber: String): String {
        if (phoneNumber.isEmpty()) return "غير معروف"
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            var name = phoneNumber
            cursor?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            name
        } catch (e: Exception) {
            phoneNumber
        }
    }

    @SuppressLint("MissingPermission")
    private fun readLastCalls(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            var count = 0
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                while (it.moveToNext() && count < 15) {
                    val num = it.getString(numIdx) ?: ""
                    val name = lookupContactName(num)
                    val type = when (it.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "📥 واردة"
                        CallLog.Calls.OUTGOING_TYPE -> "📤 صادرة"
                        CallLog.Calls.MISSED_TYPE -> "❌ فائتة"
                        else -> "📞"
                    }
                    val dateStr = sdf.format(Date(it.getLong(dateIdx)))
                    sb.append("$type | $name ($num) - $dateStr\n")
                    count++
                }
            }
        } catch (e: Exception) {
            return "تعذر قراءة السجل: تأكد من منح صلاحية Call Logs"
        }
        return if (sb.isEmpty()) "لا توجد مكالمات مسجلة." else sb.toString()
    }

    private fun readLastSms(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null,
                "${Telephony.Sms.DATE} DESC"
            )
            var count = 0
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            cursor?.use {
                while (it.moveToNext() && count < 15) {
                    val addr = it.getString(0) ?: ""
                    val body = it.getString(1) ?: ""
                    val dateStr = sdf.format(Date(it.getLong(2)))
                    val name = lookupContactName(addr)
                    sb.append("📩 [$name - $dateStr]:\n$body\n──────────────\n")
                    count++
                }
            }
        } catch (e: Exception) {
            return "تعذر قراءة الرسائل: تأكد من منح صلاحية SMS"
        }
        return if (sb.isEmpty()) "صندوق الوارد فارغ." else sb.toString()
    }

    private fun startRingingAlert() {
        try {
            stopRingingAlert()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone = RingtoneManager.getRingtone(applicationContext, uri)
            activeRingtone?.play()
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    private fun stopRingingAlert() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) {}
    }

    private fun enableBluetoothAudioRouting() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            appendLog("🎧 تم توجيه الصوت عبر البلوتوث (HFP).")
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun registerCallAndBatteryMonitors() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                        if (state == TelephonyManager.EXTRA_STATE_RINGING && MirrorBridge.isHostMode) {
                            val callerName = lookupContactName(incomingNumber)
                            appendLog("📞 يرن الآن: $callerName ($incomingNumber)")
                            MirrorBridge.sendPacket("INCOMING_CALL", incomingNumber, "", callerName)
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        if (MirrorBridge.isHostMode) sendBatteryStatusToClient()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(receiver, filter)
    }

    private fun sendBatteryStatusToClient() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (bm.isCharging) "متصل بالشاحن ⚡" else "غير متصل بالشاحن"
        val lowAlert = if (pct <= 15 && !bm.isCharging) "LOW" else "OK"
        batteryBanner.text = "🔋 بطارية هذا الجهاز: $pct% ($charging)"
        MirrorBridge.sendPacket("BATTERY_STATUS", pct.toString(), charging, lowAlert)
    }

    private fun setupBridgeListeners() {
        MirrorBridge.onUiUpdate = { json ->
            scope.launch { handleIncomingJson(json) }
        }
        MirrorBridge.onLogEvent = { msg ->
            scope.launch { appendLog(msg) }
        }
    }

    private fun startForegroundKeepAlive() {
        try {
            val serviceIntent = Intent(this, MirrorBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("SIM_MIRROR_ALERTS", "تنبيهات المكالمات والرسائل", NotificationManager.IMPORTANCE_HIGH)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun showPopUpNotification(title: String, message: String) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, "SIM_MIRROR_ALERTS")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(System.currentTimeMillis().toInt(), notif)
        } catch (_: Exception) {}
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
            if (ip == "0.0.0.0") "192.168.43.1" else ip
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText.text = "[$time] $message\n${logText.text}"
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 30, 0, 12)
        }
    }

    private fun createStyledInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 22, 25, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10; bottomMargin = 10 }
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
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 102)
    }
}

class MirrorBackgroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "SIM_MIRROR_BG"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "خدمة المزامنة بالخلفية", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("SIM Mirror Pro يعمل في الخلفية")
            .setContentText("متصل وجاهز لتحويل المكالمات والرسائل وأكواد الـ OTP")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.originatingAddress ?: "Unknown"
                val body = sms.messageBody ?: ""
                val contactName = try {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(sender))
                    var name = sender
                    context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) name = it.getString(0)
                    }
                    name
                } catch (_: Exception) { sender }

                MirrorBridge.onLogEvent?.invoke("📩 رسالة جديدة من $contactName ($sender): $body")
                MirrorBridge.sendPacket("INCOMING_SMS", sender, body, contactName)
            }
        }
    }
}
