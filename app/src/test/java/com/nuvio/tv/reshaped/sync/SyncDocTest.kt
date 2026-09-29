package com.nuvio.tv.reshaped.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class SyncDocTest {
    private val on = JsonPrimitive(true)
    private val fav = "live_tv/1/favorites"

    private fun device(base: SyncSections, values: Map<String, Map<String, JsonElement>>, remote: SyncSections, clock: Long): SyncSections {
        val now = SyncDoc.stampTime(clock, base, remote)
        return SyncDoc.prune(SyncDoc.merge(SyncDoc.stamp(base, values, now), remote), now)
    }

    @Test
    fun editsOnTwoDevicesBothSurvive() {
        val start = device(emptyMap(), mapOf(fav to mapOf("a" to on)), emptyMap(), 1_000)
        // Phone adds b, TV removes a, both from the same start.
        val phone = device(start, mapOf(fav to mapOf("a" to on, "b" to on)), start, 2_000)
        val tv = device(start, mapOf(fav to emptyMap()), phone, 3_000)
        assertEquals(setOf("b"), SyncDoc.values(tv, fav).keys)
    }

    @Test
    fun uploadOverNewerFileLosesNothing() {
        val start = device(emptyMap(), mapOf(fav to mapOf("a" to on)), emptyMap(), 1_000)
        val phone = device(start, mapOf(fav to mapOf("a" to on, "b" to on)), start, 2_000)
        // The TV read the file before the phone wrote, then overwrote it.
        val tv = device(start, mapOf(fav to mapOf("a" to on, "c" to on)), start, 2_500)
        // The phone's next round puts b back.
        val phoneAgain = device(phone, mapOf(fav to mapOf("a" to on, "b" to on)), tv, 4_000)
        assertEquals(setOf("a", "b", "c"), SyncDoc.values(phoneAgain, fav).keys)
    }

    @Test
    fun newDeviceDefaultsDoNotReplaceTheAccountsSettings() {
        val section = "settings/tv"
        val first = device(emptyMap(), mapOf(section to mapOf("pill_nav" to JsonPrimitive(false))), emptyMap(), 1_000)
        val second = device(emptyMap(), mapOf(section to mapOf("pill_nav" to JsonPrimitive(true))), first, 9_000)
        assertEquals(JsonPrimitive(false), SyncDoc.values(second, section)["pill_nav"])
    }

    @Test
    fun changeOnDeviceWithSlowClockStillWins() {
        val section = "settings/tv"
        val start = device(emptyMap(), mapOf(section to mapOf("x" to JsonPrimitive(1))), emptyMap(), 10_000)
        val synced = device(start, mapOf(section to mapOf("x" to JsonPrimitive(2))), start, 20_000)
        // A TV whose clock is hours behind changes it after seeing the file.
        val tv = device(synced, mapOf(section to mapOf("x" to JsonPrimitive(3))), synced, 5)
        assertEquals(JsonPrimitive(3), SyncDoc.values(tv, section)["x"])
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val doc = device(emptyMap(), mapOf(fav to mapOf("a" to on), "settings/shared" to mapOf("n" to JsonPrimitive(300))), emptyMap(), 1_000)
        val removed = device(doc, mapOf(fav to emptyMap()), doc, 2_000)
        assertEquals(removed, SyncDoc.decode(SyncDoc.encode(removed)))
        assertNull(SyncDoc.decode(SyncDoc.encode(removed))[fav]!!["a"]!!.value)
    }

    @Test
    fun oldDeletionsAreForgotten() {
        val doc = mapOf(fav to mapOf("a" to SyncEntry(null, 0L), "b" to SyncEntry(on, 0L)))
        assertEquals(setOf("b"), SyncDoc.prune(doc, SyncDoc.TOMBSTONE_MS + 1).getValue(fav).keys)
    }

    @Test
    fun unchangedDeviceStampsNothing() {
        val doc = device(emptyMap(), mapOf(fav to mapOf("a" to on)), emptyMap(), 1_000)
        assertEquals(doc, SyncDoc.stamp(doc, mapOf(fav to mapOf("a" to on), "live_tv/1/recent" to emptyMap()), 0L))
    }

    @Test
    fun newerFormatIsRefused() {
        assertFailsWith<SyncDoc.NewerFormatException> { SyncDoc.decode("""{"v":99,"s":{}}""") }
        assertEquals(emptyMap(), SyncDoc.decode("not json"))
    }
}
