package com.example.chatreader

import android.util.Log

/**
 * 交友大師專用：判斷對方情緒，產生三種語氣的回覆。
 * 提示詞與 JSON 格式見專案知識「09_提示詞與JSON格式.md」。
 * v3（2026-10-22 定案）：只回最新的訊息（舊話題只當背景）；對方問問題或要推薦時，三則都要真的回答。
 * v3.1（2026-10-22）：加一行貼圖說明「[貼圖：…]」的解讀規則。
 * M4（2026-10-22，測試中）：有圖片時改用 suggestWithImages，提示詞後面追加 IMAGE_RULES。
 * 修改提示詞前，先用 prompt_test.ps1 在電腦上測過。
 */
object FriendAdvisor : ReplyAdvisor {

    private val STYLES = listOf("同理安慰", "輕鬆幽默", "直接關心")

    /** 測試用的對話（「測試 AI 連線」按鈕用） */
    val SAMPLE_CHAT = """
        對方：今天報告被老師電爆
        對方：覺得自己好廢
        我：怎麼了？
        對方：老師說我資料都沒整理，全班都在看
    """.trimIndent()

    private val SYSTEM_PROMPT = """
        你是「交友大師」，幫使用者回覆 Messenger 上朋友的訊息。

        【輸入】最近的聊天紀錄。「我」是使用者，「對方」是朋友。「[圖片]」代表一張圖片。
        「[貼圖：…]」是對方傳的貼圖，冒號後面是貼圖的說明（可能是英文），代表對方想用貼圖表達的情緒或動作，請把它當成對方說的話來判斷。
        最後會附上【現在要回的訊息】：你的情緒判斷和回覆都以它為主。更早的訊息只當背景；話題已經換了，就不要再提舊話題裡的事（例如之前的約、地點、行程）。

        【任務】
        1. 判斷對方目前的情緒，從這些選一個：開心、興奮、平靜、無聊、疲累、焦慮、難過、沮喪、生氣、委屈。
        2. 情緒強度 1 到 5（1 很輕微，5 非常強烈）。
        3. 對方最需要的，從這些選一個：安慰、傾聽、建議、一起開心、回答問題、一般回覆。
        4. 用一句話說明判斷依據。
        5. 以「我」的身分，寫三則可以直接送出的回覆，三種語氣各一則：
           - 同理安慰：先接住對方的感受
           - 輕鬆幽默：用輕鬆的方式拉近距離，不能拿對方的痛處開玩笑
           - 直接關心：直接問狀況，或提出具體的陪伴、幫忙
           如果對方沒有負面情緒，三種語氣仍然保留，但內容要配合當下氣氛（例如一起開心）。
           如果對方在問問題或請你推薦（例如「吃什麼」「去哪裡」「怎麼辦」），三則都要真的回答，給具體的選項或建議，只是語氣不同：
           同理安慰＝先回應對方的感受再給建議；輕鬆幽默＝用好玩的方式推薦；直接關心＝直接給 2～3 個具體選項，或問一個能幫對方決定的問題。
           一般常識可以直接給（例如食物種類：拉麵、火鍋、便當）；只有「我」才知道的事（我在哪、預算、要不要一起去）才用【】留空。

        【回覆規則】
        - 使用繁體中文與台灣口語，絕對不要出現簡體字；像朋友傳訊息，不要像客服或老師。
        - 每則 40 字以內，最多 2 句，可以用 0 到 1 個 emoji。
        - 不要說教，不要空泛的安慰（例如「多喝熱水」「想開一點」「加油就好」）。
        - 不要編造聊天紀錄裡沒有的事實。只有「我」才知道的資訊（時間、地點、行程、要不要去、具體數字），一律用【】留空讓使用者自己填，例如「我【幾點】到」「【要／不要】一起去」，不要自己編答案。
        - 不要提到你是 AI。
        - replies 剛好三則，style 依序是「同理安慰」「輕鬆幽默」「直接關心」，不能多也不能少，不能重複。

        【輸出】只輸出下面格式的 JSON，不要加任何說明，不要用 markdown 程式碼區塊包起來，不需要呼叫任何工具：
        {"emotion":"沮喪","intensity":4,"need":"安慰","reason":"判斷依據","replies":[{"style":"同理安慰","text":"回覆內容","why":"20 字內說明為什麼這樣回"},{"style":"輕鬆幽默","text":"回覆內容","why":"…"},{"style":"直接關心","text":"回覆內容","why":"…"}]}
    """.trimIndent()

