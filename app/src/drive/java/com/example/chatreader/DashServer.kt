package com.example.chatreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 網頁車機的連線（2026-11-02 雛形）
 *
 * 手機 = 車上的電腦（AI、語音、真的 Messenger）；網頁 = 車機螢幕＋模擬駕駛。
 * 電腦瀏覽器打開 http://手機IP:8765 就會載入 assets/dash.html。
 *
 * - GET  /         → 網頁車機（assets/dash.html）
 * - GET  /events   → SSE：CarMate 狀態有變就推一份 JSON（type=state）
 * - POST /api      → 網頁送來的事情（JSON，用 text/plain 送，避免跨網域預檢）：
 *     {"type":"hello"}                                  網頁連上了 → CarMate 進入網頁模式
 *     {"type":"cmd","text":"想去上次跟孟澤約的地方"}       等同駕駛說了這句話
 *     {"type":"action","name":"board|listen|end|stop|fatigue|late"}
 *     {"type":"vehicle","fuel":72,"speed":45}           模擬車況
 *     {"type":"nav", "id":3, "status":"planned|progress|arrived|failed", ...}
 *
 * 只用 Java 內建的 ServerSocket，不需要改 Gradle。只在區網用，沒有帳號密碼。
 */
object DashServer {
    private const val TAG = "DashServer"
    const val PORT = 8765

    private val main = Handler(Looper.getMainLooper())
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private var server: ServerSocket? = null
    private lateinit var app: Context
    @Volatile private var lastState = "{}"

    val connected get() = clients.isNotEmpty()

    fun start(ctx: Context) {
        if (server != null) return
        app = ctx.applicationContext
        Thread({
            try {
                val s = ServerSocket(PORT)
                server = s
                Log.i(TAG, "網頁車機伺服器啟動：${url()}")
                while (!s.isClosed) {
                    val c = s.accept()
                    Thread({ handle(c) }, "DashClient").start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "伺服器停止", e)
                server = null
            }
        }, "DashServer").start()
    }

    /**
     * 給人看的網址。手機同時有熱點和行動網路時會有好幾個 IP，全部列出來；
     * 熱點通常是 192.168.x.x 那個（電腦上 ipconfig 看到的「預設閘道」就是它）。
     */
    fun url(): String {
        val ips = localIps()
        return if (ips.isEmpty()) "http://手機IP:$PORT" else ips.joinToString(" 或 ") { "http://$it:$PORT" }
    }

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sortedBy { when { it.startsWith("192.168.") -> 0; it.startsWith("172.") -> 1; it.startsWith("10.") -> 2; else -> 3 } }
    }.getOrDefault(emptyList())

    /** CarMate 狀態有變時呼叫（主執行緒），把最新狀態推給所有網頁 */
    fun push(stateJson: String) {
        lastState = stateJson
        if (clients.isEmpty()) return
        val data = "event: state\ndata: $stateJson\n\n".toByteArray(Charsets.UTF_8)
        Thread {
            for (c in clients) {
                runCatching { synchronized(c) { c.write(data); c.flush() } }
                    .onFailure { clients.remove(c) }
            }
        }.start()
    }

    // ───────────────────────── HTTP ─────────────────────────

    private fun handle(sock: Socket) {
        try {
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()
            // 標頭一個位元組一個位元組讀到空行；本文照 Content-Length 讀位元組（中文是多位元組，不能用字元數）
            val headBytes = java.io.ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = inp.read()
                if (b < 0) break
                headBytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A || headBytes.size() > 16_384) break
            }
            val lines = headBytes.toString("UTF-8").split("\r\n")
            val parts = lines.firstOrNull()?.split(" ") ?: return sock.close()
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }.substringBefore("?")
            val length = lines.firstOrNull { it.lowercase().startsWith("content-length:") }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
            when {
                method == "OPTIONS" -> { respond(out, 204, "text/plain", ByteArray(0)); sock.close() }
                method == "GET" && path == "/events" -> openEvents(out)          // 不關閉，持續推送
                method == "GET" && (path == "/" || path == "/dash.html") -> {
                    val html = runCatching { app.assets.open("dash.html").readBytes() }
                        .getOrElse { "找不到 assets/dash.html".toByteArray(Charsets.UTF_8) }
                    respond(out, 200, "text/html; charset=utf-8", html); sock.close()
                }
                // 2026-11-18：網頁用的函式庫（three.js、Leaflet）打包在 assets/lib，會場沒網路也能開車機網頁
                method == "GET" && LIB_PATH.matches(path) -> {
                    val name = path.removePrefix("/lib/")
                    val bytes = runCatching { app.assets.open("lib/$name").readBytes() }.getOrNull()
                    if (bytes == null) respond(out, 404, "text/plain", "not found".toByteArray())
                    else respond(out, 200, libType(name), bytes)
                    sock.close()
                }
                method == "GET" && path == "/state" -> {
                    respond(out, 200, "application/json; charset=utf-8", lastState.toByteArray(Charsets.UTF_8)); sock.close()
                }
                method == "POST" && path == "/api" -> {
                    val buf = ByteArray(length.coerceIn(0, 65_536))
                    var read = 0
                    while (read < buf.size) {
                        val n = inp.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = String(buf, 0, read, Charsets.UTF_8)
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json != null) main.post { CarMate.onDash(json) }
                    respond(out, if (json != null) 200 else 400, "application/json", """{"ok":${json != null}}""".toByteArray())
                    sock.close()
                }
                else -> { respond(out, 404, "text/plain", "not found".toByteArray()); sock.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "連線錯誤：${e.message}")
            runCatching { sock.close() }
        }
    }

    private fun openEvents(out: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        synchronized(out) {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write("event: state\ndata: $lastState\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        clients.add(out)
        main.post { CarMate.onDashConnected() }
        // 每 15 秒送一行註解，連線斷掉時才會發現並移除
        Thread {
            while (clients.contains(out)) {
                Thread.sleep(15_000)
                runCatching { synchronized(out) { out.write(": ping\n\n".toByteArray()); out.flush() } }
                    .onFailure { clients.remove(out) }
            }
        }.start()
    }

    /** 只接受 /lib/檔名（英數字、點、底線、減號），不能往上層資料夾讀 */
    private val LIB_PATH = Regex("""^/lib/[A-Za-z0-9._-]+$""")

    private fun libType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }
}
