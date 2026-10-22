package com.example.chatreader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * B 手機：另一支手機上跑的本地模型。
 * 兩支手機連同一個 Wi-Fi（或車機手機開熱點、模型手機連上），用 HTTP 呼叫。
 * 對方要提供 OpenAI 相容的介面：POST {位址}/chat/completions
 * （例如 llama.cpp 的 llama-server、Ollama 都有）。位址和模型名稱在 CoDriver 的「設定」裡填。
 */
object PhoneModel {
    private const val TAG = "CarMatePhone"
    private const val PREFS = "codriver"

    var baseUrl = ""        // 例如 http://192.168.43.20:8080/v1
        private set
    var model = ""          // 例如 qwen2.5-1.5b-instruct（llama-server 可以留空）
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl = p.getString("phone_base", "") ?: ""
        model = p.getString("phone_model", "") ?: ""
    }

    fun save(ctx: Context, base: String, modelName: String) {
        baseUrl = base.trim().trimEnd('/')
        model = modelName.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phone_base", baseUrl).putString("phone_model", model).apply()
    }

    fun isSet() = baseUrl.isNotBlank()

    /** 在背景執行緒呼叫；回傳模型回答的文字 */
    fun chat(system: String, user: String, temperature: Double = 0.3): String {
        if (!isSet()) throw IllegalStateException("還沒設定手機模型位址")
        val body = JSONObject().apply {
            if (model.isNotBlank()) put("model", model)
            put("temperature", temperature)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(200)}")
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim()
            Log.i(TAG, "手機模型回答：$content")
            return content
        } finally {
            conn.disconnect()
        }
    }
}

/** 車輛控制的判斷：優先用手機模型，連不上就改用雲端 */
data class CarCommand(val device: String, val action: String, val amount: Int?, val reply: String, val by: String)

object CarControlBrain {
    private val PROMPT = """
        你是車機上 AI 副駕駛的「車輛控制」模組。把駕駛的話轉成一個控制指令。
        device 只能是：冷氣、風量、車窗、後照鏡、座椅、音量、車燈、無
        action 只能是：調高、調低、打開、關閉、調整、無
        amount：要調多少（數字，例如溫度幾度）；沒說就 null。「好冷」代表要把冷氣溫度調高，「好熱」代表調低。
        reply：要唸給駕駛聽的一句話，繁體中文，20 字內。
        只輸出 JSON，不要任何說明：{"device":"冷氣","action":"調高","amount":1,"reply":"好，幫你把冷氣調高一度。"}
    """.trimIndent()

    /** 在背景執行緒呼叫 */
    fun parse(text: String): CarCommand {
        var by = "手機模型"
        val content = try {
            PhoneModel.chat(PROMPT, text, 0.1)
        } catch (e: Exception) {
            by = "雲端（手機模型連不上）"
            LlmClient.chat(PROMPT, text, temperature = 0.1)
        }
        val o = LlmClient.extractJson(content)
        return CarCommand(
            device = o.optString("device", "無"),
            action = o.optString("action", "無"),
            amount = if (o.isNull("amount")) null else o.optInt("amount").takeIf { it != 0 },
            reply = o.optString("reply", "").trim(),
            by = by,
        )
    }
}
