package com.example.chatreader

import android.annotation.SuppressLint
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
 * 一直浮在畫面上的 🎤 泡泡（導航、Messenger 上都看得到）。
 * - 點一下：跟 CarMate 說話
 * - 拖曳：移到你要的位置
 * - 長按：關掉泡泡（也會關掉免持喚醒）
 * 旁邊的小字幕顯示 CarMate 剛說的話。
 * 用無障礙服務的 TYPE_ACCESSIBILITY_OVERLAY，不需要「顯示在其他應用程式上層」權限。
 */
object AssistantBubble {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var caption: TextView? = null
    private var icon: TextView? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var savedX = -1
    private var savedY = -1
    private val hideCaption = Runnable { caption?.visibility = View.GONE }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    val showing get() = root != null

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
            maxWidth = (240 * dp).toInt()
            visibility = View.GONE
            val p = (10 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6202832"))
                cornerRadius = 12 * dp
            }
        }
        val size = (64 * dp).toInt()
        val btn = TextView(service).apply {
            text = "🎤"
            textSize = 26f
            gravity = Gravity.CENTER
            setOnClickListener { onTap?.invoke() }
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
            y = if (savedY >= 0) savedY else
                (maxOf(dm.widthPixels, dm.heightPixels) * 0.38).toInt()
                    .coerceAtMost(minOf(dm.widthPixels, dm.heightPixels) - size - (16 * dp).toInt())
        }

        // 拖曳／點一下／長按
        btn.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var longFired = false
            val longPress = Runnable { longFired = true; onLongPress?.invoke() }

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

        return runCatching {
            w.addView(box, params)
            wm = w; root = box; caption = cap; icon = btn; lp = params
            setHandsFree(HandsFree.enabled)
            true
        }.getOrDefault(false)
    }

    /** 免持喚醒開著時，泡泡外框變綠色 */
    fun setHandsFree(on: Boolean) {
        val b = icon ?: return
        val dp = b.resources.displayMetrics.density
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FF1E88E5"))
            setStroke((3 * dp).toInt(), if (on) Color.parseColor("#FF4CAF50") else Color.WHITE)
        }
    }

    /** 在泡泡旁顯示一句話，8 秒後自動收起 */
    fun say(text: String) {
        val c = caption ?: return
        c.text = text
        c.visibility = View.VISIBLE
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, 8000)
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm?.removeView(r) }
        root = null; caption = null; icon = null; wm = null; lp = null
    }
}
