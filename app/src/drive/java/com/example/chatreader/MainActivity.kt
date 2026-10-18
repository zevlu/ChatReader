package com.example.chatreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 行車助理「CarMate」主畫面（黑客松 demo 版，橫向車機畫面）
 *
 * 一段旅程：
 *  🚗 上車 → CarMate 去 Messenger 讀你跟朋友的聊天 → AI 找出約定的地點與時間
 *        → 語音問你要不要導航 → 你說「好」→ 交給 Google 地圖導航（導航本身用現成的）
 *  ⏰ 路上 → 預估會遲到（目前用按鈕模擬）→ 問你要不要通知朋友 → 填好訊息 → 你按「送出」才會傳
 *  🎤 隨時 → 用說的下指令：「我今天跟澤約哪裡」「跟澤說我會晚十分鐘」「跟澤說我到了」
 *  🏁 下車 → 把這趟旅程的紀錄存成檔案
 *
 * 語音辨識用 Android 內建的語音輸入（RecognizerIntent），語音播報用 TextToSpeech，不需要額外套件。
 * 送出訊息前一定要使用者在懸浮窗按「送出」。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CarMate"
        private const val MESSENGER = "com.facebook.orca"
        private const val MAPS = "com.google.android.apps.maps"

        // 旅程狀態放在這裡：畫面重建也不會不見
        var appointment: Appointment? = null
        val tripLog = mutableListOf<String>()
        var tripStart = 0L
    }

    private lateinit var sayView: TextView
    private lateinit var heardView: TextView
    private lateinit var statusView: TextView
    private lateinit var friendField: EditText
    private lateinit var textInput: EditText
    private lateinit var logView: TextView

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val afterSpeak = HashMap<String, () -> Unit>()

    /** 正在等「好／不要」的回答 */
    private var pendingYesNo: ((Boolean) -> Unit)? = null

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) {
                onHeard(text)
            } else {
                heardView.text = "（沒有聽到，可以再按一次 🎤，或在下面用打字的）"
            }
        }

    // ───────────────────────── 畫面 ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 車機畫面是橫的（如果手機上轉來轉去造成問題，可以把這行註解掉）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        fun label(size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; setTextColor(color)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 16f
            setOnClickListener { onClick() }
        }
        fun weighted(v: View) = v.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = label(22f).apply { text = "🚗 CarMate　AI 行車助理" }
        statusView = label(13f, Color.LTGRAY)
        sayView = label(22f).apply {
            text = "🚗 按「上車」開始旅程，或按 🎤 跟我說話"
            setPadding(0, pad, 0, pad / 2)
        }
        heardView = label(15f, Color.parseColor("#9AD0FF"))

        val mic = button("🎤 說話") { listen() }.apply {
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (72 * dp).toInt()
            )
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(weighted(button("🚗 上車") { startTrip() }))
            addView(weighted(button("⏰ 模擬遲到") { simulateLate() }))
            addView(weighted(button("🏁 下車") { endTrip() }))
            addView(weighted(button("⏹ 停止") { stopAll() }))
        }

        textInput = EditText(this).apply {
            hint = "也可以打字：跟澤說我會晚十分鐘"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(weighted(textInput))
            addView(button("送出文字") {
                val t = textInput.text.toString().trim()
                if (t.isNotEmpty()) { textInput.setText(""); onHeard(t) }
            })
        }

        friendField = EditText(this).apply {
            setText("澤")
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
        }
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(14f, Color.LTGRAY).apply { text = "今天約的人：" })
            addView(weighted(friendField))
            addView(button("無障礙設定") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.apply { textSize = 12f })
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(statusView); addView(sayView); addView(heardView)
            addView(mic); addView(row1); addView(row2); addView(row3)
        }
        logView = label(13f, Color.LTGRAY).apply { setTextIsSelectable(true) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#101820"))
            setPadding(pad, pad, pad, pad)
            addView(ScrollView(this@MainActivity).apply {
                addView(left)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
            })
            addView(ScrollView(this@MainActivity).apply {
                setPadding(pad, 0, 0, 0)
                addView(logView)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
        }
        setContentView(root)
        initTts()
    }

    override fun onResume() {
        super.onResume()
        val acc = if (ChatReaderService.instance != null) "✅ 無障礙服務" else "⚠️ 無障礙服務未啟用"
        val ai = if (LlmClient.isConfigured()) "✅ AI" else "⚠️ AI 未設定"
        statusView.text = "$acc　$ai"
        MessengerAgent.onStatus = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        MessengerAgent.onStatus = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── 說與聽 ─────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) init@{ status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失敗：$status")
                return@init
            }
            val t = tts ?: return@init
            val r = t.setLanguage(Locale.TAIWAN)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.CHINESE)
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { utteranceId?.let { afterSpeak.remove(it)?.invoke() } }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { utteranceId?.let { afterSpeak.remove(it)?.invoke() } }
                }
            })
            ttsReady = true
        }
    }

    /** CarMate 說話：顯示在畫面上並唸出來；then = 唸完之後要做的事 */
    private fun speak(text: String, then: (() -> Unit)? = null) {
        sayView.text = "🚗 $text"
        log("CarMate：$text")
        val t = tts
        if (!ttsReady || t == null) {
            then?.let { main.postDelayed(it, 500) }
            return
        }
        val id = "u${System.nanoTime()}"
        if (then != null) afterSpeak[id] = then
        // emoji 不要唸
        val spoken = text.replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
        t.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** 開啟語音輸入 */
    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (pendingYesNo != null) "請說「好」或「不要」" else "請說…")
        }
        try {
            speechLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            heardView.text = "這支手機沒有語音輸入，請在下面用打字的"
        }
    }

    private fun onHeard(text: String) {
        heardView.text = "你說：$text"
        log("你：$text")
        val yn = pendingYesNo
        if (yn != null) {
            val ans = answerOf(text)
            if (ans == null) {
                speak("我沒聽懂，請說好，或是不要。") { listen() }
            } else {
                pendingYesNo = null
                yn(ans)
            }
            return
        }
        handleCommand(text)
    }

    /** 問一個是非題，唸完自動開始聽 */
    private fun askYesNo(question: String, onAnswer: (Boolean) -> Unit) {
        pendingYesNo = onAnswer
        speak(question) { listen() }
    }

    /** 好 → true，不要 → false，聽不懂 → null */
    private fun answerOf(t: String): Boolean? {
        val s = t.replace(" ", "")
        if (listOf("不要", "不用", "不必", "先不", "別", "取消", "算了", "不好").any { s.contains(it) }) return false
        if (listOf("好", "要", "可以", "對", "嗯", "OK", "ok", "導航", "走", "是", "沒問題", "傳", "送", "麻煩").any { s.contains(it) }) return true
        return null
    }

    // ───────────────────────── 指令 ─────────────────────────

    private fun handleCommand(text: String) {
        if (!LlmClient.isConfigured()) { speak("AI 還沒設定好，請檢查 local.properties。"); return }
        sayView.text = "🚗 思考中…"
        val contacts = contacts()
        val trip = appointment?.let { "跟${it.friend}約${it.time}在${it.place}" }
        Thread {
            val r = runCatching { DriveBrain.route(text, contacts, trip) }
            runOnUiThread {
                val d = r.getOrElse {
                    speak("AI 連線失敗：${it.message}")
                    return@runOnUiThread
                }
                when (d.intent) {
                    "appointment" -> checkAppointment(d.friend.ifEmpty { firstContact() })
                    "late" -> notifyLate(d.friend, d.minutes, d.message)
                    "send" -> if (d.message.isEmpty()) speak("要跟${d.friend}說什麼呢？") else sendMessage(d.friend, d.message)
                    else -> speak(d.reply.ifEmpty { "我在，有需要再叫我。" })
                }
            }
        }.start()
    }

    private fun contacts(): List<String> =
        friendField.text.toString().split(',', '，', '、', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun firstContact(): String = contacts().firstOrNull() ?: ""

    // ───────────────────────── 情境一：上車 → 找約定 → 導航 ─────────────────────────

    private fun startTrip() {
        tripLog.clear()
        tripStart = System.currentTimeMillis()
        appointment = null
        val friend = firstContact()
        log("🚗 上車，旅程開始")
        if (friend.isEmpty()) {
            speak("歡迎上車！請先在「今天約的人」填上朋友的名字。")
            return
        }
        speak("歡迎上車！我先去 Messenger，看一下你今天跟${friend}的約。")
        main.postDelayed({ checkAppointment(friend) }, 2500)
    }

    private fun checkAppointment(friend: String) {
        if (friend.isEmpty()) { speak("你要找跟誰的約呢？"); return }
        val launch = packageManager.getLaunchIntentForPackage(MESSENGER)
            ?: run { speak("找不到 Messenger。"); return }
        log("去 Messenger 讀跟「$friend」的聊天")
        val ok = MessengerAgent.startRead(friend) { snap -> onChatRead(friend, snap) }
        if (ok) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            speak("現在沒辦法讀 Messenger：${MessengerAgent.history.lastOrNull() ?: ""}")
        }
    }

    /** 讀完聊天：回到 CarMate，交給 AI 找約定 */
    private fun onChatRead(friend: String, snap: ChatSnapshot?) {
        if (isDestroyed) return
        bringToFront()
        if (snap == null) {
            speak("讀取 Messenger 失敗了。${MessengerAgent.history.lastOrNull() ?: ""}")
            return
        }
        val name = snap.partnerName ?: friend
        val chatText = snap.messages.joinToString("\n") { "${it.speaker}：${it.text}" }
        log("讀到跟「$name」的 ${snap.messages.size} 則訊息，交給 AI 找約定")
        sayView.text = "🚗 正在找你們約定的地點…"
        Thread {
            val r = runCatching { DriveBrain.findAppointment(friend, chatText) }
            runOnUiThread {
                val a = r.getOrElse {
                    speak("AI 連線失敗：${it.message}")
                    return@runOnUiThread
                }
                if (!a.found) { speak(a.speech); return@runOnUiThread }
                appointment = a
                log("📍 約定：${a.time}　${a.place}（地圖搜尋：${a.mapQuery}）")
                askYesNo(a.speech) { yes ->
                    if (yes) navigate(a) else speak("好，需要的時候再叫我。")
                }
            }
        }.start()
    }

    /** 把 CarMate 叫回前景（由無障礙服務啟動，Android 允許） */
    private fun bringToFront() {
        val i = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        val ctx: Context = ChatReaderService.instance ?: this
        runCatching { ctx.startActivity(i) }.onFailure { Log.w(TAG, "回到前景失敗", it) }
    }

    /** 導航交給 Google 地圖（現成的功能，我們不重做） */
    private fun navigate(a: Appointment) {
        log("🧭 開始導航到「${a.place}」")
        speak("好，幫你導航到${a.place}。")
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.mapQuery)))
            .setPackage(MAPS)
        try {
            startActivity(nav)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.mapQuery))))
            } catch (e2: ActivityNotFoundException) {
                speak("手機上沒有地圖 App。")
            }
        }
    }

    // ───────────────────────── 情境三：遲到通知 ─────────────────────────

    /** 模擬「導航預估會遲到」：之後可改成用真正的預估抵達時間 */
    private fun simulateLate() {
        val a = appointment
        val friend = a?.friend ?: firstContact()
        val minutes = 10
        log("⏰（模擬）導航預估會晚 $minutes 分鐘")
        val where = a?.place?.let { "到$it" } ?: ""
        askYesNo("照目前路況，你大概會晚 $minutes 分鐘$where。要我幫你跟${friend}說一聲嗎？") { yes ->
            if (yes) notifyLate(friend, minutes, "") else speak("好，那我先不通知。")
        }
    }

    private fun notifyLate(friend: String, minutes: Int?, message: String) {
        val f = friend.ifEmpty { appointment?.friend ?: firstContact() }
        sendMessage(f, message.ifEmpty { DriveBrain.lateTemplate(minutes) })
    }

    /** 填好訊息，懸浮窗確認後才會送出 */
    private fun sendMessage(friend: String, msg: String) {
        if (friend.isEmpty()) { speak("要傳給誰呢？"); return }
        val launch = packageManager.getLaunchIntentForPackage(MESSENGER)
            ?: run { speak("找不到 Messenger。"); return }
        log("✉️ 準備傳給「$friend」：$msg")
        if (MessengerAgent.start(SendCommand(friend, msg))) {
            speak("我幫你跟${friend}說：$msg。確認沒問題，再按送出。")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            speak("現在沒辦法傳訊息：${MessengerAgent.history.lastOrNull() ?: ""}")
        }
    }

    // ───────────────────────── 下車：記錄旅程 ─────────────────────────

    private fun endTrip() {
        log("🏁 下車，旅程結束")
        val mins = if (tripStart > 0) (System.currentTimeMillis() - tripStart) / 60000 else 0
        val name = "trip_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.TAIWAN).format(Date()) + ".txt"
        val content = buildString {
            appendLine("CarMate 旅程紀錄")
            appointment?.let { appendLine("約定：跟${it.friend}　${it.time}　${it.place}") }
            appendLine()
            tripLog.forEach { appendLine(it) }
        }
        runCatching { File(getExternalFilesDir(null), name).writeText(content) }
            .onSuccess { log("已存檔：$name") }
            .onFailure { log("存檔失敗：${it.message}") }
        speak("到了！這趟旅程花了大約 $mins 分鐘，過程我都幫你記錄好了，下車小心。")
    }

    // ───────────────────────── 其他 ─────────────────────────

    private fun stopAll() {
        MessengerAgent.cancel()
        pendingYesNo = null
        afterSpeak.clear()
        tts?.stop()
        sayView.text = "🚗 已停止"
    }

    private fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
        tripLog.add("$t　$s")
        render()
    }

    private fun render() {
        val agent = if (MessengerAgent.history.isEmpty()) "" else
            "\n\n【Messenger 操作】\n" + MessengerAgent.history.joinToString("\n")
        logView.text = "【旅程紀錄】\n" + tripLog.joinToString("\n") + agent
    }
}
