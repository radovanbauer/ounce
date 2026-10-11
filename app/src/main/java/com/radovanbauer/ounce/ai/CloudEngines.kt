package com.radovanbauer.ounce.ai

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.radovanbauer.ounce.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal fun Bitmap.jpegBase64(maxSide: Int = 1536): String =
    Base64.encodeToString(ByteArrayOutputStream().also { scaledTo(maxSide).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        .toByteArray(), Base64.NO_WRAP)

/** Small JSON-over-HTTP helper for the providers called without an SDK. */
internal fun httpJson(url: String, method: String, headers: Map<String, String>, body: JSONObject?, provider: String): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(text).let { o -> o.optJSONObject("error")?.optString("message") ?: o.optString("message") }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200)
            throw AiUnavailable(when (code) {
                401, 403 -> "$provider rejected the API key ($code). Check it in Settings."
                404 -> "$provider doesn't know that model: ${msg.take(160)}"
                429 -> "$provider rate limit or quota reached. Try again shortly."
                else -> "$provider error $code: ${msg.take(200)}"
            })
        }
        return JSONObject(text)
    } catch (e: java.io.IOException) {
        throw AiUnavailable("Couldn't reach $provider (${e.message}). Check your connection.")
    } finally {
        conn.disconnect()
    }
}

/** OpenAI Chat Completions; also OpenRouter, which speaks the same API for hundreds of models. */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val providerName: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : AiEngine {
    override val label = model

    override suspend fun generate(prompt: String, image: Bitmap?): String = withContext(Dispatchers.IO) {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (image != null) content.put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${image.jpegBase64()}")))
        // no temperature: reasoning models reject it, and the default works for every model
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val res = httpJson("$baseUrl/chat/completions", "POST",
            mapOf("Authorization" to "Bearer $apiKey") + extraHeaders, body, providerName)
        val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiUnavailable("$providerName returned no answer.")
        when (val c = msg.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString("")
            else -> throw AiUnavailable(msg.optString("refusal").ifBlank { "$providerName returned an empty answer." })
        }
    }
}

/** Claude through Anthropic's official Java SDK. */
class AnthropicEngine(private val model: String, private val apiKey: String) : AiEngine {
    override val label = model

    override suspend fun generate(prompt: String, image: Bitmap?): String = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val blocks = mutableListOf<ContentBlockParam>()
            if (image != null) blocks += ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder().data(image.jpegBase64()).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                .build())
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build())
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .addUserMessageOfBlockParams(blocks)
            if (model in REFUSAL_FALLBACK_MODELS) {
                // a classifier refusal on these models is retried server-side on a suitable model
                params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val msg = client.messages().create(params.build())
            if (msg.stopReason().map { it.asString() }.orElse("") == "refusal")
                throw AiUnavailable("Claude declined this request. Try another photo, or describe the meal instead.")
            msg.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        } catch (e: AnthropicServiceException) {
            throw AiUnavailable(when (e.statusCode()) {
                401, 403 -> "Anthropic rejected the API key (${e.statusCode()}). Check it in Settings."
                404 -> "Anthropic doesn't know the model \"$model\"."
                429 -> "Anthropic rate limit reached. Try again shortly."
                529 -> "Anthropic is overloaded right now. Try again shortly."
                else -> "Anthropic error ${e.statusCode()}: ${e.message?.take(200)}"
            })
        } catch (e: AnthropicIoException) {
            throw AiUnavailable("Couldn't reach Anthropic (${e.message}). Check your connection.")
        } finally {
            client.close()
        }
    }

    companion object {
        /** Models that accept the server-side refusal fallback ("default" mode). */
        val REFUSAL_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}

