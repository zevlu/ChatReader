package com.example.chatreader

import android.content.Context

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
    const val DEFAULT_DGX_MODEL = "qwen3.8-flash-next"

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

    /** 狀態燈與紀錄用的短名稱 */
    fun label() = if (useDgx) "DGX" else "iAI"

    private fun apply() {
        LlmClient.endpointOverride = if (useDgx) LlmClient.Endpoint(dgxBase, "local", dgxModel) else null
    }
}
