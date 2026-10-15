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
 * 測試用主畫面（M1 版）
 * 1. 開無障礙設定
 * 2. 輸入文字指令 → 自動操作 Messenger 填入訊息 → 懸浮窗確認後才送出
 * 3. 下面顯示最近一次從 Messenger 讀到的對話
 * 版面用程式碼建立，不需要另外改 layout XML。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var agentLog: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val openSettings = Button(this).apply {
            text = "開啟無障礙設定（找到「交友大師 讀取測試」並啟用）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val hint = TextView(this).apply {
            text = "指令格式：傳給 名字：內容　或　跟名字說內容"
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        input = EditText(this).apply {
            setText("傳給 澤：這是交友大師的 Agent 測試")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val run = Button(this).apply {
            text = "執行（會打開 Messenger）"
            setOnClickListener { runCommand() }
        }
        val stop = Button(this).apply {
            text = "停止"
            setOnClickListener { MessengerAgent.cancel() }
        }
        agentLog = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(openSettings)
            addView(hint)
            addView(input)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(run)
                addView(stop)
            })
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

    private fun runCommand() {
        val cmd = CommandParser.parse(input.text.toString())
        if (cmd == null) {
            agentLog.text = "❌ 看不懂指令。請用「傳給 名字：內容」或「跟名字說內容」"
            return
        }
        val launch = packageManager.getLaunchIntentForPackage("com.facebook.orca")
        if (launch == null) {
            agentLog.text = "❌ 找不到 Messenger（請確認已安裝，並檢查 AndroidManifest 的 <queries>）"
            return
        }
        agentLog.text = "對象：${cmd.target}\n訊息：${cmd.message}"
        if (MessengerAgent.start(cmd)) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            renderAgentLog()
        }
    }

    private fun renderAgentLog() {
        if (MessengerAgent.history.isEmpty()) return
        agentLog.text = "【Agent 進度】\n" + MessengerAgent.history.joinToString("\n")
    }

    private fun render(summary: String) {
        val on = ChatReaderService.instance != null
        val head = if (on) "✅ 無障礙服務已啟用\n\n" else "⚠️ 尚未啟用無障礙服務\n\n"
        status.text = head + summary
    }
}