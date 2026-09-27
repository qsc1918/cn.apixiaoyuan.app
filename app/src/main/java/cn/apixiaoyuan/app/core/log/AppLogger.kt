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
            // ⚠️ 不要用 `ArrayDeque.removeFirst()`：那是 **Java 21 新增方法，
            // Android 上要求 API 35**，而本项目 minSdk=33 —— 在 33/34 机型上会
            // `NoSuchMethodError` 崩溃（CI 的 lint 已对同类的 `removeLast()` 报错）。
            // 用 `removeFirstOrNull()`（Kotlin 标准库扩展，全 API 可用）。
            while (buffer.size > MAX_MEMORY_LINES) buffer.removeFirstOrNull()
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

    /**
     * 结构化日志行。
     *
     * 从 `MM-dd HH:mm:ss.SSS L/TAG: msg` 形态的原始行里拆出三段，
     * 供日志页做**按级别过滤 / 按关键字搜索 / 按 tag 过滤**。
     * 拆不出来（多行堆栈、非标准行）时 [level] / [tag] 为 null，[raw] 仍是原文。
     */
    data class Entry(
        val time: String,
        val level: String?,
        val tag: String?,
        val message: String,
    ) {
        /** 原文（日志页需要展示与复制）。 */
        val raw: String get() = buildString {
            append(time)
            if (level != null) append(' ').append(level)
            if (tag != null) append('/').append(tag)
            append(": ").append(message)
        }
    }

    /** 行首形态：`09-27 18:31:02.123 I/Tag: msg`。 */
    private val LINE_RE = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([A-Z])/([^:]+): (.*)$""")

    /** 把一段日志文本解析为结构化行（多行堆栈会并入上一行）。 */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        text.lineSequence().forEach { line ->
            val m = LINE_RE.matchEntire(line)
            if (m != null) {
                out.add(
                    Entry(
                        time = m.groupValues[1],
                        level = m.groupValues[2],
                        tag = m.groupValues[3],
                        message = m.groupValues[4],
                    )
                )
            } else if (line.isNotBlank() && out.isNotEmpty()) {
                // 堆栈 / 续行：并入上一条，不丢信息。
                //
                // ⚠️ 用「读 + 按下标删」而不是 `ArrayList.removeLast()`：
                // 后者是 **Java 21 新增、Android 上要求 API 35**，而本项目 minSdk=33 ——
                // CI 的 lint 直接报了 `NewApi` error（真会在 33/34 机型上
                // `NoSuchMethodError` 崩）。`AppLogger.write` 本身在运行时也走
                // `ArrayDeque.removeFirst()`（同为 Java 21 API），一起换掉。
                val lastIdx = out.lastIndex
                val last = out[lastIdx]
                out.removeAt(lastIdx)
                out.add(last.copy(message = last.message + "\n" + line))
            } else if (line.isNotBlank()) {
                out.add(Entry(time = "", level = null, tag = null, message = line))
            }
        }
        return out
    }

    /**
     * 结构化查询运行日志（**待办 4**：更详细的日志系统）。
     *
     * 数据源优先内存缓冲（当天的、最新的），为空时退回最新运行日志文件 ——
     * 这样「刚启动、文件还没写」和「内存被环形缓冲截断」两种情况都能看到内容。
     *
     * @param levels   级别白名单（如 `setOf("W","E")`）；空集 = 不过滤
     * @param tagQuery tag 子串（忽略大小写）；空白 = 不过滤
     * @param keyword  正文子串（忽略大小写，匹配 message + tag）；空白 = 不过滤
     * @param limit    最多返回多少条（取**最新**的 N 条）
     */
    fun query(
        levels: Set<String> = emptySet(),
        tagQuery: String = "",
        keyword: String = "",
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<Entry> {
        val text = synchronized(lock) { buffer.joinToString("\n") }
            .ifBlank { runFiles().firstOrNull()?.let { read(it) }.orEmpty() }
        var list = parse(text)
        if (levels.isNotEmpty()) list = list.filter { it.level in levels }
        if (tagQuery.isNotBlank()) {
            list = list.filter { it.tag?.contains(tagQuery, ignoreCase = true) == true }
        }
        if (keyword.isNotBlank()) {
            list = list.filter {
                it.message.contains(keyword, ignoreCase = true) ||
                    it.tag?.contains(keyword, ignoreCase = true) == true
            }
        }
        return list.takeLast(limit)
    }

    /** 出现过的 tag（按出现次数倒序），供日志页做 tag 筛选。 */
    fun knownTags(limit: Int = 40): List<String> {
        val text = synchronized(lock) { buffer.joinToString("\n") }
        return parse(text)
            .mapNotNull { it.tag }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /** 查询默认上限（条）。超过就只看最近的，避免一次性渲染几万行。 */
    const val DEFAULT_QUERY_LIMIT = 3000

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