package wasai.link.mcilm.top.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun saveToken(token: String) {
        sharedPreferences.edit { putString("mci_token", token) }
    }

    fun getToken(): String? = try {
        sharedPreferences.getString("mci_token", null)
    } catch (_: Throwable) {
        null
    }

    fun clearToken() {
        try {
            sharedPreferences.edit { remove("mci_token") }
        } catch (_: Throwable) { /* 已损坏也视为清除成功 */ }
    }

    // [新增] 强制覆盖 Token（开发者模式使用）
    fun overrideToken(token: String) {
        try {
            sharedPreferences.edit { putString("mci_token", token.trim()) }
        } catch (t: Throwable) {
            Log.e("TokenManager", "overrideToken failed", t)
        }
    }
}