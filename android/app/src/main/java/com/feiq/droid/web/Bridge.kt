package com.feiq.droid.web

import android.util.Log
import com.feiq.droid.core.ChatMessage
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.Peer
import com.feiq.droid.web.MiniHttp.Req
import com.feiq.droid.web.MiniHttp.Resp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 网页桥：把 [FeiqEngine] 的能力通过 HTTP + SSE 暴露给浏览器。
 *
 * 这个类被**手机 App 和机顶盒 JVM 共用**（放在 app 模块里，`:bridge` 通过 srcDirs 复用），
 * 所以刻意做到零第三方依赖、不碰任何 Android 专属 API：
 * - HTTP 服务端是自己写的 [MiniHttp]（Android 上没有 `com.sun.net.httpserver`）
 * - 服务端 → 浏览器 的推送用 SSE；浏览器 → 服务端 用表单/查询参数 + 原始字节上传
 * - JSON 只写不读
 *
 * 页面内容由 [pageProvider] 提供：App 端从 assets 读，机顶盒端从 classpath 读。
 */
class Bridge(
    private val engine: FeiqEngine,
    private val httpPort: Int,
    private val protocolPort: Int,
    private val nick: String,
    workDir: File,
    private val pageProvider: () -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<SseClient>()
    private val recent = ArrayDeque<ChatMessage>()
    private val hub = MediaHub(engine, workDir, ::broadcast)
    private val server = MiniHttp(httpPort) { req, resp -> handle(req, resp) }

    @Volatile
    private var peers: List<Peer> = emptyList()

    private class SseClient {
        val queue = LinkedBlockingQueue<String>()

        @Volatile
        var alive = true
    }

    fun start() {
        server.start()
        hub.start()

        scope.launch {
            engine.peers.collect { list ->
                peers = list
                broadcast("peers", peersJson())
            }
        }
        scope.launch {
            engine.incoming.collect { msg ->
                remember(msg)
                broadcast("message", messageJson(msg))
            }
        }
        Log.i(TAG, "网页服务已启动，端口 $httpPort")
    }

    fun stop() {
        server.stop()
        hub.stop()
    }

    // ---------------- 数据 ----------------

    private fun remember(msg: ChatMessage) {
        synchronized(recent) {
            recent.addLast(msg)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    private fun peersJson(): String = Json.arr(peers.map { p ->
        Json.obj(
            "ip" to p.ip,
            "name" to p.displayName,
            "group" to p.group,
            "user" to p.user,
            "host" to p.host,
        )
    })

    private fun messageJson(m: ChatMessage): String = Json.obj(
        "peer" to m.peerIp,
        "text" to m.text,
        "outgoing" to m.outgoing,
        "time" to m.time,
    )

    private fun historyJson(): String =
        Json.arr(synchronized(recent) { recent.toList() }.map { messageJson(it) })

    private fun infoJson(): String = Json.obj(
        "nick" to nick,
        "localIp" to (localIpOrEmpty()),
        "protocolPort" to protocolPort,
        "httpPort" to httpPort,
        "peerCount" to peers.size,
    )

    private fun filesJson(): String = Json.obj(
        "offers" to Json.Raw(hub.offersJson()),
        "blobs" to Json.Raw(hub.blobsJson()),
    )

    /** 由宿主覆盖（App 用 NetworkInfo，机顶盒用网卡枚举）。 */
    var localIpProvider: () -> String? = { null }

    private fun localIpOrEmpty(): String = localIpProvider() ?: ""

    // ---------------- 路由 ----------------

    private fun handle(req: Req, resp: Resp) {
        val path = req.path
        try {
            when (path) {
                "/", "/index.html" -> serveIndex(resp)
                "/api/info" -> respondJson(resp, infoJson())
                "/api/peers" -> respondJson(resp, peersJson())
                "/api/history" -> respondJson(resp, historyJson())
                "/api/files" -> respondJson(resp, filesJson())
                "/api/events" -> sse(resp)
                "/api/send" -> sendText(req, resp)
                "/api/accept" -> acceptFile(req, resp)
                "/api/upload" -> uploadFile(req, resp)
                "/api/blob" -> serveBlob(req, resp)
                "/api/refresh" -> {
                    engine.refresh()
                    respondJson(resp, OK)
                }

                else -> respond(resp, 404, TYPE_TEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 $path 失败: ${e.message}", e)
            // SSE 已经把响应头发出去了，不能再写第二个响应
            if (!resp.committed) {
                runCatching { respond(resp, 500, TYPE_TEXT, "error: ${e.message}") }
            }
        }
    }

    private fun serveIndex(resp: Resp) {
        val html = pageProvider()
        if (html == null) {
            respond(resp, 500, TYPE_TEXT, "网页文件缺失（assets/web/index.html）")
        } else {
            respond(resp, 200, "text/html; charset=utf-8", html)
        }
    }

    private fun sendText(req: Req, resp: Resp) {
        if (!requirePost(req, resp)) return
        val form = parseQuery(req.body.use { it.readBytes().toString(Charsets.UTF_8) })
        val peer = form["peer"].orEmpty().trim()
        val text = form["text"].orEmpty()
        if (peer.isEmpty() || text.isBlank()) {
            respondJson(resp, Json.obj("ok" to false, "error" to "peer 和 text 不能为空"))
            return
        }
        engine.sendMessage(peer, text)
        // 回显给所有页面（包括发送方自己），保证多客户端看到的内容一致
        val echo = ChatMessage(peerIp = peer, text = text, outgoing = true)
        remember(echo)
        broadcast("message", messageJson(echo))
        respondJson(resp, OK)
    }

    private fun acceptFile(req: Req, resp: Resp) {
        if (!requirePost(req, resp)) return
        val key = parseQuery(req.body.use { it.readBytes().toString(Charsets.UTF_8) })["key"].orEmpty()
        if (key.isEmpty()) {
            respondJson(resp, Json.obj("ok" to false, "error" to "缺少 key"))
            return
        }
        hub.accept(key)
        respondJson(resp, OK)
    }

    /** 原始字节上传：/api/upload?peer=<ip>&name=<文件名>&inline=0|1 */
    private fun uploadFile(req: Req, resp: Resp) {
        if (!requirePost(req, resp)) return
        val q = parseQuery(req.query)
        respondJson(resp, hub.upload(q["peer"].orEmpty().trim(), q["name"].orEmpty(), req.body, q["inline"] == "1"))
    }

    private fun serveBlob(req: Req, resp: Resp) {
        val key = parseQuery(req.query)["k"].orEmpty()
        val blob = hub.blob(key)
        if (blob == null || !blob.file.exists() || blob.file.isDirectory) {
            respond(resp, 404, TYPE_TEXT, "文件不存在或还没接收")
            return
        }
        val mime = URLConnection.guessContentTypeFromName(blob.file.name) ?: "application/octet-stream"
        val encodedName = URLEncoder.encode(blob.file.name, "UTF-8").replace("+", "%20")
        val disposition = (if (blob.image) "inline" else "attachment") + "; filename*=UTF-8''" + encodedName
        resp.sendStream(200, mime, blob.file.length(), mapOf("Content-Disposition" to disposition)) { out ->
            blob.file.inputStream().use { it.copyTo(out) }
        }
    }

    // ---------------- SSE ----------------

    private fun sse(resp: Resp) {
        val out = resp.startStream(
            200,
            "text/event-stream; charset=utf-8",
            mapOf("Cache-Control" to "no-cache", "X-Accel-Buffering" to "no"),
        )
        val client = SseClient()
        clients.add(client)
        Log.i(TAG, "SSE 客户端接入，当前 ${clients.size} 个")
        try {
            writeFrame(out, "info", infoJson())
            writeFrame(out, "peers", peersJson())
            writeFrame(out, "history", historyJson())
            writeFrame(out, "files", filesJson())
            while (client.alive) {
                val frame = client.queue.poll(15, TimeUnit.SECONDS)
                if (frame == null) {
                    out.write(": ping\n\n".toByteArray(Charsets.UTF_8))  // 心跳，顺便探测断开
                } else {
                    out.write(frame.toByteArray(Charsets.UTF_8))
                }
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端断开，正常路径
        } finally {
            client.alive = false
            clients.remove(client)
            Log.i(TAG, "SSE 客户端断开，剩余 ${clients.size} 个")
        }
    }

    private fun writeFrame(out: OutputStream, event: String, data: String) {
        out.write("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun broadcast(event: String, data: String) {
        val frame = "event: $event\ndata: $data\n\n"
        clients.forEach { it.queue.offer(frame) }
    }

    // ---------------- 工具 ----------------

    private fun requirePost(req: Req, resp: Resp): Boolean {
        if (req.method != "POST") {
            respond(resp, 405, TYPE_TEXT, "请用 POST")
            return false
        }
        return true
    }

    /** 解析 `a=1&b=2` 形式的查询串或表单体。 */
    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val i = part.indexOf('=')
            if (i < 0) {
                URLDecoder.decode(part, "UTF-8") to ""
            } else {
                URLDecoder.decode(part.substring(0, i), "UTF-8") to
                    URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
        }.toMap()
    }

    private fun respondJson(resp: Resp, body: String) =
        respond(resp, 200, "application/json; charset=utf-8", body)

    private fun respond(resp: Resp, code: Int, contentType: String, body: String) {
        resp.sendBytes(code, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val TAG = "Bridge"
        const val MAX_RECENT = 200
        const val TYPE_TEXT = "text/plain; charset=utf-8"
        val OK = Json.obj("ok" to true)
    }
}
