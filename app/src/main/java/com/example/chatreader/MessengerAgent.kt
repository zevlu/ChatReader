package com.example.chatreader

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 共用核心：操作 Messenger 的 Agent — v3（M2）
 *
 * 兩種任務：
 * - start(cmd)：M1，文字指令「傳給 X：內容」→ 找聊天室 → 填字 → 確認 → 送出（AI 行車助理用）
 * - startReply(target, advisor)：M2，找聊天室 → 讀對話 → AI 判斷情緒與三種回覆 → 懸浮窗三選一
 *   → 填字 → 使用者可在 Messenger 修改（含【】要改掉）→ 確認 → 送出（交友大師用）
 *
 * 安全原則：
 * - 只有使用者在懸浮窗按「送出」，程式才會按 Messenger 的「傳送」鍵
 * - 只認 contentDescription 完全等於「傳送」的按鈕；「發送👍」絕對不碰
 * - 送出前再檢查：還在同一個聊天室；M1 要求內容完全沒變，M2 允許使用者修改但不能是空的、不能還有【】
 *
 * 畫面規則見專案知識「07_Messenger節點結構.md」。
 */

/** 解析後的指令（M1） */
data class SendCommand(val target: String, val message: String)

/** 把文字指令拆成「對象」和「訊息」（M1） */
object CommandParser {
    private val patterns = listOf(
        // 傳給 小明：我會晚 10 分鐘
        Regex("""^\s*(?:傳給|傳訊息給|發給)\s*(.+?)\s*[:：]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL),
        // 跟小明說我會晚 10 分鐘
        Regex("""^\s*(?:跟|和)\s*(.+?)\s*(?:說|講)\s*[:：,，]?\s*(.+)$""", RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(input: String): SendCommand? {
        for (p in patterns) {
            val m = p.find(input.trim()) ?: continue
            val target = m.groupValues[1].trim()
            val message = m.groupValues[2].trim()
            if (target.isNotEmpty() && message.isNotEmpty()) return SendCommand(target, message)
        }
        return null
    }
}

/** Google 地圖路線預覽讀到的資訊（行車助理用） */
data class RouteInfo(
    val summary: String,      // 例如「16 分鐘 (15 公里)」
    val minutes: Int?,        // 車程分鐘數
    val toll: Boolean,        // 有沒有收費路段
    val arrival: String?,     // 預計抵達時間 HH:mm
    val lateMinutes: Int?,    // 比約定晚幾分鐘；沒有約定時間或不會遲到為 null
)

object MessengerAgent {
    private const val TAG = "MessengerAgent"
    private const val PKG = "com.facebook.orca"
    private const val TITLE_SUFFIX = ", 對話串詳情"
    private const val HOME_SEARCH = "萬事問 Meta AI 或搜尋"
    private const val SEND_DESC = "傳送"          // 只有輸入框有焦點又有字時才會出現
    private const val MAX_STEPS = 10               // 找聊天室最多嘗試幾次
    private const val MAX_SCROLLS = 6              // 聊天列表最多往下捲幾次
    private const val MAX_RELAUNCH = 2             // 離開 Messenger 最多重開幾次
    private const val HUMAN_WAIT_MS = 15_000L      // 不認識的畫面，等使用者手動處理多久
    private const val MAPS_PKG = "com.google.android.apps.maps"
    /** Google 地圖路線預覽的「開始」鍵（依 2026/10/19 手機截圖；讀不到時看 maps_fail.txt） */
    private val MAPS_START_WORDS = setOf("開始", "開始導航", "Start")

    /** 跳出視窗常見的「關掉」按鈕文字（只在不認識的畫面才會用到，完全相等才算） */
    private val DISMISS_WORDS = setOf(
        "稍後再說", "以後再說", "不用了", "不用，謝謝", "不用了，謝謝", "略過", "跳過", "關閉", "取消", "不允許",
        "Not now", "Not Now", "Skip", "Close", "Cancel",
    )

    /** 進度回報給畫面（在主執行緒呼叫） */
    var onStatus: ((String) -> Unit)? = null

    /** 地圖列出好幾家（例如「加油站」）時，把第一家的店名交給 CarMate，讓它用這個店名重新規劃路線 */
    var onPlaceList: ((String) -> Unit)? = null

    /** 這次任務的所有進度紀錄（只在主執行緒修改） */
    val history = mutableListOf<String>()

    /** 最近一次 AI 的判斷結果（M2） */
    @Volatile var lastSuggestion: Suggestion? = null
        private set

    @Volatile var busy = false
        private set

    /** drive flavor：使用者取消傳送時，把輸入框清空（friend 維持「文字留著給使用者自己改」） */
    @Volatile var clearOnCancel = false
    @Volatile private var cancelled = false

    private val main = Handler(Looper.getMainLooper())
    private var overlay: AgentOverlay? = null      // 只在主執行緒存取

    private class Stopped : RuntimeException()
    private class FailException(msg: String) : RuntimeException(msg)

    // ───────────────────────── 對外介面 ─────────────────────────

    /** M1：文字指令直接傳送（從主執行緒呼叫）。回傳 false 代表沒有開始。 */
    fun start(cmd: SendCommand): Boolean = launch { service -> runFill(service, cmd) }

    /**
     * M2：讀對話 → AI 建議 → 三選一 → 填入 → 確認送出（從主執行緒呼叫）
     * @param target 聊天對象名字；空白代表「Messenger 目前所在的聊天室」
     */
    fun startReply(target: String, advisor: ReplyAdvisor): Boolean =
        launch { service -> runReply(service, target.trim(), advisor) }

    /**
     * 行車助理：只讀取某人的聊天室，讀完把結果交給 onResult（在主執行緒呼叫）。
     * 失敗或被停止時 onResult 收到 null。不會填字、不會送出。
     * @param pagesUp 讀完目前畫面後再往上捲幾頁，把更早的訊息也讀進來（0 = 只讀目前畫面）
     */
    fun startRead(target: String, pagesUp: Int = 0, onResult: (ChatSnapshot?) -> Unit): Boolean {
        if (busy) {
            report("⚠️ 上一個任務還沒結束，請先按停止")
            return false
        }
        pendingRead = onResult
        val ok = launch { service -> runRead(service, target.trim(), pagesUp) }
        if (!ok) pendingRead = null
        return ok
    }

    /** startRead 的回呼（只在主執行緒存取） */
    private var pendingRead: ((ChatSnapshot?) -> Unit)? = null

    /**
     * 行車助理：Google 地圖打開路線預覽後，讀出車程、算出會不會遲到，
     * 用懸浮窗問使用者要不要開始導航；使用者按了才幫他按「開始」。
     * @param apptHHmm 約定時間（HH:mm），空字串代表不知道
     * @param onReady 讀到路線時呼叫（主執行緒），可以用來唸出路線
     * @param onDone 結束時呼叫（主執行緒）：choice 0 = 沒開始導航／失敗，1 = 已開始導航，2 = 已開始導航且要通知朋友
     */
    fun startRoute(
        apptHHmm: String,
        friend: String,
        onReady: (RouteInfo) -> Unit,
        onDone: (RouteInfo?, Int) -> Unit,
    ): Boolean {
        if (busy) {
            report("⚠️ 上一個任務還沒結束，請先按停止")
            return false
        }
        routeResult = null
        routeChoice = 0
        pendingRoute = onDone
        val ok = launch { service -> runRoute(service, apptHHmm.trim(), friend.trim(), onReady) }
        if (!ok) pendingRoute = null
        return ok
    }

    private var pendingRoute: ((RouteInfo?, Int) -> Unit)? = null   // 只在主執行緒存取
    @Volatile private var routeResult: RouteInfo? = null
    @Volatile private var routeChoice = 0

    /**
     * 懸浮窗出現需要使用者回答的問題時通知（主執行緒）。
     * kind：「confirm」= 要不要送出訊息、「route」= 要不要開始導航。行車助理用它改成語音回答。
     */
    var onAsk: ((kind: String, text: String, options: List<String>) -> Unit)? = null

    // 目前懸浮窗問題的答案（只在主執行緒存取）
    private var answerYes: (() -> Unit)? = null
    private var answerAlt: (() -> Unit)? = null
    private var answerNo: (() -> Unit)? = null

    private fun setAnswers(yes: () -> Unit, alt: (() -> Unit)?, no: () -> Unit) {
        answerYes = yes; answerAlt = alt; answerNo = no
    }

    private fun clearAnswers() {
        answerYes = null; answerAlt = null; answerNo = null
    }

    /**
     * 用語音回答目前懸浮窗的問題（從主執行緒呼叫），效果和使用者按按鈕一樣。
     * @param choice 0 = 是／第一個選項，1 = 第二個選項，-1 = 取消
     * @return false 代表目前沒有這個選項可以回答
     */
    fun respond(choice: Int): Boolean {
        val f = when (choice) {
            0 -> answerYes
            1 -> answerAlt
            else -> answerNo
        } ?: return false
        clearAnswers()
        f()
        return true
    }

    /** 停止任務（任何時候都可以按） */
    fun cancel() {
        if (!busy) return
        cancelled = true
        finish("⏹ 已停止")
    }

    private fun launch(task: (ChatReaderService) -> Unit): Boolean {
        val service = ChatReaderService.instance
        if (service == null) {
            history.clear(); report("❌ 無障礙服務未啟用，請先到設定打開")
            return false
        }
        if (busy) {
            report("⚠️ 上一個任務還沒結束，請先按停止")
            return false
        }
        busy = true
        cancelled = false
        clearAnswers()
        history.clear()
        overlay?.hide()
        overlay = AgentOverlay(service).also {
            it.show()
            it.showRunning("準備中…") { cancel() }
        }
        Thread({ guarded { task(service) } }, "MessengerAgent").start()
        return true
    }

    /** 在背景執行緒跑任務，統一處理停止與失敗 */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Stopped) {
            // 使用者按了停止，finish() 已經處理過
        } catch (e: FailException) {
            fail(e.message ?: "失敗")
        } catch (e: Exception) {
            Log.e(TAG, "執行失敗", e)
            fail("發生錯誤：${e.message}")
        }
    }

    // ───────────────────────── M1：指令傳送 ─────────────────────────

    private fun runFill(service: ChatReaderService, cmd: SendCommand) {
        report("① 等待 Messenger 開啟…")
        waitForMessenger(service)
        val chatTitle = navigateToChat(service, cmd.target)

        report("④ 填入訊息…")
        fillMessage(service, cmd.message)
        report("⑤ 已填入，等你確認")
        askConfirm(service, chatTitle, cmd.message, allowEdit = false)
    }

    // ───────────────────────── M2：AI 回覆建議 ─────────────────────────

    private fun runReply(service: ChatReaderService, target: String, advisor: ReplyAdvisor) {
        report("① 等待 Messenger 開啟…")
        waitForMessenger(service)
        val chatTitle = navigateToChat(service, target)

        // 讀對話
        sleep(800)
        report("④ 讀取對話…")
        val root = root(service) ?: throw FailException("讀不到畫面")
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val snapshot = MessengerParser.parse(root, screen.width())
        if (snapshot.messages.isEmpty()) throw FailException("這個聊天室畫面上沒有讀到訊息")
        val chatText = snapshot.messages.joinToString("\n") { "${it.speaker}：${it.text}" }

        // M4：畫面上有圖片訊息 → 截圖、只裁出圖片範圍（最近 2 張）
        val images = captureImages(service, snapshot)
        report("讀到 ${snapshot.messages.size} 則訊息" +
            (if (images.isNotEmpty()) "、${images.size} 張圖片" else "") +
            "，交給 AI 判斷…（約 2～5 秒）")

        // 問 AI
        val t0 = System.currentTimeMillis()
        val sug = try {
            if (images.isEmpty()) advisor.suggest(chatText) else advisor.suggestWithImages(chatText, images)
        } catch (e: LlmException) {
            throw FailException("AI 呼叫失敗：${e.message}")
        }
        if (cancelled) throw Stopped()
        lastSuggestion = sug
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        report("⑤ AI 回覆（%.1f 秒）：%s %d/5，需要%s".format(sec, sug.emotion, sug.intensity, sug.need))
        report("　依據：${sug.reason}")
        sug.replies.forEach { report("　・${it.style}：${it.text}（${it.why}）") }

        // 懸浮窗三選一
        val header = "對方情緒：${sug.emotion}（${sug.intensity}/5）・需要：${sug.need}\n${sug.reason}\n\n選一則回覆："
        val options = sug.replies.map { "${it.style}｜${it.text}" }
        main.post {
            overlay?.showChoices(
                header, options,
                onPick = { i ->
                    overlay?.showRunning("填入中…", null)
                    Thread({ guarded { pickReply(service, chatTitle, sug.replies[i].text) } }, "MessengerAgentPick").start()
                },
                onCancel = { finish("已取消，沒有填入任何文字") },
            )
        }
    }

    // ───────────────────────── M4：圖片訊息截圖 ─────────────────────────

    /** 最多送幾張圖片給 AI（越多越慢） */
    private const val MAX_IMAGES = 2

    /**
     * 截一張畫面，裁出最近 MAX_IMAGES 張圖片訊息的範圍，回傳 JPEG base64（順序和聊天紀錄裡的「[圖片]」一致）。
     * Android 11 以下、截圖失敗或畫面上沒有圖片時回傳空清單（照樣只用文字判斷，不會失敗）。
     */
    private fun captureImages(service: ChatReaderService, snapshot: ChatSnapshot): List<String> {
        val targets = snapshot.images.takeLast(MAX_IMAGES)
        if (targets.isEmpty()) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            report("畫面上有圖片，但截圖需要 Android 11 以上，這次只用文字判斷")
            return emptyList()
        }
        report("看到 ${targets.size} 張圖片，截圖給 AI 看…")

        // 懸浮窗可能蓋住圖片：截圖前先藏起來，截完再顯示
        main.post { overlay?.setVisible(false) }
        val shot = try {
            sleep(350)
            takeScreenshotSync(service) ?: run { sleep(1000); takeScreenshotSync(service) }   // 太頻繁會失敗，等一下再試一次
        } finally {
            main.post { overlay?.setVisible(true) }
        }
        if (shot == null) {
            report("截圖失敗，這次只用文字判斷")
            return emptyList()
        }

        val out = mutableListOf<String>()
        for (img in targets) {
            val r = Rect(img.bounds)
            if (!r.intersect(0, 0, shot.width, shot.height)) continue
            if (r.width() < 40 || r.height() < 40) continue   // 太小（例如只露出一角）就略過
            val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
            out.add(LlmClient.encodeJpeg(crop))
        }
        Log.i(TAG, "M4：截到 ${out.size} 張圖片")
        return out
    }

    /** 用無障礙服務截圖（Android 11 以上），最多等 3 秒；失敗回傳 null */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotSync(service: AccessibilityService): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    // 3 = 呼叫太頻繁（讀取服務剛截過圖）
                    Log.w(TAG, "M4 截圖失敗 errorCode=$errorCode")
                    latch.countDown()
                }
            },
        )
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    // ───────────────────────── 行車助理：Google 地圖確認導航 ─────────────────────────

    private fun runRoute(service: ChatReaderService, appt: String, friend: String, onReady: (RouteInfo) -> Unit) {
        report("① 等待 Google 地圖開啟…")
        if (!waitFor(10_000) { root(service)?.packageName?.toString() == MAPS_PKG }) {
            throw FailException("等了 10 秒還沒看到 Google 地圖")
        }
        report("② 等路線規劃好…")
        var noOriginSince = 0L
        var picks = 0
        var relaunched = false
        val ready = waitFor(30_000) {
            val r = root(service)
            if (r != null && findMapsStart(r) == null) {
                dismissPopup(r)
                // 不小心開到「在路線中新增停靠點」面板：按返回關掉，回到路線預覽
                if (findNode(r) { n -> n.text?.toString()?.trim() == "在路線中新增停靠點" } != null) {
                    report("關掉「新增停靠點」面板，回到路線")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(1500)
                } else if (!relaunched) {
                    // 目的地或停靠點是「加油站」這種類別時，地圖會先列出好幾家讓你選：
                    // 讀出第一家的店名，請 CarMate 用店名重新規劃（不用在地圖上點來點去）
                    val first = firstListedPlace(r)
                    if (first != null && onPlaceList != null) {
                        relaunched = true
                        report("地圖列出好幾家，選第一家：$first")
                        main.post { onPlaceList?.invoke(first) }
                        Thread.sleep(3000)
                    } else if (picks < 1 && pickFirstPlace(r)) {
                        picks++
                    }
                }
                // 起點欄是空的「選擇起點」：手機定位沒開，地圖不會出現「開始」鍵
                if (findNode(r) { n -> n.text?.toString()?.trim() == "選擇起點" } != null) {
                    if (noOriginSince == 0L) noOriginSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - noOriginSince > 4000) {
                        throw FailException("地圖找不到你的位置（起點是空的）。請打開手機的「位置」再試一次")
                    }
                } else noOriginSince = 0L
            }
            r != null && findMapsStart(r) != null
        }
        if (!ready) {
            root(service)?.let { dumpTree(service, it, "maps_fail.txt") }
            throw FailException("沒看到地圖的「開始」鍵（已存 maps_fail.txt），請自己按開始")
        }
        sleep(1000)   // 等車程數字穩定
        val root = root(service) ?: throw FailException("讀不到地圖畫面")
        val info = readRoute(root, appt)
        routeResult = info
        report("③ 路線：${info.summary}，預計 ${info.arrival ?: "?"} 到" +
            (info.lateMinutes?.let { "，比約定晚 $it 分鐘" } ?: ""))
        main.post { onReady(info) }

        val header = buildString {
            append("🧭 ").append(info.summary)
            if (info.toll) append("・有收費路段")
            info.arrival?.let { append("\n預計 ").append(it).append(" 抵達") }
            info.lateMinutes?.let { append("\n⚠️ 會比約定的 ").append(appt).append(" 晚約 ").append(it).append(" 分鐘") }
            append("\n\n要開始導航嗎？")
        }
        val notifyLabel = info.lateMinutes?.takeIf { friend.isNotEmpty() }
            ?.let { "▶ 開始導航，並通知「$friend」我會晚 $it 分鐘" }
        val options = listOfNotNull("▶ 開始導航", notifyLabel)
        main.post {
            val pick: (Int) -> Unit = { i ->
                overlay?.showRunning("幫你按「開始」…", null)
                Thread({ guarded { pressMapsStart(service, if (i == 1) 2 else 1) } }, "MapsStart").start()
            }
            val cancel: () -> Unit = { finish("好，先不導航（路線還留在地圖上）") }
            val alt: (() -> Unit)? = notifyLabel?.let { { pick(1) } }
            setAnswers({ pick(0) }, alt, cancel)
            overlay?.showChoices(
                header, options,
                onPick = { i -> clearAnswers(); pick(i) },
                onCancel = { clearAnswers(); cancel() },
            )
            onAsk?.invoke("route", header, options)
        }
    }

    private fun pressMapsStart(service: ChatReaderService, choice: Int) {
        val root = root(service) ?: throw FailException("讀不到地圖畫面")
        if (root.packageName?.toString() != MAPS_PKG) throw FailException("畫面已經不是 Google 地圖")
        val start = findMapsStart(root) ?: run {
            dumpTree(service, root, "maps_fail.txt")
            throw FailException("找不到「開始」鍵（已存 maps_fail.txt），請自己按開始")
        }
        if (!start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw FailException("按「開始」失敗，請自己按")
        routeChoice = choice
        finish("✅ 已開始導航")
    }

    /** 擋住地圖的彈出視窗（例如「定位精確度」）：只按「不用了」這類，不碰地圖自己的關閉／取消 */
    private val POPUP_DISMISS = setOf("不用了，謝謝", "不用，謝謝", "不用了", "稍後再說", "以後再說", "略過", "No thanks", "Not now")

    /**
     * 地圖列出多個地點（例如搜尋「加油站」）時，按第一家的「新增停靠點」或「路線」，
     * 地圖就會回到路線預覽、出現「開始」鍵。有按到回傳 true。
     */
    private val PLACE_PICK = listOf("新增停靠點", "Add stop", "路線", "Directions")

    private fun pickFirstPlace(root: AccessibilityNodeInfo): Boolean {
        for (word in PLACE_PICK) {
            val hits = collect(root) { n ->
                listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { it.trim() == word }
            }
            // 至少兩個一樣的按鈕才是「地點列表」；只有一個可能是路線畫面上的選項，不要亂按
            if (hits.size < 2) continue
            val hit = hits.first()
            var cur: AccessibilityNodeInfo? = hit
            repeat(4) {
                val c = cur ?: return@repeat
                if (c.isClickable) {
                    val name = placeTitleNear(c)
                    report("地圖列出好幾個地點，幫你選第一個${name?.let { "：$it" } ?: ""}")
                    c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(2500)
                    return true
                }
                cur = c.parent
            }
        }
        return false
    }

    /**
     * 地點列表的第一家店名。列表每一筆都有「xx 公里」那一行，至少兩筆才算列表；
     * 從第一個「公里」往前找，跳過評分、評論數、類別，第一個像名字的就是店名。
     */
    private fun firstListedPlace(root: AccessibilityNodeInfo): String? {
        val texts = collect(root) { !it.text.isNullOrBlank() }.map { it.text.toString().trim() }
        val distIdx = texts.indices.filter { texts[it].contains("公里") || texts[it].matches(Regex(""".*\d+\s*(m|km|公尺)$""")) }
        if (distIdx.size < 2) return null
        for (i in distIdx.first() - 1 downTo maxOf(0, distIdx.first() - 6)) {
            val t = texts[i]
            if (t.length < 4) continue
            if (t.startsWith("·") || t.startsWith("(") || t.contains("★") || t.contains("營業") || t.contains("打烊")) continue
            if (t.matches(Regex("""^[\d.,\s()]+$"""))) continue
            if (t == "加油站" || t in PLACE_PICK) continue
            return t
        }
        return null
    }

    /** 找按鈕所在那一筆的店名（往上找幾層，拿第一段像名稱的文字） */
    private fun placeTitleNear(button: AccessibilityNodeInfo): String? {
        var p: AccessibilityNodeInfo? = button.parent
        repeat(4) {
            val x = p ?: return null
            val t = findNode(x) { n ->
                val s = n.text?.toString()?.trim() ?: return@findNode false
                s.length in 3..30 && s !in PLACE_PICK && s !in setOf("致電", "分享", "儲存") && !s.contains("★") && !s.contains("公里")
            }?.text?.toString()
            if (t != null) return t
            p = x.parent
        }
        return null
    }

    /** 「要開啟裝置定位嗎？」這種視窗要按同意：沒有定位，地圖就沒有起點、無法導航 */
    private val POPUP_ACCEPT = setOf("開啟", "開啟定位", "確定", "OK", "Turn on")
    private val LOCATION_WORDS = listOf("定位", "位置資訊", "裝置位置", "location", "Location")

    private fun dismissPopup(root: AccessibilityNodeInfo) {
        val hit = findNode(root) { n ->
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { it.trim() in POPUP_DISMISS }
        } ?: return
        // 有「不用了」才代表真的有彈出視窗；如果是在問定位，改按「開啟」
        val aboutLocation = findNode(root) { n ->
            val t = n.text?.toString() ?: return@findNode false
            t.length > 4 && LOCATION_WORDS.any { t.contains(it) }
        } != null
        if (aboutLocation) {
            val ok = findNode(root) { n ->
                listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { it.trim() in POPUP_ACCEPT }
            }
            var c: AccessibilityNodeInfo? = ok
            repeat(6) {
                val x = c ?: return@repeat
                if (x.isClickable) {
                    report("地圖需要定位，幫你按「${labelOf(x)}」開啟定位")
                    x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(2000)
                    return
                }
                c = x.parent
            }
        }
        var cur: AccessibilityNodeInfo? = hit
        repeat(6) {
            val c = cur ?: return
            if (c.isClickable) {
                report("有彈出視窗擋住地圖，幫你按「${labelOf(c)}」")
                c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(1200)
                return
            }
            cur = c.parent
        }
    }

    /** 路線預覽的「開始」鍵：文字或描述是「開始」，回傳可以點的那一層 */
    private fun findMapsStart(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hit = findNode(root) { n ->
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                .any { it.trim() in MAPS_START_WORDS }
        } ?: return null
        var cur: AccessibilityNodeInfo? = hit
        repeat(6) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    /** 從路線預覽畫面讀出車程，並和約定時間比較 */
    private fun readRoute(root: AccessibilityNodeInfo, appt: String): RouteInfo {
        val texts = collect(root) { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
            .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val summary = texts.firstOrNull { it.contains("分鐘") && it.contains("公里") && it.length <= 30 }
            ?: texts.firstOrNull { (it.contains("分鐘") || it.contains("小時")) && it.length <= 15 }
            ?: "（讀不到車程）"
        val minutes = parseMinutes(summary)
        val toll = texts.any { it.contains("收費") }

        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val arrivalMin = minutes?.let { nowMin + it }
        val arrival = arrivalMin?.let { "%02d:%02d".format((it / 60) % 24, it % 60) }

        var late: Int? = null
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(appt)
        if (m != null && arrivalMin != null) {
            val apptMin = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            // 約定時間已經過了很久（或是明天）就不算遲到
            if (apptMin >= nowMin - 60) late = (arrivalMin - apptMin).takeIf { it > 0 }
        }
        return RouteInfo(summary, minutes, toll, arrival, late)
    }

    /** 「1 小時 5 分鐘」→ 65；讀不到回傳 null */
    private fun parseMinutes(s: String): Int? {
        val h = Regex("""(\d+)\s*小時""").find(s)?.groupValues?.get(1)?.toInt() ?: 0
        val m = Regex("""(\d+)\s*分鐘""").find(s)?.groupValues?.get(1)?.toInt() ?: 0
        return if (h == 0 && m == 0) null else h * 60 + m
    }

    // ───────────────────────── 行車助理：只讀取 ─────────────────────────

    private fun runRead(service: ChatReaderService, target: String, pagesUp: Int = 0) {
        report("① 等待 Messenger 開啟…")
        waitForMessenger(service)
        val chatTitle = navigateToChat(service, target)
        sleep(800)
        report("④ 讀取對話…")
        var root = root(service) ?: throw FailException("讀不到畫面")
        val screen = Rect().also { root.getBoundsInScreen(it) }
        var snapshot = MessengerParser.parse(root, screen.width())
        // 剛點進聊天室時訊息可能還在載入：多等幾次再讀
        var tries = 0
        while (snapshot.messages.isEmpty() && tries < 4) {
            tries++
            report("訊息還沒出現，再等一下（$tries/4）")
            sleep(700)
            root = root(service) ?: continue
            snapshot = MessengerParser.parse(root, screen.width())
        }
        Log.i(TAG, "runRead：「$chatTitle」畫面上讀到 ${snapshot.messages.size} 則（等了 $tries 次），pagesUp=$pagesUp")
        if (snapshot.messages.isEmpty()) {
            // 畫面上只有讚、貼圖、圖片時讀不到文字：直接往上翻找文字訊息
            report("畫面上沒有文字訊息，往上找")
            snapshot = snapshot.copy(messages = readOlder(service, emptyList(), maxOf(pagesUp, 3), screen))
            if (snapshot.messages.isEmpty()) throw FailException("「$chatTitle」的聊天室往上翻也沒有讀到文字訊息")
        } else if (pagesUp > 0) {
            snapshot = snapshot.copy(messages = readOlder(service, snapshot.messages, pagesUp, screen))
        }
        val result = snapshot.copy(partnerName = snapshot.partnerName ?: chatTitle)
        main.post {
            val cb = pendingRead
            pendingRead = null
            cb?.invoke(result)
        }
        finish("✅ 讀到「$chatTitle」${snapshot.messages.size} 則訊息")
    }

    /**
     * 在聊天室裡往上捲，把更早的訊息接在前面（行車助理找很久以前的約定用）。
     * Messenger 的訊息列表不接受無障礙的「往回捲」（2026-11-09 A55 實測：最大的可捲動節點是 GridView，
     * 而且捲不動），所以改用手勢：在畫面中間由上往下滑，跟手指往上翻一樣。
     * 需要 accessibility_service_config.xml 的 canPerformGestures="true"。
     * 滑完沒有新訊息就停（到頂了）。
     */
    private fun readOlder(service: ChatReaderService, newest: List<ChatMessage>, pages: Int, screen: Rect): List<ChatMessage> {
        val width = screen.width()
        var merged = newest
        for (p in 1..pages) {
            if (!swipeDown(service, screen)) {
                report("沒辦法在 Messenger 上滑動：請到手機的無障礙設定，把服務關掉再打開")
                Log.w(TAG, "readOlder：dispatchGesture 失敗（canPerformGestures 還沒生效？）")
                break
            }
            report("往上找更早的訊息（$p/$pages）")
            sleep(900)
            val r2 = root(service) ?: break
            val older = MessengerParser.parse(r2, width).messages
            val before = merged.size
            merged = mergeOlder(older, merged)
            Log.i(TAG, "readOlder：第 $p 頁讀到 ${older.size} 則，合併後 ${merged.size} 則")
            if (merged.size == before) { report("往上沒有更早的訊息了"); break }
        }
        return merged
    }

    /** 在聊天室中間由上往下滑（看更早的訊息）。成功送出手勢回傳 true */
    private fun swipeDown(service: ChatReaderService, screen: Rect): Boolean {
        val x = screen.centerX().toFloat()
        val y1 = screen.top + screen.height() * 0.30f      // 避開上面的標題列
        val y2 = screen.top + screen.height() * 0.75f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        val latch = CountDownLatch(1)
        var done = false
        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(3, TimeUnit.SECONDS)
        return done
    }

    /** 把往上捲讀到的訊息接在前面：兩頁重疊的部分只留一份 */
    private fun mergeOlder(older: List<ChatMessage>, newer: List<ChatMessage>): List<ChatMessage> {
        fun key(m: ChatMessage) = "${m.fromMe}|${m.text}"
        val max = minOf(older.size, newer.size)
        for (k in max downTo 1) {
            val tail = older.takeLast(k).map(::key)
            val head = newer.take(k).map(::key)
            if (tail == head) return older.dropLast(k) + newer
        }
        // 找不到重疊：整頁都是新的（或捲太多），直接接在前面；去掉完全相同的訊息
        val seen = newer.map(::key).toSet()
        return older.filter { key(it) !in seen } + newer
    }

    private fun pickReply(service: ChatReaderService, chatTitle: String, text: String) {
        report("⑥ 填入：$text")
        val root = root(service) ?: throw FailException("讀不到畫面")
        if (findChatTitle(root) != chatTitle) throw FailException("聊天室變了，沒有填入")
        fillMessage(service, text)
        if (hasPlaceholder(text)) {
            report("⑦ 已填入，請先在 Messenger 把【】改掉，再按送出")
        } else {
            report("⑦ 已填入，可以直接送出，也可以先在 Messenger 修改")
        }
        askConfirm(service, chatTitle, text, allowEdit = true)
    }

    private fun hasPlaceholder(s: String) = s.contains('【') || s.contains('】')

    // ───────────────────────── 確認與送出 ─────────────────────────

    /** 顯示確認懸浮窗；allowEdit = true 時允許使用者在 Messenger 改過再送 */
    private fun askConfirm(
        service: ChatReaderService,
        chatTitle: String,
        expected: String,
        allowEdit: Boolean,
        warning: String? = null,
    ) {
        val text = buildString {
            if (warning != null) append("⚠️ ").append(warning).append("\n\n")
            if (allowEdit && hasPlaceholder(expected)) {
                append("請在 Messenger 輸入框把【】改成你要的內容，改好再按「送出」\n\n")
            } else if (allowEdit) {
                append("可以先在 Messenger 修改，按「送出」會傳出輸入框目前的內容\n\n")
            }
            append("要傳給「").append(chatTitle).append("」嗎？\n\n").append(expected)
        }
        main.post {
            val send: () -> Unit = {
                overlay?.showRunning("送出中…", null)   // 先鎖住，避免連按兩次
                Thread({ guarded { runSend(service, expected, chatTitle, allowEdit) } }, "MessengerAgentSend").start()
            }
            val cancel: () -> Unit = {
                if (clearOnCancel) {
                    Thread({
                        guarded {
                            val e = root(service)?.let { findEdit(it) }
                            val ok = e != null && setText(e, "")      // 只清文字，不按任何按鈕
                            finish(if (ok) "已取消：已清掉輸入框，沒有送出" else "已取消：沒有送出（輸入框沒清掉，請手動刪除）")
                        }
                    }, "MessengerAgentCancel").start()
                } else {
                    finish("已取消：文字留在輸入框，你可以自己修改或送出")
                }
            }
            setAnswers(send, null, cancel)
            overlay?.showConfirm(
                text,
                onSend = { clearAnswers(); send() },
                onCancel = { clearAnswers(); cancel() },
            )
            onAsk?.invoke("confirm", text, listOf("送出"))
        }
    }

    private fun runSend(service: ChatReaderService, expected: String, chatTitle: String, allowEdit: Boolean) {
        var root = root(service) ?: throw FailException("讀不到畫面，沒有送出")
        if (findChatTitle(root) != chatTitle) {
            throw FailException("畫面已經不是「$chatTitle」的聊天室，為了安全沒有送出")
        }

        var send: AccessibilityNodeInfo? = null
        var finalText = expected
        for (attempt in 1..3) {
            val edit = findEdit(root) ?: throw FailException("找不到輸入框，沒有送出")
            val current = if (edit.isShowingHintText) "" else edit.text?.toString().orEmpty()

            if (allowEdit) {
                when {
                    current.isBlank() && !hasPlaceholder(expected) -> refill(service, edit, expected)
                    current.isBlank() -> {
                        askConfirm(service, chatTitle, expected, allowEdit, "輸入框是空的，請填好內容再按送出")
                        return
                    }
                    hasPlaceholder(current) -> {
                        askConfirm(service, chatTitle, expected, allowEdit, "輸入框裡還有【】，請改掉再按送出")
                        return
                    }
                    else -> finalText = current
                }
            } else {
                when {
                    current == expected -> {}
                    current.isEmpty() -> refill(service, edit, expected)
                    else -> throw FailException("輸入框被改成「$current」，和確認的內容不同，為了安全沒有送出")
                }
            }

            root = root(service) ?: throw FailException("讀不到畫面，沒有送出")
            send = findSendButton(root)
            if (send != null) break

            report("沒看到「傳送」箭頭，重新點輸入框（$attempt/3）")
            findEdit(root)?.let { focusEdit(it) }
            sleep(700)
            root = root(service) ?: throw FailException("讀不到畫面，沒有送出")
        }
        val sendButton = send ?: run {
            dumpTree(service, root, "agent_fail.txt")
            throw FailException("找不到「傳送」鍵，沒有送出（已存 agent_fail.txt）")
        }

        // 最後再確認一次還在同一個聊天室
        if (findChatTitle(root) != chatTitle) throw FailException("聊天室變了，為了安全沒有送出")
        if (!sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw FailException("按「傳送」失敗")
        }
        sleep(1200)
        val after = root(service)?.let { findEdit(it) }
        val cleared = after == null || after.isShowingHintText || after.text.isNullOrEmpty()
        finish(if (cleared) "✅ 已送出給「$chatTitle」：$finalText" else "⚠️ 已按下傳送，但輸入框還有字，請看一下 Messenger")
    }

    /** 輸入框被清空時，重新填入「使用者確認過的同一句」 */
    private fun refill(service: ChatReaderService, edit: AccessibilityNodeInfo, text: String) {
        report("輸入框被清空了，重新填入")
        focusEdit(edit); sleep(400)
        val e2 = root(service)?.let { findEdit(it) } ?: throw FailException("找不到輸入框，沒有送出")
        setText(e2, text); sleep(700)
    }

    // ───────────────────────── 導航：開 Messenger、找聊天室 ─────────────────────────

    private fun waitForMessenger(service: ChatReaderService) {
        if (!waitFor(8000) { root(service)?.packageName?.toString() == PKG }) {
            throw FailException("等了 8 秒還沒看到 Messenger")
        }
    }

    /**
     * 進到對象的聊天室，回傳聊天室標題。
     * target 空白：直接用目前所在的聊天室（不在聊天室就失敗）。
     */
    private fun navigateToChat(service: ChatReaderService, target: String): String {
        var relaunches = 0
        var opened: String? = null   // 剛從列表點進去的名字（可能是同音字修正後的）
        repeat(MAX_STEPS) {
            sleep(600)
            val root = root(service) ?: return@repeat

            // 跑出 Messenger 了 → 重新打開
            if (root.packageName?.toString() != PKG) {
                if (relaunches >= MAX_RELAUNCH) throw FailException("一直離開 Messenger，任務中止")
                relaunches++
                report("離開了 Messenger，重新打開（$relaunches/$MAX_RELAUNCH）")
                relaunchMessenger(service)
                sleep(1500)
                return@repeat
            }

            val chatTitle = findChatTitle(root)
            when {
                chatTitle != null && (target.isEmpty() || opened != null || nameMatches(chatTitle, target)) -> {
                    report("③ 已在「$chatTitle」的聊天室")
                    return chatTitle
                }
                chatTitle != null -> {
                    report("目前在「$chatTitle」的聊天室，先返回")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    sleep(1000)
                }
                isHome(root) -> {
                    if (target.isEmpty()) {
                        throw FailException("目前在聊天列表，請先打開要回覆的聊天室，或輸入對象名字")
                    }
                    report("② 在聊天列表找「$target」…")
                    opened = openFromList(service, target)
                        ?: throw FailException("聊天列表裡找不到「$target」（往下捲了 $MAX_SCROLLS 次）")
                    sleep(1200)
                }
                else -> handleUnknownScreen(service, root)
            }
        }
        throw FailException(
            if (target.isEmpty()) "試了 $MAX_STEPS 次還是沒進到聊天室"
            else "試了 $MAX_STEPS 次還是沒進到「$target」的聊天室"
        )
    }

    /**
     * 不是聊天列表、也不是聊天室：
     * 1. 先找「稍後再說／略過／關閉…」按掉
     * 2. 找不到就停下來等使用者手動處理（不盲目按返回）
     */
    private fun handleUnknownScreen(service: ChatReaderService, root: AccessibilityNodeInfo) {
        val dismiss = findDismissButton(root)
        if (dismiss != null) {
            report("跳出了視窗，幫你按「${labelOf(dismiss)}」")
            dismiss.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            sleep(1200)
            return
        }
        dumpTree(service, root, "agent_unknown.txt")
        report("⚠️ 看到不認識的畫面，請手動關掉或回到聊天列表（等你 ${HUMAN_WAIT_MS / 1000} 秒）")
        val ok = waitFor(HUMAN_WAIT_MS) {
            val r = root(service)
            r != null && r.packageName?.toString() == PKG && (findChatTitle(r) != null || isHome(r))
        }
        if (!ok) throw FailException("等了 ${HUMAN_WAIT_MS / 1000} 秒畫面還是不認識（已存 agent_unknown.txt）")
        report("畫面恢復了，繼續")
    }

    /**
     * 填字：先點輸入框取得焦點 → SET_TEXT → 確認字有進去、「傳送」箭頭出現
     * 失敗會丟 FailException
     */
    private fun fillMessage(service: ChatReaderService, message: String) {
        val root = root(service) ?: throw FailException("讀不到畫面")
        val edit = findEdit(root) ?: throw FailException("找不到輸入框")
        if (!edit.isShowingHintText && !edit.text.isNullOrBlank() && edit.text.toString() != message) {
            report("（輸入框原本有草稿，會被取代）")
        }
        focusEdit(edit)
        sleep(500)

        val edit2 = root(service)?.let { findEdit(it) } ?: throw FailException("點輸入框後找不到它")
        if (!setText(edit2, message)) throw FailException("填字失敗（SET_TEXT 回傳 false）")
        sleep(800)

        // 第一次沒看到「傳送」，再點一次輸入框試試
        for (attempt in 0..1) {
            val r = root(service) ?: throw FailException("讀不到畫面")
            val e = findEdit(r)
            if (e == null || e.isShowingHintText || e.text?.toString() != message) {
                dumpTree(service, r, "agent_fail.txt")
                throw FailException("填字後檢查不符：輸入框目前是「${e?.text}」（已存 agent_fail.txt）")
            }
            if (findSendButton(r) != null) return
            if (attempt == 0) {
                report("還沒看到「傳送」箭頭，重新點一下輸入框")
                focusEdit(e)
                sleep(700)
            } else {
                dumpTree(service, r, "agent_fail.txt")
            }
        }
        throw FailException("訊息已填入，但沒看到「傳送」鍵（已存 agent_fail.txt）")
    }

    // ───────────────────────── 畫面判斷與操作 ─────────────────────────

    private fun root(service: ChatReaderService): AccessibilityNodeInfo? = service.rootInActiveWindow

    private fun relaunchMessenger(service: ChatReaderService) {
        val intent = service.packageManager.getLaunchIntentForPackage(PKG) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }
            .onFailure { Log.w(TAG, "重新打開 Messenger 失敗", it) }
    }

    /** 聊天室標題「澤, 對話串詳情」→「澤」；不在聊天室則回傳 null */
    private fun findChatTitle(root: AccessibilityNodeInfo): String? =
        findNode(root) { it.contentDescription?.toString()?.endsWith(TITLE_SUFFIX) == true }
            ?.contentDescription?.toString()?.removeSuffix(TITLE_SUFFIX)?.trim()

    /** 首頁特徵：最上方的搜尋列，或底部「聊天室」頁籤 */
    private fun isHome(root: AccessibilityNodeInfo): Boolean =
        findNode(root) {
            val d = it.contentDescription?.toString() ?: ""
            d == HOME_SEARCH || (d.startsWith("聊天室") && d.contains("頁籤"))
        } != null

    /** 名稱比對：完全相同，或包含（例如指令說「崇宇」，標題是「簡崇宇」） */
    private fun nameMatches(name: String, target: String) =
        name == target || name.contains(target) || target.contains(name)   // 標題可能只顯示暱稱（「孟澤」→「澤」）

    private fun findEdit(root: AccessibilityNodeInfo) = findNode(root) { it.isEditable }

    /** 點輸入框：取得焦點後 Messenger 才會切換成有「傳送」箭頭的版面 */
    private fun focusEdit(edit: AccessibilityNodeInfo) {
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setText(edit: AccessibilityNodeInfo, message: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 在聊天列表找到對象並點進去，找不到就往下捲 */
    /** 在聊天列表找到對象並點進去，回傳點進去的名字；找不到回傳 null */
    private fun openFromList(service: ChatReaderService, target: String): String? {
        repeat(MAX_SCROLLS + 1) { i ->
            val root = root(service) ?: return null
            var entry = findChatEntry(root, target)
            // 第一頁找不到：可能是語音辨識的同音錯字（例如「孟則」→「孟澤」），請 AI 從這一頁的名字裡挑
            if (entry == null && i == 0) entry = guessEntry(root, target)
            if (entry != null) {
                val name = entry.contentDescription.toString().substringBefore(", ").trim()
                report("找到「$name」，點進去")
                return if (entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) name else null
            }
            if (i == MAX_SCROLLS) return null
            val list = findNode(root) { it.isScrollable } ?: return null
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return null
            report("這一頁沒有，往下捲（${i + 1}/$MAX_SCROLLS）")
            sleep(900)
        }
        return null
    }

    private fun chatEntries(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, String>> =
        collect(root) {
            it.isClickable &&
                it.className?.toString()?.endsWith("Button") == true &&
                it.contentDescription?.toString()?.contains(", ") == true
        }.map { it to it.contentDescription.toString().substringBefore(", ").trim() }

    /** 請 AI 判斷語音說的名字最像列表上的哪一個（同音字）；沒有像的就回傳 null */
    private fun guessEntry(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        if (!LlmClient.isConfigured()) return null
        val entries = chatEntries(root)
        if (entries.isEmpty()) return null
        val names = entries.map { it.second }.distinct()
        report("列表上沒有完全一樣的「$target」，請 AI 比對同音字…")
        val answer = runCatching {
            LlmClient.chat(
                "使用者用語音說了一個 Messenger 聯絡人的名字，語音辨識可能有同音或近音錯字。" +
                    "從清單中選出最可能是同一個人的名字，只輸出清單裡的名字本身；都不像就只輸出「無」。",
                "使用者說：$target\n清單：${names.joinToString("、")}",
                temperature = 0.0,
            ).trim().trim('「', '」', '"', '。')
        }.getOrNull() ?: return null
        val hit = entries.firstOrNull { it.second == answer } ?: return null
        report("AI 判斷「$target」應該是「${hit.second}」")
        return hit.first
    }

    /** 聊天列表的每一列：可點的 Button，contentDescription =「名稱, 預覽」 */
    private fun findChatEntry(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val entries = chatEntries(root)
        return entries.firstOrNull { it.second == target }?.first
            ?: entries.firstOrNull { it.second.contains(target) }?.first
    }

    /** 只認「傳送」，不會碰到「發送👍」 */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(root) { it.isClickable && it.contentDescription?.toString() == SEND_DESC }

    /** 跳出視窗的關閉按鈕：文字完全等於 DISMISS_WORDS，回傳可以點的那一層 */
    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hit = findNode(root) { n ->
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                .any { it.trim() in DISMISS_WORDS }
        } ?: return null
        var cur: AccessibilityNodeInfo? = hit
        repeat(6) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).firstOrNull { it.isNotBlank() }
            ?: findNode(n) { !it.text.isNullOrBlank() }?.text?.toString()
            ?: "關閉"

    private fun findNode(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), pred)?.let { return it }
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(node)
        return out
    }

    /** 把目前畫面結構存成檔案（除錯用），含焦點與可執行的動作 */
    private fun dumpTree(service: ChatReaderService, root: AccessibilityNodeInfo, fileName: String) {
        val sb = StringBuilder()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            val actions = n.actionList.joinToString(",") { a ->
                a.label?.toString()?.takeIf { it.isNotEmpty() } ?: a.id.toString()
            }
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                .append(" pkg=").append(n.packageName)
                .append(" text=").append(n.text ?: "-")
                .append(" desc=").append(n.contentDescription ?: "-")
                .append(" bounds=").append(r.toShortString())
                .append(if (n.isEditable) " [輸入框]" else "")
                .append(if (n.isFocused) " [焦點]" else "")
                .append(if (n.isShowingHintText) " [提示字]" else "")
                .append(if (n.isClickable) " [可點]" else "")
                .append(" actions=").append(actions)
                .append('\n')
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        runCatching {
            visit(root, 0)
            File(service.getExternalFilesDir(null), fileName).writeText(sb.toString())
            Log.i(TAG, "畫面結構已存：$fileName")
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun sleep(ms: Long) {
        Thread.sleep(ms)
        if (cancelled) throw Stopped()
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            sleep(300)
        }
        return false
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
        main.post {
            history.add(msg)
            overlay?.setStatus(msg)
            onStatus?.invoke(msg)
        }
    }

    private fun fail(msg: String) = finish("❌ $msg")

    /** 結束任務：顯示最後結果 4 秒後收起懸浮窗 */
    private fun finish(msg: String) {
        report(msg)
        main.post {
            busy = false
            clearAnswers()
            // 只讀取的任務如果失敗或被停止，也要通知呼叫的人
            pendingRead?.let { cb -> pendingRead = null; cb(null) }
            pendingRoute?.let { cb -> pendingRoute = null; cb(routeResult, routeChoice) }
            val o = overlay
            o?.showRunning(msg, null)
            main.postDelayed({
                o?.hide()
                if (overlay === o) overlay = null
            }, 4000)
        }
    }
}

/**
 * 懸浮窗：用無障礙服務專屬的 TYPE_ACCESSIBILITY_OVERLAY 顯示，
 * 不需要另外申請「顯示在其他應用程式上層」權限。
 * 設為不可取得焦點，所以不會搶走 Messenger 輸入框的焦點，使用者也能在 Messenger 裡打字修改。
 *
 * 三種模式：執行中（進度＋停止）、三選一（AI 回覆）、確認送出。
 */
private class AgentOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val dp = service.resources.displayMetrics.density
    private val status = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
    }
    private val choiceBox = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val btnNo = Button(service).apply { isAllCaps = false }
    private val btnYes = Button(service).apply { isAllCaps = false }
    private val panel = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (12 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6202832"))
            cornerRadius = 16 * dp
        }
        addView(status)
        addView(choiceBox)
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(btnNo)
            addView(btnYes)
        })
    }
    private var added = false
    private var interactive = false   // 三選一或確認中：不讓進度訊息蓋掉

    fun show() {
        if (added) return
        // 用短邊計算：從橫向的 CarMate 切到直向的 Messenger 時，按鈕才不會被切到畫面外
        val dm = service.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val width = (shortSide * 0.94).toInt()
        val lp = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (88 * dp).toInt()   // 避開聊天室標題列
        }
        wm.addView(panel, lp)
        added = true
    }

    fun setStatus(msg: String) {
        if (!interactive) status.text = "🤖 $msg"
    }

    /** 執行中：顯示進度，可選擇顯示「停止」 */
    fun showRunning(msg: String, onStop: (() -> Unit)?) {
        interactive = false
        status.text = "🤖 $msg"
        choiceBox.visibility = View.GONE
        btnYes.visibility = View.GONE
        if (onStop != null) {
            btnNo.text = "停止"
            btnNo.visibility = View.VISIBLE
            btnNo.setOnClickListener { onStop() }
        } else {
            btnNo.visibility = View.GONE
        }
    }

    /** 三選一：每個選項一顆按鈕 */
    fun showChoices(header: String, options: List<String>, onPick: (Int) -> Unit, onCancel: () -> Unit) {
        interactive = true
        status.text = header
        choiceBox.removeAllViews()
        options.forEachIndexed { i, label ->
            choiceBox.addView(Button(service).apply {
                isAllCaps = false
                text = label
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onPick(i) }
            })
        }
        choiceBox.visibility = View.VISIBLE
        btnYes.visibility = View.GONE
        btnNo.text = "取消"
        btnNo.visibility = View.VISIBLE
        btnNo.setOnClickListener { onCancel() }
    }

    /** 等待確認：只有按「送出」才會傳送 */
    fun showConfirm(text: String, onSend: () -> Unit, onCancel: () -> Unit) {
        interactive = true
        status.text = text
        choiceBox.visibility = View.GONE
        btnNo.text = "取消"
        btnNo.visibility = View.VISIBLE
        btnNo.setOnClickListener { onCancel() }
        btnYes.text = "送出"
        btnYes.visibility = View.VISIBLE
        btnYes.setOnClickListener { onSend() }
    }

    /** M4：截圖前暫時藏起來（不移除，位置不變） */
    fun setVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun hide() {
        if (!added) return
        runCatching { wm.removeView(panel) }
        added = false
    }
}
