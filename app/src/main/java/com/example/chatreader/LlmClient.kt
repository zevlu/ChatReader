package com.example.chatreader

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 共用核心：呼叫學校 iAI Hub（OpenAI 相容 API）
 *
 * 設定放在專案根目錄的 local.properties（不會上傳 GitHub）：
 *   IAI_BASE=https://xxx/v1
 *   IAI_KEY=你的金鑰
 *
 * 一定要在背景執行緒呼叫（會連網路）。
 */

/** 一則推薦回覆 */
data class ReplyOption(val style: String, val text: String, val why: String)

/** AI 的判斷結果 */
data class Suggestion(
    val emotion: String,
    val intensity: Int,
    val need: String,
    val reason: String,
    val replies: List<ReplyOption>,
)

/** 由各個 App（flavor）自己實作：給聊天紀錄，回傳建議。交友大師的實作是 FriendAdvisor。 */
interface ReplyAdvisor {
    fun suggest(chatText: String): Suggestion
}

class LlmException(msg: String) : Exception(msg)

object LlmClient {
    private const val TAG = "LlmClient"
    private const val MODEL = "qwen3.8-flash-next"

    fun isConfigured() = BuildConfig.IAI_BASE.isNotBlank() && BuildConfig.IAI_KEY.isNotBlank()

    /**
     * 送出一次對話，回傳 AI 的回答（已去掉頭尾空白）。
     * thinking = false 會關閉「先思考再回答」，實測約 2 秒（開啟約 5～12 秒）。
     */
    fun chat(system: String, user: String, temperature: Double = 0.7, thinking: Boolean = false): String {
        if (!isConfigured()) {
            throw LlmException("沒有設定 IAI_BASE／IAI_KEY：請在 local.properties 加上後按 Sync，再重新安裝")
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            if (!thinking) put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }

        val url = URL(BuildConfig.IAI_BASE.trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.IAI_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw LlmException("伺服器回應 HTTP $code：${text.take(300)}")

            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
                .trim()
            Log.i(TAG, "AI 回答：$content")
            return content
        } catch (e: UnknownHostException) {
            throw LlmException("連不到伺服器 ${url.host}，請檢查網路")
        } catch (e: SocketTimeoutException) {
            throw LlmException("連線逾時（超過 30 秒）")
        } finally {
            conn.disconnect()
        }
    }

    /** 從 AI 的回答中取出 JSON 物件：容忍前後多餘文字或被 ``` 包住 */
    fun extractJson(content: String): JSONObject {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw LlmException("AI 回覆不是 JSON：${content.take(100)}")
        return try {
            JSONObject(content.substring(start, end + 1))
        } catch (e: Exception) {
            throw LlmException("AI 回覆格式錯誤：${e.message}")
        }
    }
}
