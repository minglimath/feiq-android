package com.feiq.droid.bridge

import android.util.Log
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.Peer
import com.feiq.droid.web.Bridge
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 只为拿到 classloader 去读打包进 jar 的网页文件。
 * 顶层函数里没有 `this`，用不了 `javaClass`，所以借一个空对象。
 */
private object PageAnchor

/**
 * 飞秋网页桥（机顶盒 / 桌面版启动器）。
 *
 * 绑定协议端口接入局域网飞秋设备，同时在另一个端口上开网页服务，
 * 让局域网里任何设备的浏览器都能收发消息和文件。
 *
 * 运行：
 *   ./gradlew :bridge:installDist
 *   bridge/build/install/feiq-bridge/bin/feiq-bridge [协议端口] [昵称] [网页端口] [数据目录] [-v]
 *
 * 例：feiq-bridge 2425 大屏 8080
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("-") }
    val protocolPort = positional.getOrNull(0)?.toIntOrNull() ?: 2425
    val nick = positional.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "FeiQ-Bridge"
    val httpPort = positional.getOrNull(2)?.toIntOrNull() ?: 8080
    val dataDir = File(positional.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "feiq-bridge-data")
    Log.verbose = args.contains("-v")

    val ip = localIp()
    println("本机 IPv4 : ${ip ?: "未识别（检查网卡）"}")
    println("协议端口  : UDP/TCP $protocolPort")
    println("网页端口  : $httpPort")
    println("昵称      : $nick")
    println("数据目录  : ${dataDir.absolutePath}")
    println()
    println("浏览器打开: http://${ip ?: "<本机IP>"}:$httpPort")
    println()

    val engine = FeiqEngine(
        FeiqEngine.Identity(
            user = nick,
            host = nick,
            nick = nick,
            group = "桥",
            pseudoMac = pseudoMac(),
            portProvider = { protocolPort },
        )
    )
    val bridge = Bridge(
        engine = engine,
        httpPort = httpPort,
        protocolPort = protocolPort,
        nick = nick,
        workDir = dataDir,
        // 机顶盒端从 classpath 读页面；App 端从 assets 读，两边同一份文件
        pageProvider = {
            PageAnchor::class.java.getResourceAsStream("/web/index.html")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        },
    )
    bridge.localIpProvider = { localIp() }

    Runtime.getRuntime().addShutdownHook(Thread {
        println()
        println("正在下线…")
        runCatching { bridge.stop() }
        runCatching { engine.stop() }
    })

    try {
        bridge.start()
    } catch (e: Exception) {
        println("网页服务启动失败（端口 $httpPort 可能被占用）：${e.message}")
        return
    }

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
