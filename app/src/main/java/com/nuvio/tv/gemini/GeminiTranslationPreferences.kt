package com.nuvio.tv.gemini

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SupportedLanguage(
    val code: String,
    val name: String,
    val nativeName: String,
) {
    val displayName: String get() = "$name ($nativeName)"
}

internal object GeminiTranslationPreferences {
    private const val PREFS = "nuvio_gemini_translation_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_TARGET_LANG = "gemini_target_language"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_AUTO_TRANSLATE = "gemini_auto_translate"

    const val DEFAULT_MODEL = "gemini-1.5-flash"
    val AVAILABLE_MODELS = listOf(
        "gemini-1.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-pro",
    )

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("es", "Spanish", "Español"),
        SupportedLanguage("fr", "French", "Français"),
        SupportedLanguage("de", "German", "Deutsch"),
        SupportedLanguage("it", "Italian", "Italiano"),
        SupportedLanguage("pt", "Portuguese", "Português"),
        SupportedLanguage("tr", "Turkish", "Türkçe"),
        SupportedLanguage("ar", "Arabic", "العربية"),
        SupportedLanguage("ru", "Russian", "Русский"),
        SupportedLanguage("ja", "Japanese", "日本語"),
        SupportedLanguage("ko", "Korean", "한국어"),
        SupportedLanguage("zh", "Chinese", "中文"),
        SupportedLanguage("hi", "Hindi", "हिन्दी"),
        SupportedLanguage("nl", "Dutch", "Nederlands"),
        SupportedLanguage("pl", "Polish", "Polski"),
        SupportedLanguage("sv", "Swedish", "Svenska"),
        SupportedLanguage("da", "Danish", "Dansk"),
        SupportedLanguage("no", "Norwegian", "Norsk"),
        SupportedLanguage("fi", "Finnish", "Suomi"),
        SupportedLanguage("el", "Greek", "Ελληνικά"),
        SupportedLanguage("cs", "Czech", "Čeština"),
        SupportedLanguage("hu", "Hungarian", "Magyar"),
        SupportedLanguage("ro", "Romanian", "Română"),
        SupportedLanguage("uk", "Ukrainian", "Українська"),
        SupportedLanguage("vi", "Vietnamese", "Tiếng Việt"),
        SupportedLanguage("th", "Thai", "ไทย"),
        SupportedLanguage("id", "Indonesian", "Bahasa Indonesia"),
        SupportedLanguage("he", "Hebrew", "עברית"),
        SupportedLanguage("fa", "Persian", "فارسی"),
        SupportedLanguage("en", "English", "English"),
    )

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _targetLanguage = MutableStateFlow("es")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _model = MutableStateFlow(DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _autoTranslate = MutableStateFlow(false)
    val autoTranslate: StateFlow<Boolean> = _autoTranslate.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _apiKey.value = prefs.getString(KEY_API_KEY, null).orEmpty()
            val defaultLang = resolveDefaultLanguage()
            _targetLanguage.value = prefs.getString(KEY_TARGET_LANG, null).takeUnless { it.isNullOrBlank() } ?: defaultLang
            _model.value = prefs.getString(KEY_MODEL, null).takeUnless { it.isNullOrBlank() } ?: DEFAULT_MODEL
            _autoTranslate.value = prefs.getBoolean(KEY_AUTO_TRANSLATE, false)
            loaded = true
        }
    }

    private fun resolveDefaultLanguage(): String {
        val sysLang = Locale.getDefault().language.lowercase()
        return if (SUPPORTED_LANGUAGES.any { it.code == sysLang }) sysLang else "es"
    }

    fun setApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        _apiKey.value = trimmed
        prefs(context).edit().putString(KEY_API_KEY, trimmed).apply()
    }

    fun setTargetLanguage(context: Context, langCode: String) {
        _targetLanguage.value = langCode
        prefs(context).edit().putString(KEY_TARGET_LANG, langCode).apply()
    }

    fun setModel(context: Context, modelName: String) {
        _model.value = modelName
        prefs(context).edit().putString(KEY_MODEL, modelName).apply()
    }

    fun setAutoTranslate(context: Context, enabled: Boolean) {
        _autoTranslate.value = enabled
        prefs(context).edit().putBoolean(KEY_AUTO_TRANSLATE, enabled).apply()
    }

    fun getLanguageName(code: String): String {
        return SUPPORTED_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }?.name
            ?: Locale(code).displayLanguage.ifBlank { code }
    }

    fun clearCache(context: Context): Long {
        val cacheDir = File(context.cacheDir, "gemini_subtitles")
        if (!cacheDir.exists()) return 0L
        val size = cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        cacheDir.deleteRecursively()
        return size
    }

    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.cacheDir, "gemini_subtitles")
        if (!cacheDir.exists()) return 0L
        return cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    suspend fun validateApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext false
        runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${key.trim()}"
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", "ping") })
                        })
                    })
                })
            }
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
