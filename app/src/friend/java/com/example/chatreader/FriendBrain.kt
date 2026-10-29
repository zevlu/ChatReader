package com.example.chatreader

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 交友小精靈的大腦：五種 AI 任務（都要在背景執行緒呼叫）
 *
 * 1. extractFromChat：聊天裡「對方」說的事實和有日期的事 → 要記下來嗎？（第一期 ①）
 * 2. brief：聊天前小抄（第一期 ③）
 * 3. debrief：聊完復盤，看的是「我」的表現（第一期 ④）
 * 4. cheer：提醒當天，幫我想一句話（第一期 ②）
 * 5. scanProfile：Facebook 個人檔案／貼文／限動 → 名字、事實、事件、破冰話題（第二期）
 *
 * 共同原則：只記看得到的事實，不推測個性；不編造；繁體中文。
 * 提示詞定案後要更新回專案知識（15_交友小精靈.md）。
 */

/** AI 找到的一條事實（還沒存） */
data class FactDraft(val category: String, val text: String)

/** AI 找到的一件事（還沒存）；dateLabel 給人看，例如「10/31（週六）」 */
data class EventDraft(val title: String, val date: String, val kind: String, val note: String) {
    val dateLabel: String get() = FriendBrain.label(date)
}

data class Extracted(
    val name: String,
    val facts: List<FactDraft>,
    val events: List<EventDraft>,
    val icebreakers: List<String> = emptyList(),
)

data class Brief(val lines: List<String>, val ask: String)

data class Debrief(val summary: String, val good: List<String>, val tips: List<String>)

