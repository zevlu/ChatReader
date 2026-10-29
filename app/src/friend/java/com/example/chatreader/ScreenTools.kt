package com.example.chatreader

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 第二期：讀「任何 App」目前畫面的工具（Facebook、之後的 Instagram）
 *
 * Facebook 的節點結構還沒實測，所以先用通用做法：
 * 把畫面上看得到的文字全部收集起來 ＋ 截一張圖，一起交給 AI 判斷。
 * 每次都會把節點樹存成 fb_tree.txt、文字存成 fb_text.txt，讀不好時拿來調整規則。
 */
object ScreenTools {
    private const val TAG = "ScreenTools"
    private const val MAX_CHARS = 3000

    /** 收集畫面上「使用者看得到」的文字（text 和 contentDescription），去重複，最多 3000 字 */
    fun visibleText(root: AccessibilityNodeInfo): String {
        val seen = LinkedHashSet<String>()
        var total = 0
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || total > MAX_CHARS) return
            if (n.isVisibleToUser) {
                listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).forEach { raw ->
                    val t = raw.trim()
                    if (t.length >= 2 && seen.add(t)) total += t.length + 1
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(root)
        return seen.joinToString("\n").take(MAX_CHARS)
    }

    /** 存下節點樹和文字（除錯用，在 /sdcard/Android/data/<套件>/files/） */
    fun dumpForDebug(service: AccessibilityService, root: AccessibilityNodeInfo, text: String) {
        runCatching {
            val dir = service.getExternalFilesDir(null)
            val sb = StringBuilder()
            fun visit(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null) return
                val r = Rect().also { n.getBoundsInScreen(it) }
                sb.append("  ".repeat(depth))
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(" id=").append(n.viewIdResourceName ?: "-")
                    .append(" text=").append(n.text?.toString()?.replace("\n", "⏎") ?: "-")
                    .append(" desc=").append(n.contentDescription?.toString()?.replace("\n", "⏎") ?: "-")
                    .append(" bounds=").append(r.toShortString())
                    .append(if (n.isVisibleToUser) "" else " [看不到]")
                    .append(if (n.isClickable) " [可點]" else "")
                    .append('\n')
                for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
            }
            visit(root, 0)
            File(dir, "fb_tree.txt").writeText(sb.toString())
            File(dir, "fb_text.txt").writeText(text)
        }.onFailure { Log.w(TAG, "存除錯檔失敗", it) }
    }

    /**
     * 截整個螢幕（Android 11 以上），最多等 3 秒；失敗回傳 null。
     * ⚠️ 一定要在背景執行緒呼叫（結果會送到主執行緒，在主執行緒等會卡死）。
     */
    fun screenshotSync(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    // 3 = 呼叫太頻繁
                    Log.w(TAG, "截圖失敗 errorCode=$errorCode")
                    latch.countDown()
                }
            },
        )
        latch.await(3, TimeUnit.SECONDS)
        return result
    }
}
