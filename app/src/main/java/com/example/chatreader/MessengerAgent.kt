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
 * 交友大師 M1：動作層（不接 LLM）— v2
 *
 * 文字指令 → 自動開啟 Messenger → 找到聊天室 → 填入訊息 → 懸浮窗確認 → 使用者按「送出」才傳送
 *
 * v2 修正：
 * - 遇到跳出視窗（例如「開啟通知」）會找「稍後再說／略過／關閉…」按掉，不再一直按返回
 * - 按不掉的畫面會停下來等你手動處理 15 秒，並把畫面結構存成 agent_unknown.txt
 * - 填字前先點輸入框（Messenger 要輸入框有焦點又有字，「傳送」箭頭才會出現）
 * - 送出時找不到「傳送」會先重新點輸入框；字被清空就重新填入同一句（你確認過的內容）
 * - 不小心離開 Messenger 會自動重新打開（最多 2 次）
 * - 失敗時自動存 agent_fail.txt，方便除錯
 *
 * 安全原則：
 * - 只有使用者在懸浮窗按「送出」，程式才會按 Messenger 的「傳送」鍵
 * - 只認 contentDescription 完全等於「傳送」的按鈕；「發送👍」絕對不碰
 * - 送出前再檢查：還在同一個聊天室、輸入框沒有被改成別的內容
 */

/** 解析後的指令 */
data class SendCommand(val target: String, val message: String)

/** 把文字指令拆成「對象」和「訊息」 */
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

