package com.example.chatreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 來源切換（2026-11-17）：不用重新安裝，App 裡就能切換。
 *  - iAI：學校 iAI Hub（local.properties 的 IAI_BASE／IAI_KEY），平常測試用
 *  - DGX：DGX Spark 上的 codriver-llm（OpenAI 相容），記者會展示用
 *    手機要能連到 127.0.0.1:8300：
 *      手機 USB 直接接 DGX → 在 DGX 執行 adb reverse tcp:8300 tcp:8300
 *      手機接筆電（遠端測試）→ 筆電 adb reverse tcp:8300 tcp:8300，再用 ssh -L 8300 轉到 DGX
 *
 * 切換方式：點上方的「AI」狀態燈，或在「設定」按「AI 來源」按鈕。選擇會記住（SharedPreferences）。
 * friend 版本不會呼叫這裡，LlmClient.endpointOverride 一直是 null，行為不變。
 */
object AiSource {
    private const val PREFS = "codriver"
    const val DEFAULT_DGX_BASE = "http://127.0.0.1:8300/v1"
    const val DEFAULT_DGX_MODEL = "Qwen3.6-35B-A3B-FP8"   // 2026-11-18 DGX 的 codriver-llm

    var useDgx = false
        private set
    var dgxBase = DEFAULT_DGX_BASE
        private set
    var dgxModel = DEFAULT_DGX_MODEL
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        useDgx = p.getBoolean("ai_use_dgx", false)
        dgxBase = p.getString("ai_dgx_base", DEFAULT_DGX_BASE)?.ifBlank { DEFAULT_DGX_BASE } ?: DEFAULT_DGX_BASE
        dgxModel = p.getString("ai_dgx_model", DEFAULT_DGX_MODEL)?.ifBlank { DEFAULT_DGX_MODEL } ?: DEFAULT_DGX_MODEL
        apply()
        syncModelAsync(ctx)   // 啟動時順便確認 DGX 上的模型名稱（連不上就維持原本的）
    }

    fun save(ctx: Context, dgx: Boolean, base: String = dgxBase, model: String = dgxModel) {
        useDgx = dgx
        dgxBase = base.trim().trimEnd('/').ifBlank { DEFAULT_DGX_BASE }
        dgxModel = model.trim().ifBlank { DEFAULT_DGX_MODEL }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("ai_use_dgx", useDgx)
            .putString("ai_dgx_base", dgxBase)
            .putString("ai_dgx_model", dgxModel)
            .apply()
        apply()
    }

    fun toggle(ctx: Context) = save(ctx, !useDgx)

    /**
     * 2026-11-19：問 DGX 實際在跑哪個模型（GET {位址}/models，取第一個 id）。
     * DGX 換模型（例如 Qwen3-8B ↔ Qwen3.6-35B-A3B-FP8）後，App 不用手動改名稱。
     * 在背景執行緒呼叫；連不上或格式不對回傳 null。
     */
    fun fetchServedModel(): String? = runCatching {
        val conn = URL("$dgxBase/models").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer local")
            if (conn.responseCode !in 200..299) return@runCatching null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 背景執行緒用：DGX 的模型名稱跟設定不同就自動更新，回傳更新後的名稱（沒變或連不上回傳 null） */
    fun syncModel(ctx: Context): String? {
        if (!useDgx) return null
        val served = fetchServedModel() ?: return null
        if (served == dgxModel) return null
        Log.i("AiSource", "DGX 模型名稱自動更新：$dgxModel → $served")
        Handler(Looper.getMainLooper()).post { save(ctx, true, dgxBase, served) }
        dgxModel = served   // 同一個背景執行緒接下來的呼叫馬上用新名稱
        apply()
        return served
    }

    /** 主執行緒用：在背景同步模型名稱，有變動就在主執行緒呼叫 done */
    fun syncModelAsync(ctx: Context, done: () -> Unit = {}) {
        if (!useDgx) return
        val app = ctx.applicationContext
        Thread({ if (syncModel(app) != null) Handler(Looper.getMainLooper()).post(done) }, "AiSourceSync").start()
    }

    /** 狀態燈與紀錄用的短名稱 */
    fun label() = if (useDgx) "DGX" else "iAI"

    private fun apply() {
        LlmClient.endpointOverride = if (useDgx) LlmClient.Endpoint(dgxBase, "local", dgxModel) else null
    }
}
