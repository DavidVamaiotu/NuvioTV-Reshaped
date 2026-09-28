package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.gemini.GeminiSubtitleService
import com.nuvio.tv.gemini.GeminiTranslationPreferences
import com.nuvio.tv.ui.screens.player.autosync.bubble.AutoSyncBubbleKind
import com.nuvio.tv.ui.screens.player.autosync.bubble.showAutoSyncMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GeminiTranslation"

internal fun PlayerRuntimeController.translateSubtitleWithGemini(
    sourceSubtitle: Subtitle? = null,
    targetLanguageCode: String? = null,
) {
    scope.launch {
        GeminiTranslationPreferences.ensureLoaded(context)
        val apiKey = GeminiTranslationPreferences.apiKey.value
        if (apiKey.isBlank()) {
            showAutoSyncMessage(
                context,
                AutoSyncBubbleKind.Failure,
                "Gemini • API Key Missing • Configure in Settings"
            )
            return@launch
        }

        val targetLang = targetLanguageCode
            ?: GeminiTranslationPreferences.targetLanguage.value
        val targetLangName = GeminiTranslationPreferences.getLanguageName(targetLang)

        // Find candidate source subtitle
        val candidate = sourceSubtitle
            ?: _uiState.value.selectedAddonSubtitle
            ?: _uiState.value.addonSubtitles.firstOrNull { it.lang.startsWith("en", ignoreCase = true) }
            ?: _uiState.value.addonSubtitles.firstOrNull()

        if (candidate == null) {
            showAutoSyncMessage(
                context,
                AutoSyncBubbleKind.Failure,
                "Gemini • No Subtitle Found to Translate"
            )
            return@launch
        }

        showAutoSyncMessage(
            context,
            AutoSyncBubbleKind.Working,
            "Gemini • Translating into $targetLangName…"
        )

        try {
            // Download raw source subtitle text
            val rawBody = downloadSubtitleBody(candidate.url, candidate.lang, candidate.headers)

            val result = GeminiSubtitleService.translateSubtitle(
                context = context,
                sourceIdOrUrl = candidate.url,
                sourceText = rawBody,
                targetLanguageCode = targetLang,
                onProgress = { progress ->
                    val pct = (progress * 100).toInt()
                    if (pct in listOf(25, 50, 75)) {
                        showAutoSyncMessage(
                            context,
                            AutoSyncBubbleKind.Working,
                            "Gemini • Translating into $targetLangName ($pct%)"
                        )
                    }
                }
            ).getOrThrow()

            // Prepare translated Subtitle item
            val translatedTrackTitle = "Gemini AI ($targetLangName)"
            val translatedSubtitle = Subtitle(
                id = "gemini_${candidate.id}_$targetLang",
                url = result.localFilePath,
                lang = targetLang,
                addonName = "Gemini AI",
                addonLogo = null,
            )

            if (isUsingMpvEngine()) {
                val wasPlaying = isPlaybackCurrentlyPlaying()
                val added = mpvView?.addAndSelectExternalSubtitle(
                    url = result.localFilePath,
                    title = translatedTrackTitle,
                    language = targetLang
                ) == true
                if (added) {
                    _uiState.update {
                        it.copy(
                            selectedAddonSubtitle = translatedSubtitle,
                            selectedSubtitleTrackIndex = -1
                        )
                    }
                    updateMpvAvailableTracks()
                    keepMpvPlayingIfNeeded(wasPlaying)
                }
            } else {
                // ExoPlayer Sidecar path (preserves buffer, hot swaps cues)
                disableSubtitles()
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = translatedSubtitle,
                        selectedSubtitleTrackIndex = -1
                    )
                }
                startSidecarAddonSubtitle(
                    subtitle = translatedSubtitle,
                    rawBodyLoader = { result.srtText }
                )
            }

            showAutoSyncMessage(
                context,
                AutoSyncBubbleKind.Success,
                "Gemini • Subtitles Applied ($targetLangName)"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini translation failed", e)
            showAutoSyncMessage(
                context,
                AutoSyncBubbleKind.Failure,
                "Gemini • Translation Failed • ${e.localizedMessage ?: "Network or API Error"}"
            )
        }
    }
}

/**
 * Checks if auto-translate is enabled and if the stream lacks the target language.
 * If so, automatically begins translating an English or base subtitle.
 */
internal fun PlayerRuntimeController.autoTranslateSubtitlesIfNeeded() {
    GeminiTranslationPreferences.ensureLoaded(context)
    if (!GeminiTranslationPreferences.autoTranslate.value) return
    val apiKey = GeminiTranslationPreferences.apiKey.value
    if (apiKey.isBlank()) return

    val targetLang = GeminiTranslationPreferences.targetLanguage.value

    // If an existing track already matches the target language, don't auto-translate
    val hasTargetInternal = _uiState.value.subtitleTracks.any {
        PlayerSubtitleUtils.matchesLanguageCode(it.language, targetLang)
    }
    if (hasTargetInternal) return

    val hasTargetAddon = _uiState.value.addonSubtitles.any {
        PlayerSubtitleUtils.matchesLanguageCode(it.lang, targetLang)
    }
    if (hasTargetAddon) return

    // Find the best English or first available addon subtitle
    val candidate = _uiState.value.addonSubtitles.firstOrNull {
        PlayerSubtitleUtils.matchesLanguageCode(it.lang, "en")
    } ?: _uiState.value.addonSubtitles.firstOrNull() ?: return

    Log.d(TAG, "Auto-translating subtitle ${candidate.id} to $targetLang")
    translateSubtitleWithGemini(sourceSubtitle = candidate, targetLanguageCode = targetLang)
}
