package com.example.chatreader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 交友小精靈的常駐泡泡 🧚（浮在所有畫面上）
 *
 * 點一下 → 依照目前在哪個 App 跳出小選單：
 * - Messenger 聊天室：💬 幫我回／📋 聊天前小抄／📌 記下重點／🪞 聊完復盤
 * - Facebook（之後 Instagram）：🔍 看看這個人（記下名字、興趣、近況，給破冰話題）
 * - 其他地方：📒 打開記憶本
 * 拖曳：移到不會擋住畫面的位置
 * 長按：收起泡泡（之後在 App 裡再打開）；正在處理時長按 = 停止
 *
 * 用無障礙服務的 TYPE_ACCESSIBILITY_OVERLAY，不需要「顯示在其他應用程式上層」權限。
 * 這種懸浮窗不能打字，所以要勾選、改名字的畫面改用 CompanionActivity。
 */
object FriendBubble {
    const val MESSENGER = "com.facebook.orca"
    const val FACEBOOK = "com.facebook.katana"
    const val INSTAGRAM = "com.instagram.android"
    private const val PREFS = "friend"
    private const val KEY_ON = "bubble_on"
    private const val ICON = "🧚"

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var caption: TextView? = null
    private var menu: LinearLayout? = null
    private var icon: TextView? = null
    private var wm: WindowManager? = null
    private var savedX = -1
    private var savedY = -1
    private var working = false
    private val hideCaption = Runnable { caption?.visibility = View.GONE }

    /** 泡泡狀態有變時通知（主執行緒），MainActivity 用來更新按鈕文字 */
    var onChange: (() -> Unit)? = null

    val showing get() = root != null

    /** 使用者希望泡泡開著嗎（記在手機裡，下次打開 App 自動顯示） */
    fun wanted(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setWanted(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    // ───────────────────────── 點泡泡：跳出選單 ─────────────────────────

    private fun onTap() {
        if (menu?.visibility == View.VISIBLE) { closeMenu(); return }
        if (MessengerAgent.busy || CompanionActions.busy) {
            say("還在處理中，稍等一下（長按泡泡可以停止）")
            return
        }
        val service = ChatReaderService.instance ?: run {
            say("無障礙服務沒有開，請到交友大師 App 打開")
            return
        }
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        val items: List<Pair<String, () -> Unit>> = when (pkg) {
            MESSENGER -> listOf(
                "💬 幫我回" to {
                    if (CompanionActions.replyHelp(service)) { setWorking(true); watchUntilDone() }
                },
                "📋 聊天前小抄" to { CompanionActions.brief(service) },
                "📌 記下重點" to { CompanionActions.remember(service) },
                "🪞 聊完復盤" to { CompanionActions.debrief(service) },
            )
            FACEBOOK, INSTAGRAM -> listOf(
                "🔍 看看這個人" to { CompanionActions.scan(service) },
            )
            else -> listOf(
                "📒 打開記憶本" to { openApp(service) },
            )
        }
        if (pkg != MESSENGER && pkg != FACEBOOK && pkg != INSTAGRAM) {
            say("在 Messenger 聊天室或 Facebook 上點我，會有更多功能")
        }
        openMenu(items)
    }

    private fun onLongPress() {
        closeMenu()
        if (MessengerAgent.busy) {
            MessengerAgent.cancel()
            say("已停止")
            return
        }
        if (CompanionActions.busy) {
            say("小精靈正在等 AI 回覆，等一下就好")
            return
        }
        val ctx = root?.context
        hide()
        if (ctx != null) setWanted(ctx, false)
    }

    private fun openApp(ctx: Context) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    private fun openMenu(items: List<Pair<String, () -> Unit>>) {
        val m = menu ?: return
        val dp = m.resources.displayMetrics.density
        m.removeAllViews()
        items.forEach { (label, action) ->
            m.addView(TextView(m.context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                val p = (12 * dp).toInt()
                setPadding(p, (9 * dp).toInt(), p, (9 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F06A4BC4"))
                    cornerRadius = 18 * dp
                }
                setOnClickListener { closeMenu(); action() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt(); gravity = Gravity.END })
        }
        m.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menu?.visibility = View.GONE
    }

    /** M2 處理期間泡泡變 ⏳，結束後變回 🧚 */
    private val watcher = object : Runnable {
        override fun run() {
            if (MessengerAgent.busy) main.postDelayed(this, 500) else setWorking(false)
        }
    }

    private fun watchUntilDone() {
        main.removeCallbacks(watcher)
        main.postDelayed(watcher, 500)
    }

    /** 處理中 ⏳／平常 🧚 */
    fun setWorking(busy: Boolean) {
        working = busy
        val b = icon ?: return
        b.text = if (busy) "⏳" else ICON
        val dp = b.resources.displayMetrics.density
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (busy) "#FF8E6BBF" else "#FF7B5CD6"))
            setStroke((3 * dp).toInt(), Color.WHITE)
        }
    }

    /** 暫時藏起來（截圖、小精靈視窗打開時），不會改變「使用者要不要泡泡」的設定 */
    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) closeMenu()
    }

    // ───────────────────────── 顯示與拖曳 ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val service = ChatReaderService.instance ?: return false
        val w = service.getSystemService(WindowManager::class.java)
        val dm = service.resources.displayMetrics
        val dp = dm.density
        val slop = ViewConfiguration.get(service).scaledTouchSlop

        val cap = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = (220 * dp).toInt()
            visibility = View.GONE
            val p = (10 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6202832"))
                cornerRadius = 12 * dp
            }
        }
        val menuBox = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        val left = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(cap)
            addView(menuBox)
        }
        val size = (56 * dp).toInt()
        val btn = TextView(service).apply {
            text = ICON
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "交友小精靈"
            setOnClickListener { onTap() }
        }
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(left, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * dp).toInt() })
            addView(btn, LinearLayout.LayoutParams(size, size))
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,   // 點選單外面 → 收起選單
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END   // x = 離右邊的距離
            x = if (savedX >= 0) savedX else (8 * dp).toInt()
            // 預設放在畫面右側偏上，避開 Messenger 右下角的傳送鍵
            y = if (savedY >= 0) savedY else (dm.heightPixels * 0.30).toInt()
        }
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }

        // 拖曳／點一下／長按
        btn.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var longFired = false
            val longPress = Runnable { longFired = true; onLongPress() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        dragging = false; longFired = false
                        main.postDelayed(longPress, 800)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            main.removeCallbacks(longPress)
                            closeMenu()
                        }
                        if (dragging) {
                            params.x = (startX - dx).toInt().coerceAtLeast(0)   // 往右拖 = 離右邊更近
                            params.y = (startY + dy).toInt().coerceAtLeast(0)
                            savedX = params.x; savedY = params.y
                            runCatching { w.updateViewLayout(box, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPress)
                        if (!dragging && !longFired) v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
                }
                return true
            }
        })

        val ok = runCatching {
            w.addView(box, params)
            wm = w; root = box; caption = cap; icon = btn; menu = menuBox
            setWorking(MessengerAgent.busy || CompanionActions.busy)
            true
        }.getOrDefault(false)
        onChange?.invoke()
        return ok
    }

    /** 在泡泡旁顯示一句話，5 秒後自動收起 */
    fun say(text: String) {
        val c = caption ?: return
        c.text = text
        c.visibility = View.VISIBLE
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, 5000)
    }

    fun hide() {
        val r = root ?: return
        main.removeCallbacks(watcher)
        runCatching { wm?.removeView(r) }
        root = null; caption = null; icon = null; menu = null; wm = null
        onChange?.invoke()
    }
}
