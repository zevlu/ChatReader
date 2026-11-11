package com.example.chatreader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * CarMate 的嘴巴和耳朵，不管現在畫面在哪個 App 都能用：
 * - 說：TextToSpeech（手機內建的語音引擎）
 * - 聽：開一個透明的 VoiceActivity 叫出系統語音輸入，聽完就關掉，
 *       底下的 Google 地圖／Messenger 畫面不會被換掉
 */
object Voice {
    private const val TAG = "CarMateVoice"

    private var appCtx: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val afterSpeak = HashMap<String, () -> Unit>()
    private var speaking = 0   // 還沒唸完的句數；唸完才讓免持模式繼續聽
    private var pendingListen: ((String?) -> Unit)? = null

    fun init(ctx: Context) {
        if (tts != null) return
        val c = ctx.applicationContext
        appCtx = c
        tts = TextToSpeech(c) init@{ status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失敗：$status")
                return@init
            }
            val t = tts ?: return@init
            val r = t.setLanguage(Locale.TAIWAN)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.CHINESE)
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { finished(utteranceId) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { finished(utteranceId) }
                }
            })
            ready = true
        }
    }

    /**
     * 網頁車機（三站展示）接手聲音：手機不唸也不聽，改由電腦的喇叭唸、電腦的麥克風聽。
     * 由網頁送 {"type":"hello","silent":true} 打開（DashServer → CarMate）。
     */
    @Volatile var silent = false

    /** 唸出來（排隊，不會打斷前一句）；then = 唸完之後要做的事 */
    fun speak(text: String, then: (() -> Unit)? = null) {
        if (silent) {
            // 不唸，但「唸完之後要做的事」照樣做；大約等網頁唸完（每字 0.2 秒）
            then?.let { main.postDelayed(it, (text.length * 200L + 300L).coerceAtMost(8000L)) }
            return
        }
        val t = tts
        if (!ready || t == null) {
            then?.let { main.postDelayed(it, 500) }
            return
        }
        val id = "u${System.nanoTime()}"
        if (then != null) afterSpeak[id] = then
        speaking++
        HandsFree.pause()   // 說話時不要聽，免得聽到自己
        // emoji 和網址不要唸
        val spoken = text
            .replace(Regex("https?://\\S+"), "地圖連結")
            .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
        t.speak(spoken, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun finished(id: String?) {
        id?.let { afterSpeak.remove(it)?.invoke() }
        speaking = (speaking - 1).coerceAtLeast(0)
        if (speaking == 0) HandsFree.resume()
    }

    /** 停止說話，也取消「說完之後要做的事」 */
    fun stop() {
        afterSpeak.clear()
        tts?.stop()
        speaking = 0
        HandsFree.resume()
    }

    /** 開始聽一次；聽到的文字（或 null = 沒聽到）會在主執行緒交給 onResult */
    fun listen(prompt: String, onResult: (String?) -> Unit) {
        if (silent) return   // 網頁車機負責聽（按住空白鍵說話），手機不開語音輸入
        // 免持模式：直接用背景聆聽，不跳出語音輸入畫面
        if (HandsFree.enabled) {
            AssistantBubble.say("🎤 $prompt")
            HandsFree.listenOnce { t -> main.post { onResult(t) } }
            return
        }
        val c = appCtx ?: run { onResult(null); return }
        pendingListen = onResult
        val i = Intent(c, VoiceActivity::class.java)
            .putExtra(VoiceActivity.EXTRA_PROMPT, prompt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 由無障礙服務啟動：CarMate 在背景時 Android 也允許
        val starter: Context = ChatReaderService.instance ?: c
        try {
            starter.startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "開不了語音輸入", e)
            deliver(null)
        }
    }

    internal fun deliver(text: String?) {
        val cb = pendingListen
        pendingListen = null
        main.post { cb?.invoke(text) }
    }
}

/**
 * 透明的「聽」畫面：開系統語音輸入 → 拿到文字 → 馬上關掉。
 * 在 drive 的 AndroidManifest 設成獨立工作（taskAffinity=""），不會把 CarMate 主畫面拉到前面。
 */
class VoiceActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PROMPT = "prompt"
    }

    private var delivered = false

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            done(if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) text else null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PROMPT, intent.getStringExtra(EXTRA_PROMPT) ?: "請說…")
        }
        try {
            launcher.launch(i)
        } catch (e: Exception) {
            done(null)
        }
    }

    private fun done(text: String?) {
        if (delivered) return
        delivered = true
        Voice.deliver(text)
        finish()
    }

    override fun onDestroy() {
        // 被系統關掉、沒拿到結果，也要告訴等待的人
        if (!delivered && isFinishing) { delivered = true; Voice.deliver(null) }
        super.onDestroy()
    }
}
