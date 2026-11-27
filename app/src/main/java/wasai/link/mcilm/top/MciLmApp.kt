package wasai.link.mcilm.top

import android.app.Application
import android.os.Process
import wasai.link.mcilm.top.data.LogFileManager
import wasai.link.mcilm.top.link.MciLmNative

/**
 * 全局 Application。
 *
 * 1. 把文件日志初始化提前到进程启动最早期（早于任何 Activity）；
 * 2. 注册全局未捕获异常处理器，保证崩溃堆栈一定能落盘。
 *
 * 【修复】此前工程既没有 Application 子类，也没有 setDefaultUncaughtExceptionHandler，
 * 于是 `LogFileManager.writeCrash()` 没有任何调用点 —— 这段"崩溃必落盘"的能力实际是死代码。
 */
class MciLmApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 尽量早地打开日志文件，后续所有 LogFileManager.write 才有落点。
        // 这里即便失败也不能让进程起不来（失败时后续崩溃处理器仍会安装，只是写不出文件）。
        try {
            LogFileManager.init(this)
        } catch (_: Throwable) {
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 关键：这里必须**同步写完并 fsync** 之后再交给系统处理器，
            // 否则进程被回收时日志只停在缓冲区里。
            try {
                LogFileManager.writeCrashSync(
                    error,
                    "未捕获异常（线程 ${thread.name}）",
                    MciLmNative.recentLogsForCrash()
                )
            } catch (_: Throwable) {
                // 崩溃路径里绝不能再抛异常，否则会替换掉原始崩溃原因
            }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 没有系统默认处理器时，手动结束进程，避免卡在异常状态
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
