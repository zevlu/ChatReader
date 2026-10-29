package com.example.chatreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 交友大師（交友小精靈）主畫面
 *
 * 1. 開啟無障礙服務、打開 🧚 泡泡、允許通知
 * 2. 在 Messenger 聊天室點泡泡：💬 幫我回／📋 聊天前小抄／📌 記下重點／🪞 聊完復盤
 * 3. 在 Facebook 個人檔案、貼文、限動上點泡泡：🔍 看看這個人
 * 4. 下面的「📒 記憶本」可以看、可以刪小精靈記下的所有東西（只存在這支手機）
 *
 * 版面用程式碼建立，不需要另外改 layout XML。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceState: TextView
    private lateinit var bubbleBtn: Button
    private lateinit var notifyBtn: Button
    private lateinit var agentLog: TextView
    private lateinit var status: TextView
    private lateinit var memoryBox: LinearLayout
    private lateinit var growth: TextView
    private var dp = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MemoryStore.init(this)
        ReminderScheduler.ensureChannel(this)
        dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val howTo = TextView(this).apply {
            textSize = 15f
            text = "🧚 交友小精靈幫你把新認識的人變成朋友。\n\n" +
                "・Messenger 聊天室點泡泡：💬 幫我回、📋 聊天前小抄、📌 記下重點、🪞 聊完復盤\n" +
                "・Facebook 個人檔案／貼文／限動點泡泡：🔍 看看這個人\n" +
                "・記下的考試、生日，當天早上 8:30 提醒你\n\n" +
                "小精靈只在你點的時候讀畫面，記之前一定先問你；訊息一定由你按「送出」才會傳。\n" +
                "泡泡可以拖曳；長按收起（處理中長按 = 停止）。"
            setPadding(0, 0, 0, pad)
        }
        serviceState = TextView(this).apply { textSize = 14f }
        val openSettings = Button(this).apply {
            text = "① 開啟無障礙設定（找到「${getString(R.string.service_label)}」並啟用）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        bubbleBtn = Button(this).apply { setOnClickListener { toggleBubble() } }
        notifyBtn = Button(this).apply { setOnClickListener { askNotification() } }
        val llmState = TextView(this).apply {
            textSize = 13f
            text = if (LlmClient.isConfigured()) "✅ AI 設定已載入" else
                "⚠️ 沒有 AI 設定：請在 local.properties 加 IAI_BASE、IAI_KEY，Sync 後重新安裝"
            setPadding(0, pad / 2, 0, 0)
        }
        val openMessenger = Button(this).apply {
            text = "打開 Messenger"
            setOnClickListener { openApp("com.facebook.orca", "Messenger") }
        }
        val openFacebook = Button(this).apply {
            text = "打開 Facebook"
            setOnClickListener { openApp("com.facebook.katana", "Facebook") }
        }
        val stop = Button(this).apply {
            text = "停止"
            setOnClickListener { MessengerAgent.cancel() }
        }
        val testAi = Button(this).apply {
            text = "測試 AI 連線"
            setOnClickListener { testAi(this) }
        }
        val testVision = Button(this).apply {
            text = "測試 AI 看圖"
            setOnClickListener { testVision(this) }
        }
        val testExtract = Button(this).apply {
            text = "🧪 測試記重點"
            setOnClickListener { testExtract(this) }
        }
        val testRemind = Button(this).apply {
            text = "🧪 1 分鐘後提醒"
            setOnClickListener { testReminder() }
        }
        growth = TextView(this).apply { textSize = 14f }
        memoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                text = "🧚 交友大師"
                textSize = 24f
                setPadding(0, 0, 0, pad / 2)
            })
            addView(howTo)
            addView(serviceState)
            addView(openSettings)
            addView(bubbleBtn)
            addView(notifyBtn)
            addView(llmState)
            addView(row(openMessenger, openFacebook, stop))
            addView(row(testAi, testVision))
            addView(row(testExtract, testRemind))
            addView(sectionTitle("🌱 我的成長"))
            addView(growth)
            addView(sectionTitle("📒 記憶本"))
            addView(memoryBox)
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
        renderMemory()
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

    // ───────────────────────── 設定 ─────────────────────────

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

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askNotification() {
        if (notificationsAllowed()) {
            toast("通知已經允許了")
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderState()
        if (!notificationsAllowed() && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // 拒絕過兩次之後系統不會再跳視窗 → 帶去設定頁
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openApp(pkg: String, name: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            agentLog.text = "❌ 找不到 $name（請確認已安裝，並檢查 AndroidManifest 的 <queries>）"
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    // ───────────────────────── 記憶本 ─────────────────────────

    private fun renderMemory() {
        growth.text = CompanionActions.growthText() +
            (MemoryStore.allDebriefs().takeLast(3).reversed().joinToString("") { "\n・${it.person}：${it.summary}" })

        memoryBox.removeAllViews()
        val people = MemoryStore.allPeople()
        if (people.isEmpty()) {
            memoryBox.addView(TextView(this).apply {
                text = "還沒有記任何人。在 Messenger 點「📌 記下重點」，或在 Facebook 點「🔍 看看這個人」。"
                textSize = 14f; setTextColor(Color.GRAY)
            })
            return
        }
        val upcoming = MemoryStore.upcomingEvents()
        if (upcoming.isNotEmpty()) {
            memoryBox.addView(TextView(this).apply {
                text = "⏰ 接下來：" + upcoming.take(3).joinToString("、") { (p, e) -> "${FriendBrain.label(e.date)} ${p.name}${e.title}" }
                textSize = 14f
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
        }
        people.sortedByDescending { it.createdAt }.forEach { p ->
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            head.addView(TextView(this).apply {
                text = "👤 ${p.name}"
                textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(smallButton("刪除這個人") {
                AlertDialog.Builder(this)
                    .setMessage("刪除「${p.name}」的整張卡片？")
                    .setPositiveButton("刪除") { _, _ ->
                        p.events.forEach { ReminderScheduler.cancel(this, it.id) }
                        MemoryStore.deletePerson(p.id); renderMemory()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
            memoryBox.addView(head)
            p.facts.forEach { f ->
                memoryBox.addView(itemRow("【${f.category}】${f.text}") { MemoryStore.deleteFact(p.id, f.id); renderMemory() })
            }
            p.events.sortedBy { it.date }.forEach { e ->
                val mark = if (e.reminded) "　✓已提醒" else ""
                memoryBox.addView(itemRow("📅 ${FriendBrain.label(e.date)} ${e.title}（${e.kind}）$mark") {
                    ReminderScheduler.cancel(this, e.id)
                    MemoryStore.deleteEvent(p.id, e.id); renderMemory()
                })
            }
        }
    }

    private fun itemRow(text: String, onDelete: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { this.text = text; textSize = 14f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton("✕") { onDelete() })
    }

    private fun smallButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
        }

    // ───────────────────────── 測試 ─────────────────────────

    /** 不用 Messenger，直接用假對話測「記下重點」的提示詞 */
    private fun testExtract(button: Button) {
        val chat = """
            對方：欸我禮拜六要考微積分 好怕
            我：你準備得怎樣
            對方：還好啦 最近一直在學做甜點有點分心😂
            對方：對了我下個月14號生日欸
        """.trimIndent()
        button.isEnabled = false
        agentLog.text = "【測試記重點】呼叫中…\n\n$chat"
        Thread {
            val t0 = System.currentTimeMillis()
            val result = try {
                val ex = FriendBrain.extractFromChat("測試朋友", chat)
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                buildString {
                    appendLine("✅ 成功（${"%.1f".format(sec)} 秒）")
                    ex.facts.forEach { appendLine("・【${it.category}】${it.text}") }
                    ex.events.forEach { appendLine("・📅 ${it.dateLabel} ${it.title}（${it.kind}）${it.note}") }
                    appendLine("\n（應該要有：學做甜點、微積分考試在這週六、生日在下個月 14 號）")
                }
            } catch (e: Exception) {
                "❌ 失敗：${e.message}\n（Logcat 搜尋 tag:LlmClient 看詳細）"
            }
            runOnUiThread {
                agentLog.text = "【測試記重點】\n$chat\n\n$result"
                button.isEnabled = true
            }
        }.start()
    }

    /** 建一個「今天」的測試事件，1 分鐘後跳通知（點通知可以試「幫我想一句」） */
    private fun testReminder() {
        if (!notificationsAllowed()) {
            toast("請先允許通知")
            askNotification()
            return
        }
        val person = MemoryStore.findOrCreate("小精靈測試")
        val event = FriendEvent(MemoryStore.newId(), "測試考試", MemoryStore.today(), "考試", "", System.currentTimeMillis())
        MemoryStore.addEvents(person, listOf(event))
        ReminderScheduler.testSoon(this, person, event, 60)
        toast("1 分鐘後會跳通知（可以先回桌面等）")
        renderMemory()
    }

    /** 只測 AI：用內建測試對話呼叫一次，顯示結果與花費時間（網路不能在主執行緒） */
    private fun testAi(button: Button) {
        button.isEnabled = false
        FriendAdvisor.memoryHint = ""
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

    /** M4：畫一張「考卷 100 分」的測試圖，請 AI 描述，確認 iAI Hub 收得到圖片 */
    private fun testVision(button: Button) {
        button.isEnabled = false
        agentLog.text = "【測試 AI 看圖】呼叫中…（測試圖：一張寫著 100 分的考卷）"
        Thread {
            val t0 = System.currentTimeMillis()
            val result = try {
                val b64 = LlmClient.encodeJpeg(drawTestImage())
                val answer = LlmClient.chatWithImages(
                    "你是看圖助手，用一句繁體中文描述圖片內容，20 字以內。",
                    "這張圖片裡有什麼？",
                    listOf(b64),
                )
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                "✅ 成功（${"%.1f".format(sec)} 秒）\nAI 看到：$answer\n\n（有提到考卷或 100 分，就代表看得到圖片）"
            } catch (e: Exception) {
                "❌ 失敗：${e.message}\n（Logcat 搜尋 tag:LlmClient 看詳細，把錯誤貼給 Claude）"
            }
            runOnUiThread {
                agentLog.text = "【測試 AI 看圖】\n$result"
                button.isEnabled = true
            }
        }.start()
    }

    private fun drawTestImage(): Bitmap {
        val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        paint.color = Color.RED
        paint.textSize = 160f
        c.drawText("100", 240f, 200f, paint)
        paint.color = Color.BLACK
        paint.textSize = 48f
        c.drawText("數學考卷", 240f, 300f, paint)
        return bmp
    }

    // ───────────────────────── 顯示 ─────────────────────────

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun describe(s: Suggestion): String = buildString {
        appendLine("情緒：${s.emotion}（強度 ${s.intensity}）　需要：${s.need}")
        appendLine("依據：${s.reason}")
        s.replies.forEach { appendLine("・${it.style}｜${it.text}") }
    }

    private fun renderState() {
        val on = ChatReaderService.instance != null
        serviceState.text = if (on) "✅ 無障礙服務已啟用" else "⚠️ 尚未啟用無障礙服務（先按 ①）"
        bubbleBtn.text = if (FriendBubble.showing) "② 🧚 泡泡：開啟中（點一下收起）" else "② 🧚 開啟浮動泡泡"
        notifyBtn.text = if (notificationsAllowed()) "③ ✅ 通知已允許（提醒會準時跳出）" else "③ 🔔 允許通知（考試、生日提醒要用）"
    }

    private fun renderAgentLog() {
        if (MessengerAgent.history.isEmpty()) return
        val ai = MessengerAgent.lastSuggestion?.let { "\n\n【AI 判斷】\n" + describe(it) } ?: ""
        agentLog.text = "【最近一次】\n" + MessengerAgent.history.joinToString("\n") + ai
    }

    private fun render(summary: String) {
        status.text = "【最近讀到的對話】\n" + summary
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
