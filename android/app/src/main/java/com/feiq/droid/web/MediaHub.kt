package com.feiq.droid.web

import android.util.Log
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.ImageType
import com.feiq.droid.core.IncomingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件与图片的收发、暂存。
 *
 * 落盘策略：
 * - 收到的文件/图片 → `workDir/received/`，页面通过 `/api/blob?k=<key>` 下载
 * - 网页上传的文件 → `workDir/outgoing/`，交给引擎发送。**发送期间不能删**
 *   （对方是按需通过 TCP 来拉的，`sendFile` 的 openStream 会被延迟调用），
 *   所以只在退出时统一清理。
 */
class MediaHub(
    private val engine: FeiqEngine,
    private val workDir: File,
    private val emit: (event: String, data: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val blobs = ConcurrentHashMap<String, Blob>()
    private val offers = ConcurrentHashMap<String, Offer>()

    private val receivedDir = File(workDir, "received").apply { mkdirs() }
    private val outgoingDir = File(workDir, "outgoing").apply { mkdirs() }

    /** 已在本地可下载的文件或图片。 */
    data class Blob(
        val key: String,
        val peer: String,
        val name: String,
        val file: File,
        val image: Boolean,
        val time: Long,
    )

    /** 对方通告过来、还没接收的文件。 */
    data class Offer(
        val key: String,
        val peer: String,
        val packetId: String,
        val fileId: Int,
        val name: String,
        val size: Long,
        val isDir: Boolean,
    )

    fun start() {
        scope.launch {
            engine.incomingFiles.collect { f ->
                val key = offerKey(f.peerIp, f.packetId, f.fileId)
                val offer = Offer(key, f.peerIp, f.packetId, f.fileId, f.name, f.size, f.isDir)
                offers[key] = offer
                emit("file-offered", offerJson(offer))
            }
        }
        scope.launch {
            engine.inlineImages.collect { img ->
                try {
                    val (ext, _) = ImageType.sniff(img.data)
                    val f = File(receivedDir, "img_${img.imageId}.$ext")
                    f.writeBytes(img.data)
                    val blob = Blob(
                        newKey("img"), img.peerIp, f.name, f,
                        image = true, time = System.currentTimeMillis(),
                    )
                    blobs[blob.key] = blob
                    emit("image", blobJson(blob))
                } catch (e: Exception) {
                    Log.w(TAG, "保存收到的图片失败: ${e.message}")
                }
            }
        }
        scope.launch {
            engine.fileProgress.collect { p ->
                emit(
                    "progress",
                    Json.obj(
                        "fileId" to p.fileId,
                        "done" to p.done,
                        "total" to p.total,
                        "outgoing" to p.outgoing,
                    ),
                )
            }
        }
    }

    /** 接收对方通告的文件。下载是异步的，结果通过 SSE 的 file-done / file-failed 通知。 */
    fun accept(key: String) {
        val offer = offers.remove(key)
        if (offer == null) {
            emit("file-failed", Json.obj("key" to key, "error" to "这条文件通告已经处理过了"))
            return
        }
        scope.launch {
            try {
                val incoming = IncomingFile(
                    peerIp = offer.peer,
                    packetId = offer.packetId,
                    fileId = offer.fileId,
                    name = offer.name,
                    size = offer.size,
                    isDir = offer.isDir,
                )
                val dest = uniqueFile(receivedDir, offer.name)
                val n = if (offer.isDir) {
                    dest.mkdirs()
                    engine.downloadDir(incoming, dest)
                } else {
                    dest.outputStream().use { out -> engine.downloadFile(incoming, out) }
                }
                if (n >= 0) {
                    val blob = Blob(
                        newKey("f"), offer.peer, offer.name, dest,
                        image = false, time = System.currentTimeMillis(),
                    )
                    blobs[blob.key] = blob
                    emit("file-done", blobJson(blob))
                } else {
                    if (offer.isDir) dest.deleteRecursively() else dest.delete()
                    emit("file-failed", Json.obj("key" to key, "name" to offer.name))
                }
            } catch (e: Exception) {
                Log.w(TAG, "接收文件失败: ${e.message}")
                emit("file-failed", Json.obj("key" to key, "name" to offer.name, "error" to e.message))
            }
        }
    }

    /** 把网页上传的内容发给对端。inline=true 时按飞秋内联图片发送。 */
    fun upload(peer: String, name: String, body: InputStream, inline: Boolean): String {
        if (peer.isBlank()) return Json.obj("ok" to false, "error" to "没有指定对端")
        val safeName = sanitize(name).ifBlank { "upload.bin" }
        val tmp = File(outgoingDir, "up_${System.currentTimeMillis()}_$safeName")
        // 流式落盘，避免大文件整个进内存
        tmp.outputStream().use { out -> body.copyTo(out) }
        val size = tmp.length()
        if (inline) {
            if (size > MAX_INLINE_BYTES) {
                tmp.delete()
                return Json.obj(
                    "ok" to false,
                    "error" to "图片超过 ${MAX_INLINE_BYTES / 1024 / 1024}MB，请改用「发送文件」",
                )
            }
            engine.sendInlineImage(peer, tmp.readBytes())
            tmp.delete()
            return Json.obj("ok" to true, "mode" to "inline", "name" to safeName, "size" to size)
        }
        // 不能删 tmp：对方稍后才通过 TCP 来拉，openStream 会被延迟调用
        engine.sendFile(peer, safeName, size, System.currentTimeMillis()) { tmp.inputStream() }
        return Json.obj("ok" to true, "mode" to "file", "name" to safeName, "size" to size)
    }

    fun blob(key: String): Blob? = blobs[key]

    fun offersJson(): String = Json.arr(offers.values.map { offerJson(it) })

    fun blobsJson(): String = Json.arr(blobs.values.sortedByDescending { it.time }.map { blobJson(it) })

    fun stop() {
        // 上传的临时文件只在退出时清理
        try {
            outgoingDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    // ---------------- JSON ----------------

    private fun offerJson(o: Offer) = Json.obj(
        "key" to o.key,
        "peer" to o.peer,
        "name" to o.name,
        "size" to o.size,
        "isDir" to o.isDir,
        "kind" to "offer",
    )

    private fun blobJson(b: Blob) = Json.obj(
        "key" to b.key,
        "peer" to b.peer,
        "name" to b.name,
        "size" to b.file.length(),
        "image" to b.image,
        "time" to b.time,
        "url" to "/api/blob?k=${b.key}",
        "kind" to "blob",
    )

    // ---------------- 工具 ----------------

    private fun offerKey(peer: String, packetId: String, fileId: Int) = "$peer|$packetId|$fileId"

    private fun newKey(prefix: String) = "$prefix-${UUID.randomUUID()}"

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()

    private fun uniqueFile(dir: File, name: String): File {
        val safe = sanitize(name).ifBlank { "file" }
        var f = File(dir, safe)
        if (!f.exists()) return f
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$stem($i)$ext")
            i++
        }
        return f
    }

    private companion object {
        const val TAG = "MediaHub"
        const val MAX_INLINE_BYTES = 4L * 1024 * 1024
    }
}
