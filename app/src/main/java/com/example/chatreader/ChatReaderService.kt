package com.example.chatreader

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream

/**
 * 交友大師：Messenger 讀取服務（第 2 階段・整理版）
 *
 * 1. Messenger 畫面有變化時，等 1.5 秒沒有新變化才讀取（避免一直觸發）
 * 2. 依「07_Messenger節點結構.md」的規則，只挑出真正的訊息，輸出乾淨的「對方／我」對話
 * 3. 寫出的檔案（/sdcard/Android/data/com.example.chatreader/files/）：
 *    - latest_messages.txt：乾淨對話（之後送給 AI 的就是這個格式）
 *    - latest_tree.txt：完整節點樹（Messenger 改版、讀不到時拿來比對）
 *    - latest.png：截圖（Android 11 以上）
 * 4. 只讀取，不會對 Messenger 做任何操作，也不會送出訊息
 * 5. M4（2026-10-22）：圖片訊息除了輸出「[圖片]」，也記下它在螢幕上的位置（ChatSnapshot.images），
 *    讓 MessengerAgent 截圖時只裁出圖片範圍交給 AI
 * 6. 貼圖（2026-10-22 Galaxy A55 實測）：Button，desc =「已收到貼圖訊息，<Messenger 附的英文說明>」
 *    → 輸出「[貼圖：說明]」直接給 AI 看；沒有說明的貼圖才當成圖片截圖
 *
 * 看 Log：Android Studio 的 Logcat 搜尋 tag:ChatReader
 */
class ChatReaderService : AccessibilityService() {

