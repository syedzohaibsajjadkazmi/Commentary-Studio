package com.commentary.studio

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/* ---------------------------------------------------------------
   PROVIDERS
   --------------------------------------------------------------- */

enum class ProviderKind(val label: String, val defaultBase: String, val keyHint: String) {
    OPENAI(
        "OpenAI-compatible",
        "https://api.openai.com/v1",
        "Works with OpenAI, OpenRouter, Groq, Together, LM Studio and similar."
    ),
    ANTHROPIC(
        "Anthropic (Claude)",
        "https://api.anthropic.com",
        "Native Claude API. Required for claude-opus-5 without a proxy."
    ),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com",
        "Native video upload with server-side audio handling."
    )
}

enum class Role(val label: String, val blurb: String) {
    VISION("Vision", "Describes the frames. Use a cheap capable vision model."),
    TRANSCRIBE("Transcribe", "Turns the audio into timestamped words."),
    SCRIPT("Script", "Writes the commentary. Use your strongest reasoning model.")
}

data class Conn(
    val kind: ProviderKind = ProviderKind.OPENAI,
    val baseUrl: String = ProviderKind.OPENAI.defaultBase,
    val apiKey: String = "",
    val model: String = ""
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class AiFailure(message: String) : Exception(message)

/* ---------------------------------------------------------------
   TRANSCRIPT MODEL
   --------------------------------------------------------------- */

data class SpokenWord(val start: Double, val end: Double, val text: String)

data class TranscriptResult(
    val fullText: String,
    val words: List<SpokenWord>,
    val hasTimestamps: Boolean,
    val language: String
) {
    /** Verbatim words overlapping the window [second, second + 1). */
    fun wordsInSecond(second: Int): String {
        if (!hasTimestamps) return ""
        val from = second.toDouble()
        val to = second + 1.0
        val hit = words.filter { it.start < to && it.end > from }
        return hit.joinToString(" ") { it.text.trim() }.trim()
    }

    companion object {
        val EMPTY = TranscriptResult("", emptyList(), false, "")
    }
}

/* ---------------------------------------------------------------
   CLIENT
   --------------------------------------------------------------- */

object Ai {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val ANTHROPIC_VERSION = "2023-06-01"

    fun cancelAll() {
        http.dispatcher.cancelAll()
    }

    private fun trimBase(url: String): String = url.trim().trimEnd('/')

    private fun shorten(s: String, max: Int = 400): String =
        if (s.length <= max) s else s.take(max) + " ..."

    private fun call(req: Request): String {
        try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw AiFailure("HTTP ${res.code}. ${shorten(body)}")
                }
                if (body.isBlank()) throw AiFailure("The server returned an empty response.")
                return body
            }
        } catch (e: AiFailure) {
            throw e
        } catch (e: java.io.IOException) {
            throw AiFailure("Network problem: ${e.message}")
        }
    }

    /* ============================== MODELS ============================== */

    fun listModels(conn: Conn): List<String> {
        if (conn.baseUrl.isBlank() || conn.apiKey.isBlank()) {
            throw AiFailure("Enter both a Base URL and an API key.")
        }
        return when (conn.kind) {
            ProviderKind.OPENAI -> {
                val body = call(
                    Request.Builder()
                        .url("${trimBase(conn.baseUrl)}/models")
                        .addHeader("Authorization", "Bearer ${conn.apiKey}")
                        .get().build()
                )
                val arr = JSONObject(body).optJSONArray("data")
                    ?: throw AiFailure("No model list in the response. ${shorten(body)}")
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it)?.optString("id") }
                    .filter { it.isNotBlank() }
                    .distinct().sorted()
            }

            ProviderKind.ANTHROPIC -> {
                val body = call(
                    Request.Builder()
                        .url("${trimBase(conn.baseUrl)}/v1/models?limit=1000")
                        .addHeader("x-api-key", conn.apiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .get().build()
                )
                val arr = JSONObject(body).optJSONArray("data")
                    ?: throw AiFailure("No model list in the response. ${shorten(body)}")
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it)?.optString("id") }
                    .filter { it.isNotBlank() }
                    .distinct().sorted()
            }

            ProviderKind.GEMINI -> {
                val body = call(
                    Request.Builder()
                        .url("${trimBase(conn.baseUrl)}/v1beta/models?pageSize=400&key=${conn.apiKey}")
                        .get().build()
                )
                val arr = JSONObject(body).optJSONArray("models")
                    ?: throw AiFailure("No model list in the response. ${shorten(body)}")
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .map { it.removePrefix("models/") }
                    .filter { it.isNotBlank() && !it.contains("embedding") }
                    .distinct().sorted()
            }
        }
    }

    /* ============================== TEXT ============================== */

    fun text(conn: Conn, system: String, user: String, maxTokens: Int): String {
        if (!conn.ready) throw AiFailure("This connection is not configured yet.")
        return when (conn.kind) {
            ProviderKind.OPENAI -> openAiChat(
                conn,
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
                maxTokens
            )
            ProviderKind.ANTHROPIC -> anthropicMessages(
                conn,
                system,
                JSONArray().put(JSONObject().put("type", "text").put("text", user)),
                maxTokens
            )
            ProviderKind.GEMINI -> geminiGenerate(
                conn,
                JSONArray().put(JSONObject().put("text", user)),
                system,
                maxTokens
            )
        }
    }

    /* ============================== VISION ============================== */

    /**
     * [blocks] is an ordered list of either text or a base64 JPEG.
     * The same list is translated into each provider's own content format.
     */
    sealed interface Block {
        data class Txt(val text: String) : Block
        data class Img(val jpegBase64: String, val detail: String) : Block
    }

    fun vision(conn: Conn, system: String, blocks: List<Block>, maxTokens: Int): String {
        if (!conn.ready) throw AiFailure("The vision connection is not configured yet.")
        return when (conn.kind) {
            ProviderKind.OPENAI -> {
                val content = JSONArray()
                blocks.forEach { b ->
                    when (b) {
                        is Block.Txt -> content.put(
                            JSONObject().put("type", "text").put("text", b.text)
                        )
                        is Block.Img -> content.put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject()
                                    .put("url", "data:image/jpeg;base64,${b.jpegBase64}")
                                    .put("detail", b.detail)
                            )
                        )
                    }
                }
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", content))
                openAiChat(conn, messages, maxTokens)
            }

            ProviderKind.ANTHROPIC -> {
                val content = JSONArray()
                blocks.forEach { b ->
                    when (b) {
                        is Block.Txt -> content.put(
                            JSONObject().put("type", "text").put("text", b.text)
                        )
                        is Block.Img -> content.put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", b.jpegBase64)
                            )
                        )
                    }
                }
                anthropicMessages(conn, system, content, maxTokens)
            }

            ProviderKind.GEMINI -> {
                val parts = JSONArray()
                blocks.forEach { b ->
                    when (b) {
                        is Block.Txt -> parts.put(JSONObject().put("text", b.text))
                        is Block.Img -> parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject()
                                    .put("mime_type", "image/jpeg")
                                    .put("data", b.jpegBase64)
                            )
                        )
                    }
                }
                geminiGenerate(conn, parts, system, maxTokens)
            }
        }
    }

    /* ============================== TRANSCRIBE ============================== */

    /**
     * Posts an audio file to an OpenAI-compatible /audio/transcriptions endpoint.
     * Asks for verbose_json with word and segment timestamps, and falls back to
     * plain json when a provider rejects that combination.
     */
    fun transcribe(conn: Conn, audio: File, language: String?): TranscriptResult {
        if (!conn.ready) throw AiFailure("The transcription connection is not configured yet.")
        if (conn.kind != ProviderKind.OPENAI) {
            throw AiFailure("Transcription needs an OpenAI-compatible endpoint.")
        }
        if (audio.length() > 25_000_000L) {
            throw AiFailure(
                "The extracted audio is ${audio.length() / 1_000_000} MB. " +
                    "The transcription limit is 25 MB. Trim the video."
            )
        }

        fun post(verbose: Boolean): String {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.m4a",
                    audio.asRequestBody("audio/m4a".toMediaType())
                )
                .addFormDataPart("model", conn.model)
            if (verbose) {
                builder.addFormDataPart("response_format", "verbose_json")
                builder.addFormDataPart("timestamp_granularities[]", "word")
                builder.addFormDataPart("timestamp_granularities[]", "segment")
            } else {
                builder.addFormDataPart("response_format", "json")
            }
            if (!language.isNullOrBlank()) {
                builder.addFormDataPart("language", language)
            }
            return call(
                Request.Builder()
                    .url("${trimBase(conn.baseUrl)}/audio/transcriptions")
                    .addHeader("Authorization", "Bearer ${conn.apiKey}")
                    .post(builder.build())
                    .build()
            )
        }

        val body = try {
            post(true)
        } catch (_: AiFailure) {
            post(false)
        }

        val obj = JSONObject(body)
        val full = obj.optString("text").orEmpty()
        val lang = obj.optString("language").orEmpty()
        val wordsArr = obj.optJSONArray("words")
        val words = mutableListOf<SpokenWord>()

        if (wordsArr != null) {
            for (i in 0 until wordsArr.length()) {
                val w = wordsArr.optJSONObject(i) ?: continue
                val t = w.optString("word").ifBlank { w.optString("text") }
                if (t.isBlank()) continue
                words.add(SpokenWord(w.optDouble("start", 0.0), w.optDouble("end", 0.0), t))
            }
        }

        // Fall back to segment timings when word timings are absent.
        if (words.isEmpty()) {
            val segs = obj.optJSONArray("segments")
            if (segs != null) {
                for (i in 0 until segs.length()) {
                    val s = segs.optJSONObject(i) ?: continue
                    val t = s.optString("text").trim()
                    if (t.isBlank()) continue
                    words.add(SpokenWord(s.optDouble("start", 0.0), s.optDouble("end", 0.0), t))
                }
            }
        }

        if (full.isBlank() && words.isEmpty()) {
            throw AiFailure("The transcription came back empty.")
        }
        return TranscriptResult(full, words, words.isNotEmpty(), lang)
    }

    /* ============================== GEMINI NATIVE VIDEO ============================== */

    fun geminiUploadAndAnalyse(
        conn: Conn,
        bytes: ByteArray,
        mime: String,
        prompt: String,
        maxTokens: Int,
        onStatus: (String) -> Unit,
        stillActive: () -> Boolean
    ): String {
        onStatus("Starting upload")
        val startBody = JSONObject().put(
            "file", JSONObject().put("display_name", "footage")
        ).toString()

        val uploadUrl = http.newCall(
            Request.Builder()
                .url("${trimBase(conn.baseUrl)}/upload/v1beta/files?key=${conn.apiKey}")
                .addHeader("X-Goog-Upload-Protocol", "resumable")
                .addHeader("X-Goog-Upload-Command", "start")
                .addHeader("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
                .addHeader("X-Goog-Upload-Header-Content-Type", mime)
                .addHeader("Content-Type", "application/json")
                .post(startBody.toRequestBody(JSON_MEDIA))
                .build()
        ).execute().use { res ->
            val b = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiFailure("Upload start failed. HTTP ${res.code}. ${shorten(b)}")
            res.header("X-Goog-Upload-URL")
                ?: throw AiFailure("The server did not return an upload URL.")
        }

        onStatus("Uploading ${bytes.size / 1_000_000} MB")
        val finishBody = call(
            Request.Builder()
                .url(uploadUrl)
                .addHeader("X-Goog-Upload-Offset", "0")
                .addHeader("X-Goog-Upload-Command", "upload, finalize")
                .post(bytes.toRequestBody(mime.toMediaType()))
                .build()
        )
        val fileObj = JSONObject(finishBody).optJSONObject("file")
            ?: throw AiFailure("Upload finished but no file was returned. ${shorten(finishBody)}")
        val fileUri = fileObj.getString("uri")
        val fileName = fileObj.getString("name")

        var waited = 0
        while (true) {
            if (!stillActive()) throw AiFailure("Cancelled.")
            val stateBody = call(
                Request.Builder()
                    .url("${trimBase(conn.baseUrl)}/v1beta/$fileName?key=${conn.apiKey}")
                    .get().build()
            )
            val state = JSONObject(stateBody).optString("state", "UNKNOWN")
            if (state == "ACTIVE") break
            if (state == "FAILED") throw AiFailure("The server failed to process this video.")
            if (waited >= 600) throw AiFailure("Video processing timed out after 10 minutes.")
            Thread.sleep(4000L)
            waited += 4
            onStatus("Server is processing the video (${waited}s)")
        }

        onStatus("Analysing second by second")
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "file_data",
                    JSONObject().put("mime_type", mime).put("file_uri", fileUri)
                )
            )
            .put(JSONObject().put("text", prompt))
        return geminiGenerate(conn, parts, null, maxTokens)
    }

    /* ============================== RAW CALLS ============================== */

    /**
     * Newer OpenAI-family models reject max_tokens and reject a custom
     * temperature. The first attempt uses the classic shape; if the provider
     * complains about either parameter the call is retried with the newer shape.
     */
    private fun openAiChat(conn: Conn, messages: JSONArray, maxTokens: Int): String {

        fun attempt(useCompletionTokens: Boolean, sendTemperature: Boolean): String {
            val payload = JSONObject()
                .put("model", conn.model)
                .put("messages", messages)
            if (sendTemperature) payload.put("temperature", 0.25)
            if (useCompletionTokens) {
                payload.put("max_completion_tokens", maxTokens)
            } else {
                payload.put("max_tokens", maxTokens)
            }

            val body = call(
                Request.Builder()
                    .url("${trimBase(conn.baseUrl)}/chat/completions")
                    .addHeader("Authorization", "Bearer ${conn.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
            )

            val obj = JSONObject(body)
            obj.optJSONObject("error")?.let {
                throw AiFailure(it.optString("message", "The provider returned an error."))
            }
            val choices = obj.optJSONArray("choices")
                ?: throw AiFailure("No choices in the response. ${shorten(body)}")
            val first = choices.optJSONObject(0)
                ?: throw AiFailure("Empty completion. ${shorten(body)}")
            val content = first.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) {
                throw AiFailure(
                    "The model returned no text. finish_reason=" +
                        first.optString("finish_reason", "unknown")
                )
            }
            return content
        }

        return try {
            attempt(useCompletionTokens = false, sendTemperature = true)
        } catch (first: AiFailure) {
            val m = first.message.orEmpty().lowercase()
            val paramProblem = m.contains("max_tokens") ||
                m.contains("max_completion_tokens") ||
                m.contains("temperature") ||
                m.contains("unsupported") ||
                m.contains("not supported")
            if (paramProblem) {
                attempt(useCompletionTokens = true, sendTemperature = false)
            } else {
                throw first
            }
        }
    }

    private fun anthropicMessages(
        conn: Conn,
        system: String,
        userContent: JSONArray,
        maxTokens: Int
    ): String {
        val payload = JSONObject()
            .put("model", conn.model)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", userContent)
                )
            )
        if (system.isNotBlank()) payload.put("system", system)

        val body = call(
            Request.Builder()
                .url("${trimBase(conn.baseUrl)}/v1/messages")
                .addHeader("x-api-key", conn.apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
        )

        val obj = JSONObject(body)
        obj.optJSONObject("error")?.let {
            throw AiFailure(it.optString("message", "Claude returned an error."))
        }
        val content = obj.optJSONArray("content")
            ?: throw AiFailure("No content in the response. ${shorten(body)}")

        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                sb.append(block.optString("text"))
            }
        }
        if (sb.isBlank()) {
            throw AiFailure(
                "Claude returned no text. stop_reason=" +
                    obj.optString("stop_reason", "unknown")
            )
        }
        return sb.toString()
    }

    private fun geminiGenerate(
        conn: Conn,
        parts: JSONArray,
        system: String?,
        maxTokens: Int
    ): String {
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("maxOutputTokens", maxTokens)
            )
        if (!system.isNullOrBlank()) {
            payload.put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", system))
                )
            )
        }

        val body = call(
            Request.Builder()
                .url(
                    "${trimBase(conn.baseUrl)}/v1beta/models/" +
                        "${conn.model}:generateContent?key=${conn.apiKey}"
                )
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
        )

        val obj = JSONObject(body)
        obj.optJSONObject("error")?.let {
            throw AiFailure(it.optString("message", "Gemini returned an error."))
        }
        val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiFailure("No candidates in the response. ${shorten(body)}")
        val outParts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (outParts != null) {
            for (i in 0 until outParts.length()) {
                sb.append(outParts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        if (sb.isBlank()) {
            throw AiFailure(
                "Gemini returned no text. finishReason=" +
                    candidate.optString("finishReason", "unknown")
            )
        }
        return sb.toString()
    }
}
