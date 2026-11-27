package wasai.link.mcilm.top.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LogFileManager {
    private const val TAG = "LogFileManager"
    private const val MAX_SESSION_FILE_SIZE = 5 * 1024 * 1024L // Session 日志最大 5MB
    private const val MAX_DEBUG_FILE_SIZE = 1 * 1024 * 1024L   // debug.log 最大 1MB
    private const val CRASH_FILE_NAME = "mcilmlinkandroid_crash.log"
    private const val MAX_CRASH_FILE_SIZE = 2 * 1024 * 1024L   // 崩溃日志最大 2MB
    private const val MAX_SESSION_FILES = 10 // 最多保留最近 10 次启动的日志
    private const val CHANNEL_CAPACITY = 10_000

    // 强制使用东八区（北京时间）
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private var logDir: File? = null
    private var currentFile: File? = null
    private var debugFile: File? = null

    /** 崩溃专用文件：不经过日志 Channel，崩溃时同步写入并 fsync */
    private var crashFile: File? = null

    /** 崩溃写入使用独立锁，避免被后台日志写线程的 IO 卡住 */
    private val crashLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val logChannel = Channel<String>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var isInitialized = false

    // 脱敏正则
    private val passJsonRegex = Regex("(?i)(\"pass(?:word)?\"\\s*:\\s*\")([^\"]*)(\")")
    private val passEqRegex = Regex("(?i)(?<![a-zA-Z])(pass(?:word)?\\s*[=:]\\s*)([^\\s,;\"'}&]+)")
    private val tokenRegex = Regex("\\bmci_[a-zA-Z0-9]{10,}\\b")
    private val ipRegex = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b")

    // 扩展错误/崩溃关键字，覆盖常见的网络失败、超时等
    private val errorKeywords = listOf(
        "[Error]", "ERROR", "Exception", "fatal", "崩溃", "报错", "NullPointer",
        "失败", "异常", "超时", "timeout", "refused", "disconnect", "断开"
    )

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return

        // 【修复】外置私有目录不可用（未挂载 / 只读 / 空间满）时直接 return，
        // 会导致整个日志系统失能、崩溃日志静默丢失。现在回退到内部私有目录。
        val dir = resolveLogDir(context) ?: return

        logDir = dir
        debugFile = File(dir, "mcilmlinkandroid_debug.log")
        crashFile = File(dir, CRASH_FILE_NAME)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).apply {
            timeZone = this@LogFileManager.timeZone
        }.format(Date())

        currentFile = File(dir, "mcilmlinkandroid_$timestamp.log")

        try {
            if (!currentFile!!.exists()) currentFile!!.createNewFile()
            if (!debugFile!!.exists()) debugFile!!.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log files", e)
        }

        isInitialized = true

        scope.launch { cleanOldSessionLogs(dir) }
        scope.launch {
            for (msg in logChannel) {
                writeToFile(msg)
            }
        }
    }

    /**
     * 选择可写的日志目录：优先外部私有目录（用户能直接拿到文件），
     * 外部不可用时回退到内部私有目录，保证崩溃日志至少能落盘。
     * 两者都不可写时返回 null（此时日志系统整体失能）。
     */
    private fun resolveLogDir(context: Context): File? {
        val external = try {
            context.getExternalFilesDir("logs")
        } catch (t: Throwable) {
            Log.e(TAG, "getExternalFilesDir failed", t)
            null
        }

        if (external != null) {
            try {
                if (!external.exists() && !external.mkdirs()) {
                    Log.w(TAG, "external log dir mkdirs failed, fallback to internal")
                } else if (external.canWrite()) {
                    return external
                }
            } catch (t: Throwable) {
                Log.e(TAG, "external log dir unusable, fallback to internal", t)
            }
        }

        val internal = File(context.filesDir, "logs")
        return try {
            if (!internal.exists() && !internal.mkdirs()) null
            else if (internal.canWrite()) internal else null
        } catch (t: Throwable) {
            Log.e(TAG, "internal log dir unusable", t)
            null
        }
    }

    /**
     * 崩溃专用写入：**同步**完成，不经过日志 Channel、也不等后台写线程。
     *
     * 顺序上先写独立的 crash.log 并 fsync 落盘（此时崩溃信息已经安全），
     * 再尽力同步双写 debug.log / 当前 session 日志。这样即使进程随后被系统杀掉，
     * 崩溃堆栈与「最近日志」也不会丢。
     */
    fun writeCrashSync(
        throwable: Throwable,
        customMsg: String? = null,
        recentLogs: List<String> = emptyList()
    ) {
        val dir = logDir ?: return

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .apply { timeZone = this@LogFileManager.timeZone }
            val time = sdf.format(Date())
            val msg = customMsg ?: throwable.message ?: "Unknown Error"

            val report = buildString {
                append("========== CRASH ").append(time).append(" ==========\n")
                append("原因: ").append(msg).append('\n')
                append(sanitizeStackTrace(throwable)).append('\n')
                if (recentLogs.isNotEmpty()) {
                    append("---------- 最近日志（").append(recentLogs.size).append(" 条）----------\n")
                    recentLogs.forEach { append(it).append('\n') }
                }
                append("==================================================\n\n")
            }
            val bytes = report.toByteArray(Charsets.UTF_8)

            // 1) 独立 crash.log：独占锁 + 直接写 + fsync，保证已经落在磁盘上
            val file = crashFile ?: File(dir, CRASH_FILE_NAME)
            synchronized(crashLock) {
                try {
                    if (file.length() > MAX_CRASH_FILE_SIZE) file.delete()
                    FileOutputStream(file, true).use { out ->
                        out.write(bytes)
                        out.flush()
                        out.fd.sync()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "write crash file failed", t)
                }
            }

            // 2) 尽力同步双写常规日志（即使这里卡住，上面的崩溃文件也已经落盘）
            try {
                synchronized(this) {
                    debugFile?.let {
                        if (it.length() > MAX_DEBUG_FILE_SIZE) rotateDebugLog(it)
                        it.appendText(report)
                    }
                    currentFile?.let {
                        if (it.length() > MAX_SESSION_FILE_SIZE) rotateSessionLog(it)
                        it.appendText(report)
                    }
                }
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            Log.e(TAG, "writeCrashSync failed", t)
        }
    }

    /** 兼容旧调用：不带最近日志的崩溃写入 */
    fun writeCrash(throwable: Throwable, customMsg: String? = null) {
        writeCrashSync(throwable, customMsg)
    }

    /** 堆栈脱敏：密码 / token / IPv4 */
    private fun sanitizeStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        var safe = passJsonRegex.replace(sw.toString()) { m -> "${m.groupValues[1]}***${m.groupValues[3]}" }
        safe = passEqRegex.replace(safe) { m -> "${m.groupValues[1]}***" }
        safe = tokenRegex.replace(safe, "mci_***")
        safe = ipRegex.replace(safe, "*.*.*.*")
        return safe
    }

    /** 业务层主动捕获的普通 Error 日志，强制双写 */
    fun writeError(msg: String) {
        if (!isInitialized) return
        val safeMsg = sanitize(msg)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = this@LogFileManager.timeZone }
        val logLine = "${sdf.format(Date())} | $safeMsg\n"
        synchronized(this) {
            debugFile?.let {
                if (it.length() > MAX_DEBUG_FILE_SIZE) rotateDebugLog(it)
                it.appendText(logLine)
            }
            currentFile?.appendText(logLine)
        }
    }

    fun sanitize(msg: String): String {
        var safeMsg = msg.replace("\r", " ").replace("\n", " ")
        safeMsg = passJsonRegex.replace(safeMsg) { m -> "${m.groupValues[1]}***${m.groupValues[3]}" }
        safeMsg = passEqRegex.replace(safeMsg) { m -> "${m.groupValues[1]}***" }
        safeMsg = tokenRegex.replace(safeMsg, "mci_***")
        safeMsg = ipRegex.replace(safeMsg, "*.*.*.*")

        if (safeMsg.length > 800) { // 放宽了长度限制
            safeMsg = safeMsg.take(800) + "...(内容过长截断)"
        }
        return safeMsg
    }

    fun write(msg: String) {
        if (!isInitialized) return
        logChannel.trySend(sanitize(msg))
    }

    fun getLogFile(): File? = if (isInitialized) currentFile else null
    fun getDebugFile(): File? = if (isInitialized) debugFile else null

    private fun writeToFile(msg: String) {
        if (!isInitialized) return
        try {
            val file = currentFile ?: return
            val isError = msg.contains("[CRITICAL]") || errorKeywords.any { msg.contains(it, ignoreCase = true) }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = this@LogFileManager.timeZone }
            val logLine = "${sdf.format(Date())} | $msg\n"

            synchronized(this) {
                if (file.length() > MAX_SESSION_FILE_SIZE) rotateSessionLog(file)
                file.appendText(logLine)

                if (isError) {
                    debugFile?.let {
                        if (it.length() > MAX_DEBUG_FILE_SIZE) rotateDebugLog(it)
                        it.appendText(logLine)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write log failed", e)
        }
    }

    /** session 日志分卷：超过 5MB 时改名保留，并新建空文件继续写 */
    private fun rotateSessionLog(file: File) {
        try {
            val oldFile = File(logDir, "${file.nameWithoutExtension}_part${System.currentTimeMillis()}.log")
            if (!file.renameTo(oldFile)) {
                // 改名失败（如外部存储异常）时至少清空，避免单文件无限增长
                file.delete()
            }
            file.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Rotate session log failed", e)
        }
    }

    private fun rotateDebugLog(dbgFile: File) {
        try {
            val oldFile = File(logDir, "mcilmlinkandroid_debug_old.log")
            if (oldFile.exists()) oldFile.delete()
            if (!dbgFile.renameTo(oldFile)) dbgFile.delete()
            dbgFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Rotate debug log failed", e)
        }
    }

    private fun cleanOldSessionLogs(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("mcilmlinkandroid_") && name.endsWith(".log") && !name.contains("debug") }
            if (files != null && files.size > MAX_SESSION_FILES) {
                val sorted = files.sortedBy { it.lastModified() }
                val deleteCount = sorted.size - MAX_SESSION_FILES
                for (i in 0 until deleteCount) {
                    if (sorted[i].name != currentFile?.name) sorted[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clean old logs failed", e)
        }
    }
}