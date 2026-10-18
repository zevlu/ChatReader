package com.example.chatreader

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarMate 的大腦：用 iAI Hub 判斷語音指令、從聊天紀錄找出約定。
 * 一定要在背景執行緒呼叫（會連網路）。
 */

/** 語音指令判斷結果 */
data class DriveIntent(
    val intent: String,      // appointment / late / send / chat
    val friend: String,
    val minutes: Int?,
    val message: String,
    val reply: String,
)

/** 從聊天紀錄找到的約定 */
data class Appointment(
    val found: Boolean,
    val friend: String,
    val place: String,
    val mapQuery: String,
    val time: String,
    val speech: String,      // 要唸給駕駛聽的一句話
)

object DriveBrain {
    private const val TAG = "DriveBrain"

    private fun now(): String =
        SimpleDateFormat("yyyy/MM/dd（E）HH:mm", Locale.TAIWAN).format(Date())

    private val ROUTER_PROMPT = """
        你是車上的 AI 副駕駛「CarMate」的指令判斷器。駕駛正在開車，用語音對你說話；語音辨識可能有同音錯字。
        從下面四種意圖選一個：
        - appointment：想知道或前往跟某人約好的地點（例：「我今天跟澤約哪裡」「帶我去跟澤吃飯的地方」）
        - late：要通知某人自己會遲到（例：「跟澤說我會晚十分鐘」）
        - send：要傳一般訊息給某人（例：「跟澤說我到了」）
        - chat：其他閒聊或問題

        欄位規則：
        - friend：對象名字。必須從【聯絡人】中選最接近的（考慮同音字）；沒提到對象但【目前行程】有對象，就用行程的對象；都沒有就空字串。
        - minutes：遲到幾分鐘，數字；沒說就 null。
        - message：late 和 send 時，以「我」的口吻寫成可以直接傳出的訊息，繁體中文、台灣口語、30 字內，不要編造原因或沒說過的事實。其他意圖為空字串。
        - reply：chat 時給駕駛的簡短口語回答（30 字內，適合唸出來，不要 emoji）。其他意圖為空字串。

        只輸出 JSON，不要任何說明，不要 markdown：
        {"intent":"late","friend":"澤","minutes":10,"message":"抱歉，我大概會晚 10 分鐘到 🙏","reply":""}
    """.trimIndent()

    private val APPOINTMENT_PROMPT = """
        從 Messenger 聊天紀錄中，找出「我」和對方約好要見面的資訊。「我」是使用者，「對方」是朋友。
        規則：
        - 只能用聊天紀錄裡有的資訊，不要編造地點或時間；沒提到的欄位留空字串。
        - 如果有好幾個約定，取最新的一個。
        - map_query：給 Google 地圖搜尋用的字串，盡量包含城市或區域（聊天紀錄有提到才加）。
        - speech：要唸給正在開車的駕駛聽的一句話，繁體中文、口語、35 字內、不要 emoji，最後問要不要幫他導航。
          例如：「你跟澤約今晚 7 點，在駁二的大魯閣餐廳，要幫你導航嗎？」
        - 找不到約定時 found 為 false，speech 說明找不到，例如：「我在你跟澤的聊天裡沒找到約定的地點。」

        只輸出 JSON，不要任何說明，不要 markdown：
        {"found":true,"place":"地點名稱","map_query":"高雄 駁二 地點名稱","time":"今晚 7 點","speech":"…"}
    """.trimIndent()

    /** 判斷語音指令 */
    fun route(utterance: String, contacts: List<String>, trip: String?): DriveIntent {
        val user = buildString {
            appendLine("【現在時間】${now()}")
            appendLine("【聯絡人】${contacts.joinToString("、").ifEmpty { "（無）" }}")
            appendLine("【目前行程】${trip ?: "（無）"}")
            append("【駕駛說】").append(utterance)
        }
        val o = LlmClient.extractJson(LlmClient.chat(ROUTER_PROMPT, user, temperature = 0.2))
        Log.i(TAG, "route：$o")
        return DriveIntent(
            intent = o.optString("intent", "chat"),
            friend = o.optString("friend", "").trim(),
            minutes = if (o.isNull("minutes")) null else o.optInt("minutes").takeIf { it > 0 },
            message = o.optString("message", "").trim(),
            reply = o.optString("reply", "").trim(),
        )
    }

    /** 從聊天紀錄找約定 */
    fun findAppointment(friend: String, chatText: String): Appointment {
        val user = "【現在時間】${now()}\n【聊天對象】$friend\n【聊天紀錄】\n$chatText"
        val o = LlmClient.extractJson(LlmClient.chat(APPOINTMENT_PROMPT, user, temperature = 0.2))
        Log.i(TAG, "appointment：$o")
        val place = o.optString("place", "").trim()
        return Appointment(
            found = o.optBoolean("found", false) && place.isNotEmpty(),
            friend = friend,
            place = place,
            mapQuery = o.optString("map_query", "").trim().ifEmpty { place },
            time = o.optString("time", "").trim(),
            speech = o.optString("speech", "").trim()
                .ifEmpty { if (place.isNotEmpty()) "你跟${friend}約在$place，要幫你導航嗎？" else "我沒找到約定的地點。" },
        )
    }

    /** 遲到訊息（AI 沒給時用的固定句） */
    fun lateTemplate(minutes: Int?): String =
        if (minutes != null) "抱歉，我這邊大概會晚 $minutes 分鐘到 🙏" else "抱歉，我這邊會晚一點到 🙏"
}
