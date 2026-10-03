package com.feiq.droid.web

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 极简 HTTP/1.1 服务端，只实现网页桥需要的那部分。
 *
 * 为什么不用 `com.sun.net.httpserver`：**Android 上不存在这个包**（它是 JDK 私有实现，
 * 安卓运行时没有）。而桥要能同时跑在机顶盒 JVM 和手机 App 里，所以基于 `ServerSocket`
 * 自己实现，两端共用同一份代码、零依赖。
 *
 * 支持：GET/POST、Content-Length 与 chunked 请求体、流式响应（SSE 需要长连接）。
 * 每连接一个线程——局域网里就几个浏览器，够用。
 */
class MiniHttp(
    private val port: Int,
    private val handler: (Req, Resp) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    fun start() {
        val s = ServerSocket(port)
        server = s
        running = true
        pool.execute {
            while (running) {
                val sock = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { serve(sock) }
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        pool.shutdownNow()
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 30_000  // 读请求阶段限时，防止半开连接占线程
            val input = BufferedInputStream(sock.getInputStream(), 8192)
            val out = BufferedOutputStream(sock.getOutputStream(), 8192)
            val req = readRequest(input, sock)
            if (req != null) {
                sock.soTimeout = 0  // 响应阶段可能很长（SSE），不限时
                handler(req, Resp(out))
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端断开是常态
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readRequest(input: InputStream, sock: Socket): Req? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) target.substring(q + 1) else null

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }

        val body: InputStream = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                ChunkedInputStream(input)

            else -> {
                val len = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
                if (len > 0) LimitedInputStream(input, len) else EmptyInputStream
            }
        }
        return Req(method, path, query, headers, body, sock.inetAddress?.hostAddress ?: "")
    }

    /** 按行读（只用于请求行与头部，都是 ASCII 或百分号编码后的内容）。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null  // 头部异常，直接放弃
        }
    }

    /** 一次请求。body 只在 handler 内有效，读完即随连接关闭。 */
    class Req(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: InputStream,
        val remote: String,
    )

    /** 一次响应。每个请求只能写一次。 */
    class Resp(private val out: OutputStream) {

        /** 响应头是否已经写出。SSE 已经开流之后就不能再写第二个响应了。 */
        var committed: Boolean = false
            private set

        fun sendText(status: Int, contentType: String, text: String) =
            sendBytes(status, contentType, text.toByteArray(Charsets.UTF_8))

        fun sendBytes(
            status: Int,
            contentType: String,
            bytes: ByteArray,
            extra: Map<String, String> = emptyMap(),
        ) {
            writeHead(status, contentType, bytes.size.toLong(), extra)
            out.write(bytes)
            out.flush()
        }

        /** 已知长度：流式写出去，不把整个文件读进内存。 */
        fun sendStream(
            status: Int,
            contentType: String,
            length: Long,
            extra: Map<String, String> = emptyMap(),
            write: (OutputStream) -> Unit,
        ) {
            writeHead(status, contentType, if (length > 0) length else 0L, extra)
            write(out)
            out.flush()
        }

        /**
         * 未知长度的长连接（SSE 用）。
         * 刻意不写 Content-Length、也不用 chunked：靠 `Connection: close` + 关闭连接表示结束，
         * 浏览器和 EventSource 都能正确处理。
         */
        fun startStream(
            status: Int,
            contentType: String,
            extra: Map<String, String> = emptyMap(),
        ): OutputStream {
            writeHead(status, contentType, null, extra)
            out.flush()
            return out
        }

        private fun writeHead(status: Int, contentType: String, length: Long?, extra: Map<String, String>) {
            committed = true
            val sb = StringBuilder(160)
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
            extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }

    private object EmptyInputStream : InputStream() {
        override fun read(): Int = -1
    }

    private class LimitedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            val n = src.read(b, off, want)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }

    /** chunked 请求体解码。浏览器的 fetch 带 File 时一般用 Content-Length，这里只是兜底。 */
    private class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var chunkLeft = 0L
        private var done = false

        private fun nextChunk(): Boolean {
            if (done) return false
            if (chunkLeft > 0) return true
            val sizeLine = readAsciiLine(src) ?: run { done = true; return false }
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: run { done = true; return false }
            if (size <= 0L) {
                readAsciiLine(src)  // 吃掉结尾的空行
                done = true
                return false
            }
            chunkLeft = size
            return true
        }

        override fun read(): Int {
            if (!nextChunk()) return -1
            val b = src.read()
            if (b >= 0) {
                chunkLeft--
                if (chunkLeft == 0L) readAsciiLine(src)  // 块尾 CRLF
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!nextChunk()) return -1
            val want = minOf(len.toLong(), chunkLeft).toInt()
            val n = src.read(b, off, want)
            if (n > 0) {
                chunkLeft -= n
                if (chunkLeft == 0L) readAsciiLine(src)
            }
            return n
        }
    }

    private companion object {
        fun readAsciiLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 1024) return null
            }
        }
    }
}
