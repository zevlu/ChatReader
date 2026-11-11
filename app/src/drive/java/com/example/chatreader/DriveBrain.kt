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
    val intent: String,      // appointment / go / stop / late / send / eta / save_place / share_place / joke / talk / end_trip / home / chat
    val friend: String,
    val minutes: Int?,
    val message: String,
    val reply: String,
    val note: String,        // save_place：駕駛描述的地點，例如「右邊那家咖啡廳」
    val place: String,       // go：要導航去的地點；stop：中途要先去的地點
    val dest: String = "",   // stop：最後的目的地（駕駛有說才有，沒說就沿用目前的導航）
)

/** 從聊天紀錄找到的約定 */
data class Appointment(
    val found: Boolean,
    val friend: String,
    val place: String,
    val mapQuery: String,
    val time: String,
    val timeHHmm: String,    // 約定時間 24 小時制 HH:mm，不知道為空字串（用來算會不會遲到）
    val speech: String,      // 要唸給駕駛聽的一句話
    val evidence: String = "",   // 聊天裡決定地點的那一句原文（網頁標亮「AI 從這句找到的」）
)

object DriveBrain {
    private const val TAG = "DriveBrain"

    private fun now(): String =
        SimpleDateFormat("yyyy/MM/dd（E）HH:mm", Locale.TAIWAN).format(Date())

    private val ROUTER_PROMPT = """
        你是車上的 AI 副駕駛「CarMate」的指令判斷器。駕駛正在開車，用語音對你說話；語音辨識可能有同音錯字。
        CarMate 做得到：從 Messenger 聊天找約定並導航、導航到任何地點、傳 Messenger 訊息給任何人、回報還要多久、記地點、分享地點。
        駕駛要求這些事時，一定要歸到對應的意圖，絕對不要歸到 chat，也不要回答做不到。

        從下面的意圖選一個：
        - appointment：想知道或前往跟某人約好的地點（例：「我今天跟澤約哪裡」「帶我去跟孟澤約的地方」「今天要去跟孟澤吃飯」「加完油帶我去跟孟澤約的地方」）
        - go：要導航到一個明確的地點，不是跟人約的（例：「帶我去高雄車站」「導航到最近的加油站」）
        - stop：要在路上先去另一個地方、加一個中途停靠點，或「先去 A 再去 B」（例：「先去加油站」「途中先繞去學校」「在去逢甲夜市之前可以先經過學校嗎」「先去學校再去逢甲夜市」）。【目前行程】有導航時，「我要先去／順便去 某地」都算 stop，不是 go。最後要去的是「跟某人約的地方」時用 appointment，不是 stop
        - late：要通知某人自己會遲到（例：「跟澤說我會晚十分鐘」）
        - send：要傳一般訊息給某人（例：「跟澤說我到了」）
        - eta：問還要多久、幾點會到、還有多遠、會不會遲到（例：「還要多久」「我來得及嗎」）
        - save_place：要記下現在的位置，或路上看到的店家／地點（例：「記一下右邊那家咖啡廳」「這家店幫我記起來」）
        - share_place：要把剛剛記下的地點分享給某人（例：「把剛剛那家店分享給澤」）
        - joke：想聽笑話（例：「講個笑話」）
        - talk：想聊天、說自己很無聊或想睡（例：「陪我聊聊」「好想睡」）
        - end_trip：旅程結束、要下車（例：「我到了，結束旅程」「下車了」）。注意「跟澤說我到了」是 send，不是 end_trip
        - home：要回到車機／CoDriver 主畫面、離開 Messenger 或地圖畫面（例：「回到車機」「回主畫面」「關掉這個畫面」）
        - chat：其他閒聊或問題

        欄位規則：
        - friend：對象名字，照駕駛說的寫（可以是任何人，不限聯絡人）；如果和【聯絡人】裡的名字同音或很像，改用聯絡人的寫法；沒提到對象但【目前行程】有對象，就用行程的對象；都沒有就空字串。
        - place：go 時要去的地點；stop 時是「中途先去」的那個地點（A）。給 Google 地圖搜尋用。其他意圖為空字串。
        - dest：stop 時，駕駛有說最後要去哪（B）就填，沒說就空字串。其他意圖為空字串。
        - minutes：遲到幾分鐘，數字；沒說就 null。
        - message：late 和 send 時，以「我」的口吻寫成可以直接傳出的訊息，繁體中文、台灣口語、30 字內，不要編造原因或沒說過的事實。其他意圖為空字串。
        - reply：chat 時給駕駛的簡短口語回答（30 字內，適合唸出來，不要 emoji）。其他意圖為空字串。
        - note：save_place 時，駕駛描述的地點特徵（例：「右邊那家咖啡廳」）；沒說就空字串。其他意圖為空字串。

        只輸出 JSON，不要任何說明，不要 markdown：
        {"intent":"late","friend":"澤","minutes":10,"message":"抱歉，我大概會晚 10 分鐘到 🙏","reply":"","note":"","place":"","dest":""}
    """.trimIndent()

