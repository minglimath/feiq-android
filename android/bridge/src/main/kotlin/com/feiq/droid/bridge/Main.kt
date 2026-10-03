package com.feiq.droid.bridge

import android.util.Log
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.Peer
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 机顶盒/桌面「桥」的最小验证程序。
 *
 * 只做一件事：绑定协议端口 → 广播上线 → 把局域网里发现的飞秋/本 App 设备打印出来。
 * 目的是验证「现有协议代码能否原封不动跑在 Linux JVM 上」。
 *
 * 运行：
 *   ./gradlew :bridge:installDist
 *   bridge/build/install/feiq-bridge/bin/feiq-bridge [端口] [昵称] [-v]
 *
 * 加 -v 会打开协议内部日志。
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("-") }
    val port = positional.getOrNull(0)?.toIntOrNull() ?: 2425
    val nick = positional.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "FeiQ-Bridge"
    Log.verbose = args.contains("-v")

    println("本机 IPv4 : ${localIp() ?: "未识别（检查网卡）"}")
    println("协议端口  : UDP/TCP $port")
    println("昵称      : $nick")
    println()

    val engine = FeiqEngine(
        FeiqEngine.Identity(
            user = nick,
            host = nick,
            nick = nick,
            group = "桥",
            pseudoMac = pseudoMac(),
            portProvider = { port },
        )
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        println()
        println("正在下线…")
        runCatching { engine.stop() }
    })

    engine.start()
    println("已上线，等待局域网设备…（Ctrl+C 退出）")
    println()

    runBlocking {
        engine.peers.collect { peers -> printPeers(peers) }
    }
}

private fun printPeers(peers: List<Peer>) {
    println("=== 发现 ${peers.size} 台设备 ===")
    if (peers.isEmpty()) {
        println("  （还没有。确认本机和对方在同一网段，且路由器没开 AP 隔离）")
    } else {
        peers.sortedBy { it.ip }.forEach { p ->
            println("  ${p.ip.padEnd(15)}  ${p.displayName}  [${p.group}]  ${p.user}@${p.host}")
        }
    }
    println()
}

/** 本机局域网 IPv4：跳过回环与未启用的网卡。 */
private fun localIp(): String? {
    try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
            if (nif.isUp && !nif.isLoopback) {
                Collections.list(nif.inetAddresses).forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}

/** 仿飞秋版本段用的 12 位 HEX 伪 MAC：优先取本机网卡 MAC，取不到用固定值。 */
private fun pseudoMac(): String {
    try {
        val mac = Collections.list(NetworkInterface.getNetworkInterfaces())
            .firstNotNullOfOrNull { it.hardwareAddress?.takeIf { b -> b.size == 6 } }
        if (mac != null) return mac.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    } catch (_: Exception) {
    }
    return FALLBACK_MAC
}

private const val FALLBACK_MAC = "FEIQBRIDGE00"
