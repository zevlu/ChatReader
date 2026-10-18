package com.example.chatreader

import android.util.Log

/**
 * 交友大師專用：判斷對方情緒，產生三種語氣的回覆。
 * 提示詞與 JSON 格式定案於專案知識「09_提示詞與JSON格式.md」（v2，2026-10-15）。
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

        【任務】
        1. 判斷對方目前的情緒，從這些選一個：開心、興奮、平靜、無聊、疲累、焦慮、難過、沮喪、生氣、委屈。
        2. 情緒強度 1 到 5（1 很輕微，5 非常強烈）。
        3. 對方最需要的，從這些選一個：安慰、傾聽、建議、一起開心、一般回覆。
        4. 用一句話說明判斷依據。
        5. 以「我」的身分，寫三則可以直接送出的回覆，三種語氣各一則：
           - 同理安慰：先接住對方的感受
           - 輕鬆幽默：用輕鬆的方式拉近距離，不能拿對方的痛處開玩笑
           - 直接關心：直接問狀況，或提出具體的陪伴、幫忙
           如果對方沒有負面情緒，三種語氣仍然保留，但內容要配合當下氣氛（例如一起開心、正常回答問題）。

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

    override fun suggest(chatText: String): Suggestion {
        val content = LlmClient.chat(SYSTEM_PROMPT, chatText)
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