    private val APPOINTMENT_PROMPT = """
        從 Messenger 聊天紀錄中，找出「我」和對方約好要見面的資訊。「我」是使用者，「對方」是朋友。
        規則：
        - 只能用聊天紀錄裡有的資訊，不要編造地點或時間；沒提到的欄位留空字串。
        - 如果有好幾個約定，取最新的一個。
        - map_query：給 Google 地圖搜尋用的字串，盡量包含城市或區域（聊天紀錄有提到才加）。
        - time_24h：約定時間換成 24 小時制「HH:mm」（例如晚上 7 點 →「19:00」）；聊天沒有提到時間就空字串，不要猜。
        - speech：要唸給正在開車的駕駛聽的一句話，繁體中文、口語、35 字內、不要 emoji，最後問要不要幫他導航。
          例如：「你跟澤約今晚 7 點，在駁二的大魯閣餐廳，要幫你導航嗎？」
        - 找不到約定時 found 為 false，speech 說明找不到，例如：「我在你跟澤的聊天裡沒找到約定的地點。」
        - evidence：聊天紀錄裡決定最後地點的那一句，照原文抄（不含「誰：」），找不到就空字串。

        只輸出 JSON，不要任何說明，不要 markdown：
        {"found":true,"place":"地點名稱","map_query":"高雄 駁二 地點名稱","time":"今晚 7 點","time_24h":"19:00","speech":"…","evidence":"…"}
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
            note = o.optString("note", "").trim(),
            place = o.optString("place", "").trim(),
            dest = o.optString("dest", "").trim(),
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
            timeHHmm = o.optString("time_24h", "").trim()
                .let { t -> Regex("""^(\d{1,2}):(\d{2})$""").find(t)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) } ?: "" },
            speech = o.optString("speech", "").trim()
                .ifEmpty { if (place.isNotEmpty()) "你跟${friend}約在$place，要幫你導航嗎？" else "我沒找到約定的地點。" },
            evidence = o.optString("evidence", "").trim(),
        )
    }

    /** 講一個開車時聽的短笑話；told = 這趟已經講過的，避免重複（伺服器對相同請求會回一樣的答案） */
    fun joke(told: List<String>): String {
        val system = "你是車上的 AI 副駕駛 CarMate。駕駛有點累，講一個能提神的短笑話：繁體中文、台灣口語、60 字內、" +
            "不要冒犯任何人、不要 emoji、不要解釋笑點。只輸出笑話本身。"
        val user = buildString {
            append("現在時間 ${now()}。")
            if (told.isNotEmpty()) append("這些已經講過了，換一個不一樣的：").append(told.joinToString("／"))
            else append("請講一個。")
        }
        return LlmClient.chat(system, user, temperature = 0.9).trim()
    }

    /** 陪駕駛閒聊一句；history = 最近幾輪 (駕駛, CarMate) */
    fun chat(history: List<Pair<String, String>>, text: String): String {
        val system = "你是車上的 AI 副駕駛 CarMate，正在陪開車的人聊天提神。回答要短（40 字內、最多 2 句）、" +
            "輕鬆友善、適合唸出來、不要 emoji；偶爾反問一個簡單的問題讓對方開口。不要提到你是語言模型。"
        val user = buildString {
            if (history.isNotEmpty()) {
                appendLine("之前的對話：")
                history.forEach { (u, c) -> appendLine("駕駛：$u"); appendLine("CarMate：$c") }
            }
            append("駕駛：").append(text).append("\nCarMate：")
        }
        return LlmClient.chat(system, user, temperature = 0.8).trim().removePrefix("CarMate：").trim()
    }

    /** 遲到訊息（AI 沒給時用的固定句） */
    fun lateTemplate(minutes: Int?): String =
        if (minutes != null) "抱歉，我這邊大概會晚 $minutes 分鐘到 🙏" else "抱歉，我這邊會晚一點到 🙏"
}
