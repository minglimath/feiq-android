package com.feiq.droid.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.feiq.droid.web.Bridge

/**
 * 网页桥的生命周期管理（手机端）。
 *
 * 手机上的飞秋 App 本就在 2425 上监听、有前台服务保活，让它**自己**再开一个网页服务，
 * 局域网里任何设备的浏览器就能收发消息和文件 —— 不需要在机顶盒或电脑上装 Java。
 *
 * 网页服务层在 `com.feiq.droid.web`，与机顶盒版共用同一份代码。
 */
object WebServer {
    private const val TAG = "WebServer"

    @Volatile
    private var bridge: Bridge? = null

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    /** 最近一次启动失败的原因，供设置页展示。 */
    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = bridge != null

    /** 按当前设置启停。设置页改了开关后调用它即可，不用重启 App。 */
    fun sync(ctx: Context, engine: FeiqEngine) {
        if (Prefs.webEnabled(ctx)) start(ctx, engine) else stop()
    }

    private fun start(ctx: Context, engine: FeiqEngine) {
        if (bridge != null) return
        val port = Prefs.webPort(ctx)
        try {
            val b = Bridge(
                engine = engine,
                httpPort = port,
                protocolPort = Prefs.port(ctx),
                nick = Prefs.nick(ctx),
                // 收到的网页文件落在公共目录里，和 App 自己收的文件在同一处
                workDir = Storage.root(ctx),
                pageProvider = {
                    ctx.assets.open("web/index.html")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                },
            )
            b.localIpProvider = { NetworkInfo.localIp() }
            b.start()
            bridge = b
            lastError = null
            acquireWifiLock(ctx)
            Log.i(TAG, "网页服务已启动：${url(ctx) ?: "(IP 未识别)"}")
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "网页服务启动失败: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            bridge?.stop()
        } catch (_: Exception) {
        }
        bridge = null
        releaseWifiLock()
    }

    /** 给用户看的访问地址；未运行或取不到 IP 时返回 null。 */
    fun url(ctx: Context): String? {
        if (bridge == null) return null
        val ip = NetworkInfo.localIp() ?: return null
        return "http://$ip:${Prefs.webPort(ctx)}"
    }

    /**
     * 网页会话通常比 App 会话长得多（人可能一整天开着标签页），
     * 持一个 WifiLock，免得 Wi-Fi 进省电模式导致连接掉线或变慢。
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock(ctx: Context) {
        if (wifiLock != null) return
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "feiq-web").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock 获取失败（不影响功能）: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }
}
