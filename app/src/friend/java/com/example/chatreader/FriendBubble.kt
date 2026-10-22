package com.example.chatreader

import android.annotation.SuppressLint
import android.content.Context
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
 * 交友大師的常駐泡泡 💬（浮在所有畫面上，包括 Messenger）
 *
 * - 點一下：在 Messenger 聊天室裡 → 讀目前畫面的對話 → AI 判斷情緒、想三種回覆
 *           → 懸浮窗三選一 → 填入輸入框 → 你按「送出」才會傳
 * - 拖曳：移到不會擋住聊天的位置
 * - 長按：收起泡泡（之後在交友大師 App 裡再打開）；正在處理時長按 = 停止
 *
 * 用無障礙服務的 TYPE_ACCESSIBILITY_OVERLAY，不需要「顯示在其他應用程式上層」權限。
 */
object FriendBubble {
    private const val MESSENGER = "com.facebook.orca"
    private const val PREFS = "friend"
    private const val KEY_ON = "bubble_on"

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var caption: TextView? = null
    private var icon: TextView? = null
    private var wm: WindowManager? = null
    private var savedX = -1
    private var savedY = -1
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

    // ───────────────────────── 點泡泡要做的事 ─────────────────────────

    private fun onTap() {
        if (MessengerAgent.busy) {
            say("還在處理中，稍等一下（長按泡泡可以停止）")
            return
        }
        val service = ChatReaderService.instance ?: run {
            say("無障礙服務沒有開，請到交友大師 App 打開")
            return
        }
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        if (pkg != MESSENGER) {
            say("先打開 Messenger 的聊天室，再點我")
            return
        }
        // 空白 = 用 Messenger 目前所在的聊天室；不會切換聊天室，也不會自動送出
        if (MessengerAgent.startReply("", FriendAdvisor)) {
            setBusyLook(true)
            watchUntilDone()
        }
    }

    private fun onLongPress() {
        if (MessengerAgent.busy) {
            MessengerAgent.cancel()
            say("已停止")
            return
        }
        val ctx = root?.context
        hide()
        if (ctx != null) setWanted(ctx, false)
    }

    /** 處理期間泡泡變 ⏳，結束後變回 💬 */
    private val watcher = object : Runnable {
        override fun run() {
            if (MessengerAgent.busy) main.postDelayed(this, 500) else setBusyLook(false)
        }
    }

    private fun watchUntilDone() {
        main.removeCallbacks(watcher)
        main.postDelayed(watcher, 500)
    }

    private fun setBusyLook(busy: Boolean) {
        val b = icon ?: return
        b.text = if (busy) "⏳" else "💬"
        val dp = b.resources.displayMetrics.density
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (busy) "#FF8E6BBF" else "#FF7B5CD6"))
            setStroke((3 * dp).toInt(), Color.WHITE)
        }
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
        val size = (56 * dp).toInt()
        val btn = TextView(service).apply {
            text = "💬"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "交友大師：幫我想回覆"
            setOnClickListener { onTap() }
        }
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cap, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * dp).toInt() })
            addView(btn, LinearLayout.LayoutParams(size, size))
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END   // x = 離右邊的距離
            x = if (savedX >= 0) savedX else (8 * dp).toInt()
            // 預設放在畫面右側偏上，避開 Messenger 右下角的傳送鍵
            y = if (savedY >= 0) savedY else (dm.heightPixels * 0.30).toInt()
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
            wm = w; root = box; caption = cap; icon = btn
            setBusyLook(MessengerAgent.busy)
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
        root = null; caption = null; icon = null; wm = null
        onChange?.invoke()
    }
}
