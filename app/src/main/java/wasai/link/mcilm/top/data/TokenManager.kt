package wasai.link.mcilm.top.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class TokenManager(context: Context) {

    // 【修复 12】使用 applicationContext 防止 Activity 泄漏
    private val appContext = context.applicationContext

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "mcilm_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.e("TokenManager", "安全存储初始化失败，尝试清理可能损坏的加密文件并重建", t)
            // 【修复 5】处理备份恢复导致 KeyStore 密钥丢失的问题：清理损坏的 prefs 文件后重试
            try {
                val prefsFile = appContext.getSharedPreferences("mcilm_secure_prefs", Context.MODE_PRIVATE)
                prefsFile.edit().clear().commit()

                // 重试一次
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    "mcilm_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (retryEx: Throwable) {
                throw IllegalStateException("安全存储彻底损坏，请清除应用数据后重试。", retryEx)
            }
        }
    }

    // ==================== Token 基础管理 ====================

    fun saveToken(token: String) {
        // 【修复 4 & 8】捕获异常并 trim
        try {
            sharedPreferences.edit { putString("mci_token", token.trim()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "saveToken failed", t)
        }
    }

    fun getToken(): String? {
        return try {
            // 【修复 8】把空字符串视作 null
            sharedPreferences.getString("mci_token", null)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e("TokenManager", "getToken failed", t)
            null
        }
    }

    fun clearToken() {
        try {
            // 【修复 9】登出属于安全敏感操作，使用 commit 确保立即写入磁盘
            sharedPreferences.edit(commit = true) { remove("mci_token") }
        } catch (t: Throwable) {
            Log.e("TokenManager", "clearToken failed", t)
        }
    }

    fun overrideToken(token: String) {
        try {
            sharedPreferences.edit { putString("mci_token", token.trim()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "overrideToken failed", t)
        }
    }

    // ==================== 记住账号密码列表 ====================

    // 【修复 3】重写 toString 防止密码泄漏到日志
    data class RememberedAccount(
        val account: String,
        val password: String,
        val expireAt: Long,
        val lastManualLoginAt: Long,
        val lastAutoLoginAt: Long
    ) {
        override fun toString(): String {
            return "RememberedAccount(account='$account', password='***', expireAt=$expireAt, lastManualLoginAt=$lastManualLoginAt, lastAutoLoginAt=$lastAutoLoginAt)"
        }
    }

    // 【修复 1】区分“读取失败”与“确实为空”
    private fun readRememberedAccountsOrNull(): List<RememberedAccount>? {
        return try {
            val jsonStr = sharedPreferences.getString("remembered_accounts_list", null)
            if (jsonStr.isNullOrBlank()) return emptyList()

            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<RememberedAccount>()
            val now = System.currentTimeMillis()
            var needCleanup = false

            for (i in 0 until jsonArray.length()) {
                // 【修复 2】单条解析容错，一条坏数据不影响其他账号
                try {
                    val obj = jsonArray.getJSONObject(i)
                    val expireAt = obj.optLong("expireAt", 0L)

                    if (now <= expireAt && expireAt > 0) {
                        val acc = obj.optString("account")
                        val pwd = obj.optString("password")
                        val lastManual = obj.optLong("lastManualLoginAt", expireAt - 10 * 24 * 60 * 60 * 1000L)
                        val lastAuto = obj.optLong("lastAutoLoginAt", lastManual)

                        if (acc.isNotBlank() && pwd.isNotBlank()) {
                            result.add(RememberedAccount(acc, pwd, expireAt, lastManual, lastAuto))
                        } else {
                            needCleanup = true // 字段缺失也清理
                        }
                    } else {
                        needCleanup = true // 标记已过期
                    }
                } catch (e: Exception) {
                    Log.w("TokenManager", "解析单条记住账号失败，跳过", e)
                    needCleanup = true
                }
            }

            // 【修复 7】cleanup 写回失败只记录日志，不影响已经解析出的结果返回
            if (needCleanup) {
                try {
                    val newArray = JSONArray()
                    for (acc in result) {
                        newArray.put(accountToJson(acc))
                    }
                    sharedPreferences.edit { putString("remembered_accounts_list", newArray.toString()) }
                } catch (t: Throwable) {
                    Log.e("TokenManager", "cleanup remembered accounts failed", t)
                }
            }
            result
        } catch (t: Throwable) {
            Log.e("TokenManager", "readRememberedAccounts彻底失败", t)
            null // 返回 null 代表读取过程出错了，调用方不应直接写回空列表
        }
    }

    fun getRememberedAccounts(): List<RememberedAccount> {
        // 对 UI 层暴露的接口，如果彻底失败则返回 emptyList
        return readRememberedAccountsOrNull() ?: emptyList()
    }

    // 【修复 6】使用 synchronized 保证读-改-写的原子性
    @Synchronized
    fun saveRememberedAccount(account: String, password: String) {
        val accounts = readRememberedAccountsOrNull() ?: run {
            Log.e("TokenManager", "由于读取旧账号列表失败，中止保存以防止数据丢失")
            return
        }

        try {
            val mutableAccounts = accounts.toMutableList()
            mutableAccounts.removeAll { it.account == account }

            val now = System.currentTimeMillis()
            val expireAt = now + 10L * 24 * 60 * 60 * 1000
            mutableAccounts.add(0, RememberedAccount(account, password, expireAt, now, now))

            val limitedAccounts = mutableAccounts.take(5)

            val jsonArray = JSONArray()
            for (acc in limitedAccounts) {
                jsonArray.put(accountToJson(acc))
            }
            sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "saveRememberedAccount failed", t)
        }
    }

    @Synchronized
    fun updateAutoLoginTime(account: String) {
        val accounts = readRememberedAccountsOrNull() ?: return
        try {
            val mutableAccounts = accounts.toMutableList()
            val index = mutableAccounts.indexOfFirst { it.account == account }
            if (index != -1) {
                val old = mutableAccounts[index]
                mutableAccounts[index] = old.copy(lastAutoLoginAt = System.currentTimeMillis())

                val jsonArray = JSONArray()
                for (acc in mutableAccounts) {
                    jsonArray.put(accountToJson(acc))
                }
                sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
            }
        } catch (t: Throwable) {
            Log.e("TokenManager", "updateAutoLoginTime failed", t)
        }
    }

    @Synchronized
    fun removeRememberedAccount(account: String) {
        // 【修复 1】读取失败直接 return，绝不写回空列表覆盖旧数据
        val accounts = readRememberedAccountsOrNull() ?: return
        try {
            val filtered = accounts.filter { it.account != account }
            val jsonArray = JSONArray()
            for (acc in filtered) {
                jsonArray.put(accountToJson(acc))
            }
            sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "removeRememberedAccount failed", t)
        }
    }

    @Synchronized
    fun clearAllRememberedAccounts() {
        try {
            sharedPreferences.edit(commit = true) { remove("remembered_accounts_list") }
        } catch (t: Throwable) {
            Log.e("TokenManager", "clearAllRememberedAccounts failed", t)
        }
    }

    // 辅助工具方法
    private fun accountToJson(acc: RememberedAccount): JSONObject {
        return JSONObject().apply {
            put("account", acc.account)
            put("password", acc.password)
            put("expireAt", acc.expireAt)
            put("lastManualLoginAt", acc.lastManualLoginAt)
            put("lastAutoLoginAt", acc.lastAutoLoginAt)
        }
    }
}