package com.example.chatreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CarMate 的核心：整趟旅程的狀態與流程都在這裡，不管畫面在 CarMate、Google 地圖還是 Messenger 都能運作。
 * MainActivity 只是顯示用的畫面；🎤 泡泡（AssistantBubble）讓你在任何畫面都能叫它。
 *
 * 安全規則：訊息一定要使用者確認（說「送出」或按「送出」）才會傳。
 */
object CarMate {
    private const val TAG = "CarMate"
    const val MESSENGER = "com.facebook.orca"
    const val MAPS = "com.google.android.apps.maps"

    /** 路上記下的地點 */
    data class SavedPlace(
        val time: String,
        val note: String,
        val lat: Double?,
        val lng: Double?,
        val address: String,
    ) {
        val link: String? get() = if (lat != null && lng != null) "https://maps.google.com/?q=$lat,$lng" else null
    }

    private lateinit var app: Context
    private var initialized = false
    private val main = Handler(Looper.getMainLooper())

    // ── 旅程狀態 ──
    var contacts: List<String> = listOf("孟澤")
    var appointment: Appointment? = null
        private set
    var lastRoute: RouteInfo? = null
        private set
    val tripLog = mutableListOf<String>()
    val places = mutableListOf<SavedPlace>()
    private var tripStart = 0L
    private var navigating = false
    private var navStart = 0L

    // ── 車況（目前用畫面上的「車況模擬」按鈕代替車子的訊號；之後換成 OBD-II 或車廠介面） ──
    /** 油量 %，模擬值 */
    var fuelPercent = 80
        private set
    private const val LOW_FUEL = 15              // 低於這個 % 就提醒加油
    private const val GAS_QUERY = "加油站"        // 順路停靠點交給 Google 地圖找
    private const val NAV_CHECK_MS = 60_000L     // 導航中多久檢查一次抵達時間
    private const val FATIGUE_MIN = 90           // 連續開多久（分鐘）主動問要不要休息
    // 車上設備（模擬值，畫面上的卡片會顯示；之後換成車廠的車控介面）
    var acTemp = 24                 // 冷氣溫度 °C
        private set
    var fanLevel = 2                // 風量 0–5
        private set
    var windowOpen = false
        private set
    var mirrorFolded = false
        private set
    var volume = 6                  // 音量 0–10
        private set
    var lightsOn = false
        private set

    // ── 路由（畫面上顯示最近一次交給誰處理） ──
    var lastRouteLabel = ""
        private set
    var lastRouteDetail = ""
        private set
    private var fuelChecked = false
    private var lastWarnedLate = 0
    private var nearAsked = false
    private var fatigueAsked = false

    // ── 閒聊 ──
    private var chatting = false
    private val chatHistory = mutableListOf<Pair<String, String>>()   // (駕駛, CarMate)
    private val jokesTold = mutableListOf<String>()

    // ── 畫面顯示用 ──
    var lastSay = "按「上車」開始旅程，或按 🎤 跟我說話"
        private set
    var lastHeard = ""
        private set
    /** 狀態有變時呼叫（主執行緒），MainActivity 用來更新畫面 */
    var onUpdate: (() -> Unit)? = null

    // ── 等待回答 ──
    private var pendingAnswer: ((String) -> Boolean)? = null
    private var answerPrompt = ""

    // ── 按 🎤 那一刻的畫面（例如導航畫面），用來回答「還要多久」 ──
    private var screenPkg: String? = null
    private var screenTexts: List<String> = emptyList()

    /** 上車時要不要開免持喚醒（畫面上的開關） */
    var handsFreeWanted = true

