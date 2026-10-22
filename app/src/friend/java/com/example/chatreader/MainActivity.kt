package com.example.chatreader

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 交友大師 主畫面
 *
 * 用法：
 * 1. 開啟無障礙服務「交友大師 讀取服務」
 * 2. 打開 💬 浮動泡泡（會一直浮在畫面上）
 * 3. 在 Messenger 聊天時，覺得需要幫忙就點泡泡：
 *    讀目前聊天室的對話 → AI 判斷情緒、想三種回覆 → 懸浮窗三選一 → 填入 → 你按「送出」才會傳
 *
 * 版面用程式碼建立，不需要另外改 layout XML。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceState: TextView
    private lateinit var bubbleBtn: Button
    private lateinit var agentLog: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val howTo = TextView(this).apply {
            textSize = 15f
            text = "在 Messenger 聊天時，覺得不知道怎麼回，就點畫面上的 💬 泡泡。\n" +
                "它會讀目前的對話、判斷對方的情緒，給你三種語氣的回覆；選一則填進輸入框，你按「送出」才會傳。\n\n" +
                "泡泡可以拖曳移動；長按收起（處理中長按 = 停止）。"
            setPadding(0, 0, 0, pad)
        }
        serviceState = TextView(this).apply { textSize = 14f }
        val openSettings = Button(this).apply {
            text = "① 開啟無障礙設定（找到「${getString(R.string.service_label)}」並啟用）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        bubbleBtn = Button(this).apply {
            setOnClickListener { toggleBubble() }
        }
        val llmState = TextView(this).apply {
            textSize = 13f
            text = if (LlmClient.isConfigured()) "✅ AI 設定已載入" else
                "⚠️ 沒有 AI 設定：請在 local.properties 加 IAI_BASE、IAI_KEY，Sync 後重新安裝"
            setPadding(0, pad / 2, 0, 0)
        }
        val openMessenger = Button(this).apply {
            text = "打開 Messenger"
            setOnClickListener { openMessenger() }
        }
        val stop = Button(this).apply {
            text = "停止"
            setOnClickListener { MessengerAgent.cancel() }
        }
        val testAi = Button(this).apply {
            text = "測試 AI 連線"
            setOnClickListener { testAi(this) }
        }
        agentLog = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(TextView(this@MainActivity).apply {
                text = "💬 交友大師"
                textSize = 24f
                setPadding(0, 0, 0, pad / 2)
            })
            addView(howTo)
            addView(serviceState)
            addView(openSettings)
            addView(bubbleBtn)
            addView(llmState)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(openMessenger)
                addView(stop)
                addView(testAi)
            })
            addView(agentLog)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        // 使用者沒有關掉泡泡、而且無障礙服務已開 → 自動顯示泡泡
        if (FriendBubble.wanted(this) && ChatReaderService.instance != null) FriendBubble.show()
        FriendBubble.onChange = { runOnUiThread { renderState() } }
        renderState()
        render(ChatReaderService.lastSummary)
        renderAgentLog()
        ChatReaderService.listener = { s -> runOnUiThread { render(s) } }
        MessengerAgent.onStatus = { runOnUiThread { renderAgentLog() } }
    }

    override fun onPause() {
        super.onPause()
        FriendBubble.onChange = null
        ChatReaderService.listener = null
        MessengerAgent.onStatus = null
    }

    private fun toggleBubble() {
        if (FriendBubble.showing) {
            FriendBubble.hide()
            FriendBubble.setWanted(this, false)
        } else {
            FriendBubble.setWanted(this, true)
            if (!FriendBubble.show()) {
                agentLog.text = "⚠️ 泡泡開不起來：請先按 ① 開啟無障礙服務，回到這裡再按一次"
            }
        }
        renderState()
    }

    private fun openMessenger() {
        val launch = packageManager.getLaunchIntentForPackage("com.facebook.orca")
        if (launch == null) {
            agentLog.text = "❌ 找不到 Messenger（請確認已安裝，並檢查 AndroidManifest 的 <queries>）"
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    /** 只測 AI：用內建測試對話呼叫一次，顯示結果與花費時間（網路不能在主執行緒） */
    private fun testAi(button: Button) {
        button.isEnabled = false
        agentLog.text = "【測試 AI 連線】呼叫中…\n\n測試對話：\n${FriendAdvisor.SAMPLE_CHAT}"
        Thread {
            val t0 = System.currentTimeMillis()
            val result = try {
                val s = FriendAdvisor.suggest(FriendAdvisor.SAMPLE_CHAT)
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                "✅ 成功（${"%.1f".format(sec)} 秒）\n\n" + describe(s)
            } catch (e: Exception) {
                "❌ 失敗：${e.message}\n（Logcat 搜尋 tag:LlmClient 看詳細）"
            }
            runOnUiThread {
                agentLog.text = "【測試 AI 連線】\n$result"
                button.isEnabled = true
            }
        }.start()
    }

    private fun describe(s: Suggestion): String = buildString {
        appendLine("情緒：${s.emotion}（強度 ${s.intensity}）　需要：${s.need}")
        appendLine("依據：${s.reason}")
        s.replies.forEach { appendLine("・${it.style}｜${it.text}") }
    }

    private fun renderState() {
        val on = ChatReaderService.instance != null
        serviceState.text = if (on) "✅ 無障礙服務已啟用" else "⚠️ 尚未啟用無障礙服務（先按 ①）"
        bubbleBtn.text = if (FriendBubble.showing) "② 💬 泡泡：開啟中（點一下收起）" else "② 💬 開啟浮動泡泡"
    }

    private fun renderAgentLog() {
        if (MessengerAgent.history.isEmpty()) return
        val ai = MessengerAgent.lastSuggestion?.let { "\n\n【AI 判斷】\n" + describe(it) } ?: ""
        agentLog.text = "【最近一次】\n" + MessengerAgent.history.joinToString("\n") + ai
    }

    private fun render(summary: String) {
        status.text = "【最近讀到的對話】\n" + summary
    }
}
