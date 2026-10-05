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

    /** What this device last sent ([sent]) or fetched for a source, and the playlist file it was. */
    class Record internal constructor(internal val ref: Ref, internal val length: Long, internal val modified: Long, internal val sent: Boolean)

    /** A copy sent this round: kept as sent only once the sync file names it ([commit]). */
    class Sent internal constructor(internal val profileId: Int, internal val sourceId: String, internal val record: Record, internal val replaces: String?)

    /** The Drive copy of each imported playlist (by source id), and the copies sent this round. */
    class Uploads(val refs: Map<String, Ref>, val sent: List<Sent>)

    fun isImported(source: LiveTvSource): Boolean = source.type == LiveTvSourceType.M3u && !source.url.isHttpUrl()

    /**
     * The Drive copy of each imported playlist of [profileId] in [sources], by source id: the one
     * sent before while the file is unchanged, else a new upload. A source whose upload failed is
     * left out (its entry in the sync file stays as it was).
     */
    suspend fun uploaded(context: Context, profileId: Int, sources: List<LiveTvSource>): Uploads {
        val imported = sources.filter(::isImported)
        if (imported.isEmpty()) return Uploads(emptyMap(), emptyList())
        val store = LiveTvStorage(context.applicationContext, profileId)
        val result = HashMap<String, Ref>()
        val sent = ArrayList<Sent>()
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
                    saveRecord(context, profileId, source.id, Record(saved.ref, file.length(), file.lastModified(), saved.sent))
                    result[source.id] = saved.ref
                    return@forEach
                }
                val packed = File(context.cacheDir, "reshaped_sync_upload.m3u.gz")
                try {
                    runInterruptible(Dispatchers.IO) { gzip(file, packed) }
                    val id = DriveAppFolder.upload(context, NAME_PREFIX + hash.take(12) + ".m3u.gz", packed)
                    val ref = Ref(id, hash)
                    result[source.id] = ref
                    // Saved (and the copy it replaces deleted) once the sync file names it: until
                    // then the other devices still fetch the old one.
                    sent += Sent(profileId, source.id, Record(ref, file.length(), file.lastModified(), sent = true), saved?.takeIf { it.sent }?.ref?.driveId)
                } finally {
                    packed.delete()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not send an imported playlist", error)
            }
        }
        return Uploads(result, sent)
    }

    /** The sync file now names the copies in [sent]: keep them as sent, and delete those they replace (this device's own). */
    suspend fun commit(context: Context, sent: List<Sent>) {
        sent.forEach { upload ->
            saveRecord(context, upload.profileId, upload.sourceId, upload.record)
            upload.replaces?.let { old -> runCatching { DriveAppFolder.delete(context, old) } }
        }
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
                saveRecord(context, profileId, sourceId, Record(ref, written.length(), written.lastModified(), sent = false))
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
            val stored = DriveAppFolder.list(context, NAME_PREFIX)
            stored.filter { it.id !in named && now - it.modifiedMs > UNUSED_GRACE_MS }
                .forEach { runCatching { DriveAppFolder.delete(context, it.id) } }
            // A copy this device sent that is gone (deleted elsewhere) is sent again next time.
            val present = stored.mapTo(HashSet()) { it.id }
            val prefs = prefs(context)
            val gone = prefs.all.keys.filter { key ->
                val json = runCatching { JSONObject(prefs.getString(key, null) ?: return@filter false) }.getOrNull() ?: return@filter false
                json.optBoolean("sent") && json.optString("id") !in present
            }
            if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach(::remove) }.apply()
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
        Record(Ref(json.getString("id"), json.getString("hash")), json.getLong("length"), json.getLong("modified"), json.optBoolean("sent"))
    }.getOrNull()

    private fun saveRecord(context: Context, profileId: Int, sourceId: String, record: Record) {
        val json = JSONObject()
            .put("id", record.ref.driveId).put("hash", record.ref.hash)
            .put("length", record.length).put("modified", record.modified).put("sent", record.sent)
        prefs(context).edit().putString("$profileId/$sourceId", json.toString()).apply()
    }
}
