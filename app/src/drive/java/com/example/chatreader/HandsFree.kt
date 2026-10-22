package com.example.chatreader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 免持喚醒：上車後在背景一直聽，聽到「副駕」或「CarMate」就回應。
 *
 * - 用系統的 SpeechRecognizer 一段一段聽（不跳出畫面），不需要另外的喚醒詞引擎
 * - CarMate 說話時暫停，避免聽到自己的聲音
 * - CarMate 問問題時（listenOnce），下一句話直接當作回答，不必先喊喚醒詞
 * - Android 規定 App 在背景用麥克風要有前景服務（CarMateMicService，通知列會顯示）
 */
object HandsFree {
    private const val TAG = "CarMateHandsFree"

    /** 喚醒詞（含語音辨識常見的同音錯字）；比對前會去掉空白、轉小寫 */
    val WAKE_WORDS = listOf("副駕駛", "副駕", "富家", "副家", "附駕", "carmate", "卡美", "卡梅", "卡妹")

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var sr: SpeechRecognizer? = null

    var enabled = false
        private set
    private var paused = false
    private var running = false
    private var oneShot: ((String?) -> Unit)? = null
    private var oneShotMisses = 0

    /** 聽到喚醒詞：參數是喚醒詞後面的話（可能是空字串） */
    var onWake: ((String) -> Unit)? = null

    private val loopRunnable = Runnable { loop() }

    /** 開始免持（要在 App 畫面在前景時呼叫，例如按「上車」）。回傳 false = 開不起來 */
    fun start(context: Context): Boolean {
        if (enabled) return true
        val c = context.applicationContext
        ctx = c
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "沒有麥克風權限")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            Log.w(TAG, "這支手機沒有可用的語音辨識服務")
            return false
        }
        val ok = runCatching {
            ContextCompat.startForegroundService(c, Intent(c, CarMateMicService::class.java))
        }.isSuccess
        if (!ok) return false
        sr = SpeechRecognizer.createSpeechRecognizer(c).apply { setRecognitionListener(listener) }
        enabled = true
        paused = false
        running = false
        main.postDelayed(loopRunnable, 800)
        return true
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        main.removeCallbacks(loopRunnable)
        runCatching { sr?.destroy() }
        sr = null
        running = false
        oneShot?.let { cb -> oneShot = null; cb(null) }
        ctx?.let { c -> runCatching { c.stopService(Intent(c, CarMateMicService::class.java)) } }
    }

    /** CarMate 說話時暫停 */
    fun pause() {
        if (!enabled) return
        paused = true
        main.removeCallbacks(loopRunnable)
        if (running) {
            runCatching { sr?.cancel() }
            running = false
        }
    }

    fun resume() {
        if (!enabled) return
        paused = false
        main.removeCallbacks(loopRunnable)
        main.postDelayed(loopRunnable, 400)
    }

    /** 下一句話直接交給 cb（回答問題用）；連續幾次沒聽到就給 null */
    fun listenOnce(cb: (String?) -> Unit) {
        oneShot = cb
        oneShotMisses = 0
        if (!paused && !running) {
            main.removeCallbacks(loopRunnable)
            main.post(loopRunnable)
        }
    }

    private fun loop() {
        if (!enabled || paused || running) return
        val r = sr ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx?.packageName)
        }
        running = true
        runCatching { r.startListening(i) }.onFailure {
            Log.w(TAG, "startListening 失敗", it)
            running = false
            main.postDelayed(loopRunnable, 1000)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            running = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.i(TAG, "聽到：$list")
            handle(list)
            if (enabled && !paused) main.postDelayed(loopRunnable, 250)
        }

        override fun onError(error: Int) {
            running = false
            if (!enabled) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.w(TAG, "麥克風權限不足，關閉免持")
                    stop()
                    return
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> missOneShot()
                else -> Log.w(TAG, "辨識錯誤 $error")
            }
            if (paused) return
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1000L
                else -> 250L
            }
            main.postDelayed(loopRunnable, delay)
        }
    }

    private fun missOneShot() {
        val cb = oneShot ?: return
        oneShotMisses++
        if (oneShotMisses >= 3) {
            oneShot = null
            cb(null)
        }
    }

    private fun handle(list: List<String>) {
        val text = list.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) { missOneShot(); return }

        // CarMate 正在等回答：直接交給它（但如果先喊了喚醒詞，把喚醒詞去掉）
        val cb = oneShot
        if (cb != null) {
            oneShot = null
            cb(afterWake(text) ?: text)
            return
        }
        // 平常：只對喚醒詞有反應
        for (alt in list) {
            val rest = afterWake(alt) ?: continue
            onWake?.invoke(rest)
            return
        }
    }

    /** 有喚醒詞就回傳喚醒詞後面的話，沒有就回傳 null */
    private fun afterWake(s: String): String? {
        val norm = s.replace(" ", "")
        val lower = norm.lowercase()
        for (w in WAKE_WORDS) {
            val i = lower.indexOf(w)
            if (i >= 0) return norm.substring(i + w.length).trim('，', ',', '。', '、', '!', '！', '?', '？', ' ')
        }
        return null
    }
}

/** 免持模式的前景服務：讓 CarMate 在背景（地圖、Messenger 在前面時）也能用麥克風 */
class CarMateMicService : Service() {
    companion object {
        private const val CHANNEL = "carmate_handsfree"
        private const val NOTIFY_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "CarMate 免持喚醒", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("CarMate 正在聽")
            .setContentText("說「副駕」或「CarMate」叫我")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, type) else startForeground(NOTIFY_ID, n)
        } catch (e: Exception) {
            Log.w("CarMateHandsFree", "前景服務開不起來", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