object FriendBrain {
    private const val TAG = "FriendBrain"
    private val CATEGORIES = listOf("喜好", "近況", "背景", "其他")
    private val KINDS = listOf("考試", "生日", "行程", "其他")
    private val ymd get() = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).apply { isLenient = false }

    // ───────────────────────── 日期 ─────────────────────────

    /** 今天是哪天＋接下來 21 天的星期對照（模型自己算星期幾常常算錯，直接給它查） */
    private fun dateContext(): String {
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd（E）", Locale.TAIWAN)
        val today = fmt.format(cal.time)
        val days = (1..21).joinToString("、") {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            fmt.format(cal.time)
        }
        return "今天是 $today。\n接下來的日期對照：$days"
    }

    /** 「2026-10-31」→「10/31（週六）」；看不懂就原樣回傳 */
    fun label(date: String): String = runCatching {
        SimpleDateFormat("M/d（E）", Locale.TAIWAN).format(ymd.parse(date)!!)
    }.getOrDefault(date)

    /** 日期要合法，而且在「昨天」到「一年多後」之間（過去的事不需要提醒） */
    private fun validDate(s: String): Boolean {
        val d = runCatching { ymd.parse(s) }.getOrNull() ?: return false
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        return d.time >= now - 2 * day && d.time <= now + 400 * day
    }

    // ───────────────────────── 共用規則 ─────────────────────────

    private val COMMON_RULES = """
        【共同規則】
        - 使用繁體中文與台灣口語，絕對不要出現簡體字。
        - 只根據提供的內容，不要編造、不要猜測。看不出來的就不要寫。
        - 只記事實（做了什麼、喜歡什麼、要做什麼），不要評價或推測對方的個性、心情、感情狀態。
        - 不要記敏感資訊：健康狀況、宗教、政治立場、性傾向、家庭問題、財務、住址、電話、帳號密碼。
        - 只輸出 JSON，不要加任何說明，不要用 markdown 程式碼區塊包起來，不需要呼叫任何工具。
    """.trimIndent()

    private val FACT_RULES = """
        【事實 facts】category 從「喜好、近況、背景、其他」選一個：
        - 喜好：喜歡或討厭的東西、興趣（例如「喜歡做甜點」「在學吉他」）
        - 近況：最近在忙或發生的事（例如「最近在準備微積分期中考」）
        - 背景：學校、科系、年級、家鄉、社團、打工（例如「家鄉在台南」）
        - 其他
        每條 25 字以內，寫成短句，主詞省略。最多 6 條，挑最有助於之後聊天的。
        【事件 events】有明確日期的事才算（考試、生日、比賽、表演、出遊、面試…）：
        - date 一定要換算成 yyyy-MM-dd，「週六」「下個月14號」「後天」請用上面的日期對照換算。
        - 生日只有月日時，填今天以後最近的那一次。
        - 日期不確定（例如「下次」「過陣子」）就不要放進 events，改成近況事實。
        - kind 從「考試、生日、行程、其他」選一個；title 10 字以內，例如「微積分期中考」。
        - note 可以空白，或寫一句補充（例如「很擔心會被當」不行，這是推測；「說要考第三到五章」可以）。
    """.trimIndent()

    /** 把共用規則填進提示詞（直接用字串樣板插入多行字串，trimIndent 會失效） */
    private fun String.fill() = replace("{FACT_RULES}", FACT_RULES).replace("{COMMON_RULES}", COMMON_RULES)

    // ───────────────────────── 1. 聊天記重點 ─────────────────────────

    private val EXTRACT_PROMPT = """
        你是「交友小精靈」，幫使用者記住朋友說過的事，下次聊天才接得上話。

        【輸入】Messenger 最近的聊天紀錄。「我」是使用者，「對方」是朋友。
        【任務】只從「對方」自己說的話裡，找出關於對方的事實和有日期的事件。「我」說的關於我自己的事不要記。
        如果「我」問、對方回答（例如 我：你哪天考試？ 對方：禮拜六），可以合起來理解。

        {FACT_RULES}

        {COMMON_RULES}

        【輸出】沒有值得記的就給空陣列：
        {"facts":[{"category":"喜好","text":"最近在學做甜點"}],"events":[{"title":"微積分期中考","date":"2026-10-31","kind":"考試","note":""}]}
    """.trimIndent().fill()

    fun extractFromChat(partner: String, chatText: String): Extracted {
        val user = dateContext() + "\n\n聊天對象：$partner\n\n【聊天紀錄】\n" + chatText.lines().takeLast(20).joinToString("\n")
        val o = LlmClient.extractJson(LlmClient.chat(EXTRACT_PROMPT, user, temperature = 0.2))
        return Extracted(partner, parseFacts(o), parseEvents(o))
    }

    // ───────────────────────── 2. 聊天前小抄 ─────────────────────────

    private val BRIEF_PROMPT = """
        你是「交友小精靈」，在使用者和朋友聊天前，給他一張小抄，讓他可以自然地開話題、延續上次的話題。

        【輸入】你記得關於對方的事（事實、有日期的事件），可能還有最近的聊天紀錄。
        【任務】
        1. lines：三句可以直接傳出去的開場或接話，以「我」的身分。優先順序：
           (1) 剛過去或今天的事件 → 問結果或感受（例如考完了就問考得怎樣）
           (2) 快到的事件 → 關心或加油
           (3) 對方的喜好或近況 → 問延伸問題
        2. ask：一個能讓兩人更了解彼此的開放式問題（不是是非題），要跟對方的興趣或近況有關。

        【規則】
        - 每句 30 字以內，像朋友傳訊息，可以用 0 到 1 個 emoji。
        - 多用問句，讓對方有話可以接（研究發現多問追問的人比較討人喜歡）。
        - 自然地用上記得的事就好，不要一次把記得的全部講出來，也不要讓對方覺得被調查。
        - 記得的事太少時，就給輕鬆、通用但具體的開場（例如問最近在忙什麼課）。
        - 不要提到你是 AI，也不要說「我記得你說過」這種很刻意的話。

        {COMMON_RULES}

        【輸出】
        {"lines":["第一句","第二句","第三句"],"ask":"一個開放式問題"}
    """.trimIndent().fill()

    fun brief(partner: String, memory: String, chatText: String): Brief {
        val user = buildString {
            appendLine(dateContext()); appendLine()
            appendLine("朋友：$partner")
            appendLine("【你記得的事】")
            appendLine(memory.ifBlank { "（還沒記過任何事）" })
            if (chatText.isNotBlank()) {
                appendLine(); appendLine("【最近的聊天紀錄】")
                appendLine(chatText.lines().takeLast(10).joinToString("\n"))
            }
        }
        val o = LlmClient.extractJson(LlmClient.chat(BRIEF_PROMPT, user))
        return Brief(strings(o.optJSONArray("lines")).take(3), o.optString("ask").trim())
    }

    // ───────────────────────── 3. 聊完復盤 ─────────────────────────

    private val DEBRIEF_PROMPT = """
        你是「交友小精靈」，陪使用者在聊完天之後復盤。重點是「我」（使用者）的表現和成長，不是評論對方。

        【輸入】剛剛的聊天紀錄（「我」是使用者，「對方」是朋友）和簡單統計。
        【看這幾件事】
        - 有沒有問問題、追問對方說的事（追問比換話題更能拉近距離）
        - 對方分享好消息或心情時，有沒有熱情回應並接著問（不是只回「讚」「喔」）
        - 有沒有也分享自己的事，讓對話有來有往（不是只有一方在說）
        - 有沒有記得並提到對方之前說過的事
        【任務】
        1. summary：一句話說這次聊了什麼（20 字內）。
        2. good：做得好的地方 1～2 點，要具體指出是哪一句或哪個做法。
        3. tips：下次可以試試的 1～2 點，要具體、可以馬上做（例如「她說在學做甜點，下次可以問她做了什麼」）。

        【規則】
        - 每點 40 字以內，語氣像鼓勵你的朋友，不要說教、不要打分數。
        - 不要評論或猜測對方的個性、對你的好感。
        - 聊天太短（例如只有一兩句）就照實說，tips 給一個延續話題的方法。

        {COMMON_RULES}

        【輸出】
        {"summary":"一句話","good":["…"],"tips":["…"]}
    """.trimIndent().fill()

    fun debrief(partner: String, chatText: String, stats: String): Debrief {
        val user = "朋友：$partner\n統計：$stats\n\n【聊天紀錄】\n" + chatText.lines().takeLast(20).joinToString("\n")
        val o = LlmClient.extractJson(LlmClient.chat(DEBRIEF_PROMPT, user))
        return Debrief(o.optString("summary").trim(), strings(o.optJSONArray("good")).take(2), strings(o.optJSONArray("tips")).take(2))
    }

    // ───────────────────────── 4. 提醒當天想一句 ─────────────────────────

    private val CHEER_PROMPT = """
        你是「交友小精靈」。今天是朋友的某件事（考試、生日、行程…），幫使用者想三句可以直接傳給朋友的話。

        【規則】
        - 三句語氣不同：第一句溫暖、第二句輕鬆好玩、第三句簡短直接。
        - 每句 30 字以內，可以用 0 到 1 個 emoji，像朋友傳訊息。
        - 可以自然地用上你記得的其他事（例如她喜歡甜點，生日就可以提甜點），但不要硬塞。
        - 不要提到你是 AI，不要編造沒提供的事。

        {COMMON_RULES}

        【輸出】
        {"lines":["第一句","第二句","第三句"]}
    """.trimIndent().fill()

    fun cheer(partner: String, event: FriendEvent, memory: String): List<String> {
        val user = dateContext() + "\n\n朋友：$partner\n今天的事：${event.title}（${event.kind}）" +
            (if (event.note.isNotBlank()) "，${event.note}" else "") +
            "\n\n【你記得的其他事】\n" + memory.ifBlank { "（沒有）" }
        val o = LlmClient.extractJson(LlmClient.chat(CHEER_PROMPT, user))
        return strings(o.optJSONArray("lines")).take(3)
    }

    // ───────────────────────── 5. 第二期：看 Facebook ─────────────────────────

    private val SCAN_PROMPT = """
        你是「交友小精靈」。使用者正在看一位新朋友的 Facebook（個人檔案、簡介、貼文或限時動態），
        想記住對方的事，之後聊天比較有話題。

        【輸入】畫面上讀到的文字（可能很亂，含按鈕文字、其他人的留言、廣告），和一張螢幕截圖。
        【任務】
        1. name：這個畫面主要是誰的（個人檔案的主人、貼文或限動的發文者）。看不出來就給空字串。
           不要寫成使用者自己，也不要寫成留言或按讚的其他人。
        2. facts、events：只記這個人的事（簡介、貼文內容、照片裡明顯看得出的活動）。
           照片只描述看得到的東西（例如「貼了自己做的草莓蛋糕」），不要猜地點或關係。
        3. icebreakers：三句以「我」的身分傳給對方的破冰訊息，根據 facts 或貼文內容，每句都要是問句或帶一個問題，
           30 字以內，自然不刻意（例如「看到你做的草莓蛋糕！是自己學的嗎🍓」）。

        {FACT_RULES}

        {COMMON_RULES}

        【輸出】
        {"name":"王小明","facts":[{"category":"背景","text":"國立高雄科大資工系"}],"events":[],"icebreakers":["…","…","…"]}
    """.trimIndent().fill()

    fun scanProfile(screenText: String, screenshotB64: String?): Extracted {
        val user = dateContext() + "\n\n【畫面上的文字】\n" + screenText.ifBlank { "（沒有讀到文字，請看截圖）" }
        val content = if (screenshotB64 != null) {
            LlmClient.chatWithImages(SCAN_PROMPT, user, listOf(screenshotB64), temperature = 0.3)
        } else {
            LlmClient.chat(SCAN_PROMPT, user, temperature = 0.3)
        }
        val o = LlmClient.extractJson(content)
        return Extracted(
            o.optString("name").trim(),
            parseFacts(o),
            parseEvents(o),
            strings(o.optJSONArray("icebreakers")).take(3),
        )
    }

    // ───────────────────────── 解析 ─────────────────────────

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    private fun parseFacts(o: JSONObject): List<FactDraft> {
        val a = o.optJSONArray("facts") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            val text = x.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            val cat = x.optString("category").trim().takeIf { it in CATEGORIES } ?: "其他"
            FactDraft(cat, text)
        }.distinctBy { it.text }.take(6)
    }

    private fun parseEvents(o: JSONObject): List<EventDraft> {
        val a = o.optJSONArray("events") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            val title = x.optString("title").trim()
            val date = x.optString("date").trim()
            if (title.isEmpty()) return@mapNotNull null
            if (!validDate(date)) {
                Log.w(TAG, "略過日期不合法或已過去的事件：$title $date")
                return@mapNotNull null
            }
            val kind = x.optString("kind").trim().takeIf { it in KINDS } ?: "其他"
            EventDraft(title, date, kind, x.optString("note").trim())
        }.distinctBy { it.title + it.date }
    }

    /** 今天的日期字串（yyyy-MM-dd） */
    fun today(): String = ymd.format(Date())
}
