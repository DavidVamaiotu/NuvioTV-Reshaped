package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Byte-bounded LRU of unique images. Timeline aliases never charge or decode an image twice. */
internal class PreviewFrameCache<S, K, V>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {
    private class Frame<S, V>(val value: V, val bytes: Int, val slots: MutableSet<S>)
    private val frames = LinkedHashMap<K, Frame<S, V>>(16, 0.75f, true)
    private val aliases = HashMap<S, K>()
    private var bytes = 0L

    @Synchronized fun get(slot: S): V? = aliases[slot]?.let { frames[it]?.value }
    @Synchronized fun keyFor(slot: S): K? = aliases[slot]
    @Synchronized fun frame(key: K): V? = frames[key]?.value
    @Synchronized fun slots(): Set<S> = aliases.keys.toSet()
    @Synchronized fun size(): Int = bytes.toInt()

    @Synchronized fun put(slots: Collection<S>, key: K, value: V) {
        val size = sizeOf(value)
        if (size <= 0 || size > maxBytes || slots.isEmpty()) return
        val previous = frames.remove(key)
        if (previous != null) bytes -= previous.bytes
        val linked = previous?.slots ?: LinkedHashSet()
        for (slot in slots) {
            val old = aliases.put(slot, key)
            if (old != null && old != key) {
                frames[old]?.let { frame ->
                    frame.slots.remove(slot)
                    if (frame.slots.isEmpty()) { frames.remove(old); bytes -= frame.bytes }
                }
            }
            linked.add(slot)
        }
        frames[key] = Frame(value, size, linked)
        bytes += size
        trimToSize(maxBytes)
    }

    @Synchronized fun remove(slot: S) {
        val key = aliases.remove(slot) ?: return
        frames[key]?.let { frame ->
            frame.slots.remove(slot)
            if (frame.slots.isEmpty()) { frames.remove(key); bytes -= frame.bytes }
        }
    }

    @Synchronized fun trimToSize(limit: Int) {
        val iterator = frames.entries.iterator()
        while (bytes > limit.coerceAtLeast(0) && iterator.hasNext()) {
            val (key, frame) = iterator.next()
            for (slot in frame.slots) if (aliases[slot] == key) aliases.remove(slot)
            bytes -= frame.bytes
            iterator.remove()
        }
    }
}
