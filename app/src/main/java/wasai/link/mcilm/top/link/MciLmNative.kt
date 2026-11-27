package wasai.link.mcilm.top.link

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import wasai.link.mcilm.top.data.AudioEngine
import wasai.link.mcilm.top.data.LogFileManager
import wasai.link.mcilm.top.data.NativeLibraryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MciLmNative {
    private const val TAG = "MciLmNative"
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    val appLogHistory: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val coreLogHistory: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // 内核日志连续重复行折叠用的状态
    private val coreLogGate = Any()
    private var lastCoreLog: String? = null
    private var lastCoreRepeat = 0

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _appLogs = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val appLogs: SharedFlow<String> = _appLogs.asSharedFlow()

    fun initFileLogging(context: Context) {
        LogFileManager.init(context)
    }

    fun logApp(msg: String) {
        Log.d("MciMlApp", msg)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.timeZone = timeZone
        val time = sdf.format(Date())

        val safeMsg = if (msg.length > 500) msg.take(500) + "...(过长截断)" else msg
        val sanitized = LogFileManager.sanitize(safeMsg)
        val formatted = "[$time] [App] $sanitized"

        appLogHistory.add(formatted)
        if (appLogHistory.size > 300) appLogHistory.removeAt(0)

        _appLogs.tryEmit(formatted)
        LogFileManager.write(formatted)
    }

    sealed interface Event {
        data class Room(val roomId: String?, val roomMeta: String?, val maxOnline: Int?, val raw: String) : Event
        data class Terminal(val kind: String, val raw: String) : Event
        data class Raw(val kind: String, val json: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun loadBridge(): Boolean {
        return try {
            System.loadLibrary("mcilm_jni")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load bridge lib failed", t)
            false
        }
    }

    suspend fun initialize(context: Context): NativeLibraryManager.LoadResult {
        if (!loadBridge()) {
            return NativeLibraryManager.LoadResult(null, null, "JNI 桥接库加载失败，请重试或重装 App。")
        }

        val manager = NativeLibraryManager(context)
        val result = manager.prepareNativeLibrary()

        if (result.fatalError != null) return result

        manager.markAttempting()

        val rc = initLibrary(result.path ?: "")
        if (rc != 0) {
            if (!result.path.isNullOrEmpty()) {
                val badFile = File(result.path)
                if (badFile.exists()) {
                    badFile.delete()
                    Log.e(TAG, "内核加载失败，已强制删除损坏的私有库: ${result.path}")
                    context.getSharedPreferences("mci_safety_prefs", Context.MODE_PRIVATE)
                        .edit { remove("last_kernel_version") }
                }

                val fallbackRc = initLibrary("")
                if (fallbackRc == 0 && !linkVersion().isNullOrEmpty()) {
                    manager.markHealthy()
                    return NativeLibraryManager.LoadResult(
                        path = null,
                        warningMsg = "检测到新内核损坏，已自动为您恢复到安全版本。",
                        fatalError = null
                    )
                }
            }
            return NativeLibraryManager.LoadResult(null, null,
                "内核动态库加载失败(rc=$rc)。您的安装包可能已损坏，请重新下载安装。")
        }

        val currentVersion = linkVersion()
        if (currentVersion.isNullOrEmpty()) {
            if (!result.path.isNullOrEmpty()) {
                val badFile = File(result.path)
                if (badFile.exists()) badFile.delete()
                context.getSharedPreferences("mci_safety_prefs", Context.MODE_PRIVATE)
                    .edit { remove("last_kernel_version") }

                val fallbackRc = initLibrary("")
                if (fallbackRc == 0 && !linkVersion().isNullOrEmpty()) {
                    manager.markHealthy()
                    return NativeLibraryManager.LoadResult(
                        path = null,
                        warningMsg = "私有内核不可用，已自动回退到内置版本。",
                        fatalError = null
                    )
                }
            }
            return NativeLibraryManager.LoadResult(null, null, "内核动态库功能异常，请重装 App。")
        }

        manager.markHealthy()
        context.getSharedPreferences("mci_safety_prefs", Context.MODE_PRIVATE)
            .edit { putString("last_kernel_version", currentVersion) }

        return result
    }

    external fun initLibrary(customPath: String): Int

    /**
     * 崩溃时附带的上下文日志：合并「应用」与「内核」两个环形缓冲，按时间排序后取最近 [max] 条。
     * 两个 history 都是 "[HH:mm:ss]" 前缀，同一天内按字符串排序等价于按时间排序。
     */
    fun recentLogsForCrash(max: Int = 80): List<String> {
        val merged = ArrayList<String>(appLogHistory.size + coreLogHistory.size)
        synchronized(appLogHistory) { merged.addAll(appLogHistory) }
        synchronized(coreLogHistory) { merged.addAll(coreLogHistory) }
        return merged.sortedBy { it }.takeLast(max)
    }

    @JvmStatic
    fun onNativeLog(msg: String) {
        Log.d(TAG, msg)

        // 内核日志已默认开启，连续完全相同的行容易把面板和文件刷满：
        // 只保留第 1 条，之后每 50 条补一条提醒仍在重复（不同内容不受影响）。
        synchronized(coreLogGate) {
            if (msg == lastCoreLog) {
                lastCoreRepeat++
                if (lastCoreRepeat % 50 != 0) return
            } else {
                lastCoreLog = msg
                lastCoreRepeat = 0
            }
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.timeZone = timeZone
        val time = sdf.format(Date())

        val safeMsg = if (msg.length > 500) msg.take(500) + "...(过长截断)" else msg
        val sanitized = LogFileManager.sanitize(safeMsg)
        val formatted = "[$time] [Core] $sanitized"

        coreLogHistory.add(formatted)
        if (coreLogHistory.size > 300) coreLogHistory.removeAt(0)

        _logs.tryEmit(formatted)
        LogFileManager.write(formatted)
    }

    @JvmStatic
    fun onNativeEvent(kind: String, json: String) {
        Log.d(TAG, "event: $kind $json")
        val ev: Event = try {
            when (kind) {
                "room" -> {
                    val o = JSONObject(json)
                    val roomId = if (o.has("room_id")) o.getString("room_id") else null
                    val roomMeta = if (o.has("room_meta")) o.getString("room_meta") else null
                    val maxOnline = if (o.has("max_online")) o.optInt("max_online") else null
                    Event.Room(roomId, roomMeta, maxOnline, json)
                }
                "room_closed", "kicked", "banned", "fatal" -> Event.Terminal(kind, json)
                else -> Event.Raw(kind, json)
            }
        } catch (_: Throwable) {
            Event.Raw(kind, json)
        }
        _events.tryEmit(ev)
    }

    // [新增] 音频采集回调（引擎线程调用，直接转发给 AudioEngine）
    @JvmStatic
    fun onAudioRead(buf: ByteArray): Int {
        return try { AudioEngine.onAudioRead(buf) } catch (t: Throwable) { -1 }
    }

    // [新增] 音频播放回调
    @JvmStatic
    fun onAudioWrite(buf: ByteArray, len: Int): Int {
        return try { AudioEngine.onAudioWrite(buf, len) } catch (t: Throwable) { 0 }
    }

    external fun linkVersion(): String?

    @Suppress("unused")
    external fun linkRunning(): Boolean

    external fun linkSetLog(enable: Boolean)
    external fun linkSetEvent(enable: Boolean)
    external fun linkSetDiscovery(token: String, thresholdMs: Int)

    // [新增] 音频注入：enable=true 注册回调，false 清除
    external fun linkSetAudio(enable: Boolean): Int

    // [新增] 附加参数（如 "minecraft=25565"），空串 = 清除
    external fun linkSetExtra(extras: String): Int

    external fun linkHostStart(server: String, room: String, key: String, pass: String, features: String, ports: String, sni: String, plain: Int): Int
    external fun linkClientStart(server: String, room: String, key: String, pass: String, ports: String, sni: String, plain: Boolean, forceRelay: Boolean, preferPeers: Boolean): Int
    external fun linkQuery(what: String): String?
    external fun linkCommand(line: String): String?
    external fun linkStop()
}