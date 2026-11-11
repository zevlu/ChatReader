package com.example.chatreader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模擬聊天（2026-11-05，三站展示用）
 *
 * 模擬器上沒有 Messenger（Messenger 只有 ARM 版，x86 模擬器會閃退），
 * 所以 2、3 號站改讀 assets/demo_chats.json 裡預先準備好的對話。
 * 找約定一樣交給雲端 AI；傳訊息一樣要使用者說「送出」，只是不會真的傳出去。
 */
object DemoChats {
    private const val TAG = "DemoChats"
    private var cache: LinkedHashMap<String, List<String>>? = null

    /** 全部對話：名字 → 每一行「誰：內容」 */
    fun all(ctx: Context): Map<String, List<String>> {
        cache?.let { return it }
        val map = LinkedHashMap<String, List<String>>()
        runCatching {
            val text = ctx.assets.open("demo_chats.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            for (k in o.keys()) {
                if (k.startsWith("_")) continue                // _說明 之類的註解欄位
                val arr = o.optJSONArray(k) ?: continue
                map[k] = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
        }.onFailure { Log.e(TAG, "讀不到 assets/demo_chats.json", it) }
        cache = map
        return map
    }

    fun names(ctx: Context): List<String> = all(ctx).keys.toList()

    /**
     * 用名字找對話。語音辨識常把名字聽成同音字，所以：完全一樣 → 互相包含 → 有一個字一樣（只有一個人符合時）。
     * 找不到回傳 null。
     */
    fun find(ctx: Context, friend: String): Pair<String, List<String>>? {
        val chats = all(ctx)
        val f = friend.trim()
        if (f.isEmpty()) return null
        chats[f]?.let { return f to it }
        chats.entries.firstOrNull { f.contains(it.key) || it.key.contains(f) }?.let { return it.key to it.value }
        val one = chats.entries.filter { e -> e.key.any { ch -> f.contains(ch) } }
        return one.singleOrNull()?.let { it.key to it.value }
    }

    /** 給網頁顯示用 */
    fun toJson(ctx: Context): JSONObject = JSONObject().apply {
        all(ctx).forEach { (k, v) -> put(k, JSONArray(v)) }
    }
}
