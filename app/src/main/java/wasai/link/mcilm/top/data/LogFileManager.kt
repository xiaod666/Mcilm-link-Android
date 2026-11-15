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
    private const val MAX_SESSION_FILES = 10 // 最多保留最近 10 次启动的日志
    private const val CHANNEL_CAPACITY = 10_000

    // 强制使用东八区（北京时间）
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private var logDir: File? = null
    private var currentFile: File? = null
    private var debugFile: File? = null
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

        val dir = try {
            context.getExternalFilesDir("logs")?.apply {
                if (!exists()) mkdirs()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getExternalFilesDir failed", t)
            null
        }

        if (dir == null) return

        logDir = dir
        debugFile = File(dir, "mcilmlinkandroid_debug.log")

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

    /** 强制记录异常堆栈到 debug.log */
    fun writeCrash(throwable: Throwable, customMsg: String? = null) {
        if (!isInitialized) return
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            val safeTrace = ipRegex.replace(tokenRegex.replace(stackTrace, "mci_***"), "*.*.*.*")

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = this@LogFileManager.timeZone }
            val time = sdf.format(Date())
            val msg = customMsg ?: throwable.message ?: "Unknown Error"
            val crashLine = "$time | [CRITICAL] CRASH: $msg\n$safeTrace\n"

            synchronized(this) {
                debugFile?.let {
                    if (it.length() > MAX_DEBUG_FILE_SIZE) rotateDebugLog(it)
                    it.appendText(crashLine)
                }
                currentFile?.appendText(crashLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write crash failed", e)
        }
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
                if (file.length() > MAX_SESSION_FILE_SIZE) {
                    val oldFile = File(logDir, "${file.nameWithoutExtension}_part${System.currentTimeMillis()}.log")
                    file.renameTo(oldFile)
                    file.createNewFile()
                }
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