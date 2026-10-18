package com.example.chatreader

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 交友大師 主畫面（M2）
 * 1. 開無障礙設定
 * 2. 「幫我想回覆」：讀 Messenger 對話 → AI 判斷情緒＋三種回覆 → 懸浮窗三選一 → 填入 → 確認後才送出
 * 3. 「測試 AI 連線」：不開 Messenger，只用內建的測試對話呼叫 AI，確認金鑰與網路正常
 * 版面用程式碼建立，不需要另外改 layout XML。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var target: EditText
    private lateinit var agentLog: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val openSettings = Button(this).apply {
            text = "開啟無障礙設定（找到「${getString(R.string.service_label)}」並啟用）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val llmState = TextView(this).apply {
            textSize = 13f
            text = if (LlmClient.isConfigured()) "✅ AI 設定已載入" else
                "⚠️ 沒有 AI 設定：請在 local.properties 加 IAI_BASE、IAI_KEY，Sync 後重新安裝"
        }
        val hint = TextView(this).apply {
            text = "要回覆誰？（留空 = Messenger 目前開著的聊天室）"
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        target = EditText(this).apply {
            setText("")
            this.hint = "例如：澤"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val reply = Button(this).apply {
            text = "幫我想回覆（會打開 Messenger）"
            setOnClickListener { startReply() }
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
            addView(openSettings)
            addView(llmState)
            addView(hint)
            addView(target)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(reply)
                addView(stop)
            })
            addView(testAi)
            addView(agentLog)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        render(ChatReaderService.lastSummary)
        renderAgentLog()
        ChatReaderService.listener = { s -> runOnUiThread { render(s) } }
        MessengerAgent.onStatus = { renderAgentLog() }
    }

    override fun onPause() {
        super.onPause()
        ChatReaderService.listener = null
        MessengerAgent.onStatus = null
    }

    private fun startReply() {
        val launch = packageManager.getLaunchIntentForPackage("com.facebook.orca")
        if (launch == null) {
            agentLog.text = "❌ 找不到 Messenger（請確認已安裝，並檢查 AndroidManifest 的 <queries>）"
            return
        }
        val name = target.text.toString().trim()
        agentLog.text = "對象：${name.ifEmpty { "（目前的聊天室）" }}"
        if (MessengerAgent.startReply(name, FriendAdvisor)) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            renderAgentLog()
        }
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

    private fun renderAgentLog() {
        if (MessengerAgent.history.isEmpty()) return
        val ai = MessengerAgent.lastSuggestion?.let { "\n\n【AI 判斷】\n" + describe(it) } ?: ""
        agentLog.text = "【Agent 進度】\n" + MessengerAgent.history.joinToString("\n") + ai
    }

    private fun render(summary: String) {
        val on = ChatReaderService.instance != null
        val head = if (on) "✅ 無障礙服務已啟用\n\n" else "⚠️ 尚未啟用無障礙服務\n\n"
        status.text = head + "【最近讀到的對話】\n" + summary
    }
}
