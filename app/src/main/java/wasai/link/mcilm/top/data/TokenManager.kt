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

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "mcilm_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            throw IllegalStateException(
                "安全存储初始化失败（AndroidKeyStore 不可用），请清除应用数据后重试。原始错误: ${t.message}",
                t
            )
        }
    }

    fun saveToken(token: String) { sharedPreferences.edit { putString("mci_token", token) } }
    fun getToken(): String? = try { sharedPreferences.getString("mci_token", null) } catch (_: Throwable) { null }
    fun clearToken() { try { sharedPreferences.edit { remove("mci_token") } } catch (_: Throwable) {} }
    fun overrideToken(token: String) { try { sharedPreferences.edit { putString("mci_token", token.trim()) } } catch (t: Throwable) { Log.e("TokenManager", "overrideToken failed", t) } }

    // ==================== 记住账号密码列表 (多账号) ====================

    data class RememberedAccount(
        val account: String,
        val password: String,
        val expireAt: Long,         // 总体过期时间 (10天)
        val lastManualLoginAt: Long, // 上次手动输密码的时间 (用于判断3天免密期)
        val lastAutoLoginAt: Long    // 上次免密登录的时间 (用于判断隔天验证)
    )

    // 仅当手动输入密码并勾选记住时调用，重置所有时间
    fun saveRememberedAccount(account: String, password: String) {
        try {
            val accounts = getRememberedAccounts().toMutableList()
            accounts.removeAll { it.account == account }

            val now = System.currentTimeMillis()
            val expireAt = now + 10L * 24 * 60 * 60 * 1000 // 总有效期 10 天

            accounts.add(0, RememberedAccount(account, password, expireAt, now, now))
            val limitedAccounts = accounts.take(5)

            val jsonArray = JSONArray()
            for (acc in limitedAccounts) {
                val obj = JSONObject()
                obj.put("account", acc.account)
                obj.put("password", acc.password)
                obj.put("expireAt", acc.expireAt)
                obj.put("lastManualLoginAt", acc.lastManualLoginAt)
                obj.put("lastAutoLoginAt", acc.lastAutoLoginAt)
                jsonArray.put(obj)
            }
            sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "saveRememberedAccount failed", t)
        }
    }

    fun getRememberedAccounts(): List<RememberedAccount> {
        return try {
            val jsonStr = sharedPreferences.getString("remembered_accounts_list", null) ?: return emptyList()
            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<RememberedAccount>()
            val now = System.currentTimeMillis()
            var needCleanup = false

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val expireAt = obj.optLong("expireAt", 0L)

                if (now <= expireAt && expireAt > 0) {
                    val acc = obj.optString("account")
                    val pwd = obj.optString("password")
                    val lastManual = obj.optLong("lastManualLoginAt", expireAt - 10 * 24 * 60 * 60 * 1000L)
                    val lastAuto = obj.optLong("lastAutoLoginAt", lastManual)

                    if (acc.isNotBlank() && pwd.isNotBlank()) {
                        result.add(RememberedAccount(acc, pwd, expireAt, lastManual, lastAuto))
                    }
                } else {
                    needCleanup = true
                }
            }

            if (needCleanup) {
                // 清理过期账号
                val newArray = JSONArray()
                for (acc in result) {
                    val obj = JSONObject()
                    obj.put("account", acc.account)
                    obj.put("password", acc.password)
                    obj.put("expireAt", acc.expireAt)
                    obj.put("lastManualLoginAt", acc.lastManualLoginAt)
                    obj.put("lastAutoLoginAt", acc.lastAutoLoginAt)
                    newArray.put(obj)
                }
                sharedPreferences.edit { putString("remembered_accounts_list", newArray.toString()) }
            }
            result
        } catch (_: Throwable) { emptyList() }
    }

    // 免密登录成功后，更新最后免密时间
    fun updateAutoLoginTime(account: String) {
        try {
            val accounts = getRememberedAccounts().toMutableList()
            val index = accounts.indexOfFirst { it.account == account }
            if (index != -1) {
                val old = accounts[index]
                accounts[index] = old.copy(lastAutoLoginAt = System.currentTimeMillis())

                val jsonArray = JSONArray()
                for (acc in accounts) {
                    val obj = JSONObject()
                    obj.put("account", acc.account)
                    obj.put("password", acc.password)
                    obj.put("expireAt", acc.expireAt)
                    obj.put("lastManualLoginAt", acc.lastManualLoginAt)
                    obj.put("lastAutoLoginAt", acc.lastAutoLoginAt)
                    jsonArray.put(obj)
                }
                sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
            }
        } catch (_: Throwable) {}
    }

    fun removeRememberedAccount(account: String) {
        try {
            val accounts = getRememberedAccounts().filter { it.account != account }
            val jsonArray = JSONArray()
            for (acc in accounts) {
                val obj = JSONObject()
                obj.put("account", acc.account)
                obj.put("password", acc.password)
                obj.put("expireAt", acc.expireAt)
                obj.put("lastManualLoginAt", acc.lastManualLoginAt)
                obj.put("lastAutoLoginAt", acc.lastAutoLoginAt)
                jsonArray.put(obj)
            }
            sharedPreferences.edit { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (_: Throwable) { }
    }
}