fun cloudEngine(p: Provider, apiKey: String, model: String): AiEngine = when (p) {
    Provider.GEMINI -> GeminiEngine(model, apiKey)
    Provider.OPENAI -> OpenAiCompatEngine("https://api.openai.com/v1", apiKey, model, "OpenAI")
    Provider.ANTHROPIC -> AnthropicEngine(model, apiKey)
    Provider.OPENROUTER -> OpenAiCompatEngine("https://openrouter.ai/api/v1", apiKey, model, "OpenRouter",
        mapOf("HTTP-Referer" to "https://github.com/radovanbauer/ounce", "X-Title" to "Ounce"))
}

data class ModelOption(val id: String, val label: String)

/** Lists the models a provider offers, so the user can pick one instead of typing an id. */
object ModelCatalog {
    // models that can't do image + text chat (speech, image generation, embeddings, agents, …)
    private val NOT_CHAT = Regex("(tts|audio|realtime|transcribe|whisper|embedding|moderation|image|dall-e|sora|search|" +
        "computer-use|robotics|lyria|veo|imagen|deep-research|antigravity|aqa|instruct|babbage|davinci|codex)", RegexOption.IGNORE_CASE)

    suspend fun list(p: Provider, apiKey: String): List<ModelOption> = withContext(Dispatchers.IO) {
        when (p) {
            Provider.GEMINI -> {
                val res = httpJson("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", "GET",
                    mapOf("x-goog-api-key" to apiKey), null, "Gemini")
                res.optJSONArray("models").objects()
                    .filter { m -> m.optJSONArray("supportedGenerationMethods").strings().contains("generateContent") }
                    .map { ModelOption(it.getString("name").removePrefix("models/"), it.optString("displayName")) }
                    .filterNot { NOT_CHAT.containsMatchIn(it.id) }
            }
            Provider.OPENAI -> {
                val res = httpJson("https://api.openai.com/v1/models", "GET", mapOf("Authorization" to "Bearer $apiKey"), null, "OpenAI")
                res.optJSONArray("data").objects().map { it.getString("id") }
                    .filter { Regex("^(gpt-|o\\d|chatgpt-)").containsMatchIn(it) && !NOT_CHAT.containsMatchIn(it) }
                    .sortedDescending().map { ModelOption(it, it) }
            }
            Provider.ANTHROPIC -> {
                val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                try {
                    client.models().list().autoPager().map { ModelOption(it.id(), it.displayName()) }
                } catch (e: AnthropicServiceException) {
                    throw AiUnavailable(if (e.statusCode() == 401) "Anthropic rejected the API key." else "Anthropic error ${e.statusCode()}")
                } finally { client.close() }
            }
            Provider.OPENROUTER -> {
                // public list; keep models that accept images, cheapest first within a name
                val res = httpJson("https://openrouter.ai/api/v1/models", "GET", emptyMap(), null, "OpenRouter")
                res.optJSONArray("data").objects()
                    .filter { m -> m.optJSONObject("architecture")?.optJSONArray("input_modalities").strings().contains("image") }
                    .map { m ->
                        val perM = m.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull()?.let { it * 1_000_000 }
                        val price = when { perM == null -> ""; perM == 0.0 -> " · free"; else -> " · $%.2f/M in".format(perM) }
                        ModelOption(m.getString("id"), m.optString("name", m.getString("id")) + price)
                    }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    /** A sensible pick from [options] when the user hasn't chosen a model yet. */
    fun recommend(p: Provider, options: List<ModelOption>): String? {
        val prefs = when (p) {
            Provider.GEMINI -> listOf("^gemini-[\\d.]+-flash$")
            Provider.OPENAI -> listOf("^gpt-[\\d.]+-mini$", "^gpt-[\\d.]+$", "^gpt-4o-mini$")
            Provider.ANTHROPIC -> listOf("^claude-opus-5-5$", "^claude-opus-")
            Provider.OPENROUTER -> listOf("^google/gemini-[\\d.]+-flash$", "^openai/gpt-[\\d.]+-mini$", "^anthropic/claude-")
        }
        val ids = options.map { it.id }.sortedDescending()
        return prefs.firstNotNullOfOrNull { re -> ids.firstOrNull { Regex(re).containsMatchIn(it) } } ?: ids.firstOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
