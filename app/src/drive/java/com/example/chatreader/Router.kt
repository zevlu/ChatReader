package com.example.chatreader

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 路由器：每一句指令先問 System One 決策模型「這件事要交給誰」。
 *  - B 手機：操作車子這類簡單、要即時的事（冷氣、後照鏡、車窗…）→ 手機本地模型
 *  - A 雲端：操作 App、需要規劃判斷的事（找約定、導航、傳訊息…）→ 雲端大型語言模型
 * System One 連不上時，改用關鍵字規則判斷，流程不會卡住。
 *
 * 設定放在 local.properties（不會上傳 GitHub）：
 *   SYS1_URL=https://…/v1/systemone
 *   SYS1_KEY=你的金鑰
 */
object Router {
    private const val TAG = "CarMateRouter"
    private const val MODEL = "Microsoft-Decision-1"

    enum class Route { PHONE, CLOUD }

    data class Decision(val route: Route, val ms: Long, val source: String, val detail: String)

    /** 給決策模型的背景說明（照組員實測過的寫法） */
    private val BACKGROUND = """
        你是一個AI Agent副駕駛系統，你會遇到很多問題，目前有兩類，第一類：幫忙駕駛調整車輛，例如調整冷氣、後照鏡、這些比較簡單操控的問題，另外第二類：比較複雜需要思考規劃判斷的問題要處理，例如我剛上車的時候跟他說想約的對象是孟澤，它會幫我打開messenger查約在哪裡幾點，幫我打開地圖問我說要不要開始導航，導航開始的時候我可以問 "副駕" 它距離目的地還要多久，它可以即時幫我看，我跟他說 "副駕" 幫我通知孟澤可能會遲到，它可以幫我轉到messsenger的畫面並輸入問我要不要送出。這兩大類的判斷標準是，操作車子的幫我用手機本地(沒有即時會感覺服務很差)，操作APP的就跑雲端，可接受稍微延遲。
    """.trimIndent()

    /** System One 連不上時的備用規則：講到車上設備就走手機 */
    private val CAR_WORDS = listOf(
        "冷氣", "空調", "溫度", "好冷", "好熱", "太冷", "太熱", "風量", "後照鏡", "車窗", "窗戶", "天窗",
        "座椅", "椅子", "音量", "大聲", "小聲", "車燈", "大燈", "雨刷", "除霧",
    )

    fun isConfigured() = BuildConfig.SYS1_URL.isNotBlank() && BuildConfig.SYS1_KEY.isNotBlank()

    /** 在背景執行緒呼叫 */
    fun decide(task: String): Decision {
        val t0 = System.currentTimeMillis()
        lastNoul = null
        if (!isConfigured()) return rule(task, t0, "規則（未設定 System One）")
        return try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("state", "background: $BACKGROUND\n任務: $task")
                put("questions", JSONObject().put("路由", JSONObject().apply {
                    put("type", "noul")
                    put("instructions", "是否需要使用雲端模型")
                    put("criteria", JSONObject().put("true", "雲端").put("false", "手機"))
                }))
            }
            val conn = URL(BuildConfig.SYS1_URL).openConnection() as HttpURLConnection
            val text = try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SYS1_KEY}")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val s = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code：${s.take(200)}")
                s
            } finally {
                conn.disconnect()
            }
            Log.i(TAG, "System One 回應：$text")
            val cloud = parseCloud(JSONObject(text))
                ?: return rule(task, t0, "規則（System One 格式不符）")
            val p = lastNoul?.let { "雲端機率 %.2f".format(it) } ?: ""
            Decision(if (cloud) Route.CLOUD else Route.PHONE, System.currentTimeMillis() - t0, "System One $p".trim(), text.take(200))
        } catch (e: Exception) {
            Log.w(TAG, "System One 失敗，改用規則", e)
            rule(task, t0, "規則（System One 連不上）")
        }
    }

    private fun rule(task: String, t0: Long, source: String): Decision {
        val phone = CAR_WORDS.any { task.contains(it) }
        return Decision(if (phone) Route.PHONE else Route.CLOUD, System.currentTimeMillis() - t0, source, "")
    }

    /**
     * System One 實際回應（2026-10-19 組員實測）：
     * {"model":"microsoft-decision-1",
     *  "answers":{"是否需要使用雲端模型":{"type":"noul","noul":0.0067}},
     *  "usage":{...}}
     * noul = 答案是 true（雲端）的機率，≥ 0.5 走雲端，否則走手機。
     * answers 底下的名稱可能是題目名稱「路由」或 instructions 文字，所以直接拿第一題。
     * 找不到回傳 null（改用規則）。
     */
    private fun parseCloud(o: JSONObject): Boolean? {
        val p = noulOf(o) ?: return null
        lastNoul = p
        return p >= 0.5
    }

    /** 最近一次的雲端機率，畫面上顯示用 */
    @Volatile var lastNoul: Double? = null
        private set

    private fun noulOf(o: JSONObject): Double? {
        val answers = o.optJSONObject("answers")
        if (answers != null) {
            val first = answers.opt("路由") ?: answers.keys().asSequence().firstOrNull()?.let { answers.opt(it) }
            when (first) {
                is JSONObject -> if (first.has("noul")) return first.optDouble("noul")
                is Number -> return first.toDouble()
            }
        }
        // 格式和上面不同時，盡量寬鬆地找
        val node = findKey(o, "noul") ?: findKey(o, "路由") ?: return null
        if (node is Number) return node.toDouble()
        return when (interpret(node)) { true -> 1.0; false -> 0.0; null -> null }
    }

    private fun interpret(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() >= 0.5
        is String -> when {
            v.contains("雲端") || v.equals("true", true) -> true
            v.contains("手機") || v.equals("false", true) -> false
            else -> v.toDoubleOrNull()?.let { it >= 0.5 }
        }
        is JSONObject -> {
            val keys = listOf("answer", "value", "result", "selected", "choice", "label", "output", "probability", "score", "p")
            keys.firstNotNullOfOrNull { k -> if (v.has(k)) interpret(v.opt(k)) else null }
        }
        is JSONArray -> if (v.length() > 0) interpret(v.opt(0)) else null
        else -> null
    }

    private fun findKey(v: Any?, key: String): Any? {
        when (v) {
            is JSONObject -> {
                if (v.has(key)) return v.opt(key)
                val it = v.keys()
                while (it.hasNext()) findKey(v.opt(it.next()), key)?.let { found -> return found }
            }
            is JSONArray -> for (i in 0 until v.length()) findKey(v.opt(i), key)?.let { return it }
        }
        return null
    }
}
