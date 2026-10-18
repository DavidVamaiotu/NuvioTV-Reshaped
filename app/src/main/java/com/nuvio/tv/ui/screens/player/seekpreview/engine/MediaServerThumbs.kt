// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Our own thumbnails for a media server's stream (Silo, Jellyfin, Emby) that has none of its own. A server away from
 * the home network is usually someone else's: it only gets asked for the picture a seek lands on, one request at a
 * time. No media server's stream is read again once the player closes.
 */
internal object MediaServerThumbs {
    /** Thumbnail requests to a server away from the home network at once. */
    const val ON_DEMAND_FETCHES = 1

    /** No coverage pass, no generating screen, no copy: only the spot a seek asks for. */
    fun onDemandOnly(mediaServer: Boolean, url: String): Boolean = mediaServer && !onHomeNetwork(url)

    /** A private, link-local or Tailscale address, a home-network name, or the box itself. */
    fun onHomeNetwork(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        return PreviewSourcePolicy.isLocalHost(host) || isLoopback(host)
    }

    /** An unfinished 4K coverage pass may carry on after the player closes, never on a media server's stream. */
    fun continuesAfterClose(mediaServer: Boolean): Boolean = !mediaServer

    /** Another version on a media server is read for copy thumbnails only from a server on the home network. */
    fun copyAllowed(serverAddress: String?): Boolean = serverAddress != null && onHomeNetwork(serverAddress)

    fun fetchLimit(onDemand: Boolean, limit: Int): Int = if (onDemand) minOf(limit, ON_DEMAND_FETCHES) else limit

    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
