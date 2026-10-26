package com.radovanbauer.ounce.ai

import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.radovanbauer.ounce.data.MealItem
import com.radovanbauer.ounce.data.Provider
import com.radovanbauer.ounce.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** A food line proposed by a model (not yet saved). */
data class ItemDraft(val name: String, val grams: Double, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double) {
    fun toItem(mealId: Long = 0, position: Int = 0) = MealItem(0, mealId, name, grams, kcal, protein, carbs, fat, position)
}

fun MealItem.toLine() = "$name; ${grams.r()}; ${kcal.r()}; ${protein.r1()}; ${carbs.r1()}; ${fat.r1()}"
private fun Double.r() = Math.round(this).toString()
private fun Double.r1() = (Math.round(this * 10) / 10.0).toString()

object Prompts {
    private const val FORMAT = "food name; grams; kcal; protein g; carbs g; fat g"

    /** Identical to the benchmarked prompt (bench/nutri.py) — change both together. */
    fun photo(note: String) = buildString {
        append("""
            You estimate nutrition from meal photos. List every food and drink in the photo, one per line, exactly in this format:
            $FORMAT
            Use generic USDA-style names (e.g. "white rice, cooked", "chicken breast, grilled", "olive oil").
            Estimate the weight in grams from visual cues like plate size and utensils. List likely cooking oil, butter, sauces and dressings as separate lines.
            Output only these lines. No header, no totals, no other text.
        """.trimIndent())
        if (note.isNotBlank()) append("\nNote from the person who ate it: ").append(note.trim())
    }

    fun text(description: String) = """
        You estimate nutrition. The person ate: "${description.trim()}"
        List every food and drink, one per line, exactly in this format:
        $FORMAT
        Use typical portion sizes when the amount isn't stated. Use generic USDA-style names.
        Output only these lines. No header, no totals, no other text.
    """.trimIndent()

    fun edit(items: List<MealItem>, instruction: String) = """
        Here is a logged meal, one food per line in this format: $FORMAT
        ITEMS

        The person says: "${instruction.trim()}"
        Rewrite the complete list with this change applied.
        - The person may call a listed food by another name (for example "toast" for bread, "rice" for "white rice, cooked", "coffee" for latte). Change that existing line; never add a second line for a food that is already listed.
        - When a portion changes, scale grams and every nutrition value of that line proportionally. A slice of bread is about 30 grams.
        - Remove foods they say they didn't eat. Add a new line only for a food that isn't in the list yet.
        - Copy every other line exactly.
        Output only the lines in the same format. No other text.
    """.trimIndent().replace("ITEMS", items.joinToString("\n") { it.toLine() })
}

private val NUM = Regex("""-?\d+(?:[.,]\d+)?""")

/** Parse "name; grams; kcal; protein; carbs; fat" lines leniently, with sanity fixes for small-model mistakes. */
fun parseItems(text: String): List<ItemDraft> = text.lines().mapNotNull { raw ->
    val line = raw.trim().trimStart('-', '*', '•', ' ').replace("**", "").replace('|', ';')
    val parts = line.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 3) return@mapNotNull null
    val name = parts[0].trim('"')
    if (name.lowercase().let { it.startsWith("food name") || it.startsWith("total") }) return@mapNotNull null
    val n = parts.drop(1).map { p -> NUM.find(p)?.value?.replace(',', '.')?.toDoubleOrNull()?.let { max(0.0, it) } }
    val grams = n.getOrNull(0) ?: return@mapNotNull null
    var kcal = n.getOrNull(1) ?: 0.0
    var p = n.getOrNull(2) ?: 0.0
    var c = n.getOrNull(3) ?: 0.0
    var f = n.getOrNull(4) ?: 0.0
    if (kcal == 0.0) kcal = 4 * p + 4 * c + 9 * f
    if (grams > 0 && kcal > grams * 9.5) kcal = grams * 9.0 // nothing is denser than pure fat
    val macroKcal = 4 * p + 4 * c + 9 * f
    if (grams > 0 && p + c + f > grams * 1.1 && macroKcal > 0) { // macros heavier than the food itself
        val s = kcal / macroKcal
        p *= s; c *= s; f *= s
    }
    ItemDraft(name.replaceFirstChar { it.uppercase() }, grams, kcal, p, c, f)
}

fun titleFor(items: List<ItemDraft>): String {
    val names = items.sortedByDescending { it.kcal }.take(2).map { it.name.substringBefore(',').trim() }
    return when (names.size) {
        0 -> "Meal"
        1 -> names[0]
        else -> "${names[0]} & ${names[1].lowercase()}"
    }
}

// ------------------------------------------------------------------------------------------- engines

interface AiEngine {
    val label: String
    suspend fun generate(prompt: String, image: Bitmap? = null): String
}

class AiUnavailable(message: String) : Exception(message)

/** Gemini Nano through ML Kit GenAI Prompt API (AICore). Runs fully on-device; foreground only. */
class NanoEngine : AiEngine {
    override val label = "Gemini Nano (on-device)"
    private val model: GenerativeModel by lazy { Generation.getClient(generationConfig { }) }