    /**
     * 只留最後 12 行，並標出「現在要回的訊息」：
     * 對方最後一段連續的訊息（之後如果「我」已經回過，還是以對方最後那段為主）。
     */
    private fun withFocus(chatText: String): String {
        val lines = chatText.lines().filter { it.isNotBlank() }.takeLast(12)
        val lastThem = lines.indexOfLast { it.startsWith("對方：") }
        if (lastThem < 0) return lines.joinToString("\n")
        var first = lastThem
        while (first > 0 && lines[first - 1].startsWith("對方：")) first--
        val focus = lines.subList(first, lastThem + 1).joinToString("\n")
        return lines.joinToString("\n") + "\n\n【現在要回的訊息】\n" + focus
    }

    /** M4：有圖片時追加在系統提示詞後面的規則 */
    private val IMAGE_RULES = """

        【圖片】
        - 這次另外附上聊天裡最近的圖片，依序對應聊天紀錄中的「[圖片1]」「[圖片2]」…。
        - 先看懂圖片內容（照片的場景、人的表情、貼圖或梗圖想表達的情緒、圖片裡的文字），把它納入情緒判斷。
        - reason 裡用一句話提到圖片內容，例如「對方傳了考卷 100 分的照片，很開心」。
        - 回覆可以提到圖片裡的東西，讓人感覺你真的有看圖；看不清楚或不確定的就不要亂猜，也不要描述得太細。
    """.trimIndent()

    override fun suggest(chatText: String): Suggestion =
        parse(LlmClient.chat(SYSTEM_PROMPT, withFocus(chatText)))

    /** M4：把最後幾個「[圖片]」編號成「[圖片1]」「[圖片2]」…，和送出的圖片一一對應 */
    override fun suggestWithImages(chatText: String, images: List<String>): Suggestion {
        if (images.isEmpty()) return suggest(chatText)
        val lines = chatText.lines().toMutableList()
        val idx = lines.indices.filter { lines[it].endsWith("：[圖片]") }.takeLast(images.size)
        idx.forEachIndexed { k, i -> lines[i] = lines[i].replace("[圖片]", "[圖片${k + 1}]") }
        val text = withFocus(lines.joinToString("\n"))
        return parse(LlmClient.chatWithImages(SYSTEM_PROMPT + "\n" + IMAGE_RULES, text, images))
    }

    private fun parse(content: String): Suggestion {
        Log.i("FriendAdvisor", "原始回覆：$content")
        val o = LlmClient.extractJson(content)
        val arr = o.optJSONArray("replies") ?: throw LlmException("AI 回覆少了 replies")

        // 防呆：每種語氣只取第一則（曾出現重複的第 4 則）
        val byStyle = linkedMapOf<String, ReplyOption>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val style = r.optString("style").trim()
            val text = r.optString("text").trim()
            if (text.isEmpty() || style in byStyle) continue
            byStyle[style] = ReplyOption(style, text, r.optString("why").trim())
        }
        val ordered = STYLES.mapNotNull { byStyle[it] }.ifEmpty { byStyle.values.toList() }.take(3)
        if (ordered.isEmpty()) throw LlmException("AI 沒有給任何回覆")

        return Suggestion(
            emotion = o.optString("emotion", "未知"),
            intensity = o.optInt("intensity", 0),
            need = o.optString("need", ""),
            reason = o.optString("reason", ""),
            replies = ordered,
        )
    }
}
