package com.nuvio.tv.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object GeminiTranslationClient {
    private const val TAG = "GeminiTranslationClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translateSrtChunk(
        apiKey: String,
        model: String,
        targetLanguageName: String,
        srtChunk: String,
        retries: Int = 2,
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Throwable? = null

        for (attempt in 0..retries) {
            if (attempt > 0) {
                delay(1000L * attempt)
            }
            try {
                val result = executeRequest(apiKey, model, targetLanguageName, srtChunk)
                return@withContext Result.success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Translation attempt $attempt failed: ${e.message}")
                lastException = e
            }
        }

        Result.failure(lastException ?: IOException("Failed to translate chunk after retries"))
    }

    private fun executeRequest(
        apiKey: String,
        model: String,
        targetLanguageName: String,
        srtChunk: String,
    ): String {
        val cleanedKey = GeminiTranslationPreferences.cleanApiKey(apiKey)
        val selectedModel = model.trim().removePrefix("models/")
        val url = "$BASE_URL/$selectedModel:generateContent?key=$cleanedKey"

        val systemInstruction = """
            You are a professional audiovisual subtitle translator. Translate the dialogue in the provided SRT subtitles into $targetLanguageName.
            STRICT RULES:
            1. Keep the exact same SRT structure: every cue number (1, 2, 3...) and timestamp (00:00:00,000 --> 00:00:00,000) must remain untouched.
            2. Only translate the spoken text. Never omit or alter timestamps or cue sequence numbers.
            3. Translate natural dialogue accurately, preserving idioms, character tone, and context.
            4. Retain any sound effects or hearing-impaired tags (e.g., [Music], (groans)) translated appropriately.
            5. Return ONLY the raw translated SRT text. Do NOT wrap output in markdown code fences or add explanations.
        """.trimIndent()

        // Open instruction-tuned models like Gemma respond best when instructions are also embedded in the prompt
        val promptText = if (selectedModel.contains("gemma", ignoreCase = true)) {
            """
            Translate the dialogue in the following SRT subtitles into $targetLanguageName.
            Preserve all cue numbers and timestamps exactly. Output ONLY raw translated SRT subtitles:

            $srtChunk
            """.trimIndent()
        } else {
            srtChunk
        }

        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanedKey)
            .post(jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }
                throw IOException("Gemini API Error (${response.code}): $errorMsg")
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("Gemini returned no candidates (possible content filter)")
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                throw IOException("Gemini returned empty parts")
            }

            val rawOutput = parts.getJSONObject(0).optString("text", "")
            return cleanMarkdownFences(rawOutput)
        }
    }

    private fun cleanMarkdownFences(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            val firstLineBreak = cleaned.indexOf('\n')
            if (firstLineBreak != -1) {
                cleaned = cleaned.substring(firstLineBreak + 1)
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3).trimEnd()
        }
        return cleaned.trim()
    }
}
