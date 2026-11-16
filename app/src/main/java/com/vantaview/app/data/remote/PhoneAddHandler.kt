// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.remote

import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.ui.tr
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds what a phone sent through "Add from your phone": a playlist (message, M3U link or file, Xtream Codes) or
 * a Stremio add-on. Returns the answer shown on the phone; it starts with "!" when something went wrong.
 */
@Singleton
class PhoneAddHandler @Inject constructor(
    private val providers: ProviderRepository,
    private val addons: AddonRepository,
    private val session: ProfileSession,
) {
    /** What was added, for the TV to show: true for an add-on, false for a playlist. */
    data class Added(val addon: Boolean, val name: String)

    suspend fun receive(r: PhoneAddServer.Received, onAdded: (Added) -> Unit): String = try {
        when (r) {
            is PhoneAddServer.Received.Addon -> installAddon(r.url, onAdded)
            is PhoneAddServer.Received.Message -> when (val s = SmartAdd.parse(r.text)) {
                is SmartAddResult.Xtream -> playlist(providers.addXtream("", s.server, s.username, s.password, ""), onAdded)
                is SmartAddResult.M3u -> if (looksLikeAddon(s.url)) installAddon(s.url, onAdded) else playlist(providers.addM3u("", s.url, ""), onAdded)
                is SmartAddResult.Stalker -> playlist(providers.addStalker("", s.portal, s.mac, "MAG250", "", "", "", ""), onAdded)
                SmartAddResult.None -> {
                    // An add-on link pasted into the message box.
                    val link = Regex("""(stremio|nuvio|https?)://\S+""").find(r.text)?.value
                    if (link != null && looksLikeAddon(link)) installAddon(link, onAdded)
                    else "!" + tr("Couldn't find a playlist link or login in that text.")
                }
            }
            is PhoneAddServer.Received.M3u ->
                if (looksLikeAddon(r.url)) installAddon(r.url, onAdded) else playlist(providers.addM3u(r.name, r.url, r.guide), onAdded)
            is PhoneAddServer.Received.Xtream -> {
                if (r.server.isBlank() || r.username.isBlank() || r.password.isBlank()) "!" + tr("Fill in the server address, username and password.")
                else playlist(providers.addXtream(r.name, r.server, r.username, r.password, r.guide), onAdded)
            }
            is PhoneAddServer.Received.M3uFile ->
                playlist(providers.addM3uFile(r.name.ifBlank { "My playlist" }, android.net.Uri.fromFile(r.file), ""), onAdded)
        }
    } catch (e: Exception) {
        "!" + (e.message ?: tr("Something went wrong"))
    }

    private fun playlist(id: Long, onAdded: (Added) -> Unit): String {
        session.setLastProvider(id)
        onAdded(Added(addon = false, name = ""))
        return tr("Added. Loading channels…")
    }

    private suspend fun installAddon(url: String, onAdded: (Added) -> Unit): String {
        if (url.isBlank()) return "!" + tr("Paste the add-on's install link first.")
        val a = addons.install(url)
        onAdded(Added(addon = true, name = a.manifest.name))
        return tr("Installed {0}.", a.manifest.name)
    }

    companion object {
        fun looksLikeAddon(url: String): Boolean {
            val u = url.trim().lowercase()
            return u.startsWith("stremio://") || u.startsWith("nuvio://") || u.substringBefore('?').endsWith("/manifest.json") ||
                u.substringBefore('?').trimEnd('/').endsWith("/configure")
        }
    }
}
