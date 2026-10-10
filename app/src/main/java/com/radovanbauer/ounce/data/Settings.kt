package com.radovanbauer.ounce.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Goals(val kcal: Int = 2000, val protein: Int = 150, val carbs: Int = 200, val fat: Int = 65)

/**
 * User preferences ("settings.xml", included in Android backup) plus the API key ("secure.xml",
 * encrypted with an Android Keystore key and excluded from backup — Keystore keys can't be restored anyway).
 */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE)

    var goals by mutableStateOf(Goals(
        prefs.getInt("kcal", 2000), prefs.getInt("protein", 150), prefs.getInt("carbs", 200), prefs.getInt("fat", 65)))
        private set
    var weightUnit by mutableStateOf(prefs.getString("unit", "lb") ?: "lb")
        private set
    var useCloud by mutableStateOf(prefs.getBoolean("useCloud", false))
        private set
    var cloudModel by mutableStateOf(prefs.getString("cloudModel", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set
    var apiKey by mutableStateOf(readKey())
        private set

    fun updateGoals(g: Goals) {
        goals = g
        prefs.edit().putInt("kcal", g.kcal).putInt("protein", g.protein).putInt("carbs", g.carbs).putInt("fat", g.fat).apply()
    }

    fun updateWeightUnit(u: String) { weightUnit = u; prefs.edit().putString("unit", u).apply() }

    fun setCloud(enabled: Boolean, model: String = cloudModel) {
        useCloud = enabled
        cloudModel = model.trim().ifEmpty { DEFAULT_MODEL }
        prefs.edit().putBoolean("useCloud", enabled).putString("cloudModel", cloudModel).apply()
    }

    fun updateApiKey(key: String) {
        apiKey = key.trim()
        if (apiKey.isEmpty()) secure.edit().clear().apply()
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
            val enc = c.doFinal(apiKey.toByteArray())
            secure.edit().putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("key", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
        }
    }

    private fun readKey(): String = try {
        val iv = secure.getString("iv", null)
        val enc = secure.getString("key", null)
        if (iv == null || enc == null) "" else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
        }
    } catch (e: Exception) {
        "" // e.g. Keystore key lost after restore to a new device
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val ALIAS = "ounce-api-key"
        const val LB_PER_KG = 2.2046226218
    }
}
