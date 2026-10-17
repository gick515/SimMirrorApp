package com.simmirror.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

object MirrorBridge {
    var onMessageReceived: ((String) -> Unit)? = null
    private var clientWriter: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun sendPayload(type: String, number: String, body: String = "") {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", type)
                    put("number", number)
                    put("body", body)
                }
                clientWriter?.println(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun attachWriter(writer: PrintWriter?) {
        clientWriter = writer
    }
}

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isHostMode = true

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var ipInput: EditText
    private lateinit var targetNumberInput: EditText
    private lateinit var smsBodyInput: EditText
    private lateinit var forwardNumberInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        buildUserInterface()
        setupMirrorListener()
        registerCallStateListener()
    }

    private fun buildUserInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        val title = TextView(this).apply {
            text = "SIM Mirror Hub"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "الحالة: اختر وضع الهاتف للبدء\nIP الجهاز الحالي: ${getLocalIpAddress()}"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 20, 0, 30)
        }
        root.addView(statusText)

        val modeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnHost = createStyledButton("وضع الأساسي (SIM Host)", "#0284C7") {
            startHostServer()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 } }

        val btnClient = createStyledButton("وضع الفرعي (Remote)", "#4F46E5") {
            connectToHost(ipInput.text.toString().trim())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 } }

        modeLayout.addView(btnHost)
        modeLayout.addView(btnClient)
        root.addView(modeLayout)

        ipInput = createStyledInput("أدخل IP الهاتف الأساسي (للربط عبر Wi-Fi / Hotspot)")
        root.addView(ipInput)

        root.addView(createSectionHeader("التحكم في المكالمات والرسائل عن بُعد"))

        targetNumberInput = createStyledInput("رقم الهاتف المستهدف (مثال: 07123456789)")
        root.addView(targetNumberInput)

        smsBodyInput = createStyledInput("نص الرسالة النصية (SMS)...")
        root.addView(smsBodyInput)

        val actionRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        actionRow1.addView(createStyledButton("إرسال SMS", "#10B981") {
            triggerAction("SEND_SMS", targetNumberInput.text.toString(), smsBodyInput.text.toString())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 } })

        actionRow1.addView(createStyledButton("إجراء مكالمة", "#059669") {
            triggerAction("MAKE_CALL", targetNumberInput.text.toString())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 } })
        root.addView(actionRow1)

        val actionRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 15, 0, 0)
        }
        actionRow2.addView(createStyledButton("الرد على المكالمة (سبيكر)", "#F59E0B") {
            triggerAction("ANSWER_CALL", "")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 } })

        actionRow2.addView(createStyledButton("توجيه الصوت للبلوتوث (HFP)", "#6366F1") {
            enableBluetoothAudioRouting()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 } })
        root.addView(actionRow2)

        root.addView(createSectionHeader("تحويل المكالمات التلقائي (بدون روت - عند الخروج)"))

        forwardNumberInput = createStyledInput("رقم الهاتف الفرعي أو حساب VoIP للتحويل إليه")
        root.addView(forwardNumberInput)

        val forwardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        forwardRow.addView(createStyledButton("تفعيل التحويل (*21*)", "#8B5CF6") {
            val forwardTo = forwardNumberInput.text.toString().trim()
            if (forwardTo.isNotEmpty()) {
                triggerAction("MAKE_CALL", "*21*$forwardTo#")
            } else {
                appendLog("يرجى إدخال رقم التحويل أولاً")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 } })

        forwardRow.addView(createStyledButton("إلغاء التحويل (##21#)", "#EC4899") {
            triggerAction("MAKE_CALL", "##21#")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 } })
        root.addView(forwardRow)

        root.addView(createSectionHeader("سجل الإشعارات والرسائل المباشر"))
        logText = TextView(this).apply {
            text = "بانتظار الأحداث...\n"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 25, 25, 25)
        }
        root.addView(logText)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun startHostServer() {
        isHostMode = true
        statusText.text = "الوضع الحالي: هاتف أساسي (Host)\nبانتظار اتصال الهاتف الفرعي على IP: ${getLocalIpAddress()} (Port: 8989)"
        appendLog("تم تشغيل سيرفر الاستماع على المنفذ 8989...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(8989)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    withContext(Dispatchers.Main) {
                        appendLog("تم ارتباط الهاتف الفرعي بنجاح: ${client.inetAddress.hostAddress}")
                    }
                    handleSocketConnection(client)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("خطأ في السيرفر: ${e.message}") }
            }
        }
    }

    private fun connectToHost(hostIp: String) {
        if (hostIp.isEmpty()) {
            appendLog("أدخل عنوان الـ IP الخاص بالهاتف الأساسي أولاً")
            return
        }
        isHostMode = false
        statusText.text = "الوضع الحالي: هاتف فرعي (Remote)\nجاري الاتصال بـ $hostIp..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket?.close()
                val socket = Socket(hostIp, 8989)
                clientSocket = socket
                withContext(Dispatchers.Main) {
                    statusText.text = "الوضع: هاتف فرعي (متصل بـ $hostIp)"
                    appendLog("تم الاتصال بالهاتف الأساسي بنجاح!")
                }
                handleSocketConnection(socket)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("فشل الاتصال: تأكد من وجود الجهازين على نفس الـ Wi-Fi أو الهوت سبوت") }
            }
        }
    }

    private suspend fun handleSocketConnection(socket: Socket) {
        val writer = PrintWriter(socket.getOutputStream(), true)
        MirrorBridge.attachWriter(writer)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { payload ->
                    withContext(Dispatchers.Main) {
                        processIncomingCommand(payload)
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendLog("انقطع الاتصال بين الجهازين.") }
        }
    }

    private fun triggerAction(type: String, number: String, body: String = "") {
        if (isHostMode) {
            executeHardwareAction(type, number, body)
        } else {
            MirrorBridge.sendPayload(type, number, body)
            appendLog("تم إرسال الأمر ($type) للهاتف الأساسي...")
        }
    }

    private fun processIncomingCommand(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            val number = json.optString("number")
            val body = json.optString("body")

            when (type) {
                "INCOMING_SMS" -> appendLog("📩 رسالة واردة من [$number]:\n$body")
                "INCOMING_CALL" -> appendLog("📞 مكالمة واردة الآن من: $number")
                "SEND_SMS", "MAKE_CALL", "ANSWER_CALL" -> executeHardwareAction(type, number, body)
            }
        } catch (e: Exception) {
            appendLog("بيانات مستلمة: $jsonStr")
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeHardwareAction(type: String, number: String, body: String) {
        try {
            when (type) {
                "SEND_SMS" -> {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        this.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(number, null, body, null, null)
                    appendLog("✅ تم إرسال الرسالة من الشريحة إلى $number")
                }
                "MAKE_CALL" -> {
                    val encodedNumber = Uri.encode(number)
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
                    startActivity(callIntent)
                    appendLog("⏳ جاري الاتصال بـ $number...")
                }
                "ANSWER_CALL" -> {
                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        telecomManager.acceptRingingCall()
                    }
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_CALL
                    audioManager.isSpeakerphoneOn = true
                    appendLog("🔊 تم الرد على المكالمة وتفعيل السبيكر")
                }
            }
        } catch (e: Exception) {
            appendLog("⚠️ خطأ أثناء التنفيذ: ${e.message}")
        }
    }

    private fun enableBluetoothAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            appendLog("🎧 تم تفعيل توجيه الصوت عبر البلوتوث (HFP).")
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun setupMirrorListener() {
        MirrorBridge.onMessageReceived = { msg ->
            scope.launch { appendLog(msg) }
        }
    }

    private fun registerCallStateListener() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "رقم غير معروف"
                    if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                        appendLog("📞 يرن الآن: $incomingNumber")
                        MirrorBridge.sendPayload("INCOMING_CALL", incomingNumber)
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }

    private fun appendLog(message: String) {
        logText.text = "• $message\n${logText.text}"
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 35, 0, 15)
        }
    }

    private fun createStyledInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 15; bottomMargin = 15 }
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
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }
}

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.originatingAddress ?: "Unknown"
                val body = sms.messageBody ?: ""
                MirrorBridge.onMessageReceived?.invoke("📩 رسالة جديدة من $sender: $body")
                MirrorBridge.sendPayload("INCOMING_SMS", sender, body)
            }
        }
    }
}
