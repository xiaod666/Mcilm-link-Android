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

    // 匹配是否是错误/崩溃日志的关键字
    private val errorKeywords = listOf("ERROR", "Exception", "fatal", "崩溃", "报错", "NullPointer")

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return

        val dir = try {
            context.getExternalFilesDir("logs")?.apply {
                if (!exists()) {
                    if (!mkdirs()) {
                        Log.e(TAG, "Failed to create log dir")
                        return
                    }
                }
                if (!canWrite()) {
                    Log.e(TAG, "Log dir is not writable")
                    return
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getExternalFilesDir failed", t)
            null
        }

        if (dir == null) return

        logDir = dir
        debugFile = File(dir, "mcilmlinkandroid_debug.log")

        // 时间戳精确到毫秒，防止同一秒内冷启动两次导致覆盖
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).apply {
            timeZone = this@LogFileManager.timeZone
        }.format(Date())

        currentFile = File(dir, "mcilmlinkandroid_$timestamp.log")

        // 初始化时立刻创建空文件，确保用户一启动就能分享
        try {
            if (!currentFile!!.exists()) currentFile!!.createNewFile()
            if (!debugFile!!.exists()) debugFile!!.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log files", e)
        }

        isInitialized = true

        // 启动后台协程清理旧日志
        scope.launch {
            cleanOldSessionLogs(dir)
        }

        // 启动后台协程消费常规日志队列
        scope.launch {
            for (msg in logChannel) {
                writeToFile(msg)
            }
        }
    }

    /**
     * 强制记录异常堆栈到 debug.log 及当前 session log
     */
    fun writeCrash(throwable: Throwable) {
        if (!isInitialized) return
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            var safeTrace = tokenRegex.replace(stackTrace, "mci_***")
            safeTrace = ipRegex.replace(safeTrace, "*.*.*.*")

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = this@LogFileManager.timeZone }
            val time = sdf.format(Date())
            val crashLine = "$time | [CRITICAL] CRASH: ${throwable.message}\n$safeTrace\n"

            // 同步追加
            synchronized(this) {
                val dbgFile = debugFile
                if (dbgFile != null) {
                    if (dbgFile.length() > MAX_DEBUG_FILE_SIZE) {
                        rotateDebugLog(dbgFile)
                    }
                    dbgFile.appendText(crashLine)
                }

                val file = currentFile
                if (file != null) {
                    file.appendText(crashLine)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write crash failed", e)
        }
    }

    fun sanitize(msg: String): String {
        var safeMsg = msg.replace("\r", " ").replace("\n", " ")
        safeMsg = passJsonRegex.replace(safeMsg) { m ->
            "${m.groupValues[1]}***${m.groupValues[3]}"
        }
        safeMsg = passEqRegex.replace(safeMsg) { m ->
            "${m.groupValues[1]}***"
        }
        safeMsg = tokenRegex.replace(safeMsg, "mci_***")
        safeMsg = ipRegex.replace(safeMsg, "*.*.*.*")

        if (safeMsg.length > 500) {
            safeMsg = safeMsg.take(500) + "...(内容过长截断)"
        }
        return safeMsg
    }

    fun write(msg: String) {
        if (!isInitialized) return
        val safeMsg = sanitize(msg)
        logChannel.trySend(safeMsg)
    }

    fun getLogFile(): File? {
        return if (isInitialized) currentFile else null
    }

    fun getDebugFile(): File? {
        return if (isInitialized) debugFile else null
    }

    private fun writeToFile(msg: String) {
        if (!isInitialized) return

        try {
            val file = currentFile ?: return

            val isError = msg.contains("[CRITICAL]") || errorKeywords.any { msg.contains(it, ignoreCase = true) }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = this@LogFileManager.timeZone }
            val time = sdf.format(Date())
            val logLine = "$time | $msg\n"

            synchronized(this) {
                if (file.length() > MAX_SESSION_FILE_SIZE) {
                    val oldFile = File(logDir, "${file.nameWithoutExtension}_part${System.currentTimeMillis()}.log")
                    file.renameTo(oldFile)
                    file.createNewFile()
                }
                file.appendText(logLine)

                if (isError) {
                    val dbgFile = debugFile
                    if (dbgFile != null) {
                        if (dbgFile.length() > MAX_DEBUG_FILE_SIZE) {
                            rotateDebugLog(dbgFile)
                        }
                        dbgFile.appendText(logLine)
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
            if (oldFile.exists()) {
                oldFile.delete()
            }
            if (!dbgFile.renameTo(oldFile)) {
                dbgFile.delete()
            }
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
                    if (sorted[i].name != currentFile?.name) {
                        sorted[i].delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clean old logs failed", e)
        }
    }
}