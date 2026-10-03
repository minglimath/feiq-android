package com.feiq.droid.bridge

import android.util.Log
import com.feiq.droid.core.ChatMessage
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.Peer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 网页桥：把 [FeiqEngine] 的能力通过 HTTP + SSE 暴露给浏览器。
 *
 * 刻意做到**零第三方依赖**，方便直接丢到机顶盒上跑：
 * - HTTP 用 JDK 自带的 `com.sun.net.httpserver`
 * - 服务端 → 浏览器 的推送用 SSE（Server-Sent Events），不用 WebSocket
 *   —— 内置 HttpServer 没有 WebSocket，而推送是单向的，SSE 用普通 HTTP 就够
 * - 浏览器 → 服务端 的操作用表单编码 POST，服务端因此不需要 JSON 解析器
 */
class Bridge(
    private val engine: FeiqEngine,
    private val httpPort: Int,
    private val protocolPort: Int,
    private val nick: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<SseClient>()
    private val recent = ArrayDeque<ChatMessage>()

    @Volatile private var peers: List<Peer> = emptyList()
    private var server: HttpServer? = null

    private class SseClient {
        val queue = LinkedBlockingQueue<String>()

        @Volatile
        var alive = true
    }

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(httpPort), 0)
        srv.executor = Executors.newCachedThreadPool()
        srv.createContext("/") { ex -> handle(ex) }
        srv.start()
        server = srv

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
        try {
            server?.stop(0)
        } catch (_: Exception) {
        }
        server = null
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
        "localIp" to (localIp() ?: ""),
        "protocolPort" to protocolPort,
        "httpPort" to httpPort,
        "peerCount" to peers.size,
    )

    // ---------------- 路由 ----------------

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        try {
            when (path) {
                "/", "/index.html" -> serveIndex(ex)
                "/api/info" -> respondJson(ex, infoJson())
                "/api/peers" -> respondJson(ex, peersJson())
                "/api/history" -> respondJson(ex, historyJson())
                "/api/events" -> sse(ex)
                "/api/send" -> sendText(ex)
                "/api/refresh" -> {
                    engine.refresh()
                    respondJson(ex, OK)
                }

                else -> respond(ex, 404, TYPE_TEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 $path 失败: ${e.message}", e)
            runCatching { respond(ex, 500, TYPE_TEXT, "error: ${e.message}") }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun serveIndex(ex: HttpExchange) {
        val html = javaClass.getResourceAsStream("/web/index.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (html == null) {
            respond(ex, 500, TYPE_TEXT, "web/index.html 没有打包进来")
        } else {
            respond(ex, 200, "text/html; charset=utf-8", html)
        }
    }

    private fun sendText(ex: HttpExchange) {
        if (ex.requestMethod != "POST") {
            respond(ex, 405, TYPE_TEXT, "请用 POST")
            return
        }
        val form = parseForm(ex)
        val peer = form["peer"].orEmpty().trim()
        val text = form["text"].orEmpty()
        if (peer.isEmpty() || text.isBlank()) {
            respondJson(ex, Json.obj("ok" to false, "error" to "peer 和 text 不能为空"))
            return
        }
        engine.sendMessage(peer, text)
        // 回显给所有页面（包括发送方自己），保证多客户端看到的内容一致
        val echo = ChatMessage(peerIp = peer, text = text, outgoing = true)
        remember(echo)
        broadcast("message", messageJson(echo))
        respondJson(ex, OK)
    }

    // ---------------- SSE ----------------

    private fun sse(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.sendResponseHeaders(200, 0)

        val out = ex.responseBody
        val client = SseClient()
        clients.add(client)
        Log.i(TAG, "SSE 客户端接入，当前 ${clients.size} 个")
        try {
            writeFrame(out, "info", infoJson())
            writeFrame(out, "peers", peersJson())
            writeFrame(out, "history", historyJson())
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

    private fun parseForm(ex: HttpExchange): Map<String, String> {
        val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        if (body.isEmpty()) return emptyMap()
        return body.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) {
                null
            } else {
                URLDecoder.decode(part.substring(0, i), "UTF-8") to
                    URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
        }.toMap()
    }

    private fun respondJson(ex: HttpExchange, body: String) =
        respond(ex, 200, "application/json; charset=utf-8", body)

    private fun respond(ex: HttpExchange, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val TAG = "Bridge"
        const val MAX_RECENT = 200
        const val TYPE_TEXT = "text/plain; charset=utf-8"
        val OK = Json.obj("ok" to true)
    }
}
