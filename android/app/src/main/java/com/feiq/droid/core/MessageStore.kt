package com.feiq.droid.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * 简单的聊天记录持久化：每个对端一个 JSON 文件，存在公共目录 `Download/FeiQ/chat`。
 * 免依赖（用 org.json），适合中小规模聊天记录。
 *
 * 目录按需解析（而非构造时缓存），这样用户中途授予「所有文件访问」权限后
 * 无需重启即可切换到公共目录。
 */
class MessageStore(private val context: Context) {
    private val dir: File get() = Storage.chatDir(context)
    private val imgDir: File get() = Storage.imagesDir(context)

    /** 旧版内部目录：迁移完成前仍从这里回退读取，避免历史记录丢失。 */
    private val legacyDir: File get() = File(context.filesDir, "chat")

    /** 图片持久化目录（内联图片、收发图片、表情副本）。 */
    fun imageDir(): File = imgDir

    private fun fileFor(peerIp: String) = File(dir, sanitize(peerIp) + ".json")
    private fun legacyFileFor(peerIp: String) = File(legacyDir, sanitize(peerIp) + ".json")

    /** 列出磁盘上所有有聊天记录的对端 IP（供会话列表用）。 */
    fun listPeers(): List<String> {
        val names = LinkedHashSet<String>()
        listOf(dir, legacyDir).forEach { d ->
            d.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.forEach { names.add(it.name.removeSuffix(".json")) }
        }
        return names.toList()
    }

    /** 删除某对端的聊天文件（新旧目录都删）。 */
    fun deletePeer(peerIp: String) {
        try { fileFor(peerIp).delete() } catch (_: Exception) {}
        try { legacyFileFor(peerIp).delete() } catch (_: Exception) {}
    }

    fun load(peerIp: String): MutableList<ChatRecord> {
        val f = fileFor(peerIp).takeIf { it.exists() } ?: legacyFileFor(peerIp)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val pairs = Storage.legacyPathPairs(context)
            MutableList(arr.length()) { remapPaths(ChatRecord.fromJson(arr.getJSONObject(it)), pairs) }
        } catch (e: Exception) {
            Log.w("MessageStore", "load ${f.name} failed: ${e.message}")
            mutableListOf()
        }
    }

    /**
     * 数据迁移到公共目录后，把记录里指向旧目录的绝对路径改写到新位置。
     * 只有新位置的文件确实存在时才改写，所以未授权（没迁移）时行为不变。
     */
    private fun remapPaths(rec: ChatRecord, pairs: List<Pair<String, String>>): ChatRecord {
        if (pairs.isEmpty()) return rec
        var out = rec
        rec.filePath?.let { p ->
            val n = remapPath(p, pairs)
            if (n != p) out = out.copy(filePath = n)
        }
        rec.imagePath?.let { p ->
            val n = remapPath(p, pairs)
            if (n != p) out = out.copy(imagePath = n)
        }
        return out
    }

    private fun remapPath(path: String, pairs: List<Pair<String, String>>): String {
        pairs.forEach { (old, new) ->
            if (path.startsWith(old)) {
                val candidate = new + path.substring(old.length)
                if (File(candidate).exists()) return candidate
            }
        }
        return path
    }

    fun loadPage(peerIp: String, fromIndex: Int, limit: Int): List<ChatRecord> {
        val all = load(peerIp)
        if (all.isEmpty()) return emptyList()
        val start = fromIndex.coerceAtLeast(0).coerceAtMost(all.size)
        val end = (start + limit).coerceAtMost(all.size)
        return if (start >= end) emptyList() else all.subList(start, end)
    }

    fun search(peerIp: String, query: String, limit: Int = 200): List<ChatRecord> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return load(peerIp).asReversed().filter {
            it.text.contains(q, ignoreCase = true) ||
                it.fileName.contains(q, ignoreCase = true)
        }.take(limit)
    }

    fun searchAll(query: String, limit: Int = 300): List<Pair<String, ChatRecord>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, ChatRecord>>()
        listPeers().forEach { ip ->
            load(ip).asReversed().forEach { rec ->
                if (rec.text.contains(q, ignoreCase = true) ||
                    rec.fileName.contains(q, ignoreCase = true)
                ) {
                    out.add(ip to rec)
                }
            }
        }
        return out.sortedByDescending { it.second.time }.take(limit)
    }

    fun exportText(peerIp: String): String {
        val sb = StringBuilder()
        load(peerIp).forEach { r ->
            sb.append('[').append(r.time).append("] ")
            when (r.dir) {
                ChatRecord.DIR_OUT -> sb.append("OUT ")
                ChatRecord.DIR_IN -> sb.append("IN ")
                else -> sb.append("SYS ")
            }
            when (r.kind) {
                ChatRecord.KIND_FILE -> sb.append("[FILE] ").append(r.fileName)
                ChatRecord.KIND_IMAGE -> sb.append("[IMAGE]").append(r.imagePath ?: "")
                else -> sb.append(r.text)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun allPeerFiles(): List<Pair<String, ChatRecord>> {
        val out = ArrayList<Pair<String, ChatRecord>>()
        listPeers().forEach { ip ->
            load(ip).forEach { rec ->
                if (rec.kind == ChatRecord.KIND_FILE) out.add(ip to rec)
            }
        }
        return out
    }

    /** 覆盖保存整份记录（追加后调用；记录量不大，整写简单可靠）。 */
    fun save(peerIp: String, list: List<ChatRecord>) {
        try {
            val arr = JSONArray()
            list.takeLast(MAX_KEEP).forEach { arr.put(it.toJson()) }
            fileFor(peerIp).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w("MessageStore", "save failed: ${e.message}")
        }
    }

    private fun sanitize(ip: String) = ip.replace(Regex("[^0-9A-Za-z._-]"), "_")

    companion object { private const val MAX_KEEP = 50000 }
}
