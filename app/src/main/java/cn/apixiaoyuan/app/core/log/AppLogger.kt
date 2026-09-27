package cn.apixiaoyuan.app.core.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志收集器。
 *
 * ## 定位（对齐参考项目「老挂戏老叟」的 WeLogger，但内容是本 App 自身）
 *
 * 老挂戏老叟用 `WeLogger` 把运行日志文件化、支持多文件管理与崩溃日志。
 * 本类**结构上模仿**（按天分文件、内存环形缓冲、运行/崩溃两类），
 * 但**日志内容完全来自本项目**（网络请求、登录、设备注册、PK 提交等），
 * 不照搬对方的业务日志。
 *
 * ## 存储
 *
 *  - 运行日志：`filesDir/logs/run-yyyyMMdd.log`（按天分文件，保留最近 7 天）
 *  - 崩溃日志：`filesDir/logs/crash-<时间戳>.log`（见 [CrashCatcher]）
 *  - 内存环形缓冲：最新 [MAX_MEMORY_LINES] 条，供日志页实时展示（不必读盘）
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_MEMORY_LINES = 2000
    private const val KEEP_DAYS = 7

    private var appContext: Context? = null
    private val buffer = ArrayDeque<String>()
    private val lock = Any()

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 由 [cn.apixiaoyuan.app.App] 在 onCreate 里注入，同时清理过期日志。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val files = runFiles()
            if (files.size > KEEP_DAYS) {
                files.drop(KEEP_DAYS).forEach { it.delete() }
            }
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(timeFmt.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
        }
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_MEMORY_LINES) buffer.removeFirst()
        }
        // 同时打 logcat，便于 adb 侧排查（与文件日志互补）。
        runCatching { Log.println(levelToPriority(level), tag, msg) }
        runCatching {
            val dir = logDir() ?: return@runCatching
            File(dir, "run-${dayFmt.format(Date())}.log").appendText(line + "\n")
        }
    }

    private fun levelToPriority(level: String): Int = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }

    private fun logDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "logs").apply { if (!exists()) mkdirs() }
    }

    /** 内存快照（最新在末尾）。 */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    /** 运行日志文件（按名倒序，最新在前）。 */
    fun runFiles(): List<File> =
        logDir()?.listFiles()?.filter { it.name.startsWith("run-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** 崩溃日志文件（按名倒序，最新在前）。 */
    fun crashFiles(): List<File> =
        logDir()?.listFiles()?.filter { it.name.startsWith("crash-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    /** 清空运行日志（内存 + 文件）。 */
    fun clearRun(): Int {
        var n = 0
        runFiles().forEach { if (it.delete()) n++ }
        synchronized(lock) { buffer.clear() }
        return n
    }

    /** 清空崩溃日志。 */
    fun clearCrash(): Int {
        var n = 0
        crashFiles().forEach { if (it.delete()) n++ }
        return n
    }
}

/**
 * 全局崩溃捕获。
 *
 * 安装 `Thread.setDefaultUncaughtExceptionHandler`，把未捕获异常写到
 * `filesDir/logs/crash-<时间戳>.log`，再交回原 handler（不吞崩溃）。
 *
 * 与 [AppLogger] 同目录、同为「日志」能力的一部分，故合并在本文件。
 */
object CrashCatcher {

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(app.filesDir, "logs").apply { if (!exists()) mkdirs() }
                val f = File(dir, "crash-${System.currentTimeMillis()}.log")
                f.writeText(
                    buildString {
                        append("time=").append(Date()).append('\n')
                        append("thread=").append(thread.name).append('\n')
                        append("device=").append(android.os.Build.MODEL).append('\n')
                        append("version=").append(cn.apixiaoyuan.app.BuildConfig.VERSION_NAME).append('\n')
                        append('\n')
                        append(Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}