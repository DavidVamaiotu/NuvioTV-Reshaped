package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nuvio.tv.R
import com.nuvio.tv.core.player.FrameRateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the Live TV channel playing. The bytes the player already downloads are also written to
 * a .ts file, so a recording opens no connection of its own (many IPTV accounts allow one) and
 * keeps exactly what is watched. Only MPEG-TS is kept (plain TS channels and HLS with TS
 * segments, which is nearly all IPTV); playlists, keys and fMP4 segments are left out.
 *
 * The player's loading thread only queues a copy of each read: a writer thread does the disk
 * work, and when the disk falls behind data is dropped rather than the picture stalling.
 */
object LiveTvRecorder {
    private const val TAG = "LiveTvRecorder"
    private const val FOLDER = "Recordings"
    const val EXTENSION = "ts"

    class Recording(val playbackUrl: String, val channelName: String, val file: File, val startedAtMs: Long) {
        /** The frame rate last kept beside the file ([noteFrameRate]). */
        @Volatile internal var frameRate: Float = 0f
    }

    private val _active = MutableStateFlow<Recording?>(null)
    /** The recording in progress, or null. */
    val active: StateFlow<Recording?> = _active.asStateFlow()

    /** Read on each player read: the URL being recorded, or null. */
    @Volatile private var activeUrl: String? = null
    @Volatile private var writer: Writer? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Bytes written to the file of the recording in progress. */
    fun bytesWritten(): Long = writer?.written?.get() ?: 0L

    fun isRecording(playbackUrl: String?): Boolean = playbackUrl != null && activeUrl == playbackUrl

    /** Starts recording [playbackUrl]; any other recording is saved first. Null when no storage can be written. */
    @Synchronized
    fun start(context: Context, playbackUrl: String, channelName: String): Recording? {
        stop()
        appContext = context.applicationContext
        val dir = recordingsDir(context) ?: return null
        val file = File(dir, fileName(channelName))
        val opened = try {
            Writer(file)
        } catch (error: IOException) {
            Log.w(TAG, "Could not create ${file.path}", error)
            return null
        }
        writer = opened
        activeUrl = playbackUrl
        return Recording(playbackUrl, channelName, file, System.currentTimeMillis()).also { _active.value = it }
    }

    /** Saves the recording in progress (shows where), and returns it. */
    @Synchronized
    fun stop(): Recording? {
        val recording = _active.value ?: return null
        activeUrl = null
        val finished = writer
        writer = null
        _active.value = null
        finished?.finish()
        toast(R.string.live_tv_recording_saved, recording.channelName)
        return recording
    }

    /** The writer could not write (disk full or removed): what was written is kept. */
    @Synchronized
    private fun stopAfterFailure(failed: Writer) {
        if (writer !== failed) return
        val recording = _active.value
        activeUrl = null
        writer = null
        _active.value = null
        if (recording != null) toast(R.string.live_tv_recording_failed, recording.channelName)
    }

    /**
     * The frame rate the channel plays at, as the player matched the display to it live. It is
     * kept beside the recording, so playing it back matches the display the same way: probing the
     * file instead can tell apart frames and fields (25 for a 1080i channel shown at 50 Hz).
     */
    fun noteFrameRate(raw: Float, snapped: Float, videoWidth: Int?, videoHeight: Int?) {
        val recording = _active.value ?: return
        if (snapped <= 0f || recording.frameRate == snapped) return
        recording.frameRate = snapped
        runCatching { frameRateFile(recording.file).writeText("$raw;$snapped;${videoWidth ?: 0};${videoHeight ?: 0}") }
            .onFailure { Log.w(TAG, "Could not keep the frame rate of ${recording.file.name}", it) }
    }

    /** Hands the frame rate kept for [file] to Nuvio's frame rate matching, which then skips probing it. */
    fun prepareFrameRate(file: File) {
        val values = runCatching { frameRateFile(file).readText().split(';') }.getOrNull() ?: return
        val raw = values.getOrNull(0)?.toFloatOrNull() ?: return
        val snapped = values.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: return
        val width = values.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
        val height = values.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
        FrameRateUtils.cacheFrameRate(
            url = Uri.fromFile(file).toString(),
            headers = emptyMap(),
            detection = FrameRateUtils.FrameRateDetection(raw, snapped, width, height),
            filename = file.name,
        )
    }

    private fun frameRateFile(file: File) = File(file.path + ".afr")

    /** Called with each read of a Live TV stream; keeps it when [playbackUrl] is being recorded. */
    internal fun write(playbackUrl: String, buffer: ByteArray, offset: Int, length: Int) {
        if (activeUrl != playbackUrl) return
        writer?.offer(buffer.copyOfRange(offset, offset + length))
    }