    enum class Status { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    suspend fun status(): Status = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            else -> Status.UNAVAILABLE
        }
    } catch (e: Exception) { Status.UNAVAILABLE }

    fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun generate(prompt: String, image: Bitmap?): String {
        when (status()) {
            Status.AVAILABLE -> {}
            Status.DOWNLOADABLE, Status.DOWNLOADING -> throw AiUnavailable("The on-device model is still downloading. Try again in a few minutes, or add a Gemini API key in Settings.")
            Status.UNAVAILABLE -> throw AiUnavailable("On-device AI isn't available on this phone. Add a Gemini API key in Settings, or add foods manually.")
        }
        val request = if (image != null) generateContentRequest(ImagePart(image.scaledTo(768)), TextPart(prompt)) {
            temperature = 0.2f; maxOutputTokens = 512
        } else generateContentRequest(TextPart(prompt)) { temperature = 0.2f; maxOutputTokens = 512 }
        return try {
            model.generateContent(request).candidates.firstOrNull()?.text ?: ""
        } catch (e: Exception) {
            val m = e.message ?: ""
            throw AiUnavailable(when {
                "quota" in m.lowercase() -> "On-device AI is rate-limited right now. Wait a minute and try again."
                "policy" in m.lowercase() -> "The on-device model declined this photo. Try another angle, describe the meal instead, or add foods manually."
                else -> "On-device AI failed: $m"
            })
        }
    }
}

/** Cloud Gemini with the user's own API key. */
/**
 * Gemini over its REST API. Free-tier keys often get "503 model overloaded" (and per-model 429 quotas) on the newest
 * model, so a busy model is retried once and then the call falls back to older Flash models, then Flash-Lite.
 * [label] names the model that actually answered.
 */
class GeminiEngine(private val model: String, private val apiKey: String) : AiEngine {
    override var label = model
        private set

    override suspend fun generate(prompt: String, image: Bitmap?): String = withContext(Dispatchers.IO) {
        val parts = JSONArray()
        if (image != null) {
            val jpeg = ByteArrayOutputStream().also { image.scaledTo(1536).compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
            .toString()

        var busy: Busy = try {
            return@withContext call(model, body).also { label = model }
        } catch (e: Busy) { e }
        delay(2_000)
        try {
            return@withContext call(model, body).also { label = model }
        } catch (e: Busy) { busy = e }
        for (m in fallbacks()) {
            try {
                return@withContext call(m, body).also { label = m }
            } catch (e: Busy) { busy = e }
        }
        throw AiUnavailable(if (busy.code == 429)
            "Gemini's free quota is used up for now. Try again in a minute, or later today if it keeps happening."
        else "Gemini is overloaded right now (all models busy). Try again in a minute.")
    }

    /** Older Flash models, newest first, then Flash-Lite; from the live model list, cached per process. */
    private suspend fun fallbacks(): List<String> {
        val ids = cachedIds ?: runCatching { ModelCatalog.list(Provider.GEMINI, apiKey).map { it.id } }.getOrNull()
            ?.also { cachedIds = it } ?: return emptyList()
        fun version(id: String) = Regex("""gemini-([\d.]+)-""").find(id)?.groupValues?.get(1)?.split(".")
            ?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
        val byVersion = compareByDescending<String> { version(it).getOrElse(0) { 0 } }.thenByDescending { version(it).getOrElse(1) { 0 } }
        val flash = ids.filter { Regex("""^gemini-[\d.]+-flash$""").matches(it) && it != model }.sortedWith(byVersion)
        val lite = ids.filter { Regex("""^gemini-[\d.]+-flash-lite$""").matches(it) && it != model }.sortedWith(byVersion)
        return (flash.take(2) + lite.take(1))
    }

    private class Busy(val code: Int) : Exception()

    /** One request; throws [Busy] when the model is overloaded or out of quota, so the caller can try another. */
    private fun call(model: String, body: String): String {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text)
                throw when (code) {
                    429, 500, 503 -> Busy(code)
                    400, 401, 403 -> AiUnavailable("Gemini rejected the request ($code): ${msg.take(200)}. Check your API key and model in Settings.")
                    404 -> AiUnavailable("Gemini doesn't know the model \"$model\". Pick another one in Settings.")
                    else -> AiUnavailable("Gemini error $code: ${msg.take(200)}")
                }
            }
            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            return (0 until out.length()).map { out.getJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
        } catch (e: java.io.IOException) {
            throw AiUnavailable("Couldn't reach Gemini (${e.message}). Check your connection.")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        @Volatile private var cachedIds: List<String>? = null
    }
}

/** Picks the engine: the user's cloud key when enabled, otherwise on-device Nano. */
class Ai(private val settings: Settings) {
    val nano = NanoEngine()

    fun engine(): AiEngine =
        if (settings.useCloud && settings.apiKey.isNotBlank() && settings.cloudModel.isNotBlank())
            cloudEngine(settings.provider, settings.apiKey, settings.cloudModel) else nano

    suspend fun analyzePhoto(photo: Bitmap, note: String): Pair<List<ItemDraft>, String> = run(Prompts.photo(note), photo)
    suspend fun analyzeText(description: String): Pair<List<ItemDraft>, String> = run(Prompts.text(description), null)
    suspend fun edit(items: List<MealItem>, instruction: String): Pair<List<ItemDraft>, String> = run(Prompts.edit(items, instruction), null)

    private suspend fun run(prompt: String, image: Bitmap?): Pair<List<ItemDraft>, String> {
        val e = engine()
        val items = parseItems(e.generate(prompt, image))
        if (items.isEmpty()) throw AiUnavailable("The AI didn't return any foods. Try again, describe the meal in words, or add foods manually.")
        return items to e.label
    }
}

fun Bitmap.scaledTo(maxSide: Int): Bitmap {
    val s = maxSide.toFloat() / maxOf(width, height)
    return if (s >= 1f) this else Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
}
