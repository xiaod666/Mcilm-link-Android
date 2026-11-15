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
            try {
                val prefsFile = appContext.getSharedPreferences("mcilm_secure_prefs", Context.MODE_PRIVATE)
                prefsFile.edit().clear().commit()

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

    fun saveToken(token: String) {
        try { sharedPreferences.edit { putString("mci_token", token.trim()) } } catch (t: Throwable) { Log.e("TokenManager", "saveToken failed", t) }
    }

    fun getToken(): String? {
        return try { sharedPreferences.getString("mci_token", null)?.takeIf { it.isNotBlank() } } catch (t: Throwable) { null }
    }

    fun saveCurrentAccount(account: String) {
        try { sharedPreferences.edit { putString("current_account", account) } } catch (_: Throwable) {}
    }

    fun getCurrentAccount(): String? = try { sharedPreferences.getString("current_account", null) } catch (t: Throwable) { null }

    fun clearToken() {
        try {
            sharedPreferences.edit(commit = true) {
                remove("mci_token")
                remove("current_account")
            }
        } catch (t: Throwable) { Log.e("TokenManager", "clearToken failed", t) }
    }

    fun overrideToken(token: String) {
        try { sharedPreferences.edit { putString("mci_token", token.trim()) } } catch (t: Throwable) { Log.e("TokenManager", "overrideToken failed", t) }
    }

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

    private fun readRememberedAccountsOrNull(): List<RememberedAccount>? {
        return try {
            val jsonStr = sharedPreferences.getString("remembered_accounts_list", null)
            if (jsonStr.isNullOrBlank()) return emptyList()

            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<RememberedAccount>()
            val now = System.currentTimeMillis()
            var needCleanup = false

            for (i in 0 until jsonArray.length()) {
                try {
                    val obj = jsonArray.getJSONObject(i)
                    val expireAt = obj.optLong("expireAt", 0L)

                    if (now <= expireAt && expireAt > 0) {
                        val acc = obj.optString("account")
                        val pwd = obj.optString("password")
                        val lastManual = obj.optLong("lastManualLoginAt", expireAt - 10 * 24 * 60 * 60 * 1000L)
                        val lastAuto = obj.optLong("lastAutoLoginAt", lastManual)

                        if (acc.isNotBlank()) {
                            result.add(RememberedAccount(acc, pwd, expireAt, lastManual, lastAuto))
                        } else {
                            needCleanup = true
                        }
                    } else {
                        needCleanup = true
                    }
                } catch (e: Exception) {
                    Log.w("TokenManager", "解析单条记住账号失败，跳过", e)
                    needCleanup = true
                }
            }

            if (needCleanup) {
                try {
                    val newArray = JSONArray()
                    for (acc in result) {
                        newArray.put(accountToJson(acc))
                    }
                    sharedPreferences.edit(commit = true) { putString("remembered_accounts_list", newArray.toString()) }
                } catch (t: Throwable) {
                    Log.e("TokenManager", "cleanup remembered accounts failed", t)
                }
            }
            result
        } catch (t: Throwable) {
            Log.e("TokenManager", "readRememberedAccounts彻底失败", t)
            null
        }
    }

    fun getRememberedAccounts(): List<RememberedAccount> {
        return readRememberedAccountsOrNull() ?: emptyList()
    }

    @Synchronized
    fun saveRememberedAccount(account: String, password: String) {
        val accounts = readRememberedAccountsOrNull() ?: return
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
        val accounts = readRememberedAccountsOrNull() ?: return
        try {
            val filtered = accounts.filter { it.account != account }
            val jsonArray = JSONArray()
            for (acc in filtered) {
                jsonArray.put(accountToJson(acc))
            }
            sharedPreferences.edit(commit = true) { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "removeRememberedAccount failed", t)
        }
    }

    @Synchronized
    fun invalidateAccountPassword(account: String) {
        val accounts = readRememberedAccountsOrNull() ?: return
        try {
            val mutableAccounts = accounts.toMutableList()
            val index = mutableAccounts.indexOfFirst { it.account == account }
            if (index != -1) {
                val old = mutableAccounts[index]
                mutableAccounts[index] = old.copy(password = "")

                val jsonArray = JSONArray()
                for (acc in mutableAccounts) {
                    jsonArray.put(accountToJson(acc))
                }
                sharedPreferences.edit(commit = true) { putString("remembered_accounts_list", jsonArray.toString()) }
            }
        } catch (t: Throwable) {
            Log.e("TokenManager", "invalidateAccountPassword failed", t)
        }
    }

    // 【新增】如果无法确定当前账号，直接作废所有本地免密
    @Synchronized
    fun invalidateAllPasswords() {
        val accounts = readRememberedAccountsOrNull() ?: return
        try {
            val mutableAccounts = accounts.map { it.copy(password = "") }
            val jsonArray = JSONArray()
            for (acc in mutableAccounts) {
                jsonArray.put(accountToJson(acc))
            }
            sharedPreferences.edit(commit = true) { putString("remembered_accounts_list", jsonArray.toString()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "invalidateAllPasswords failed", t)
        }
    }

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