/** 執行 Messenger 操作的 Agent（目前步驟固定，之後 M2 會換成由 LLM 決定下一步） */
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

    /** 進度回報給 MainActivity（在主執行緒呼叫） */
    var onStatus: ((String) -> Unit)? = null

    /** 這次任務的所有進度紀錄（只在主執行緒修改） */
    val history = mutableListOf<String>()

    @Volatile var busy = false
        private set
    @Volatile private var cancelled = false

    private val main = Handler(Looper.getMainLooper())
    private var overlay: AgentOverlay? = null      // 只在主執行緒存取

    private class Stopped : RuntimeException()

    // ───────────────────────── 對外介面 ─────────────────────────

    /** 開始任務（從主執行緒呼叫）。回傳 false 代表沒有開始。 */
    fun start(cmd: SendCommand): Boolean {
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
        Thread({ runFill(service, cmd) }, "MessengerAgent").start()
        return true
    }

    /** 停止任務（任何時候都可以按） */
    fun cancel() {
        if (!busy) return
        cancelled = true
        finish("⏹ 已停止")
    }

    // ───────────────────────── 第一段：開啟、找聊天室、填字 ─────────────────────────

    private fun runFill(service: ChatReaderService, cmd: SendCommand) {
        try {
            report("① 等待 Messenger 開啟…")
            if (!waitFor(8000) { root(service)?.packageName?.toString() == PKG }) {
                return fail("等了 8 秒還沒看到 Messenger")
            }

            var title: String? = null
            var relaunches = 0
            for (step in 1..MAX_STEPS) {
                sleep(600)
                val root = root(service) ?: continue

                // 跑出 Messenger 了 → 重新打開
                if (root.packageName?.toString() != PKG) {
                    if (relaunches >= MAX_RELAUNCH) return fail("一直離開 Messenger，任務中止")
                    relaunches++
                    report("離開了 Messenger，重新打開（$relaunches/$MAX_RELAUNCH）")
                    relaunchMessenger(service)
                    sleep(1500)
                    continue
                }

                val chatTitle = findChatTitle(root)
                when {
                    chatTitle != null && nameMatches(chatTitle, cmd.target) -> {
                        title = chatTitle
                        report("③ 已在「$chatTitle」的聊天室")
                        break
                    }
                    chatTitle != null -> {
                        report("目前在「$chatTitle」的聊天室，先返回")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        sleep(1000)
                    }
                    isHome(root) -> {
                        report("② 在聊天列表找「${cmd.target}」…")
                        if (!openFromList(service, cmd.target)) {
                            return fail("聊天列表裡找不到「${cmd.target}」（往下捲了 $MAX_SCROLLS 次）")
                        }
                        sleep(1200)
                    }
                    else -> handleUnknownScreen(service, root)
                }
            }
            val chatTitle = title ?: return fail("試了 $MAX_STEPS 次還是沒進到「${cmd.target}」的聊天室")

            // 填入訊息
            report("④ 填入訊息…")
            if (!fillMessage(service, cmd.message)) return   // fillMessage 失敗時已經 fail() 過

            report("⑤ 已填入，等你確認")
            main.post {
                overlay?.showConfirm(
                    chatTitle, cmd.message,
                    onSend = {
                        overlay?.showRunning("送出中…", null)   // 先鎖住，避免連按兩次
                        Thread({ runSend(service, cmd, chatTitle) }, "MessengerAgentSend").start()
                    },
                    onCancel = { finish("已取消：文字留在輸入框，你可以自己修改或送出") },
                )
            }
        } catch (e: Stopped) {
            // 使用者按了停止，finish() 已經處理過
        } catch (e: FailException) {
            fail(e.message ?: "失敗")
        } catch (e: Exception) {
            Log.e(TAG, "執行失敗", e)
            fail("發生錯誤：${e.message}")
        }
    }

    /**
     * 不是聊天列表、也不是聊天室：
     * 1. 先找「稍後再說／略過／關閉…」按掉
     * 2. 找不到就停下來等使用者手動處理（不再盲目按返回）
     */
    private fun handleUnknownScreen(service: ChatReaderService, root: AccessibilityNodeInfo) {
        val dismiss = findDismissButton(root)
        if (dismiss != null) {
            val label = labelOf(dismiss)
            report("跳出了視窗，幫你按「$label」")
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
     * 成功回傳 true；失敗會呼叫 fail() 並回傳 false
     */
    private fun fillMessage(service: ChatReaderService, message: String): Boolean {
        val root = root(service) ?: run { fail("讀不到畫面"); return false }
        val edit = findEdit(root) ?: run { fail("找不到輸入框"); return false }
        if (!edit.isShowingHintText && !edit.text.isNullOrBlank() && edit.text.toString() != message) {
            report("（輸入框原本有草稿，會被取代）")
        }
        focusEdit(edit)
        sleep(500)

        val edit2 = root(service)?.let { findEdit(it) } ?: run { fail("點輸入框後找不到它"); return false }
        if (!setText(edit2, message)) { fail("填字失敗（SET_TEXT 回傳 false）"); return false }
        sleep(800)

        // 第一次沒看到「傳送」，再點一次輸入框試試
        repeat(2) { attempt ->
            val r = root(service) ?: return@repeat
            val e = findEdit(r)
            if (e == null || e.isShowingHintText || e.text?.toString() != message) {
                dumpTree(service, r, "agent_fail.txt")
                fail("填字後檢查不符：輸入框目前是「${e?.text}」（已存 agent_fail.txt）")
                return false
            }
            if (findSendButton(r) != null) return true
            if (attempt == 0) {
                report("還沒看到「傳送」箭頭，重新點一下輸入框")
                focusEdit(e)
                sleep(700)
            } else {
                dumpTree(service, r, "agent_fail.txt")
            }
        }
        fail("訊息已填入，但沒看到「傳送」鍵（已存 agent_fail.txt）")
        return false
    }

    // ───────────────────────── 第二段：使用者按「送出」後 ─────────────────────────

    private fun runSend(service: ChatReaderService, cmd: SendCommand, chatTitle: String) {
        try {
            var root = root(service) ?: return fail("讀不到畫面，沒有送出")
            if (findChatTitle(root) != chatTitle) {
                return fail("畫面已經不是「$chatTitle」的聊天室，為了安全沒有送出")
            }

            // 最多試 3 次：確認輸入框內容 → 找「傳送」
            var send: AccessibilityNodeInfo? = null
            for (attempt in 1..3) {
                val edit = findEdit(root) ?: return fail("找不到輸入框，沒有送出")
                val current = if (edit.isShowingHintText) "" else edit.text?.toString().orEmpty()
                when {
                    current == cmd.message -> {}
                    current.isEmpty() -> {
                        // 輸入框被清空了 → 重新填入「你確認過的同一句」
                        report("輸入框被清空了，重新填入")
                        focusEdit(edit); sleep(400)
                        val e2 = root(service)?.let { findEdit(it) } ?: return fail("找不到輸入框，沒有送出")
                        setText(e2, cmd.message); sleep(700)
                    }
                    else -> return fail("輸入框被改成「$current」，和確認的內容不同，為了安全沒有送出")
                }
                root = root(service) ?: return fail("讀不到畫面，沒有送出")
                send = findSendButton(root)
                if (send != null) break

                report("沒看到「傳送」箭頭，重新點輸入框（$attempt/3）")
                findEdit(root)?.let { focusEdit(it) }
                sleep(700)
                root = root(service) ?: return fail("讀不到畫面，沒有送出")
            }
            if (send == null) {
                dumpTree(service, root, "agent_fail.txt")
                return fail("找不到「傳送」鍵，沒有送出（已存 agent_fail.txt）")
            }

            // 最後再確認一次還在同一個聊天室
            if (findChatTitle(root) != chatTitle) return fail("聊天室變了，為了安全沒有送出")
            if (!send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return fail("按「傳送」失敗")
            }
            sleep(1200)
            val after = root(service)?.let { findEdit(it) }
            val cleared = after == null || after.isShowingHintText || after.text.isNullOrEmpty()
            finish(if (cleared) "✅ 已送出給「$chatTitle」" else "⚠️ 已按下傳送，但輸入框還有字，請看一下 Messenger")
        } catch (e: Stopped) {
        } catch (e: FailException) {
            fail(e.message ?: "失敗")
        } catch (e: Exception) {
            Log.e(TAG, "送出失敗", e)
            fail("發生錯誤：${e.message}")
        }
    }

    private class FailException(msg: String) : RuntimeException(msg)

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
 * 設為不可取得焦點，所以不會搶走 Messenger 輸入框的焦點。
 */
private class AgentOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val dp = service.resources.displayMetrics.density
    private val status = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
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
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(btnNo)
            addView(btnYes)
        })
    }
    private var added = false

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
        if (btnYes.visibility != View.VISIBLE) status.text = "🤖 $msg"
    }

    /** 執行中：顯示進度，可選擇顯示「停止」 */
    fun showRunning(msg: String, onStop: (() -> Unit)?) {
        status.text = "🤖 $msg"
        btnYes.visibility = View.GONE
        if (onStop != null) {
            btnNo.text = "停止"
            btnNo.visibility = View.VISIBLE
            btnNo.setOnClickListener { onStop() }
        } else {
            btnNo.visibility = View.GONE
        }
    }

    /** 等待確認：顯示要送出的內容，只有按「送出」才會傳送 */
    fun showConfirm(title: String, message: String, onSend: () -> Unit, onCancel: () -> Unit) {
        status.text = "要傳給「$title」嗎？\n\n$message"
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