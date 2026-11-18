package com.example.chatreader

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CoDriver 車機主畫面（橫向、像平板車機）
 *
 *  ┌ 上方列：CoDriver｜時間｜狀態燈（無障礙、雲端 AI、路由器、手機模型、免持）
 *  ├ 左欄：油量、冷氣、車窗／後照鏡、目前行程
 *  ├ 中間：CoDriver 說的話、路由結果（A 雲端／B 手機）、大麥克風、打字輸入
 *  └ 右欄：上車、遲到通知、模擬疲勞、下車、停止、設定
 *  「設定」裡填常用聯絡人、手機模型位址，並可測試連線、看紀錄。
 *
 * 流程都在 CarMate.kt，這裡只負責顯示和按鈕。
 */
class MainActivity : AppCompatActivity() {

    // ── 配色 ──
    private val BG = Color.parseColor("#0B1118")
    private val PANEL = Color.parseColor("#151F2A")
    private val PANEL2 = Color.parseColor("#1F2C3A")
    private val ACCENT = Color.parseColor("#F2A33A")
    private val TEXT = Color.parseColor("#F3F0E8")
    private val MUTED = Color.parseColor("#8FA0B0")
    private val GREEN = Color.parseColor("#4CC38A")
    private val BLUE = Color.parseColor("#5AA9F0")
    private val RED = Color.parseColor("#E5604D")

    private val ui = Handler(Looper.getMainLooper())

