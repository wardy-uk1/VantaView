// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.remote.PhoneAddServer
import com.vantaview.app.data.remote.SmartAdd
import com.vantaview.app.data.remote.SmartAddResult
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SourceTab(val label: String) { SMART("Smart Add"), M3U("M3U link"), XTREAM("Xtream Codes"), STALKER("Stalker portal"), JELLYFIN("Jellyfin") }

data class PlaylistForm(
    val tab: SourceTab = SourceTab.SMART,
    val name: String = "",
    val smartText: String = "",
    val m3uUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val portal: String = "",
    /** Optional TV guide link typed for an M3U or Xtream playlist. */
    val guide: String = "",
    val mac: String = "",
    /** Stalker › Advanced: details of the user's own MAG box, for providers that lock to one box. */
    val boxOpen: Boolean = false,
    val model: String = "MAG250",
    val serial: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: ProviderRepository,
    private val session: ProfileSession,
    private val countries: com.vantaview.app.data.country.CountryStore,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    private val db: com.vantaview.app.data.db.VantaDb,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val phoneHandler: com.vantaview.app.data.remote.PhoneAddHandler,
    private val imageText: com.vantaview.app.data.remote.ImageText,
    private val jellyfin: com.vantaview.app.data.jellyfin.JellyfinClient,
) : ViewModel() {
    /** Jellyfin › Quick Connect: the code to approve on another device, while waiting. */
    private val _quick = MutableStateFlow<com.vantaview.app.data.jellyfin.QuickCode?>(null)
    val quick: StateFlow<com.vantaview.app.data.jellyfin.QuickCode?> = _quick
    private var quickJob: kotlinx.coroutines.Job? = null

    /** Signs in to a Jellyfin server with Quick Connect: shows a code, then waits up to 5 minutes for it to be approved. */
    fun jellyfinQuick() {
        val f = _form.value
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            try {
                val base = jellyfin.normalize(f.server)
                jellyfin.serverName(base)
                if (!jellyfin.quickConnectOn(base)) throw IllegalStateException(com.vantaview.app.ui.tr("Quick Connect is switched off on this server. Sign in with your username and password instead."))
                val q = jellyfin.quickStart(base)
                _quick.value = q
                _form.update { it.copy(message = null, isError = false) }
                val until = System.currentTimeMillis() + 5 * 60_000L
                var login: com.vantaview.app.data.jellyfin.JellyfinLogin? = null
                while (login == null && System.currentTimeMillis() < until) {
                    kotlinx.coroutines.delay(3_000)
                    login = jellyfin.quickCheck(base, q)
                }
                _quick.value = null
                val l = login ?: throw IllegalStateException(com.vantaview.app.ui.tr("The code ran out of time. Start again."))
                val id = repo.addJellyfin(f.name, base, l)
                session.setLastProvider(id)
                _form.value = PlaylistForm(tab = f.tab, message = "Jellyfin server added. Loading…")
            } catch (e: Exception) {
                _quick.value = null
                if (e !is kotlinx.coroutines.CancellationException) _form.update { it.copy(message = e.message ?: "Couldn't sign in to that Jellyfin server", isError = true) }
            }
        }
    }

    fun cancelQuick() { quickJob?.cancel(); _quick.value = null }

    /** Reading a picture now (Smart Add › Choose an image). */
    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading

    /** Smart Add › Choose an image: reads the picture on this device, then fills in the form to check. */
    fun readImage(uri: android.net.Uri) {
        if (_reading.value) return
        _reading.value = true
        _form.update { it.copy(message = tr("Reading the picture…"), isError = false) }
        viewModelScope.launch {
            try {
                fillFromText(imageText.read(uri))
            } catch (e: Exception) {
                _form.update { it.copy(message = e.message ?: tr("That picture couldn't be read."), isError = true) }
            } finally {
                _reading.value = false
            }
        }
    }

    /** Puts text read from a picture into Smart Add and looks for the details; the text stays there to fix by hand. */
    private fun fillFromText(text: String) {
        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (clean.isBlank()) {
            _form.update { it.copy(message = tr("No text found in that picture. Try a sharper screenshot."), isError = true) }
            return
        }
        _form.update { it.copy(tab = SourceTab.SMART, smartText = clean) }
        detect()
    }

    val providers: StateFlow<List<ProviderEntity>> =
        repo.observeEvery().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sync: StateFlow<Map<Long, SyncState>> = repo.syncStates

    private val _form = MutableStateFlow(PlaylistForm())
    val form: StateFlow<PlaylistForm> = _form

    fun edit(change: (PlaylistForm) -> PlaylistForm) = _form.update { change(it).copy(message = null, isError = false) }

    fun detect() {
        when (val r = SmartAdd.parse(_form.value.smartText)) {
            is SmartAddResult.Xtream -> _form.update {
                it.copy(
                    tab = SourceTab.XTREAM, server = r.server, username = r.username, password = r.password,
                    message = "Found an Xtream Codes login. Check it and press Add playlist.", isError = false,
                )
            }
            is SmartAddResult.M3u -> _form.update {
                it.copy(
                    tab = SourceTab.M3U, m3uUrl = r.url,
                    message = "Found an M3U link. Check it and press Add playlist.", isError = false,
                )
            }
            is SmartAddResult.Stalker -> _form.update {
                it.copy(
                    tab = SourceTab.STALKER, portal = r.portal, mac = r.mac,
                    message = "Found a Stalker portal and MAC address. Check them and press Add portal.", isError = false,
                )
            }
            SmartAddResult.None -> _form.update {
                it.copy(message = "Couldn't find a playlist link or login in that text.", isError = true)
            }
        }
    }

    fun add() {
        val f = _form.value
        viewModelScope.launch {
            try {
                val id = when (f.tab) {
                    SourceTab.M3U -> repo.addM3u(f.name, f.m3uUrl, f.guide)
                    SourceTab.XTREAM -> repo.addXtream(f.name, f.server, f.username, f.password, f.guide)
                    SourceTab.STALKER -> repo.addStalker(f.name, f.portal, f.mac, f.model, f.serial, f.deviceId, f.deviceId2, f.signature)
                    SourceTab.JELLYFIN -> {
                        val base = jellyfin.normalize(f.server)
                        jellyfin.serverName(base)
                        repo.addJellyfin(f.name, base, jellyfin.signIn(base, f.username, f.password))
                    }
                    SourceTab.SMART -> { detect(); return@launch }
                }
                session.setLastProvider(id)
                _form.value = PlaylistForm(tab = f.tab, message = "Playlist added. Loading channels…")
            } catch (e: Exception) {
                _form.update { it.copy(message = e.message ?: "Could not add the playlist", isError = true) }
            }
        }
    }

    fun refresh(id: Long) = repo.sync(id)

    fun rename(p: ProviderEntity, name: String) { viewModelScope.launch { repo.rename(p.id, name) } }

    /** Switch a playlist off (hidden everywhere, kept) or on again. */
    fun setEnabled(p: ProviderEntity, on: Boolean) { viewModelScope.launch { repo.setEnabled(p.id, on) } }

    /** The playlist's server (or "A file on this device"), for telling playlists apart. */
    fun address(p: ProviderEntity): String = repo.address(p)

    /** M3U from a file picked on this device. */
    fun addFile(uri: android.net.Uri) {
        val f = _form.value
        viewModelScope.launch {
            try {
                val id = repo.addM3uFile(f.name, uri, f.guide)
                session.setLastProvider(id)
                _form.value = PlaylistForm(tab = f.tab, message = "Playlist added from the file. Loading channels…")
            } catch (e: Exception) {
                _form.update { it.copy(message = e.message ?: "Couldn't read that file", isError = true) }
            }
        }
    }

    /** "Add from your phone": the address in the QR code while it's on, or null. */
    private val _phone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = _phone
    private var phoneServer: PhoneAddServer? = null
    private var phoneTimer: kotlinx.coroutines.Job? = null

    fun startPhone() {
        if (phoneServer != null) return
        val server = PhoneAddServer(java.io.File(context.cacheDir, "phone-add"), onReceive = ::receive)
        val url = runCatching { server.start() }.getOrNull()
        if (url == null) {
            _form.update { it.copy(message = tr("Connect this device to Wi-Fi or a network cable first."), isError = true) }
            return
        }
        phoneServer = server
        _phone.value = url
        phoneTimer = viewModelScope.launch { kotlinx.coroutines.delay(15 * 60_000L); stopPhone() }
    }

    fun stopPhone() {
        phoneTimer?.cancel(); phoneTimer = null
        phoneServer?.stop(); phoneServer = null
        _phone.value = null
    }

    override fun onCleared() = stopPhone()

    /** Adds what the phone sent; the answer is shown on the phone ("!" first for a problem). */
    private fun receive(r: PhoneAddServer.Received): String = kotlinx.coroutines.runBlocking {
        // A picture from the phone: read here, and the details shown on the TV to check.
        if (r is PhoneAddServer.Received.Image) {
            return@runBlocking try {
                fillFromText(phoneHandler.readPicture(r))
                if (_form.value.isError) "!" + (_form.value.message ?: tr("No details found in that picture."))
                else tr("Read it. Check the details on your TV, then press Add playlist.")
            } catch (e: Exception) { "!" + (e.message ?: tr("That picture couldn't be read.")) }
        }
        phoneHandler.receive(r) { added ->
            _form.update {
                it.copy(
                    message = if (added.addon) tr("Installed {0} from your phone.", added.name) else tr("Playlist added from your phone. Loading channels…"),
                    isError = false,
                )
            }
        }
    }

    fun fileError(message: String) = _form.update { it.copy(message = message, isError = true) }

    /** A guide link typed for an existing playlist; the guide loads again with it. */
    fun guideOffset(p: ProviderEntity): Int = guide.offsetMinutes(p.id)
    fun setGuideOffset(p: ProviderEntity, minutes: Int) = guide.setOffsetMinutes(p.id, minutes)

    fun setGuideLink(p: ProviderEntity, link: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.setGuideLink(p.id, link) }
                .onSuccess { db.providers().get(p.id)?.let(guide::refresh); done(null) }
                .onFailure { done(it.message) }
        }
    }

    fun hasFreeTv(list: List<ProviderEntity>): Boolean = list.any { repo.isFreeTv(it) }

    fun addFreeTv() { viewModelScope.launch { repo.addFreeTv() } }

    fun editCountries(id: Long) { countries.editRequest.value = id }

    fun delete(id: Long) {
        countries.forget(id)
        viewModelScope.launch { repo.delete(id) }
    }
}
