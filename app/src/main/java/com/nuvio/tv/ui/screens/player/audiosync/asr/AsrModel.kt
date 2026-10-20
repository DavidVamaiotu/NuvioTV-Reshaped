package com.nuvio.tv.ui.screens.player.audiosync.asr

import android.content.Context
import android.net.ConnectivityManager
import com.nuvio.tv.ui.screens.player.audiosync.SpeechModelActions
import com.nuvio.tv.ui.screens.player.audiosync.SpeechModelState
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus
import com.nuvio.tv.ui.screens.player.audiosync.SyncLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The English recognition model (about 74 MB), kept in app storage. It can be downloaded from
 * Settings, and is fetched automatically on an unmetered connection the first time it is needed.
 * Progress and errors are published to [SubtitleSyncStatus.speechModel].
 */
internal object AsrModel {
    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74

    private const val LEGACY_VERSION = "gigaspeech-2023-12-12"
    private const val VERSION = "$LEGACY_VERSION-ort-v1"
    private const val DOWNLOAD = "https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases/download/asr-models-ort-v1/"
    private val FILES = AsrModelFiles.optimized
    private val TOTAL_BYTES = FILES.sumOf { it.size }

    private val downloading = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /** Registers the Settings actions and publishes the current state. Call once at app start. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        SubtitleSyncStatus.modelActions = object : SpeechModelActions {
            override fun download() {
                val ctx = appContext ?: return
                Thread({ download(ctx) }, "NuvioAsrModelDownload").apply { isDaemon = true }.start()
            }

            override fun delete() {
                val ctx = appContext ?: return
                if (downloading.get()) return
                directory(ctx).deleteRecursively()
                File(ctx.filesDir, "audiosync/asr/$LEGACY_VERSION").deleteRecursively()
                publish(ctx)
            }
        }
        publish(context)
    }

    fun directory(context: Context): File = File(context.filesDir, "audiosync/asr/$VERSION")

    fun isReady(context: Context): Boolean = AsrModelFiles.isComplete(directory(context), FILES)

    /** Keep the old download usable offline or if the one-time ORT upgrade cannot complete. */
    fun legacyDirectory(context: Context): File? =
        File(context.filesDir, "audiosync/asr/$LEGACY_VERSION")
            .takeIf { AsrModelFiles.isComplete(it, AsrModelFiles.legacy) }

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !manager.isActiveNetworkMetered
    }

    private fun publish(context: Context, progress: Float = 0f, error: String? = null) {
        SubtitleSyncStatus.publishSpeechModel(
            SpeechModelState(
                supported = true,
                sizeMb = DOWNLOAD_MB,
                downloaded = isReady(context),
                downloading = downloading.get(),
                progress = progress,
                error = error,
            ),
        )
    }

    /**
     * Downloads missing files. Blocking; call from a background thread. Returns true when the model
     * is ready afterwards.
     */
    @Synchronized
    fun download(context: Context): Boolean {
        if (isReady(context)) {
            publish(context)
            return true
        }
        if (!downloading.compareAndSet(false, true)) return false
        var error: String? = null
        try {
            publish(context)
            val dir = directory(context).apply { mkdirs() }
            var doneBytes = 0L
            for (asset in FILES) {
                val file = File(dir, asset.name)
                if (!file.isFile || file.length() != asset.size) {
                    val onBytes: (Long) -> Unit = { bytes ->
                        publish(context, progress = (doneBytes + bytes).toFloat() / TOTAL_BYTES)
                    }
                    val failure = fetch(DOWNLOAD + asset.name, dir, asset, onBytes)
                    if (failure != null) {
                        error = failure
                        return false
                    }
                }
                doneBytes += asset.size
            }
            return isReady(context).also { if (!it) error = "downloaded files are incomplete" }
        } finally {
            downloading.set(false)
            publish(context, error = error)
        }
    }

    /** Returns null on success, otherwise a short reason. */
    private fun fetch(url: String, directory: File, asset: AsrModelFiles.Asset, onBytes: (Long) -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            var location = URL(url)
            var redirects = 0
            while (true) {
                val current = (location.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                connection = current
                if (current.responseCode !in 300..399) break
                if (++redirects > 5) return "Too many model download redirects"
                val next = current.getHeaderField("Location") ?: return "Missing model download redirect"
                location = URL(location, next)
                current.disconnect()
            }
            val response = checkNotNull(connection)
            if (response.responseCode != 200) {
                val code = response.responseCode
                SyncLog.w("model download HTTP $code for $url")
                return "HTTP $code"
            }
            response.inputStream.use { input ->
                AsrModelFiles.saveVerified(input, directory, asset, onBytes)
            }
            null
        } catch (failure: Exception) {
            SyncLog.w("model download failed for $url: ${failure.message}")
            failure.message ?: failure.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }
}
