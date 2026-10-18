package com.example.chatreader

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

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

    /** 跳出視窗常見的「關掉」按鈕文字（只在不認識的畫面才會用到，完全相等才算） */
    private val DISMISS_WORDS = setOf(
        "稍後再說", "以後再說", "不用了", "不用，謝謝", "略過", "跳過", "關閉", "取消", "不允許",
        "Not now", "Not Now", "Skip", "Close", "Cancel",
    )

    /** 進度回報給畫面（在主執行緒呼叫） */
    var onStatus: ((String) -> Unit)? = null

    /** 這次任務的所有進度紀錄（只在主執行緒修改） */
    val history = mutableListOf<String>()

    /** 最近一次 AI 的判斷結果（M2） */
    @Volatile var lastSuggestion: Suggestion? = null
        private set

    @Volatile var busy = false
        private set
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
     */
    fun startRead(target: String, onResult: (ChatSnapshot?) -> Unit): Boolean {
        if (busy) {
            report("⚠️ 上一個任務還沒結束，請先按停止")
            return false
        }
        pendingRead = onResult
        val ok = launch { service -> runRead(service, target.trim()) }
        if (!ok) pendingRead = null
        return ok
    }

    /** startRead 的回呼（只在主執行緒存取） */
    private var pendingRead: ((ChatSnapshot?) -> Unit)? = null

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
        report("讀到 ${snapshot.messages.size} 則訊息，交給 AI 判斷…（約 2～3 秒）")

        // 問 AI
        val t0 = System.currentTimeMillis()
        val sug = try {
            advisor.suggest(chatText)
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

    // ───────────────────────── 行車助理：只讀取 ─────────────────────────

    private fun runRead(service: ChatReaderService, target: String) {
        report("① 等待 Messenger 開啟…")
        waitForMessenger(service)
        val chatTitle = navigateToChat(service, target)
        sleep(800)
        report("④ 讀取對話…")
        val root = root(service) ?: throw FailException("讀不到畫面")
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val snapshot = MessengerParser.parse(root, screen.width())
        if (snapshot.messages.isEmpty()) throw FailException("「$chatTitle」的聊天室畫面上沒有讀到訊息")
        val result = snapshot.copy(partnerName = snapshot.partnerName ?: chatTitle)
        main.post {
            val cb = pendingRead
            pendingRead = null
            cb?.invoke(result)
        }
        finish("✅ 讀到「$chatTitle」${snapshot.messages.size} 則訊息")
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
            overlay?.showConfirm(
                text,
                onSend = {
                    overlay?.showRunning("送出中…", null)   // 先鎖住，避免連按兩次
                    Thread({ guarded { runSend(service, expected, chatTitle, allowEdit) } }, "MessengerAgentSend").start()
                },
                onCancel = { finish("已取消：文字留在輸入框，你可以自己修改或送出") },
            )
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
                chatTitle != null && (target.isEmpty() || nameMatches(chatTitle, target)) -> {
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
                    if (!openFromList(service, target)) {
                        throw FailException("聊天列表裡找不到「$target」（往下捲了 $MAX_SCROLLS 次）")
                    }
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
    private fun nameMatches(name: String, target: String) = name == target || name.contains(target)

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
    private fun openFromList(service: ChatReaderService, target: String): Boolean {
        repeat(MAX_SCROLLS + 1) { i ->
            val root = root(service) ?: return false
            val entry = findChatEntry(root, target)
            if (entry != null) {
                val name = entry.contentDescription.toString().substringBefore(", ")
                report("找到「$name」，點進去")
                return entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (i == MAX_SCROLLS) return false
            val list = findNode(root) { it.isScrollable } ?: return false
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
            report("這一頁沒有，往下捲（${i + 1}/$MAX_SCROLLS）")
            sleep(900)
        }
        return false
    }

    /** 聊天列表的每一列：可點的 Button，contentDescription =「名稱, 預覽」 */
    private fun findChatEntry(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val entries = collect(root) {
            it.isClickable &&
                it.className?.toString()?.endsWith("Button") == true &&
                it.contentDescription?.toString()?.contains(", ") == true
        }.map { it to it.contentDescription.toString().substringBefore(", ").trim() }
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
            // 只讀取的任務如果失敗或被停止，也要通知呼叫的人
            pendingRead?.let { cb -> pendingRead = null; cb(null) }
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
        val screenW = service.resources.displayMetrics.widthPixels
        val width = (screenW * 0.6).toInt().coerceAtLeast((280 * dp).toInt()).coerceAtMost(screenW)
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

    fun hide() {
        if (!added) return
        runCatching { wm.removeView(panel) }
        added = false
    }
}