    companion object {
        const val TAG = "ChatReader"
        const val MESSENGER = "com.facebook.orca"
        const val DEBOUNCE_MS = 1500L

        @Volatile var instance: ChatReaderService? = null
        @Volatile var lastSummary: String = "尚未讀取。請打開 Messenger 的任一聊天室。"
        var listener: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dumpRunnable = Runnable { dumpNow(withScreenshot = true) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "無障礙服務已連線")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != MESSENGER) return
        // 畫面還在變動（打字、捲動、新訊息）就重新計時
        handler.removeCallbacks(dumpRunnable)
        handler.postDelayed(dumpRunnable, DEBOUNCE_MS)
    }

    fun dumpNow(withScreenshot: Boolean) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow 是 null（畫面還沒準備好，或此視窗不允許讀取）")
            return
        }
        if (root.packageName?.toString() != MESSENGER) return

        // 用整個視窗的寬度判斷左右（橫向、直向都適用）
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val snapshot = MessengerParser.parse(root, screen.width())

        // 不在聊天室（例如聊天列表）時，不覆蓋上一次的結果
        if (snapshot.partnerName == null && snapshot.messages.isEmpty()) {
            Log.i(TAG, "目前不是聊天室畫面，略過")
            return
        }

        val summary = MessengerParser.format(snapshot)
        val tree = StringBuilder()
        dumpTree(root, 0, tree)

        val dir = getExternalFilesDir(null)
        File(dir, "latest_tree.txt").writeText(tree.toString())
        File(dir, "latest_messages.txt").writeText(summary)

        Log.i(TAG, "===== 讀取完成：${snapshot.messages.size} 則訊息 =====")
        summary.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }

        lastSummary = summary
        listener?.invoke(summary)

        if (withScreenshot) takeScreenshotTo(File(dir, "latest.png"))
    }

    /** 把整棵節點樹寫成文字（除錯用）：類別、id、text、contentDescription、位置 */
    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int, out: StringBuilder) {
        if (node == null) return
        val r = Rect()
        node.getBoundsInScreen(r)
        out.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" text=").append(node.text?.toString() ?: "-")
            .append(" desc=").append(node.contentDescription?.toString() ?: "-")
            .append(" bounds=").append(r.toShortString())
            .append(if (node.isEditable) " [輸入框]" else "")
            .append(if (node.isClickable) " [可點]" else "")
            .append('\n')
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1, out)
        }
    }

    /** Android 11（API 30）以上可直接由無障礙服務截圖，不需要螢幕錄影權限 */
    private fun takeScreenshotTo(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "截圖需要 Android 11 以上，略過")
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) {
                        Log.w(TAG, "截圖轉換失敗")
                        return
                    }
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.i(TAG, "截圖已存：${file.absolutePath}")
                }

                override fun onFailure(errorCode: Int) {
                    // 常見：1 = 內部錯誤、3 = 呼叫太頻繁；若畫面設了 FLAG_SECURE 會得到黑畫面
                    Log.w(TAG, "截圖失敗 errorCode=$errorCode")
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 以下是解析規則（對應 07_Messenger節點結構.md）
// ─────────────────────────────────────────────────────────────

/** 一則解析後的訊息 */
data class ChatMessage(
    val fromMe: Boolean,  // true = 我，false = 對方
    val nickname: String, // Messenger 顯示的暱稱（對方為名字去姓，自己如「澤」）
    val text: String,     // 訊息內容（已去掉暱稱與固定提示）
    val top: Int,         // 在螢幕上的 y 座標，用來排序
) {
    val speaker: String get() = if (fromMe) "我" else "對方"
}

/** 一張圖片訊息在螢幕上的位置（M4：截圖時用來裁切） */
data class ChatImage(
    val bounds: Rect,     // 在螢幕上的範圍
    val fromMe: Boolean,  // true = 我傳的，false = 對方傳的
)

/** 一次讀取的結果 */
data class ChatSnapshot(
    val partnerName: String?,      // 標題列的對方全名，讀不到時為 null
    val messages: List<ChatMessage>,
    val inputFound: Boolean,       // 有沒有找到輸入框
    val draft: String?,            // 輸入框裡使用者正在打的字（沒有則為 null）
    val images: List<ChatImage> = emptyList(),   // 畫面上的圖片訊息，由上到下（M4）
)

/** 依節點規則解析 Messenger 聊天室畫面。只讀取，不做任何操作。 */
object MessengerParser {

    /** 真正的訊息節點都含有這段固定提示（繁體中文介面；換語言就要改） */
    private const val HINT_MARK = "點按兩下可查看傳送／接收日期和時間"

    /** 圖片訊息的 contentDescription 關鍵字（例如「已收到相片訊息」） */
    private const val PHOTO_MARK = "相片訊息"

    /** 貼圖訊息的 contentDescription 關鍵字（例如「已收到貼圖訊息，Polar bear …」） */
    private const val STICKER_MARK = "貼圖訊息"

    /** 標題列按鈕的 contentDescription 結尾：「<對方全名>, 對話串詳情」 */
    private const val TITLE_SUFFIX = ", 對話串詳情"

    /** 最多保留最近幾則訊息 */
    const val MAX_MESSAGES = 15

    fun parse(root: AccessibilityNodeInfo, screenWidth: Int): ChatSnapshot {
        val messages = mutableListOf<ChatMessage>()
        val images = mutableListOf<ChatImage>()
        val seen = HashSet<String>()
        var partner: String? = null
        var inputFound = false
        var draft: String? = null

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val r = Rect()
            node.getBoundsInScreen(r)
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val label = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }

            when {
                // 輸入框：hint 顯示中時 text 是「發送訊息」提示，不是草稿
                node.isEditable -> {
                    inputFound = true
                    draft = if (node.isShowingHintText) null else text?.takeIf { it.isNotBlank() }
                }

                // 標題列：「Yen-Ming Chu, 對話串詳情」→ 對方全名
                partner == null && desc != null && desc.endsWith(TITLE_SUFFIX) -> {
                    partner = desc.removeSuffix(TITLE_SUFFIX).trim()
                }

                // 真正的訊息：含固定提示（引用預覽、時間、已讀等都沒有，會自動排除）
                label != null && label.contains(HINT_MARK) -> {
                    parseMessage(label, r, screenWidth)?.let { m ->
                        if (seen.add("${r.toShortString()}|${m.text}")) messages.add(m)
                    }
                }

                // 貼圖訊息：Button，contentDescription =「已收到貼圖訊息，<英文說明>」
                desc != null && desc.contains(STICKER_MARK) &&
                    node.className?.toString()?.endsWith("Button") == true -> {
                    if (seen.add("${r.toShortString()}|sticker")) {
                        val me = isFromMe(r, screenWidth)
                        val about = desc.substringAfter(STICKER_MARK).trimStart('，', ',', ' ').trim()
                        if (about.isNotEmpty()) {
                            messages.add(ChatMessage(me, "", "[貼圖：$about]", r.top))
                        } else {
                            // 沒有說明的貼圖：當成圖片，讓 M4 截圖給 AI 看
                            messages.add(ChatMessage(me, "", "[圖片]", r.top))
                            images.add(ChatImage(Rect(r), me))
                        }
                    }
                }

                // 圖片訊息：Button，contentDescription 含「相片訊息」
                desc != null && desc.contains(PHOTO_MARK) &&
                    node.className?.toString()?.endsWith("Button") == true -> {
                    if (seen.add("${r.toShortString()}|photo")) {
                        val me = isFromMe(r, screenWidth)
                        messages.add(ChatMessage(me, "", "[圖片]", r.top))
                        images.add(ChatImage(Rect(r), me))
                    }
                }
            }

            for (i in 0 until node.childCount) visit(node.getChild(i))
        }

        visit(root)

        val recent = messages.sortedBy { it.top }.takeLast(MAX_MESSAGES)
        // 只留在「最近幾則」範圍內的圖片，順序和 messages 裡的「[圖片]」一致
        val firstTop = recent.firstOrNull()?.top ?: Int.MAX_VALUE
        val recentImages = images.filter { it.bounds.top >= firstTop }.sortedBy { it.bounds.top }
        return ChatSnapshot(partner, recent, inputFound, draft, recentImages)
    }

    /** 「澤, 內容，點按兩下可查看…」→ ChatMessage；內容是空的就回傳 null */
    private fun parseMessage(label: String, r: Rect, screenWidth: Int): ChatMessage? {
        // 1. 取固定提示前面的部分，去掉結尾的「，」
        val body = label.substringBefore(HINT_MARK).trim().removeSuffix("，").trim()

        // 2. 以第一個「, 」切開：前段 = 暱稱，後段 = 內容
        val sep = body.indexOf(", ")
        val nickname = if (sep > 0) body.substring(0, sep).trim() else ""
        val rawContent = if (sep > 0) body.substring(sep + 2) else body

        // 3. 去掉每行頭尾空白與空行，多行內容用空格接起來
        val content = rawContent.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (content.isEmpty()) return null

        return ChatMessage(isFromMe(r, screenWidth), nickname, content, r.top)
    }

    /** 泡泡離右邊界比離左邊界近 = 我（自己的訊息貼右邊，對方的貼左邊） */
    private fun isFromMe(r: Rect, screenWidth: Int): Boolean =
        (screenWidth - r.right) < r.left

    /** 輸出成給人看、也給 AI 看的文字 */
    fun format(s: ChatSnapshot): String = buildString {
        appendLine("聊天對象：${s.partnerName ?: "（未知）"}")
        appendLine("讀到 ${s.messages.size} 則訊息（最多保留最近 $MAX_MESSAGES 則）")
        val inputState = when {
            !s.inputFound -> "沒找到"
            s.draft == null -> "找到（目前沒有草稿）"
            else -> "找到，草稿：${s.draft}"
        }
        appendLine("輸入框：$inputState")
        appendLine()
        s.messages.forEach { appendLine("${it.speaker}：${it.text}") }
    }
}
