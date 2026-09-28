package com.nuvio.tv.gemini

import android.content.Context
import android.util.Log
import com.nuvio.tv.ui.screens.player.PlayerSubtitleCueParser
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object GeminiSubtitleService {
    private const val TAG = "GeminiSubtitleService"
    private const val CUES_PER_CHUNK = 250

    data class TranslationResult(
        val srtText: String,
        val localFilePath: String,
        val isCacheHit: Boolean,
    )

    suspend fun translateSubtitle(
        context: Context,
        sourceIdOrUrl: String,
        sourceText: String,
        targetLanguageCode: String,
        onProgress: (Float) -> Unit = {},
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        runCatching {
            GeminiTranslationPreferences.ensureLoaded(context)
            val apiKey = GeminiTranslationPreferences.apiKey.value
            if (apiKey.isBlank()) {
                throw IllegalStateException("Gemini API key is not configured in Settings > Nuvio Reshaped")
            }

            val model = GeminiTranslationPreferences.model.value
            val targetLanguageName = GeminiTranslationPreferences.getLanguageName(targetLanguageCode)
            val cacheFile = getCacheFile(context, sourceIdOrUrl, targetLanguageCode, model)

            // Check disk cache
            if (cacheFile.exists() && cacheFile.length() > 0) {
                Log.d(TAG, "Gemini translation cache hit for $sourceIdOrUrl ($targetLanguageCode)")
                onProgress(1f)
                return@runCatching TranslationResult(
                    srtText = cacheFile.readText(Charsets.UTF_8),
                    localFilePath = cacheFile.absolutePath,
                    isCacheHit = true,
                )
            }

            // Parse source into cues
            val cues = PlayerSubtitleCueParser.parseFromText(sourceText, sourceIdOrUrl)
            if (cues.isEmpty()) {
                throw IllegalStateException("No subtitle cues could be parsed from source")
            }

            Log.d(TAG, "Translating ${cues.size} cues into $targetLanguageName using $model")
            onProgress(0.05f)

            // Chunk cues
            val chunks = cues.chunked(CUES_PER_CHUNK)
            val translatedCues = mutableListOf<SubtitleSyncCue>()

            for ((index, chunk) in chunks.withIndex()) {
                val chunkStartIndex = (index * CUES_PER_CHUNK) + 1
                val srtChunk = formatCuesToSrt(chunk, chunkStartIndex)

                val translationChunkResult = GeminiTranslationClient.translateSrtChunk(
                    apiKey = apiKey,
                    model = model,
                    targetLanguageName = targetLanguageName,
                    srtChunk = srtChunk,
                )

                val translatedChunkText = translationChunkResult.getOrThrow()
                val parsedChunkCues = PlayerSubtitleCueParser.parseFromText(translatedChunkText, "chunk.srt")

                if (parsedChunkCues.size == chunk.size) {
                    // Perfect 1:1 match; preserve original timestamps
                    for (i in chunk.indices) {
                        translatedCues.add(
                            SubtitleSyncCue(
                                startTimeMs = chunk[i].startTimeMs,
                                endTimeMs = chunk[i].endTimeMs,
                                text = parsedChunkCues[i].text,
                            )
                        )
                    }
                } else if (parsedChunkCues.isNotEmpty()) {
                    // Fallback to parsed cues from Gemini response
                    translatedCues.addAll(parsedChunkCues)
                } else {
                    // In the unlikely event parsing failed, keep original chunk
                    translatedCues.addAll(chunk)
                }

                val progress = 0.05f + (0.90f * (index + 1).toFloat() / chunks.size.toFloat())
                onProgress(progress)
            }

            // Build full SRT
            val fullSrt = formatCuesToSrt(translatedCues, 1)

            // Save to disk cache
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(fullSrt, Charsets.UTF_8)
            onProgress(1.0f)

            TranslationResult(
                srtText = fullSrt,
                localFilePath = cacheFile.absolutePath,
                isCacheHit = false,
            )
        }
    }

    private fun getCacheFile(
        context: Context,
        sourceIdOrUrl: String,
        targetLang: String,
        model: String,
    ): File {
        val cacheDir = File(context.cacheDir, "gemini_subtitles").also { it.mkdirs() }
        val rawKey = "${sourceIdOrUrl.trim()}_${targetLang.trim()}_${model.trim()}"
        val hash = sha256(rawKey)
        return File(cacheDir, "gemini_${hash}.srt")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun formatCuesToSrt(cues: List<SubtitleSyncCue>, startIndex: Int): String {
        val sb = StringBuilder()
        for ((i, cue) in cues.withIndex()) {
            sb.append(startIndex + i).append("\n")
            sb.append(formatMsToSrtTime(cue.startTimeMs))
                .append(" --> ")
                .append(formatMsToSrtTime(cue.endTimeMs))
                .append("\n")
            sb.append(cue.text.trim()).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    private fun formatMsToSrtTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
