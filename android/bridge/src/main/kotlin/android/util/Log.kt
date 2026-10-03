package android.util

/**
 * JVM 上的 `android.util.Log` 垫片。
 *
 * 复用过来的协议源码（net/ 全部 + core/FeiqEngine）只依赖 Android 的这一个类，
 * 给它提供一个最小实现，就能让这些代码**原封不动**跑在桌面 JVM 上，
 * 不必去改动 app 模块的共享源码（避免为了桥而污染 Android 端）。
 *
 * 只实现源码里实际用到的 d/i/w/e，签名与 Android 保持一致（含带 Throwable 的重载）。
 */
object Log {
    /** 打开后输出 d/i/w；默认只输出 e，避免协议内部日志刷屏。 */
    @Volatile
    var verbose: Boolean = false

    @JvmStatic
    fun d(tag: String?, msg: String?): Int = log("D", tag, msg)

    @JvmStatic
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = log("D", tag, msg, tr)

    @JvmStatic
    fun i(tag: String?, msg: String?): Int = log("I", tag, msg)

    @JvmStatic
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = log("I", tag, msg, tr)

    @JvmStatic
    fun w(tag: String?, msg: String?): Int = log("W", tag, msg)

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = log("W", tag, msg, tr)

    @JvmStatic
    fun e(tag: String?, msg: String?): Int = log("E", tag, msg)

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = log("E", tag, msg, tr)

    private fun log(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        // 只有 e 默认输出；其余等 -v 打开，免得协议内部日志把发现结果淹没
        if (level != "E" && !verbose) return 0
        val line = "[$level] ${tag ?: "-"}: ${msg ?: ""}"
        if (level == "E") System.err.println(line) else println(line)
        tr?.printStackTrace()
        return 0
    }
}
