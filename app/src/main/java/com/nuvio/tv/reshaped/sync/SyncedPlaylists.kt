package com.nuvio.tv.reshaped.sync

import android.content.Context
import android.util.Log
import com.nuvio.tv.reshaped.livetv.LiveTvSource
import com.nuvio.tv.reshaped.livetv.LiveTvSourceType
import com.nuvio.tv.reshaped.livetv.LiveTvStorage
import com.nuvio.tv.reshaped.livetv.isHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/**
 * Imported playlist files (from the phone page or a file) travel with sync as their own Drive
 * file, gzipped, next to the sync file, which only names it. A copy is uploaded once per change
 * of the file and fetched once per change on the other devices; nothing is sent while it stays the same.
 */
internal object SyncedPlaylists {
    private const val TAG = "SyncedPlaylists"
    const val NAME_PREFIX = "Nuvio Reshaped playlist "
    private const val PREFS = "nuvio_reshaped_sync_playlists"
    /** A copy no synced source names any more is deleted after this, so a device mid-sync never loses one it just sent. */
    private const val UNUSED_GRACE_MS = 60L * 60 * 1000

    /** A playlist's Drive copy and the SHA-1 of the playlist it holds. */
    data class Ref(val driveId: String, val hash: String)

    /** What this device last sent or fetched for a source, and the playlist file it was. */
    private class Record(val ref: Ref, val length: Long, val modified: Long)

    fun isImported(source: LiveTvSource): Boolean = source.type == LiveTvSourceType.M3u && !source.url.isHttpUrl()

    /**
     * The Drive copy of each imported playlist of [profileId] in [sources], by source id: the one
     * sent before while the file is unchanged, else a new upload. A source whose upload failed is
     * left out (its entry in the sync file stays as it was).
     */
    suspend fun uploaded(context: Context, profileId: Int, sources: List<LiveTvSource>): Map<String, Ref> {
        val imported = sources.filter(::isImported)
        if (imported.isEmpty()) return emptyMap()
        val store = LiveTvStorage(context.applicationContext, profileId)
        val result = HashMap<String, Ref>()
        imported.forEach { source ->
            try {
                val file = store.playlistFile(source.id) ?: return@forEach
                val saved = record(context, profileId, source.id)
                if (saved != null && saved.length == file.length() && saved.modified == file.lastModified()) {
                    result[source.id] = saved.ref
                    return@forEach
                }
                val hash = runInterruptible(Dispatchers.IO) { sha1(file) }
                if (saved != null && saved.ref.hash == hash) {
                    saveRecord(context, profileId, source.id, Record(saved.ref, file.length(), file.lastModified()))
                    result[source.id] = saved.ref
                    return@forEach
                }
                val packed = File(context.cacheDir, "reshaped_sync_upload.m3u.gz")
                try {
                    runInterruptible(Dispatchers.IO) { gzip(file, packed) }
                    val id = DriveAppFolder.upload(context, NAME_PREFIX + hash.take(12) + ".m3u.gz", packed)
                    val ref = Ref(id, hash)
                    saveRecord(context, profileId, source.id, Record(ref, file.length(), file.lastModified()))
                    result[source.id] = ref
                    // The copy of the file it replaced, if this device sent it.
                    saved?.ref?.driveId?.let { old -> runCatching { DriveAppFolder.delete(context, old) } }
                } finally {
                    packed.delete()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not send an imported playlist", error)
            }
        }
        return result
    }

    /**
     * Fetches [ref] into [sourceId]'s playlist file unless this device already has that playlist.
     * True when the file changed (the source must load again).
     */
    suspend fun fetch(context: Context, profileId: Int, sourceId: String, ref: Ref): Boolean {
        val store = LiveTvStorage(context.applicationContext, profileId)
        val saved = record(context, profileId, sourceId)
        val file = store.playlistFile(sourceId)
        if (file != null && saved?.ref?.hash == ref.hash) return false
        return try {
            val temp = File(context.cacheDir, "reshaped_sync_fetch.m3u")
            try {
                DriveAppFolder.download(context, ref.driveId) { input ->
                    // Sent gzipped; read as is should the download already have been unpacked on the way.
                    val buffered = java.io.BufferedInputStream(input, 64 * 1024)
                    buffered.mark(2)
                    val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
                    buffered.reset()
                    val body = if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered
                    body.use { from -> temp.outputStream().use { from.copyTo(it, 64 * 1024) } }
                }
                val written = runInterruptible(Dispatchers.IO) {
                    store.savePlaylistFile(sourceId) { out -> if (!temp.renameTo(out)) temp.copyTo(out, overwrite = true) }
                }
                saveRecord(context, profileId, sourceId, Record(ref, written.length(), written.lastModified()))
                true
            } finally {
                temp.delete()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Could not fetch a synced playlist", error)
            false
        }
    }

    /** One look for unused copies every few hours is enough; it is one more request. */
    private const val TIDY_GAP_MS = 6L * 60 * 60 * 1000
    @Volatile private var lastTidyMs = 0L

    /** Deletes Drive copies that no synced source names any more (after [UNUSED_GRACE_MS]). */
    suspend fun deleteUnused(context: Context, named: Set<String>) {
        val now = System.currentTimeMillis()
        if (now - lastTidyMs < TIDY_GAP_MS) return
        lastTidyMs = now
        try {
            DriveAppFolder.list(context, NAME_PREFIX)
                .filter { it.id !in named && now - it.modifiedMs > UNUSED_GRACE_MS }
                .forEach { runCatching { DriveAppFolder.delete(context, it.id) } }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Could not tidy synced playlists", error)
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gzip(source: File, target: File) {
        source.inputStream().buffered(64 * 1024).use { input ->
            GZIPOutputStream(target.outputStream().buffered(64 * 1024), 64 * 1024).use { input.copyTo(it, 64 * 1024) }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun record(context: Context, profileId: Int, sourceId: String): Record? = runCatching {
        val json = JSONObject(prefs(context).getString("$profileId/$sourceId", null) ?: return null)
        Record(Ref(json.getString("id"), json.getString("hash")), json.getLong("length"), json.getLong("modified"))
    }.getOrNull()

    private fun saveRecord(context: Context, profileId: Int, sourceId: String, record: Record) {
        val json = JSONObject()
            .put("id", record.ref.driveId).put("hash", record.ref.hash)
            .put("length", record.length).put("modified", record.modified)
        prefs(context).edit().putString("$profileId/$sourceId", json.toString()).apply()
    }
}
