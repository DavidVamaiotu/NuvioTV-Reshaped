package com.nuvio.tv.ui.reshaped.netflix

/** A catalog can insert/reorder titles while details is open; saved identity wins over position. */
internal fun netflixRestoreIndex(keys: List<String>, savedKey: String?, fallback: Int): Int {
    if (keys.isEmpty()) return 0
    val index = savedKey?.let(keys::indexOf) ?: -1
    return if (index >= 0) index else fallback.coerceIn(keys.indices)
}