    /**
     * [upstream] for the player, also handing what it reads to the recorder while [playbackUrl] (a
     * live channel) is recorded. Anything else gets [upstream] as it is.
     */
    @OptIn(UnstableApi::class)
    fun tee(upstream: DataSource.Factory, playbackUrl: String): DataSource.Factory {
        if (!LiveTvPlaybackRegistry.isLiveTv(playbackUrl) || LiveTvPlaybackRegistry.isCatchup(playbackUrl)) return upstream
        return DataSource.Factory { RecordingDataSource(upstream.createDataSource(), playbackUrl) }
    }

    /** A recording played back: from the disk, never through network-only paths. */
    fun isLocalFile(url: String): Boolean = Uri.parse(url).scheme.equals("file", ignoreCase = true)

    /** Every recording on every storage, newest first. */
    fun recordings(context: Context): List<File> =
        candidateDirs(context)
            .map { File(it, FOLDER) }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

    /** Deletes [file] unless it is being recorded. */
    fun delete(file: File): Boolean {
        if (_active.value?.file == file || !file.delete()) return false
        frameRateFile(file).delete()
        return true
    }

    /**
     * Where new recordings go: the app's folder on removable storage (a USB drive) with the most
     * room, else on the device. App folders need no storage permission; they are removed with the app.
     */
    private fun recordingsDir(context: Context): File? {
        val dirs = candidateDirs(context)
        val base = dirs.filter { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
            .maxByOrNull { it.usableSpace }
            ?: dirs.maxByOrNull { it.usableSpace }
            ?: return null
        return File(base, FOLDER).takeIf { it.isDirectory || it.mkdirs() }
    }

    private fun candidateDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().filter {
            runCatching { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
        }.ifEmpty { listOf(context.filesDir) }

    private fun fileName(channelName: String): String {
        val name = channelName.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(60).ifBlank { "Channel" }
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        return "$name $time.$EXTENSION"
    }

    private fun toast(message: Int, channelName: String) {
        val context = appContext ?: return
        mainHandler.post { Toast.makeText(context, context.getString(message, channelName), Toast.LENGTH_SHORT).show() }
    }

    private const val MB = 1024L * 1024L

    /** Writes queued reads to [file] on its own thread. */
    private class Writer(file: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
        /** About 30 s of a 15 Mbit/s channel in typical 64 KB reads: room for a slow USB drive. */
        private val queue = ArrayBlockingQueue<ByteArray>(1024)
        val written = AtomicLong()
        private val dropped = AtomicLong()
        @Volatile private var finishing = false

        private val thread = Thread({
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    written.addAndGet(chunk.size.toLong())
                }
            } catch (error: IOException) {
                Log.w(TAG, "Recording stopped: ${file.path}", error)
                queue.clear()
                stopAfterFailure(this)
            } catch (_: InterruptedException) {
            } finally {
                runCatching { out.close() }
            }
        }, "LiveTvRecorder").apply {
            isDaemon = true
            start()
        }

        fun offer(chunk: ByteArray) {
            if (finishing || queue.offer(chunk)) return
            // Logged once per lost megabyte, not per read.
            val before = dropped.getAndAdd(chunk.size.toLong())
            if (before / MB != (before + chunk.size) / MB) Log.w(TAG, "Disk too slow: ${(before + chunk.size) / MB} MB dropped")
        }

        /** Writes what is queued, then closes the file. */
        fun finish() {
            finishing = true
            // The end marker must get in even when the queue is full.
            while (!queue.offer(ByteArray(0))) queue.poll()
        }
    }
}

/** Hands what the player reads to [LiveTvRecorder]; each open is kept only when it is MPEG-TS. */
@OptIn(UnstableApi::class)
private class RecordingDataSource(
    private val upstream: DataSource,
    private val playbackUrl: String,
) : DataSource {
    /** Whether this open is MPEG-TS (its first byte the TS sync byte), once something is read. */
    private var transportStream: Boolean? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        transportStream = null
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read > 0) {
            val keep = transportStream ?: isTransportStream(buffer, offset, read).also { transportStream = it }
            if (keep) LiveTvRecorder.write(playbackUrl, buffer, offset, read)
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private companion object {
        const val TS_SYNC_BYTE: Byte = 0x47
        const val TS_PACKET = 188

        /**
         * Whether a read looks like MPEG-TS: three sync bytes a packet apart near its start (a
         * stream may not begin on a packet). A short read only has its first byte to go on.
         */
        fun isTransportStream(buffer: ByteArray, offset: Int, length: Int): Boolean {
            if (length < 2 * TS_PACKET + 1) return buffer[offset] == TS_SYNC_BYTE
            val end = offset + length - 2 * TS_PACKET
            for (i in offset until minOf(end, offset + TS_PACKET)) {
                if (buffer[i] == TS_SYNC_BYTE && buffer[i + TS_PACKET] == TS_SYNC_BYTE && buffer[i + 2 * TS_PACKET] == TS_SYNC_BYTE) return true
            }
            return false
        }
    }
}
