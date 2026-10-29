package com.example.chatreader

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 泡泡選單裡每個按鈕要做的事（從主執行緒呼叫）
 *
 * Messenger 聊天室：💬 幫我回、📋 聊天前小抄、📌 記下重點、🪞 聊完復盤
 * Facebook／Instagram：🔍 看看這個人
 *
 * 原則：只在使用者點的時候讀「目前畫面」；AI 找到的東西一律先給使用者勾選才存。
 */
object CompanionActions {
    private const val TAG = "Companion"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var busy = false
        private set

    private data class ChatRead(val partner: String, val chatText: String, val snapshot: ChatSnapshot)

    /** 讀 Messenger 目前的聊天室；不在聊天室回傳 null */
    private fun readChat(service: ChatReaderService): ChatRead? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != ChatReaderService.MESSENGER) return null
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val s = MessengerParser.parse(root, screen.width())
        val partner = s.partnerName ?: return null
        val text = s.messages.joinToString("\n") { "${it.speaker}：${it.text}" }
        return ChatRead(partner, text, s)
    }

    // ───────────────────────── 💬 幫我回（M2 ＋ 記憶） ─────────────────────────

    fun replyHelp(service: ChatReaderService): Boolean {
        MemoryStore.init(service)
        val partner = readChat(service)?.partner
        val person = partner?.let { MemoryStore.find(it) }
        // 有記過這個人 → 回覆建議會自然地用上（例如記得她週六要考試）
        FriendAdvisor.memoryHint = MemoryStore.memoryText(person)
        return MessengerAgent.startReply("", FriendAdvisor)
    }

    // ───────────────────────── 📌 記下重點 ─────────────────────────

    fun remember(service: ChatReaderService) {
        val chat = readChat(service) ?: return FriendBubble.say("先打開 Messenger 的聊天室，再點我")
        if (chat.snapshot.messages.none { !it.fromMe }) return FriendBubble.say("畫面上還沒有對方的訊息")
        work(service, "小精靈在找重點…") {
            val found = FriendBrain.extractFromChat(chat.partner, chat.chatText)
            if (found.facts.isEmpty() && found.events.isEmpty()) {
                Payload.Message("🧚 這次沒有要記的", "畫面上這段聊天，沒看到對方說關於自己的事或有日期的事。\n（小精靈只記對方自己說的事實，不會記你的事，也不會猜對方的個性。）")
            } else {
                Payload.Review("Messenger 聊天", found)
            }
        }
    }

    // ───────────────────────── 📋 聊天前小抄 ─────────────────────────

    fun brief(service: ChatReaderService) {
        val chat = readChat(service) ?: return FriendBubble.say("先打開 Messenger 的聊天室，再點我")
        MemoryStore.init(service)
        val person = MemoryStore.find(chat.partner)
        val remembered = person?.let { p ->
            p.facts.map { "【${it.category}】${it.text}" } +
                p.events.sortedBy { it.date }.map { "${FriendBrain.label(it.date)} ${it.title}" }
        } ?: emptyList()
        val memory = MemoryStore.memoryText(person)
        work(service, "小精靈在準備小抄…") {
            Payload.BriefCard(person?.name ?: chat.partner, remembered, FriendBrain.brief(chat.partner, memory, chat.chatText))
        }
    }

    // ───────────────────────── 🪞 聊完復盤 ─────────────────────────

    fun debrief(service: ChatReaderService) {
        val chat = readChat(service) ?: return FriendBubble.say("先打開 Messenger 的聊天室，再點我")
        val msgs = chat.snapshot.messages
        val mine = msgs.filter { it.fromMe }
        if (mine.isEmpty()) return FriendBubble.say("畫面上還沒有你傳的訊息，聊幾句再來復盤")
        val theirs = msgs.size - mine.size
        val questions = mine.count { isQuestion(it.text) }
        val stats = "畫面上最近 ${msgs.size} 則：你傳了 ${mine.size} 則、對方 $theirs 則，你問了 $questions 個問題"
        MemoryStore.init(service)
        work(service, "小精靈在復盤…") {
            val result = FriendBrain.debrief(chat.partner, chat.chatText, stats)
            MemoryStore.addDebrief(DebriefLog(System.currentTimeMillis(), chat.partner, mine.size, theirs, questions, result.summary))
            Payload.DebriefCard(chat.partner, stats, growthText(), result)
        }
    }

    /** 問句：有問號，或用「嗎、呢」結尾 */
    fun isQuestion(s: String): Boolean {
        val t = s.trim().trimEnd('!', '！', '~', '～', ' ')
        return t.contains('?') || t.contains('？') || t.endsWith("嗎") || t.endsWith("呢")
    }

    /** 我的成長：最近幾次復盤的平均問句數 */
    fun growthText(): String {
        val logs = MemoryStore.allDebriefs()
        if (logs.size < 2) return "這是你第 ${logs.size} 次復盤。多復盤幾次，這裡會顯示你的進步。"
        val recent = logs.takeLast(3)
        val earlier = logs.dropLast(3).takeLast(3)
        val avgRecent = recent.map { it.myQuestions }.average()
        return buildString {
            append("你已經復盤 ${logs.size} 次，和 ${logs.map { it.person }.distinct().size} 位朋友。")
            append("最近 ${recent.size} 次平均每次問 ${"%.1f".format(avgRecent)} 個問題")
            if (earlier.isNotEmpty()) {
                val avgEarlier = earlier.map { it.myQuestions }.average()
                append(if (avgRecent > avgEarlier) "，比之前（${"%.1f".format(avgEarlier)}）多了 👏" else "（之前 ${"%.1f".format(avgEarlier)}）")
            }
            append("。")
        }
    }

    // ───────────────────────── 🔍 第二期：看看這個人（Facebook／Instagram） ─────────────────────────

    fun scan(service: ChatReaderService) {
        val root = service.rootInActiveWindow ?: return FriendBubble.say("讀不到畫面，等一下再試")
        val pkg = root.packageName?.toString()
        val source = if (pkg == FriendBubble.INSTAGRAM) "Instagram" else "Facebook"
        val text = ScreenTools.visibleText(root)
        ScreenTools.dumpForDebug(service, root, text)
        Log.i(TAG, "讀到 ${text.length} 字（存在 fb_text.txt）")
        work(service, "小精靈在看…（約 5～15 秒）") {
            // 截圖前先把泡泡藏起來，免得擋到畫面
            main.post { FriendBubble.setVisible(false) }
            Thread.sleep(400)
            val shot = try {
                ScreenTools.screenshotSync(service) ?: run { Thread.sleep(1000); ScreenTools.screenshotSync(service) }
            } finally {
                main.post { FriendBubble.setVisible(true) }
            }
            if (shot == null) Log.w(TAG, "截圖失敗，只用文字")
            val b64 = shot?.let { LlmClient.encodeJpeg(it, maxSide = 1280, quality = 75) }
            val found = FriendBrain.scanProfile(text, b64)
            if (found.facts.isEmpty() && found.events.isEmpty() && found.icebreakers.isEmpty()) {
                Payload.Message("🧚 這個畫面沒看到什麼", "在個人檔案的「簡介」、貼文或限時動態上再點我試試。")
            } else {
                Payload.Review(source, found)
            }
        }
    }

    // ───────────────────────── 共用 ─────────────────────────

    /** 在背景跑 AI，跑完打開小精靈視窗；失敗也用視窗告訴使用者 */
    private fun work(service: ChatReaderService, hint: String, job: () -> Payload) {
        if (busy) { FriendBubble.say("小精靈還在想上一件事…"); return }
        busy = true
        FriendBubble.setWorking(true)
        FriendBubble.say(hint)
        Thread({
            val payload = try {
                job()
            } catch (e: LlmException) {
                Payload.Message("🧚 出了點問題", "AI 呼叫失敗：${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "執行失敗", e)
                Payload.Message("🧚 出了點問題", "發生錯誤：${e.message}\n（Logcat 搜尋 tag:Companion）")
            }
            main.post {
                busy = false
                FriendBubble.setWorking(false)
                CompanionActivity.open(service, payload)
            }
        }, "Companion").start()
    }
}
