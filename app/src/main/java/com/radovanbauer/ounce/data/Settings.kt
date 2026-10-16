package com.radovanbauer.ounce.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Goals(val kcal: Int = 2000, val protein: Int = 150, val carbs: Int = 200, val fat: Int = 65)

/** Cloud AI providers usable with the user's own API key. */
enum class Provider(val id: String, val label: String, val defaultModel: String, val keyHint: String) {
    GEMINI("gemini", "Gemini", "gemini-3.8-flash", "aistudio.google.com/apikey"),
    OPENAI("openai", "OpenAI", "", "platform.openai.com/api-keys"),
    ANTHROPIC("anthropic", "Claude", "claude-opus-5-5", "console.anthropic.com/settings/keys"),
    OPENROUTER("openrouter", "OpenRouter", "", "openrouter.ai/keys");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI }
}

/**
 * User preferences ("settings.xml", included in Android backup) plus one API key per cloud provider ("secure.xml",
 * encrypted with an Android Keystore key and excluded from backup; Keystore keys can't be restored anyway).
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
    var provider by mutableStateOf(Provider.of(prefs.getString("provider", null)))
        private set
    private val keys = mutableStateMapOf<Provider, String>().apply { Provider.entries.forEach { put(it, readKey(it)) } }
    private val models = mutableStateMapOf<Provider, String>().apply {
        Provider.entries.forEach { p ->
            // "cloudModel" is where versions before multi-provider support kept the Gemini model
            val legacy = if (p == Provider.GEMINI) prefs.getString("cloudModel", null) else null
            put(p, prefs.getString("model_${p.id}", null) ?: legacy ?: p.defaultModel)
        }
    }

    fun keyFor(p: Provider): String = keys[p].orEmpty()
    fun modelFor(p: Provider): String = models[p].orEmpty()
    /** Key and model of the currently selected provider. */
    val apiKey: String get() = keyFor(provider)
    val cloudModel: String get() = modelFor(provider)
    /** The spreadsheet created by "Sync to Google Sheets", reused on later syncs. */
    var sheetId by mutableStateOf(prefs.getString("sheetId", null))
        private set
    var sheetSyncedAt by mutableStateOf(prefs.getLong("sheetSyncedAt", 0L))
        private set
    var acknowledgedDisclaimer by mutableStateOf(prefs.getBoolean("disclaimer", false))
        private set
    fun acknowledgeDisclaimer() { acknowledgedDisclaimer = true; prefs.edit().putBoolean("disclaimer", true).apply() }
    /** First-run setup finished. Installs from before onboarding existed (disclaimer seen or a key saved) skip it. */
    var onboarded by mutableStateOf(prefs.getBoolean("onboarded",
        prefs.getBoolean("disclaimer", false) || Provider.entries.any { keyFor(it).isNotBlank() }))
        private set
    fun finishOnboarding() {
        onboarded = true; acknowledgedDisclaimer = true
        prefs.edit().putBoolean("onboarded", true).putBoolean("disclaimer", true).apply()
    }
    /** Called when goals change, so the home-screen widget can refresh. */
    var onGoalsChanged: () -> Unit = {}

    fun updateSheet(id: String?) {
        sheetId = id
        sheetSyncedAt = if (id == null) 0L else System.currentTimeMillis()
        prefs.edit().putString("sheetId", id).putLong("sheetSyncedAt", sheetSyncedAt).apply()
    }

    fun updateGoals(g: Goals) {
        goals = g
        prefs.edit().putInt("kcal", g.kcal).putInt("protein", g.protein).putInt("carbs", g.carbs).putInt("fat", g.fat).apply()
        onGoalsChanged()
    }

    fun updateWeightUnit(u: String) { weightUnit = u; prefs.edit().putString("unit", u).apply() }

    fun setCloud(enabled: Boolean) {
        useCloud = enabled
        prefs.edit().putBoolean("useCloud", enabled).apply()
    }

    fun selectProvider(p: Provider) {
        provider = p
        prefs.edit().putString("provider", p.id).apply()
    }

    fun updateModel(p: Provider, model: String) {
        models[p] = model.trim()
        prefs.edit().putString("model_${p.id}", model.trim()).apply()
    }

    fun updateKey(p: Provider, key: String) {
        val k = key.trim()
        keys[p] = k
        val e = secure.edit().remove("iv_${p.id}").remove("key_${p.id}")
        if (p == Provider.GEMINI) e.remove("iv").remove("key") // legacy single-key slot
        if (k.isNotEmpty()) {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
            val enc = c.doFinal(k.toByteArray())
            e.putString("iv_${p.id}", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("key_${p.id}", Base64.encodeToString(enc, Base64.NO_WRAP))
        }
        e.apply()
    }

    private fun readKey(p: Provider): String = try {
        // the Gemini key may still sit in the single-key slot used before multi-provider support
        val iv = secure.getString("iv_${p.id}", null) ?: if (p == Provider.GEMINI) secure.getString("iv", null) else null
        val enc = secure.getString("key_${p.id}", null) ?: if (p == Provider.GEMINI) secure.getString("key", null) else null
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
        private const val ALIAS = "ounce-api-key"
        const val LB_PER_KG = 2.2046226218
    }
}