    // 會變動的元件
    private lateinit var clockView: TextView
    private lateinit var chipAcc: TextView
    private lateinit var chipAi: TextView
    private lateinit var chipRouter: TextView
    private lateinit var chipPhone: TextView
    private lateinit var chipHands: TextView
    private lateinit var fuelText: TextView
    private lateinit var fuelBar: ProgressBar
    private lateinit var tempText: TextView
    private lateinit var fanText: TextView
    private lateinit var windowBtn: TextView
    private lateinit var mirrorBtn: TextView
    private lateinit var tripText: TextView
    private lateinit var routeBadge: TextView
    private lateinit var sayView: TextView
    private lateinit var heardView: TextView
    private lateinit var micBtn: TextView
    private lateinit var micHint: TextView
    private lateinit var textInput: EditText
    private lateinit var settingsPanel: View
    private lateinit var rootView: View
    private lateinit var friendField: EditText
    private lateinit var phoneUrlField: EditText
    private lateinit var phoneModelField: EditText
    private lateinit var aiBtn: TextView
    private lateinit var dgxUrlField: EditText
    private lateinit var dgxModelField: EditText
    private lateinit var testResult: TextView
    private lateinit var handsBtn: TextView
    private lateinit var logView: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val tick = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date())
            ui.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        supportActionBar?.hide()
        CarMate.init(this)

        // 2026-11-19：從 Messenger 回來時不要自動彈出鍵盤（開車中用不到；手動點打字框才出現）
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            // 根容器先拿焦點，打字框就不會在回到前景時被自動選到
            isFocusable = true
            isFocusableInTouchMode = true
        }
        rootView = root
        root.addView(buildDashboard())
        settingsPanel = buildSettings().apply { visibility = View.GONE }
        root.addView(settingsPanel)
        setContentView(root)
        hideSystemBars()
        askPermissions()
    }

    /** 收起鍵盤、讓打字框失去焦點 */
    private fun hideKeyboard() {
        if (::textInput.isInitialized) textInput.clearFocus()
        if (::rootView.isInitialized) rootView.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 從別的 App（Messenger）切回來、視窗重新拿到焦點時，也收一次鍵盤
        if (hasFocus && settingsPanel.visibility != View.VISIBLE) hideKeyboard()
    }

    override fun onResume() {
        super.onResume()
        CarMate.onUpdate = { runOnUiThread { render() } }
        ui.post(tick)
        hideSystemBars()
        if (settingsPanel.visibility != View.VISIBLE) hideKeyboard()
        render()
    }

    override fun onPause() {
        super.onPause()
        CarMate.onUpdate = null
        ui.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) showSettings(false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ───────────────────────── 主畫面 ─────────────────────────

    private fun buildDashboard(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        page.addView(buildTopBar())

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) }
        }
        body.addView(column(buildLeft(), 1f))
        body.addView(gap())
        body.addView(buildCenter().apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1.55f)
        })
        body.addView(gap())
        body.addView(column(buildRight(), 1f))
        page.addView(body)
        return page
    }

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("CoDriver", 22f, ACCENT, bold = true))
        addView(text("　行車 AI Agent", 13f, MUTED))
        clockView = text("", 22f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        addView(clockView)
        chipAcc = chip(); chipAi = chip(); chipRouter = chip(); chipPhone = chip()
        chipAi.setOnClickListener { AiSource.toggle(this@MainActivity); AiSource.syncModelAsync(this@MainActivity) { render() }; render() }   // 點一下切換 iAI ↔ DGX
        chipHands = chip().apply {
            setOnClickListener { CarMate.setHandsFree(!CarMate.handsFreeWanted); render() }
        }
        listOf(chipAcc, chipAi, chipRouter, chipPhone, chipHands).forEach { addView(it) }
    }

    private fun buildLeft(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // 油量（點一下切換 80% ↔ 8%，模擬低油量）
        fuelText = text("", 28f, TEXT, bold = true)
        fuelBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(8)).apply { topMargin = dp(4) }
        }
        addView(card(
            row(text("⛽ 油量", 13f, MUTED), spacer(), text("點一下模擬", 11f, MUTED)),
            fuelText, fuelBar,
        ).apply { setOnClickListener { CarMate.toggleFuel(); render() } })

        // 冷氣
        tempText = text("", 30f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        fanText = text("", 13f, MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        addView(card(
            text("❄ 冷氣", 13f, MUTED),
            row(roundBtn("−") { CarMate.adjustAc(-1) }, tempText, roundBtn("+") { CarMate.adjustAc(1) }),
            row(fanText, smallBtn("風 −") { CarMate.adjustFan(-1) }, smallBtn("風 +") { CarMate.adjustFan(1) }),
        ))

        // 車窗、後照鏡
        windowBtn = toggleBtn { CarMate.toggleWindow() }
        mirrorBtn = toggleBtn { CarMate.toggleMirror() }
        addView(row(windowBtn, gapH(), mirrorBtn).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10) })

        // 目前行程
        tripText = text("", 14f, TEXT).apply { setLineSpacing(0f, 1.2f) }
        addView(card(text("🧭 目前行程", 13f, MUTED), tripText))
    }

    private fun buildCenter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(PANEL, 22)
        setPadding(dp(18), dp(14), dp(18), dp(14))

        routeBadge = text("", 12f, BG, bold = true).apply {
            background = round(MUTED, 20)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        addView(row(text("CoDriver", 13f, ACCENT, bold = true), spacer(), routeBadge))

        sayView = text("", 21f, TEXT, bold = true).apply { setLineSpacing(0f, 1.15f) }
        heardView = text("", 14f, BLUE)
        addView(ScrollView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(sayView)
                addView(heardView.apply { setPadding(0, dp(6), 0, 0) })
            })
        })

        micBtn = text("🎤", 34f, BG).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { syncContacts(); CarMate.listenCommand() }
        }
        addView(micBtn)
        micHint = text("", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        addView(micHint)

        textInput = EditText(this@MainActivity).apply {
            hint = "也可以打字：冷氣調低一點／跟孟澤約在哪"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setTextColor(TEXT); setHintTextColor(MUTED)
            background = round(PANEL2, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        addView(row(textInput, gapH(), smallBtn("送出", ACCENT, BG) {
            val t = textInput.text.toString().trim()
            if (t.isNotEmpty()) { textInput.setText(""); syncContacts(); hideKeyboard(); CarMate.onHeard(t) }
        }).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
    }

    private fun buildRight(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row(tile("🚗", "上車") { syncContacts(); CarMate.startTrip() }, gapH(),
            tile("⏰", "遲到通知") { syncContacts(); CarMate.lateCheck() }))
        addView(row(tile("😪", "模擬疲勞") { CarMate.simulateFatigue() }, gapH(),
            tile("🏁", "下車") { CarMate.endTrip() }))
        addView(row(tile("⏹", "停止") { CarMate.stopAll() }, gapH(),
            tile("⚙", "設定") { showSettings(true) }))
    }

    // ───────────────────────── 設定頁 ─────────────────────────

    private fun buildSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        isClickable = true   // 擋住下面的主畫面
        setPadding(dp(18), dp(12), dp(18), dp(12))
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)

        addView(row(text("⚙ 設定", 20f, TEXT, bold = true), spacer(),
            smallBtn("✕ 關閉") { showSettings(false) }))

        val form = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            friendField = field("例如：孟澤、老師的名字", CarMate.contacts.joinToString("、"))
            phoneUrlField = field("http://192.168.x.x:8080/v1", PhoneModel.baseUrl)
            phoneModelField = field("模型名稱（llama-server 可留空）", PhoneModel.model)
            dgxUrlField = field(AiSource.DEFAULT_DGX_BASE, AiSource.dgxBase)
            dgxModelField = field(AiSource.DEFAULT_DGX_MODEL, AiSource.dgxModel)
            aiBtn = smallBtn("") {
                AiSource.save(this@MainActivity, !AiSource.useDgx, dgxUrlField.text.toString(), dgxModelField.text.toString())
                AiSource.syncModelAsync(this@MainActivity) { render() }   // 切到 DGX 時，自動抓 DGX 上實際的模型名稱
                render()
            }
            testResult = text("", 13f, MUTED).apply { setPadding(0, dp(6), 0, dp(6)) }
            handsBtn = smallBtn("") { CarMate.setHandsFree(!CarMate.handsFreeWanted); render() }

            addView(text("常用聯絡人（幫語音認名字）", 13f, MUTED))
            addView(friendField)
            addView(text("AI 來源（也可以點上方的「AI」狀態燈切換）", 13f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
            addView(aiBtn.apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) })
            addView(text("DGX 模型位址與名稱（手機要 adb reverse tcp:8300 tcp:8300）", 13f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
            addView(dgxUrlField)
            addView(dgxModelField)
            addView(text("B 手機模型位址（兩支手機要連同一個 Wi-Fi／熱點）", 13f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
            addView(phoneUrlField)
            addView(phoneModelField)
            addView(row(smallBtn("儲存並測試連線", ACCENT, BG) { saveAndTest() }, gapH(),
                smallBtn("無障礙設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })
            addView(testResult)
            addView(handsBtn.apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) })
        }
        logView = text("", 12f, MUTED).apply { setTextIsSelectable(true) }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) }
            addView(ScrollView(this@MainActivity).apply {
                addView(form)
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            })
            addView(gap())
            addView(ScrollView(this@MainActivity).apply {
                background = round(PANEL, 16)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("📝 紀錄", 13f, ACCENT, bold = true))
                    addView(logView)
                })
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1.2f)
            })
        })
    }

    private fun showSettings(show: Boolean) {
        if (!show) syncContacts()
        settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
        render()
    }

    private fun saveAndTest() {
        syncContacts()
        PhoneModel.save(this, phoneUrlField.text.toString(), phoneModelField.text.toString())
        AiSource.save(this, AiSource.useDgx, dgxUrlField.text.toString(), dgxModelField.text.toString())
        testResult.text = "測試中…"
        CarMate.testConnections { testResult.text = it; render() }
    }

    private fun syncContacts() {
        if (!::friendField.isInitialized) return
        CarMate.contacts = friendField.text.toString()
            .split(',', '，', '、', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ───────────────────────── 更新畫面 ─────────────────────────

    private fun render() {
        setChip(chipAcc, "無障礙", ChatReaderService.instance != null)
        setChip(chipAi, "AI：${AiSource.label()}", LlmClient.isConfigured())
        setChip(chipRouter, "路由器", Router.isConfigured())
        setChip(chipPhone, "手機模型", PhoneModel.isSet())
        setChip(chipHands, if (HandsFree.enabled) "免持 聆聽中" else "免持", HandsFree.enabled || CarMate.handsFreeWanted)

        val fuel = CarMate.fuelPercent
        val low = fuel <= 15
        fuelText.text = "$fuel%"
        fuelText.setTextColor(if (low) RED else TEXT)
        fuelBar.progress = fuel
        fuelBar.progressTintList = ColorStateList.valueOf(if (low) RED else GREEN)

        tempText.text = if (CarMate.fanLevel == 0) "關" else "${CarMate.acTemp}°"
        fanText.text = "風量 " + "●".repeat(CarMate.fanLevel) + "○".repeat(5 - CarMate.fanLevel)
        setToggle(windowBtn, "🪟 車窗", if (CarMate.windowOpen) "開" else "關", CarMate.windowOpen)
        setToggle(mirrorBtn, "🔲 後照鏡", if (CarMate.mirrorFolded) "收起" else "展開", !CarMate.mirrorFolded)

        tripText.text = buildString {
            val a = CarMate.appointment
            if (a == null) append("還沒有行程\n按「上車」或說「找孟澤的約」")
            else {
                append("👥 ${a.friend}　⏰ ${a.time}\n📍 ${a.place}")
            }
            CarMate.lastRoute?.let { r ->
                append("\n🛣 ${r.summary}")
                r.arrival?.let { append("　抵達 $it") }
                r.lateMinutes?.let { append("\n⚠ 可能晚 $it 分鐘") }
            }
        }

        if (CarMate.lastRouteLabel.isEmpty()) {
            routeBadge.text = "等待指令"
            routeBadge.background = round(PANEL2, 20)
            routeBadge.setTextColor(MUTED)
        } else {
            val phone = CarMate.lastRouteLabel.contains("手機")
            val busy = CarMate.lastRouteLabel.contains("判斷中")
            routeBadge.text = CarMate.lastRouteLabel +
                (if (CarMate.lastRouteDetail.isNotEmpty()) "　${CarMate.lastRouteDetail}" else "")
            routeBadge.background = round(if (busy) PANEL2 else if (phone) GREEN else BLUE, 20)
            routeBadge.setTextColor(if (busy) MUTED else BG)
        }
        sayView.text = CarMate.lastSay
        heardView.text = if (CarMate.lastHeard.isEmpty()) "" else "你：${CarMate.lastHeard}"

        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
            if (HandsFree.enabled) setStroke(dp(4), GREEN)
        }
        micHint.text = if (HandsFree.enabled) "點一下說話・或直接說「副駕」" else "點一下說話"

        // DGX 的模型名稱是自動抓的：欄位沒在編輯時，顯示目前的值
        if (::dgxModelField.isInitialized && !dgxModelField.hasFocus() && dgxModelField.text.toString() != AiSource.dgxModel)
            dgxModelField.setText(AiSource.dgxModel)
        if (::aiBtn.isInitialized) aiBtn.text = if (AiSource.useDgx)
            "🖥 AI 來源：DGX（${AiSource.dgxBase}）— 點一下切回 iAI Hub"
        else "☁ AI 來源：iAI Hub（學校雲端）— 點一下切到 DGX"

        handsBtn.text = when {
            HandsFree.enabled -> "👂 免持喚醒：聆聽中（說「副駕」叫我）— 點一下關閉"
            CarMate.handsFreeWanted -> "👂 免持喚醒：上車後自動開啟 — 點一下改成不開"
            else -> "👂 免持喚醒：關閉 — 點一下開啟"
        }
        if (settingsPanel.visibility == View.VISIBLE) {
            val agent = if (MessengerAgent.history.isEmpty()) "" else
                "\n\n【App 操作】\n" + MessengerAgent.history.joinToString("\n")
            logView.text = CarMate.tripLog.joinToString("\n") + agent
        }
    }

    private fun setChip(v: TextView, label: String, ok: Boolean) {
        v.text = "● $label"
        v.setTextColor(if (ok) GREEN else MUTED)
        v.background = round(PANEL, 20, if (ok) GREEN else PANEL2)
    }

    private fun setToggle(v: TextView, label: String, state: String, on: Boolean) {
        v.text = "$label\n$state"
        v.setTextColor(if (on) TEXT else MUTED)
        v.background = pressable(if (on) PANEL2 else PANEL, 16)
    }

    // ───────────────────────── 元件小工具 ─────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** 按下去會變亮的圓角背景 */
    private fun pressable(color: Int, radiusDp: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), round(Color.parseColor("#2C3D50"), radiusDp))
        addState(intArrayOf(), round(color, radiusDp))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip() = text("", 12f, MUTED).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) }
    }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(PANEL, 18)
        setPadding(dp(14), dp(10), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        views.forEach { addView(it) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        views.forEach { addView(it) }
    }

    private fun tile(icon: String, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(PANEL, 18)
        layoutParams = LinearLayout.LayoutParams(0, dp(86), 1f).apply { bottomMargin = dp(10) }
        isClickable = true
        setOnClickListener { onClick(); render() }
        addView(text(icon, 26f, TEXT).apply { gravity = Gravity.CENTER })
        addView(text(label, 14f, TEXT, bold = true).apply { gravity = Gravity.CENTER })
    }

    private fun roundBtn(label: String, onClick: () -> Unit) = text(label, 24f, TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PANEL2) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick(); render() }
    }

    private fun smallBtn(label: String, bg: Int = PANEL2, fg: Int = TEXT, onClick: () -> Unit) =
        text(label, 13f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(bg, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) }
            setOnClickListener { onClick(); render() }
        }

    private fun toggleBtn(onClick: () -> Unit) = text("", 13f, TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        setOnClickListener { onClick(); render() }
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        textSize = 14f
        setTextColor(TEXT); setHintTextColor(MUTED)
        background = round(PANEL, 12)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
    }

    private fun column(content: View, weight: Float) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
        layoutParams = LinearLayout.LayoutParams(0, MATCH, weight)
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) }
    private fun gapH() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) }

    // ───────────────────────── 系統 ─────────────────────────

    /** 全螢幕（像車機），從邊緣滑一下可以叫出狀態列 */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** 位置（記地點）、麥克風（免持喚醒）、通知（顯示「正在聽」）：沒給過的才問 */
    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}
