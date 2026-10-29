package com.example.chatreader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 小精靈要給使用者看的內容（由 CompanionActions 在背景準備好，再打開 CompanionActivity） */
sealed class Payload {
    /** 要記下來嗎？source：「Messenger 聊天」「Facebook」… */
    data class Review(val source: String, val found: Extracted) : Payload()
    data class BriefCard(val partner: String, val remembered: List<String>, val brief: Brief) : Payload()
    data class DebriefCard(val partner: String, val stats: String, val growth: String, val result: Debrief) : Payload()
    data class Message(val title: String, val text: String) : Payload()
}

/**
 * 小精靈的對話框（浮在 Messenger／Facebook 上面的小視窗）
 *
 * 為什麼不用泡泡那種懸浮窗：懸浮窗不能打字、不能用勾選框，這裡要改名字、勾選要存的項目。
 * 這是一般的 Activity（對話框樣式），從無障礙服務打開；關掉後回到原本的 App。
 *
 * 安全：這裡的「去 Messenger 填入」只會填字，最後一定停在確認視窗，由你按「送出」。
 */
class CompanionActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PAYLOAD = "payload"
        const val MODE_REMIND = "remind"
        private const val MESSENGER = "com.facebook.orca"

        /** 下一次要顯示的內容（只在主執行緒存取） */
        private var pending: Payload? = null

        fun open(ctx: Context, payload: Payload) {
            pending = payload
            val i = Intent(ctx, CompanionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, MODE_PAYLOAD)
            runCatching { ctx.startActivity(i) }
                .onFailure { FriendBubble.say("打不開小精靈視窗：${it.message}") }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var buttons: LinearLayout
    private var dp = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MemoryStore.init(this)
        dp = resources.displayMetrics.density
        setFinishOnTouchOutside(false)   // 避免不小心點到外面，勾好的東西就不見了

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val pad = (20 * dp).toInt()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(ScrollView(this@CompanionActivity).apply { addView(body) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(outer)
        val dm = resources.displayMetrics
        window.setLayout((dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.75).toInt())
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    // 對話框出現時把泡泡藏起來，免得蓋住按鈕
    override fun onResume() {
        super.onResume()
        FriendBubble.setVisible(false)
    }

    override fun onPause() {
        super.onPause()
        FriendBubble.setVisible(true)
    }

    private fun render(intent: Intent) {
        body.removeAllViews()
        buttons.removeAllViews()
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_REMIND -> showRemind(
                intent.getStringExtra(ReminderScheduler.EXTRA_PERSON).orEmpty(),
                intent.getStringExtra(ReminderScheduler.EXTRA_EVENT).orEmpty(),
            )
            else -> {
                val p = pending
                pending = null
                when (p) {
                    is Payload.Review -> showReview(p)
                    is Payload.BriefCard -> showBrief(p)
                    is Payload.DebriefCard -> showDebrief(p)
                    is Payload.Message -> showMessage(p.title, p.text)
                    null -> showMessage("🧚 小精靈", "這個視窗的內容已經過期了，請再點一次泡泡。")
                }
            }
        }
    }

    // ───────────────────────── 要記下來嗎？ ─────────────────────────

    private fun showReview(p: Payload.Review) {
        val ex = p.found
        title("🧚 要記下來嗎？")
        small("從「${p.source}」看到的。只會存你勾選的，存在手機裡，隨時可以在 App 裡刪掉。")

        header("這是誰")
        val nameEdit = EditText(this).apply {
            setText(ex.name)
            hint = "朋友的名字（和 Messenger 上的名字一樣最好）"
            setSingleLine()
        }
        body.addView(nameEdit)
        val nameHint = small("")
        fun updateNameHint() {
            val n = nameEdit.text.toString().trim()
            val found = if (n.isEmpty()) null else MemoryStore.find(n)
            nameHint.text = when {
                n.isEmpty() -> "⚠️ 沒看出名字，請自己填"
                found != null -> "已經有「${found.name}」的卡片，會加到同一張"
                else -> "會幫「$n」建立一張新卡片"
            }
        }
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateNameHint()
        })
        updateNameHint()

        val factBoxes = mutableListOf<Pair<CheckBox, FactDraft>>()
        if (ex.facts.isNotEmpty()) {
            header("關於對方")
            ex.facts.forEach { f ->
                val cb = CheckBox(this).apply { text = "【${f.category}】${f.text}"; isChecked = true }
                body.addView(cb)
                factBoxes.add(cb to f)
            }
        }
        val eventBoxes = mutableListOf<Pair<CheckBox, EventDraft>>()
        if (ex.events.isNotEmpty()) {
            header("有日期的事（當天早上 8:30 提醒你）")
            ex.events.forEach { e ->
                val cb = CheckBox(this).apply {
                    text = "${e.dateLabel}　${e.title}（${e.kind}）" + if (e.note.isNotBlank()) "\n${e.note}" else ""
                    isChecked = true
                }
                body.addView(cb)
                eventBoxes.add(cb to e)
            }
        }
        header("補充（選填）")
        val noteEdit = EditText(this).apply { hint = "例如：迎新認識的、坐我隔壁" }
        body.addView(noteEdit)

        if (ex.icebreakers.isNotEmpty()) {
            header("可以這樣打招呼")
            ex.icebreakers.forEach { line -> lineWithActions(line) { nameEdit.text.toString().trim() } }
        }

        var saved = false
        button("都不要") { finish() }
        button("存下勾選的") { b ->
            if (saved) { finish(); return@button }
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) { toast("請先填名字"); return@button }
            val facts = factBoxes.filter { it.first.isChecked }.map { it.second.category to it.second.text }.toMutableList()
            noteEdit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { facts.add("其他" to it) }
            val events = eventBoxes.filter { it.first.isChecked }.map {
                FriendEvent(MemoryStore.newId(), it.second.title, it.second.date, it.second.kind, it.second.note, System.currentTimeMillis())
            }
            if (facts.isEmpty() && events.isEmpty()) { toast("沒有勾選任何東西"); return@button }

            val person = MemoryStore.findOrCreate(name)
            val nf = MemoryStore.addFacts(person, facts, p.source)
            val added = MemoryStore.addEvents(person, events)
            added.forEach { ReminderScheduler.schedule(this, person, it) }
            saved = true
            toast("✅ 存到「${person.name}」：$nf 件事實、${added.size} 個提醒")
            (b as Button).text = "完成"
            // 存好後勾選框鎖住，破冰話題還可以繼續用
            factBoxes.forEach { it.first.isEnabled = false }
            eventBoxes.forEach { it.first.isEnabled = false }
            nameEdit.isEnabled = false; noteEdit.isEnabled = false
        }
    }

    // ───────────────────────── 聊天前小抄 ─────────────────────────

    private fun showBrief(p: Payload.BriefCard) {
        title("📋 聊天前小抄：${p.partner}")
        header("你記得的")
        if (p.remembered.isEmpty()) {
            small("還沒記過關於 ${p.partner} 的事。聊天時點泡泡的「📌 記下重點」，或在 Facebook 點「🔍 看看這個人」。")
        } else {
            p.remembered.forEach { text("・$it") }
        }
        if (p.brief.lines.isNotEmpty()) {
            header("可以這樣開場")
            p.brief.lines.forEach { lineWithActions(it) { p.partner } }
        }
        if (p.brief.ask.isNotBlank()) {
            header("想更了解對方，可以問")
            lineWithActions(p.brief.ask) { p.partner }
        }
        button("關閉") { finish() }
    }

    // ───────────────────────── 聊完復盤 ─────────────────────────

    private fun showDebrief(p: Payload.DebriefCard) {
        title("🪞 聊完復盤：${p.partner}")
        small(p.stats)
        if (p.result.summary.isNotBlank()) text(p.result.summary)
        if (p.result.good.isNotEmpty()) {
            header("👍 做得好")
            p.result.good.forEach { text("・$it") }
        }
        if (p.result.tips.isNotEmpty()) {
            header("💡 下次可以試試")
            p.result.tips.forEach { text("・$it") }
        }
        if (p.growth.isNotBlank()) {
            header("🌱 你的成長")
            small(p.growth)
        }
        button("關閉") { finish() }
    }

    // ───────────────────────── 提醒（點通知進來） ─────────────────────────

    private fun showRemind(personId: String, eventId: String) {
        val person = MemoryStore.allPeople().firstOrNull { it.id == personId }
        val event = person?.events?.firstOrNull { it.id == eventId }
        if (person == null || event == null) {
            showMessage("🧚 小精靈", "這個提醒的事件已經被刪掉了。")
            return
        }
        title("🧚 今天的提醒")
        text(ReminderScheduler.message(person, event))
        val memory = MemoryStore.memoryText(person)
        if (memory.isNotBlank()) {
            header("你記得的")
            small(memory)
        }
        val area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(area)
        button("關閉") { finish() }
        button("幫我想一句") { b ->
            b.isEnabled = false
            area.removeAllViews()
            area.addView(TextView(this).apply { text = "小精靈想一下…"; setPadding(0, (12 * dp).toInt(), 0, 0) })
            Thread {
                val result = runCatching { FriendBrain.cheer(person.name, event, memory) }
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    area.removeAllViews()
                    b.isEnabled = true
                    result.onSuccess { lines ->
                        val target = area
                        lines.forEach { lineWithActions(it, target) { person.name } }
                        if (lines.isEmpty()) target.addView(TextView(this).apply { text = "AI 沒有給建議，再按一次試試" })
                    }.onFailure {
                        area.addView(TextView(this).apply { text = "❌ ${it.message}" })
                    }
                }
            }.start()
        }
    }

    private fun showMessage(t: String, msg: String) {
        title(t)
        text(msg)
        button("知道了") { finish() }
    }

    // ───────────────────────── 動作 ─────────────────────────

    /** 一句可以傳的話 ＋「複製」「去 Messenger 填入」 */
    private fun lineWithActions(line: String, parent: LinearLayout = body, target: () -> String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p / 2)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF1ECFB"))
                cornerRadius = 10 * dp
            }
        }
        box.addView(TextView(this).apply { text = line; textSize = 15f; setTextColor(Color.parseColor("#FF222222")) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "複製"
            setOnClickListener { copy(line) }
        })
        row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "去 Messenger 填入"
            setOnClickListener { fillInMessenger(target(), line) }
        })
        box.addView(row)
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() })
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("小精靈", text))
        toast("已複製")
    }

    /**
     * 打開 Messenger → 找到這個人的聊天室 → 填入（M1 的流程）→ 停在確認視窗，你按「送出」才會傳。
     * 名字要和 Messenger 聊天列表上的一樣；沒聊過天（列表上沒有）會找不到，請改用「複製」。
     */
    private fun fillInMessenger(name: String, text: String) {
        if (name.isBlank()) { toast("請先填名字"); return }
        if (ChatReaderService.instance == null) { toast("無障礙服務沒開，請先到交友大師 App 打開"); return }
        val launch = packageManager.getLaunchIntentForPackage(MESSENGER)
        if (launch == null) { toast("找不到 Messenger"); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        finish()
        startActivity(launch)
        main.postDelayed({ MessengerAgent.start(SendCommand(name, text)) }, 600)
    }

    // ───────────────────────── 版面小工具 ─────────────────────────

    private fun title(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (6 * dp).toInt())
    })

    private fun header(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#FF6A4BC4"))
        setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
    })

    private fun text(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 15f
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    })

    private fun small(t: String): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.GRAY)
    }.also { body.addView(it) }

    private fun button(t: String, onClick: (View) -> Unit) {
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = t
            setOnClickListener { onClick(it) }
        })
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
