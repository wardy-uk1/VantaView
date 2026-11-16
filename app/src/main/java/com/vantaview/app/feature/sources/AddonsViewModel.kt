// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.InstalledAddon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RestoreUi {
    data object Idle : RestoreUi
    data class StremioLogin(val email: String = "", val error: String? = null) : RestoreUi
    data class Loading(val text: String) : RestoreUi
    data class Pick(
        val title: String, val items: List<AddonRepository.RemoteAddon>, val selected: Set<String>,
        /** A Nuvio backup's Watchlist, Watched and Continue watching, and whether to bring them in too. */
        val extra: com.vantaview.app.data.backup.NuvioData? = null, val extraOn: Boolean = true,
    ) : RestoreUi
    data class Done(
        val text: String, val isError: Boolean,
        /** Add-ons whose servers' certificates this device refused; each can be trusted and retried. */
        val untrusted: List<Untrusted> = emptyList(),
    ) : RestoreUi
}

/** An add-on that couldn't install because its server's certificate isn't trusted. */
data class Untrusted(val name: String, val url: String, val cert: com.vantaview.app.data.security.UntrustedCert)

data class AddonForm(
    val untrusted: Untrusted? = null,
    val url: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val nuvio: com.vantaview.app.data.backup.NuvioRestore,
    private val phoneHandler: com.vantaview.app.data.remote.PhoneAddHandler,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    // ---- Add from your phone: the phone page opens on its Add-on tab ----

    private val _phone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = _phone
    private var phoneServer: com.vantaview.app.data.remote.PhoneAddServer? = null
    private var phoneTimer: kotlinx.coroutines.Job? = null

    fun startPhone() {
        if (phoneServer != null) return
        val server = com.vantaview.app.data.remote.PhoneAddServer(java.io.File(context.cacheDir, "phone-add"), startTab = "a") { r ->
            kotlinx.coroutines.runBlocking {
                phoneHandler.receive(r) { added ->
                    _form.update {
                        it.copy(
                            busy = false, isError = false,
                            message = if (added.addon) com.vantaview.app.ui.tr("Installed {0} from your phone.", added.name)
                            else com.vantaview.app.ui.tr("Playlist added from your phone. Loading channels…"),
                        )
                    }
                }
            }
        }
        val url = runCatching { server.start() }.getOrNull()
        if (url == null) {
            _form.update { it.copy(message = com.vantaview.app.ui.tr("Connect this device to Wi-Fi or a network cable first."), isError = true) }
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

    val addons: StateFlow<List<InstalledAddon>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(AddonForm())
    val form: StateFlow<AddonForm> = _form

    fun editUrl(v: String) = _form.update { it.copy(url = v, message = null, isError = false) }

    fun install(url: String = _form.value.url) {
        if (_form.value.busy) return
        _form.update { it.copy(busy = true, message = "Installing…", isError = false) }
        viewModelScope.launch {
            try {
                val a = repo.install(url)
                _form.value = AddonForm(message = "Installed ${a.manifest.name}.")
            } catch (e: com.vantaview.app.data.addon.UntrustedServerException) {
                _form.update { it.copy(busy = false, message = e.message, isError = true, untrusted = Untrusted(e.cert.host, e.url, e.cert)) }
            } catch (e: Exception) {
                _form.update { it.copy(busy = false, message = e.message ?: "Couldn't install that add-on", isError = true) }
            }
        }
    }

    fun toggle(a: InstalledAddon) = viewModelScope.launch { repo.setEnabled(a.entity.id, !a.entity.enabled) }

    // ---- Restore ----

    private val _restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    val restore: StateFlow<RestoreUi> = _restore

    fun startStremio() { _restore.value = RestoreUi.StremioLogin() }

    fun cancelRestore() { _restore.value = RestoreUi.Idle }

    fun signInStremio(email: String, password: String) {
        _restore.value = RestoreUi.Loading("Signing in to Stremio…")
        viewModelScope.launch {
            try {
                val list = repo.stremioAccountAddons(email, password)
                _restore.value = pick("Your Stremio add-ons", list)
            } catch (e: Exception) {
                _restore.value = RestoreUi.StremioLogin(email, e.message ?: "Couldn't sign in")
            }
        }
    }

    fun loadBackup(text: String) {
        viewModelScope.launch {
            try {
                val extra = if (com.vantaview.app.data.backup.NuvioImport.isNuvio(text))
                    runCatching { com.vantaview.app.data.backup.NuvioImport.parse(text) }.getOrNull()?.takeIf { !it.empty } else null
                val addons = runCatching { repo.readBackup(text) }.getOrElse { if (extra != null) emptyList() else throw it }
                _restore.value = if (extra != null) RestoreUi.Pick(
                    "Nuvio backup", addons, addons.filter { !it.alreadyInstalled }.map { it.url }.toSet(), extra,
                ) else pick("Add-ons in the backup", addons)
            } catch (e: Exception) {
                _restore.value = RestoreUi.Done(e.message ?: "Couldn't read that backup", isError = true)
            }
        }
    }

    private fun pick(title: String, list: List<AddonRepository.RemoteAddon>): RestoreUi =
        if (list.isEmpty()) RestoreUi.Done("No add-ons found.", isError = true)
        else RestoreUi.Pick(title, list, list.filter { !it.alreadyInstalled }.map { it.url }.toSet())

    fun togglePick(url: String) {
        val p = _restore.value as? RestoreUi.Pick ?: return
        _restore.value = p.copy(selected = if (url in p.selected) p.selected - url else p.selected + url)
    }

    fun toggleExtra() {
        val p = _restore.value as? RestoreUi.Pick ?: return
        _restore.value = p.copy(extraOn = !p.extraOn)
    }

    fun importSelected() {
        val p = _restore.value as? RestoreUi.Pick ?: return
        val chosen = p.items.filter { it.url in p.selected }
        val extra = p.extra?.takeIf { p.extraOn }
        extra?.let { runCatching { nuvio.apply(it) } }
        val extraText = extra?.let { "Brought in ${it.summary()}." }
        if (chosen.isEmpty()) { _restore.value = if (extraText != null) RestoreUi.Done(extraText, false) else RestoreUi.Idle; return }
        viewModelScope.launch {
            val failed = ArrayList<String>()
            val untrusted = ArrayList<Untrusted>()
            chosen.forEachIndexed { i, a ->
                _restore.value = RestoreUi.Loading("Installing ${i + 1} of ${chosen.size}: ${a.name}")
                runCatching { repo.install(a.url) }.onFailure {
                    failed += "${a.name} (${it.message})"
                    if (it is com.vantaview.app.data.addon.UntrustedServerException) untrusted += Untrusted(a.name, a.url, it.cert)
                }
            }
            val ok = chosen.size - failed.size
            _restore.value = RestoreUi.Done(
                "Restored $ok of ${chosen.size} add-ons." + (extraText?.let { " $it" } ?: "") +
                    if (failed.isNotEmpty()) "\nNot restored: " + failed.joinToString("; ") else "",
                isError = failed.isNotEmpty() && ok == 0,
                untrusted = untrusted,
            )
        }
    }

    /** The user chose to trust this server's exact certificate: remember it and install again. */
    fun trustAndInstall(u: Untrusted) {
        com.vantaview.app.data.security.CertPins.pin(u.cert.fingerprint)
        viewModelScope.launch {
            val r = runCatching { repo.install(u.url) }
            val text = if (r.isSuccess) "Installed ${u.name}." else "Still couldn't install ${u.name} (${r.exceptionOrNull()?.message})."
            _form.update { it.copy(untrusted = null, message = text, isError = r.isFailure, busy = false) }
            (_restore.value as? RestoreUi.Done)?.let { d ->
                _restore.value = d.copy(text = d.text + "\n" + text, untrusted = d.untrusted.filter { it.url != u.url })
            }
        }
    }

    suspend fun backupText(): String = repo.backupJson()

    fun showMessage(text: String, isError: Boolean = false) { _restore.value = RestoreUi.Done(text, isError) }

    fun remove(a: InstalledAddon) = viewModelScope.launch { repo.remove(a.entity.id) }

    companion object {
        /** Stremio's own catalogue and information add-on. It has no streams. */
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
    }
}