    // ── 正在傳的訊息 ──
    private var sendFriend = ""
    private var sendMsg = ""

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        PhoneModel.load(app)
        Voice.init(app)
        AssistantBubble.onTap = { listenCommand() }
        AssistantBubble.onLongPress = { closeAssistant() }
        HandsFree.onWake = { rest -> onWake(rest) }
        MessengerAgent.onAsk = { kind, _, _ -> onAgentAsk(kind) }
        MessengerAgent.onStatus = { msg -> onAgentStatus(msg) }
        MessengerAgent.onPlaceList = { name -> relaunchWithPlace(name) }
    }

    // ───────────────────────── 說、聽、問 ─────────────────────────

    fun say(text: String, then: (() -> Unit)? = null) {
        lastSay = text
        log("CarMate：$text")
        AssistantBubble.say("🚗 $text")
        Voice.speak(text, then)
    }

    /** 使用者主動要說話（按 🎤 泡泡或畫面上的「說話」） */
    fun listenCommand() {
        captureScreen()
        Voice.stop()
        AssistantBubble.say("🎤 我在聽…")
        val prompt = if (pendingAnswer != null) answerPrompt else "請說…"
        Voice.listen(prompt) { t ->
            if (t == null) AssistantBubble.say("沒聽到，再按一次 🎤") else onHeard(t)
        }
    }

    /** 免持模式聽到喚醒詞；rest = 喚醒詞後面的話 */
    private fun onWake(rest: String) {
        captureScreen()
        if (rest.length >= 2) {
            onHeard(rest)
        } else {
            Voice.stop()
            lastSay = "我在"
            AssistantBubble.say("🚗 我在，請說")
            Voice.speak("我在。") {
                Voice.listen("請說…") { t -> if (t != null) onHeard(t) else AssistantBubble.say("沒聽到，要找我再叫一次「副駕」") }
            }
        }
    }

    /** 開關免持喚醒（要在 CarMate 畫面在前景時呼叫） */
    fun setHandsFree(on: Boolean) {
        handsFreeWanted = on
        if (on) {
            if (AssistantBubble.showing) startHandsFree()
        } else {
            HandsFree.stop()
            log("👂 免持喚醒已關閉，改用 🎤 泡泡")
        }
        AssistantBubble.setHandsFree(HandsFree.enabled)
        update()
    }

    private fun startHandsFree() {
        if (HandsFree.start(app)) {
            log("👂 免持喚醒已開啟：說「副駕」或「CarMate」叫我")
        } else {
            log("⚠️ 免持喚醒開不起來（麥克風權限或語音服務），改用 🎤 泡泡")
        }
        AssistantBubble.setHandsFree(HandsFree.enabled)
    }

    /** 長按泡泡：收起助理 */
    fun closeAssistant() {
        stopNavWatch()
        chatting = false
        HandsFree.stop()
        AssistantBubble.hide()
        pendingAnswer = null
        lastSay = "助理已收起，要再用請按「上車」或 🎤 說話"
        log("👋 收起助理")
        Voice.speak("好，我先休息。")
        update()
    }

    /** 聽到（或打字輸入）一句話 */
    fun onHeard(text: String) {
        lastHeard = text
        log("你：$text")
        if (chatting && pendingAnswer == null) { chatTurn(text); return }
        val p = pendingAnswer
        if (p != null) {
            pendingAnswer = null
            if (!p(text)) {
                pendingAnswer = p
                say("我沒聽懂。$answerPrompt") { listenAnswer() }
            }
            return
        }
        handleCommand(text)
    }

    /** 問一個問題，唸完自動開始聽；handler 回傳 false 代表聽不懂，會再問一次 */
    private fun ask(question: String, prompt: String, handler: (String) -> Boolean) {
        pendingAnswer = handler
        answerPrompt = prompt
        say(question) { listenAnswer() }
    }

    private fun listenAnswer() {
        if (pendingAnswer == null) return
        Voice.listen(answerPrompt) { t ->
            if (t == null) AssistantBubble.say("沒聽到，按 🎤 再回答一次，或直接點按鈕") else onHeard(t)
        }
    }

    private fun askYesNo(question: String, onAnswer: (Boolean) -> Unit) =
        ask(question, "請說「好」或「不要」") { t ->
            val a = answerOf(t)
            if (a == null) false else { onAnswer(a); true }
        }

    /** 好 → true，不要 → false，聽不懂 → null */
    private fun answerOf(t: String): Boolean? {
        val s = t.replace(" ", "")
        if (NO_WORDS.any { s.contains(it) }) return false
        if (YES_WORDS.any { s.contains(it, ignoreCase = true) }) return true
        return null
    }

    private val NO_WORDS = listOf("不要", "不用", "不必", "先不", "別", "取消", "算了", "不好", "等一下")
    private val YES_WORDS = listOf(
        "好", "要", "可以", "對", "嗯", "OK", "導航", "走", "是", "沒問題", "傳", "送", "確定", "麻煩", "開始", "出發",
    )

    // ───────────────────────── 語音指令 ─────────────────────────

    /**
     * 每一句指令先經過路由器（System One）：
     *  B 手機 → 車輛控制（手機本地模型，要即時）
     *  A 雲端 → 找約定、導航、傳訊息等 App 操作（雲端大型模型）
     */
    private fun handleCommand(text: String) {
        if (HOME_WORDS.any { text.contains(it) }) { goHome(); return }
        lastSay = "思考中…"
        lastRouteLabel = "🔀 路由判斷中…"
        lastRouteDetail = ""
        update()
        Thread {
            val dec = Router.decide(text)
            val where = if (dec.route == Router.Route.PHONE) "B 手機・車輛控制" else "A 雲端・App 操作"
            main.post {
                lastRouteLabel = "🔀 $where"
                lastRouteDetail = "${dec.source}・${dec.ms} ms"
                log("🔀 路由：$where（${dec.source}，${dec.ms} ms）")
                update()
            }
            if (dec.route == Router.Route.PHONE) handleCarControl(text) else handleCloud(text)
        }.start()
    }

    /** 「回到車機」：畫面切回 CoDriver（不用問 AI） */
    private val HOME_WORDS = listOf("回到車機", "回車機", "車機畫面", "車機頁面", "回主畫面", "回到主畫面", "回首頁", "回到首頁", "回到CoDriver", "回到 CoDriver")

    private fun goHome() {
        log("🏠 回到車機畫面")
        bringCarMateFront()
        say("好，回到車機畫面。")
    }

    /** B 手機：車輛控制。在背景執行緒呼叫 */
    private fun handleCarControl(text: String) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { CarControlBrain.parse(text) }
        val ms = System.currentTimeMillis() - t0
        main.post {
            val c = r.getOrElse {
                say("車輛控制連線失敗：${it.message}")
                return@post
            }
            log("🚘 ${c.by}（$ms ms）：${c.device}／${c.action}${c.amount?.let { "／$it" } ?: ""}")
            lastRouteDetail += "・${c.by} $ms ms"
            val done = applyCar(c)
            say(if (done.isNotEmpty() && c.reply.isEmpty()) done else c.reply.ifEmpty { "這個我還不會控制。" })
        }
    }

    /** 把控制指令套到（模擬的）車上設備；回傳一句說明 */
    private fun applyCar(c: CarCommand): String {
        val up = c.action.contains("高") || c.action.contains("開") || c.action.contains("大")
        val down = c.action.contains("低") || c.action.contains("關") || c.action.contains("小")
        val n = c.amount ?: 1
        return when (c.device) {
            "冷氣" -> {
                when {
                    c.action == "關閉" -> fanLevel = 0
                    c.action == "打開" -> if (fanLevel == 0) fanLevel = 2
                    // 數字大於 5 當成「調到幾度」，否則當成「調高／調低幾度」
                    up -> acTemp = (if (n > 5) n else acTemp + n).coerceIn(16, 30)
                    down -> acTemp = (if (n > 5) n else acTemp - n).coerceIn(16, 30)
                    c.amount != null -> acTemp = c.amount.coerceIn(16, 30)
                }
                "冷氣 $acTemp 度，風量 $fanLevel"
            }
            "風量" -> {
                fanLevel = when { up -> fanLevel + n; down -> fanLevel - n; else -> c.amount ?: fanLevel }.coerceIn(0, 5)
                "風量 $fanLevel"
            }
            "車窗" -> { windowOpen = up || (!down && !windowOpen); if (windowOpen) "車窗打開了" else "車窗關上了" }
            "後照鏡" -> {
                mirrorFolded = when { c.action == "關閉" -> true; c.action == "打開" -> false; else -> mirrorFolded }
                if (mirrorFolded) "後照鏡收起來了" else "後照鏡調整好了"
            }
            "音量" -> {
                volume = when { up -> volume + n; down -> volume - n; else -> c.amount ?: volume }.coerceIn(0, 10)
                "音量 $volume"
            }
            "車燈" -> { lightsOn = up || (!down && !lightsOn); if (lightsOn) "車燈打開了" else "車燈關掉了" }
            "座椅" -> "座椅調整好了"
            else -> ""
        }.also { if (it.isNotEmpty()) log("🚘 車況：$it") }
    }

    /** A 雲端：原本的 App 操作流程。在背景執行緒呼叫 */
    private fun handleCloud(text: String) {
        if (!LlmClient.isConfigured()) { main.post { say("AI 還沒設定好，請檢查 local.properties。") }; return }
        val trip = appointment?.let { "跟${it.friend}約${it.time}在${it.place}" }
        val cs = (contacts + knownNames).distinct()
        run {
            val r = runCatching { DriveBrain.route(text, cs, trip) }
            main.post {
                val d = r.getOrElse {
                    say("AI 連線失敗：${it.message}")
                    return@post
                }
                when (d.intent) {
                    "appointment" -> if (d.friend.isEmpty()) askName("要找跟誰的約？") { checkAppointment(it) } else checkAppointment(d.friend)
                    "go" -> goTo(d.place)
                    "late" -> notifyLate(d.friend, d.minutes, d.message)
                    "send" -> when {
                        d.message.isEmpty() -> say("要跟${d.friend.ifEmpty { "對方" }}說什麼呢？")
                        d.friend.isEmpty() -> askName("要傳給誰？") { sendMessage(it, d.message) }
                        else -> sendMessage(d.friend, d.message)
                    }
                    "eta" -> answerEta()
                    "save_place" -> savePlace(d.note)
                    "share_place" -> sharePlace(d.friend)
                    "end_trip" -> endTrip()
                    "joke" -> tellJoke()
                    "talk" -> startChat()
                    "home" -> goHome()
                    else -> say(d.reply.ifEmpty { "我在，有需要再叫我。" })
                }
            }
        }
    }

    /** 設定頁的「測試連線」：測手機模型和 System One，結果寫進紀錄 */
    fun testConnections(done: (String) -> Unit) {
        Thread {
            val phone = if (!PhoneModel.isSet()) "手機模型：未設定位址" else {
                val t0 = System.currentTimeMillis()
                runCatching { PhoneModel.chat("只回答「OK」", "測試", 0.0) }.fold(
                    { "手機模型：✅ ${System.currentTimeMillis() - t0} ms（回答：${it.take(20)}）" },
                    { "手機模型：❌ ${it.message}" })
            }
            val d = Router.decide("把冷氣調低一度")
            val sys1 = "路由器：${d.source}，判斷「冷氣調低」→ ${if (d.route == Router.Route.PHONE) "手機" else "雲端"}（${d.ms} ms）"
            main.post { log(phone); log(sys1); done("$phone\n$sys1") }
        }.start()
    }

    fun firstContact(): String = contacts.firstOrNull() ?: ""

    // ───────────────────────── 情境一：上車 → 找約定 → 導航 ─────────────────────────

    fun startTrip() {
        tripLog.clear()
        places.clear()
        tripStart = System.currentTimeMillis()
        appointment = null
        lastRoute = null
        stopNavWatch()
        pendingAnswer = null
        fuelChecked = false
        lastWarnedLate = 0
        nearAsked = false
        fatigueAsked = false
        chatting = false
        log("🚗 上車，旅程開始（油量 $fuelPercent%）")
        if (!AssistantBubble.show()) log("⚠️ 無障礙服務沒開，🎤 泡泡出不來")
        if (handsFreeWanted) startHandsFree()
        // 用說的告訴 CarMate 要去哪：「去跟孟澤約的地方」或「帶我去高雄車站」
        // 一上車先報告基本狀況（時間、油量），再問要去哪
        val fuelNote = if (fuelPercent <= LOW_FUEL) "，有點低，等一下規劃路線時我會幫你留意" else ""
        say("歡迎上車！現在${clock().substring(0, 5)}，油量 $fuelPercent%$fuelNote。今天要去哪裡？") {
            Voice.listen("今天要去哪裡？") { t ->
                if (t != null) onHeard(t) else AssistantBubble.say("沒聽到，要出發時叫我「副駕」或按 🎤")
            }
        }
    }

    /** 直接導航到一個地點（不是跟人約的） */
    private fun goTo(place: String) {
        if (place.isEmpty()) { say("要去哪裡呢？"); return }
        log("🧭 導航到「$place」")
        if (place.contains("加油")) fuelChecked = true   // 本來就要去加油，不用再問順路加油
        navigate(Appointment(found = true, friend = "", place = place, mapQuery = place, time = "", timeHHmm = "", speech = ""))
    }

    /** 名字沒說清楚時，問一次 */
    private fun askName(question: String, then: (String) -> Unit) =
        ask(question, "請說對方的名字") { t ->
            val name = t.trim().trim('。', '，', ' ').removePrefix("傳給").removePrefix("給").trim()
            if (name.isEmpty()) false else { then(name); true }
        }

    /** 用過的名字記起來，下次語音辨識認錯字時可以對照 */
    private val knownNames = linkedSetOf<String>()

    private fun checkAppointment(friend: String) {
        if (friend.isEmpty()) { say("你要找跟誰的約呢？"); return }
        log("去 Messenger 讀跟「$friend」的聊天")
        val ok = MessengerAgent.startRead(friend) { snap -> onChatRead(friend, snap) }
        if (ok) {
            if (!launchPackage(MESSENGER)) { MessengerAgent.cancel(); say("找不到 Messenger。") }
        } else {
            say("現在沒辦法讀 Messenger：${MessengerAgent.history.lastOrNull() ?: ""}")
        }
    }

    private fun onChatRead(friend: String, snap: ChatSnapshot?) {
        bringCarMateFront()
        if (snap == null) { log("讀取 Messenger 失敗"); return }   // 失敗原因 onAgentStatus 會唸
        val name = snap.partnerName ?: friend
        knownNames.add(name)
        val chatText = snap.messages.joinToString("\n") { "${it.speaker}：${it.text}" }
        log("讀到跟「$name」的 ${snap.messages.size} 則訊息，交給 AI 找約定")
        lastSay = "正在找你們約定的地點…"
        update()
        Thread {
            val r = runCatching { DriveBrain.findAppointment(friend, chatText) }
            main.post {
                val a = r.getOrElse {
                    say("AI 連線失敗：${it.message}")
                    return@post
                }
                if (!a.found) { say(a.speech); return@post }
                appointment = a
                log("📍 約定：${a.time}　${a.place}（地圖搜尋：${a.mapQuery}）")
                askYesNo(a.speech) { yes -> if (yes) navigate(a) else say("好，需要的時候再叫我。") }
            }
        }.start()
    }

    /**
     * 導航交給 Google 地圖（現成的功能，我們不重做）。
     * CarMate 多做的事：讀出地圖算好的車程、跟約定時間比較，會遲到就主動提醒；
     * 你說「好」（或按按鈕）才幫你按「開始」。
     */
    private fun navigate(a: Appointment) {
        // 出發前先看油量：不夠就主動問要不要順路加油（一般導航不會幫你想到這件事）
        if (!fuelChecked && fuelPercent <= LOW_FUEL) {
            fuelChecked = true
            log("⛽ 油量只剩 $fuelPercent%，主動提醒")
            askYesNo("先提醒你，油量只剩 $fuelPercent%，可能撐不到${a.place}。要先去順路的加油站加油嗎？") { yes ->
                if (yes) {
                    log("⛽ 路線加上順路的加油站")
                    startRoute(a, viaGas = true)
                } else {
                    say("好，那油量更低時我再提醒你。")
                    startRoute(a, viaGas = false)
                }
            }
            return
        }
        startRoute(a, viaGas = false)
    }

    /** 請 Google 地圖規劃路線；viaGas = 中途先停加油站 */
    // 目前路線（地圖列出好幾家加油站時，換成第一家的店名重新規劃）
    private var routeDest = ""
    private var routeStop = ""

    private fun mapsDirUri(dest: String, stop: String): Uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + Uri.encode(dest) +
            if (stop.isNotEmpty()) "&waypoints=" + Uri.encode(stop) else ""
    )

    /** 地圖跳出「加油站」列表：用第一家的店名重新開路線，地圖就會直接出現「開始」鍵 */
    private fun relaunchWithPlace(name: String) {
        if (!name.contains("油")) { log("（地圖上的「$name」不像加油站，不換）"); return }
        when {
            routeStop.isNotEmpty() -> routeStop = name
            routeDest.contains("加油") -> routeDest = name
            else -> return
        }
        log("⛽ 選第一家：$name，重新規劃路線")
        runCatching { launch(Intent(Intent.ACTION_VIEW, mapsDirUri(routeDest, routeStop)).setPackage(MAPS)) }
            .onFailure { log("⚠️ 重新規劃失敗：${it.message}") }
    }

    private fun startRoute(a: Appointment, viaGas: Boolean) {
        log("🧭 請 Google 地圖規劃到「${a.place}」的路線" + if (viaGas) "（中途加油）" else "")
        say(if (viaGas) "好，我把加油站排進路線裡。" else "好，我請 Google 地圖幫你規劃路線。")
        // Google 地圖官方網址格式；waypoints = 中途停靠點
        routeDest = a.mapQuery
        routeStop = if (viaGas) GAS_QUERY else ""
        val uri = mapsDirUri(routeDest, routeStop)
        val watching = MessengerAgent.startRoute(
            a.timeHHmm, a.friend,
            onReady = { info -> lastRoute = info },
            onDone = { info, choice -> onRouteDone(a, info, choice) },
        )
        try {
            launch(Intent(Intent.ACTION_VIEW, uri).setPackage(MAPS))
        } catch (e: ActivityNotFoundException) {
            if (watching) MessengerAgent.cancel()
            try {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.mapQuery))))
            } catch (e2: ActivityNotFoundException) {
                say("手機上沒有地圖 App。")
            }
        }
    }

    private fun onRouteDone(a: Appointment, info: RouteInfo?, choice: Int) {
        if (info != null) lastRoute = info
        when (choice) {
            1 -> {
                startNavWatch(info)
                log("▶ 開始導航（${info?.summary ?: ""}）")
                say(if (HandsFree.enabled) "出發囉，路上小心。有事叫我「副駕」。" else "出發囉，路上小心。有事按麥克風泡泡叫我。")
            }
            2 -> {
                startNavWatch(info)
                log("▶ 開始導航，並通知「${a.friend}」會晚 ${info?.lateMinutes} 分鐘")
                notifyLate(a.friend, info?.lateMinutes, "")
            }
            else -> log("沒有開始導航")
        }
    }

    // ───────────────────────── 懸浮窗的問題改用語音回答 ─────────────────────────

    private fun onAgentAsk(kind: String) {
        when (kind) {
            "confirm" -> {
                val who = sendFriend.ifEmpty { "對方" }
                ask(
                    "要傳給${who}：「$sendMsg」。確認沒問題就說「送出」，要取消就說「取消」。",
                    "請說「送出」或「取消」",
                ) { t ->
                    val a = answerOf(t) ?: return@ask false
                    // 等語音輸入的畫面關掉、Messenger 回到前面再操作
                    main.postDelayed({
                        if (!MessengerAgent.respond(if (a) 0 else -1)) say("這個確認已經結束了。")
                    }, 800)
                    true
                }
            }
            "route" -> {
                val r = lastRoute ?: return
                val a = appointment
                val canNotify = r.lateMinutes != null && !a?.friend.isNullOrEmpty()
                val prompt = if (canNotify) {
                    "開始導航就說「好」，要順便通知${a?.friend}就說「通知」，不要就說「取消」"
                } else {
                    "開始導航就說「好」，不要就說「取消」"
                }
                ask(routeSpeech(r, a) + prompt + "。", prompt) { t ->
                    val choice = when {
                        NO_WORDS.any { t.contains(it) } -> -1
                        t.contains("通知") -> if (canNotify) 1 else 0
                        answerOf(t) == true -> 0
                        else -> null
                    } ?: return@ask false
                    main.postDelayed({
                        if (!MessengerAgent.respond(choice)) say("這個確認已經結束了。")
                    }, 800)
                    true
                }
            }
        }
    }

    private fun onAgentStatus(msg: String) {
        update()
        when {
            msg.startsWith("✅ 已送出") -> {
                log("✉️ 已送出給「$sendFriend」")
                if (navigating) {
                    say("送出了，幫你切回導航。")
                    main.postDelayed({ launchPackage(MAPS) }, 1500)
                } else {
                    say("送出了，幫你切回車機畫面。")
                    main.postDelayed({ bringCarMateFront() }, 1500)
                }
            }
            msg.startsWith("❌") -> say("出了點問題：" + msg.removePrefix("❌").trim())
        }
    }

    /** 把路線唸給駕駛聽 */
    private fun routeSpeech(r: RouteInfo, a: Appointment?): String = buildString {
        append("路線規劃好了，大約")
        append(r.minutes?.let { fmtMinutes(it) } ?: r.summary.substringAfter("："))
        Regex("""(\d+(?:\.\d+)?)\s*公里""").find(r.summary)?.let { append("，").append(it.value) }
        if (r.toll) append("，有收費路段")
        r.arrival?.let { append("，預計").append(it).append("到") }
        append("。")
        val late = r.lateMinutes
        if (late != null && a != null) {
            append("提醒你，這樣會比約定的").append(a.timeHHmm).append("晚大約").append(late).append("分鐘。")
        }
    }

    // ───────────────────────── 路上：還要多久、遲到通知 ─────────────────────────

    /** 回答「還要多久」：讀按 🎤 那一刻的導航畫面 */
    private fun answerEta() {
        val a = appointment
        if (screenPkg == MAPS) {
            val eta = parseNavEta(screenTexts)
            if (eta != null) {
                val (mins, dist, arrival) = eta
                val sb = StringBuilder("還要")
                sb.append(mins?.let { "大約" + fmtMinutes(it) } ?: "")
                dist?.let { sb.append("，").append(it) }
                arrival?.let { sb.append("，預計").append(it).append("到") }
                sb.append("。")
                val late = if (a != null && arrival != null) lateMinutes(arrival, a.timeHHmm) else null
                if (late != null && a != null) {
                    sb.append("會比約定的").append(a.timeHHmm).append("晚大約").append(late).append("分鐘，要幫你跟").append(a.friend).append("說一聲嗎？")
                    askYesNo(sb.toString()) { yes ->
                        if (yes) notifyLate(a.friend, late, "") else say("好，那我先不通知。")
                    }
                } else {
                    if (a != null && a.timeHHmm.isNotEmpty() && arrival != null) sb.append("跟${a.friend}約的時間來得及。")
                    say(sb.toString())
                }
                return
            }
            dumpScreen("maps_nav.txt")   // 讀不到時存下來，方便對照畫面結構
        }
        val r = lastRoute
        val rm = r?.minutes
        if (r != null && rm != null) {
            say("出發時估計車程大約${fmtMinutes(rm)}，預計${r.arrival ?: ""}到。要即時的時間，請在導航畫面按麥克風問我。")
        } else {
            say("目前沒有在導航喔。")
        }
    }

    /** 從導航畫面讀出：剩餘分鐘、距離、預計抵達時間 */
    private fun parseNavEta(texts: List<String>): Triple<Int?, String?, String?>? {
        val timeRe = Regex("""\d{1,2}:\d{2}""")
        val kmRe = Regex("""(\d+(?:\.\d+)?)\s*公里""")
        val mins = texts.firstOrNull { (it.contains("分鐘") || it.contains("小時")) && it.length <= 14 }
            ?.let { parseMinutes(it) }
        val distArrival = texts.firstOrNull { kmRe.containsMatchIn(it) && timeRe.containsMatchIn(it) }
        val dist = (distArrival ?: texts.firstOrNull { it.contains("公里") && it.length <= 20 })
            ?.let { kmRe.find(it)?.value }
        val arrival = distArrival?.let { timeRe.find(it)?.value } ?: mins?.let { nowPlus(it) }
        if (mins == null && arrival == null) return null
        return Triple(mins, dist, arrival)
    }

    /** 遲到通知：有導航算出來的遲到分鐘就用它，沒有就用 10 分鐘模擬 */
    fun lateCheck() {
        val a = appointment
        val friend = a?.friend ?: firstContact()
        val real = lastRoute?.lateMinutes
        val minutes = real ?: 10
        log(if (real != null) "⏰ 依導航預估會晚 $minutes 分鐘" else "⏰（模擬）預估會晚 $minutes 分鐘")
        val where = a?.place?.let { "到$it" } ?: ""
        askYesNo("照目前路況，你大概會晚 $minutes 分鐘$where。要我幫你跟${friend}說一聲嗎？") { yes ->
            if (yes) notifyLate(friend, minutes, "") else say("好，那我先不通知。")
        }
    }

    private fun notifyLate(friend: String, minutes: Int?, message: String) {
        val f = friend.ifEmpty { appointment?.friend ?: firstContact() }
        sendMessage(f, message.ifEmpty { DriveBrain.lateTemplate(minutes) })
    }

    /** 填好訊息；確認（說「送出」或按「送出」）後才會傳 */
    private fun sendMessage(friend: String, msg: String) {
        if (friend.isEmpty()) { say("要傳給誰呢？"); return }
        sendFriend = friend
        sendMsg = msg
        knownNames.add(friend)
        log("✉️ 準備傳給「$friend」：$msg")
        if (MessengerAgent.start(SendCommand(friend, msg))) {
            say("好，我去 Messenger 幫你把訊息填好。")
            if (!launchPackage(MESSENGER)) { MessengerAgent.cancel(); say("找不到 Messenger。") }
        } else {
            say("現在沒辦法傳訊息：${MessengerAgent.history.lastOrNull() ?: ""}")
        }
    }

    // ───────────────────────── 路上：記地點、分享地點 ─────────────────────────

    private fun savePlace(note: String) {
        lastSay = "記下這個地點…"
        update()
        Thread {
            val loc = lastLocation()
            val addr = loc?.let { geocode(it) } ?: ""
            main.post {
                val p = SavedPlace(clock(), note, loc?.latitude, loc?.longitude, addr)
                places.add(p)
                log("📌 記下地點：${note.ifEmpty { "（沒有描述）" }}　$addr　${p.link ?: "（沒有位置）"}")
                val where = if (addr.isNotEmpty()) "，在${addr}附近" else if (loc == null) "，不過我拿不到現在的位置" else ""
                say("幫你記下來了${if (note.isNotEmpty()) "：$note" else ""}$where。下車時我會整理給你。")
            }
        }.start()
    }

    private fun sharePlace(friend: String) {
        val p = places.lastOrNull() ?: run { say("還沒有記下任何地點喔。"); return }
        val f = friend.ifEmpty { appointment?.friend ?: firstContact() }
        val msg = buildString {
            append("我剛剛路過一個地方，看起來不錯")
            if (p.note.isNotEmpty()) append("：").append(p.note)
            if (p.address.isNotEmpty()) append("（").append(p.address).append("）")
            p.link?.let { append(" ").append(it) }
        }
        sendMessage(f, msg)
    }

    @SuppressLint("MissingPermission")   // 上面已經檢查過權限
    private fun lastLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun geocode(loc: Location): String = runCatching {
        val a = Geocoder(app, Locale.TAIWAN).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            ?: return@runCatching ""
        listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare)
            .distinct().joinToString("")
            .ifEmpty { a.getAddressLine(0) ?: "" }
    }.getOrDefault("")

    // ───────────────────────── 車況模擬（展示用） ─────────────────────────

    /** 畫面上的「⛽ 油量」按鈕：80% ↔ 8% 切換 */
    fun toggleFuel() {
        fuelPercent = if (fuelPercent > LOW_FUEL) 8 else 80
        fuelChecked = false
        log("（模擬）油量改成 $fuelPercent%")
        update()
    }

    /** 車機畫面上直接按的車控（不經過 AI） */
    fun adjustAc(delta: Int) {
        acTemp = (acTemp + delta).coerceIn(16, 30)
        if (fanLevel == 0) fanLevel = 2
        log("🚘 手動：冷氣 $acTemp 度")
    }

    fun adjustFan(delta: Int) {
        fanLevel = (fanLevel + delta).coerceIn(0, 5)
        log("🚘 手動：風量 $fanLevel")
    }

    fun toggleWindow() { windowOpen = !windowOpen; log("🚘 手動：車窗${if (windowOpen) "打開" else "關上"}") }
    fun toggleMirror() { mirrorFolded = !mirrorFolded; log("🚘 手動：後照鏡${if (mirrorFolded) "收起" else "展開"}") }

    /** 畫面上的「😪 疲勞」按鈕：模擬偵測到駕駛疲勞 */
    fun simulateFatigue() {
        log("😪（模擬）偵測到駕駛疲勞")
        offerBreak()
    }

    // ───────────────────────── 導航中主動關心 ─────────────────────────

    private val navWatch = object : Runnable {
        override fun run() {
            if (!navigating) return
            checkNavProgress()
            main.postDelayed(this, NAV_CHECK_MS)
        }
    }

    private fun startNavWatch(info: RouteInfo?) {
        navigating = true
        navStart = System.currentTimeMillis()
        lastWarnedLate = info?.lateMinutes ?: 0   // 出發時已經提醒過的不重複講
        nearAsked = false
        main.removeCallbacks(navWatch)
        main.postDelayed(navWatch, NAV_CHECK_MS)
    }

    private fun stopNavWatch() {
        navigating = false
        main.removeCallbacks(navWatch)
    }

    /** 導航中每分鐘看一次地圖：會不會遲到、快到了沒、開太久了沒 */
    private fun checkNavProgress() {
        if (pendingAnswer != null || chatting || MessengerAgent.busy) return   // 正在問問題或做事，不打擾
        val a = appointment
        // 開太久：主動問要不要休息
        val drivingMin = (System.currentTimeMillis() - navStart) / 60000
        if (!fatigueAsked && drivingMin >= FATIGUE_MIN) {
            log("😪 已連續開 $drivingMin 分鐘")
            offerBreak()
            return
        }
        captureScreen()
        if (screenPkg != MAPS) return
        val eta = parseNavEta(screenTexts) ?: return
        val (mins, _, arrival) = eta
        // 會遲到（比上次提醒又多晚 3 分鐘以上才再講）
        val late = if (a != null && arrival != null) lateMinutes(arrival, a.timeHHmm) else null
        if (a != null && late != null && late >= lastWarnedLate + 3 && a.friend.isNotEmpty()) {
            lastWarnedLate = late
            log("⏰ 導航顯示會晚 $late 分鐘，主動提醒")
            askYesNo("照現在路況，你會比約定的${a.timeHHmm}晚大約${late}分鐘。要幫你跟${a.friend}說一聲嗎？") { yes ->
                if (yes) notifyLate(a.friend, late, "") else say("好，那我先不通知。")
            }
            return
        }
        // 快到了
        if (!nearAsked && mins != null && mins <= 3 && a != null && a.friend.isNotEmpty()) {
            nearAsked = true
            log("📍 剩 $mins 分鐘，主動問要不要通知")
            askYesNo("再 $mins 分鐘就到了，要跟${a.friend}說你快到了嗎？") { yes ->
                if (yes) sendMessage(a.friend, "我快到了，大概再 $mins 分鐘 🙌") else say("好。")
            }
        }
    }

    // ───────────────────────── 提神：笑話與閒聊 ─────────────────────────

    private fun offerBreak() {
        fatigueAsked = true
        ask(
            "你好像有點累了。要聽個笑話，還是陪你聊聊天提提神？太累的話我也可以幫你找休息站。",
            "說「笑話」、「聊天」，或是「不用」",
        ) { t ->
            when {
                t.contains("笑話") -> { tellJoke(); true }
                t.contains("聊") -> { startChat(); true }
                t.contains("休息") -> { goTo("休息站"); true }
                NO_WORDS.any { t.contains(it) } -> { say("好，累的話記得找地方休息。"); true }
                else -> false
            }
        }
    }

    private fun tellJoke() {
        lastSay = "想一個笑話…"
        update()
        val told = jokesTold.toList()
        Thread {
            val r = runCatching { DriveBrain.joke(told) }
            main.post {
                val joke = r.getOrElse { say("我一時想不到笑話，AI 連線好像有點慢。"); return@post }
                jokesTold.add(joke)
                say(joke) {
                    askYesNo("還要再聽一個嗎？") { yes -> if (yes) tellJoke() else say("好，打起精神，注意安全喔。") }
                }
            }
        }.start()
    }

    private fun startChat() {
        chatting = true
        chatHistory.clear()
        say("好啊，想聊什麼？說「不聊了」我就安靜。") { listenChat() }
    }

    private fun listenChat() {
        if (!chatting) return
        Voice.listen("想聊什麼？") { t ->
            if (t == null) {
                chatting = false
                AssistantBubble.say("先不聊了，要找我再叫「副駕」")
            } else {
                onHeard(t)
            }
        }
    }

    private fun chatTurn(text: String) {
        if (listOf("不聊", "不用聊", "結束", "好了", "安靜").any { text.contains(it) }) {
            chatting = false
            say("好，有需要再叫我。")
            return
        }
        val history = chatHistory.toList()
        Thread {
            val r = runCatching { DriveBrain.chat(history, text) }
            main.post {
                val reply = r.getOrElse { chatting = false; say("AI 連線好像有點慢，我們等等再聊。"); return@post }
                chatHistory.add(text to reply)
                if (chatHistory.size > 6) chatHistory.removeAt(0)
                say(reply) { listenChat() }
            }
        }.start()
    }

    // ───────────────────────── 下車 ─────────────────────────

    fun endTrip() {
        log("🏁 下車，旅程結束")
        val mins = if (tripStart > 0) (System.currentTimeMillis() - tripStart) / 60000 else 0
        val name = "trip_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.TAIWAN).format(Date()) + ".txt"
        val content = buildString {
            appendLine("CarMate 旅程紀錄")
            appointment?.let { appendLine("約定：跟${it.friend}　${it.time}　${it.place}") }
            if (places.isNotEmpty()) {
                appendLine("路上記下的地點：")
                places.forEach { appendLine("・${it.time}　${it.note}　${it.address}　${it.link ?: ""}") }
            }
            appendLine()
            tripLog.forEach { appendLine(it) }
        }
        runCatching { File(app.getExternalFilesDir(null), name).writeText(content) }
            .onSuccess { log("已存檔：$name") }
            .onFailure { log("存檔失敗：${it.message}") }
        val placeText = if (places.isNotEmpty()) "，路上記下了 ${places.size} 個地點" else ""
        say("到了！這趟旅程大約 $mins 分鐘$placeText，過程我都幫你記錄好了，下車小心。")
        stopNavWatch()
        chatting = false
        pendingAnswer = null
        HandsFree.stop()
        AssistantBubble.hide()
    }

    fun stopAll() {
        MessengerAgent.cancel()
        pendingAnswer = null
        chatting = false
        Voice.stop()
        lastSay = "已停止"
        update()
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = ChatReaderService.instance ?: app
        ctx.startActivity(intent)
    }

    /**
     * 切到某個 App（回到它原本的畫面，例如導航中的地圖）。
     * Android 11 以後查不到別的 App 時 getLaunchIntentForPackage 會是 null，
     * 所以改用「主畫面圖示」的方式直接開，不需要先查。
     */
    private fun launchPackage(pkg: String): Boolean {
        val i = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { launch(i) }
            .onFailure { Log.w(TAG, "開不了 $pkg", it); log("⚠️ 切不回 $pkg：${it.message}") }
            .isSuccess
    }

    private fun bringCarMateFront() {
        val i = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { launch(i) }.onFailure { Log.w(TAG, "回到前景失敗", it) }
    }

    /** 記下目前畫面上的所有文字（按 🎤 的那一刻） */
    private fun captureScreen() {
        val root = ChatReaderService.instance?.rootInActiveWindow
        screenPkg = root?.packageName?.toString()
        val out = mutableListOf<String>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        runCatching { visit(root) }
        screenTexts = out
    }

    private fun dumpScreen(name: String) {
        runCatching {
            File(app.getExternalFilesDir(null), name)
                .writeText("pkg=$screenPkg\n" + screenTexts.joinToString("\n"))
            log("（讀不到導航時間，畫面文字已存成 $name）")
        }
    }

    private fun parseMinutes(s: String): Int? {
        val h = Regex("""(\d+)\s*小時""").find(s)?.groupValues?.get(1)?.toInt() ?: 0
        val m = Regex("""(\d+)\s*分""").find(s)?.groupValues?.get(1)?.toInt() ?: 0
        return if (h == 0 && m == 0) null else h * 60 + m
    }

    private fun fmtMinutes(m: Int): String =
        if (m >= 60) "${m / 60}小時${if (m % 60 > 0) "${m % 60}分鐘" else ""}" else "${m}分鐘"

    private fun nowPlus(minutes: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.MINUTE, minutes) }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** 抵達時間比約定晚幾分鐘；不會遲到或不知道約定時間回傳 null */
    private fun lateMinutes(arrival: String, appt: String): Int? {
        fun toMin(s: String): Int? = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim())
            ?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val arr = toMin(arrival) ?: return null
        val ap = toMin(appt) ?: return null
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (ap < now - 60) return null   // 約定時間早就過了（或是明天）
        return (arr - ap).takeIf { it > 0 }
    }

    private fun clock(): String = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())

    fun log(s: String) {
        tripLog.add("${clock()}　$s")
        update()
    }

    private fun update() {
        onUpdate?.invoke()
    }
}
