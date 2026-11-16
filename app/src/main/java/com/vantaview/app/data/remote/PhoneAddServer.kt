// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.remote

import com.vantaview.app.ui.tr
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Add from your phone": a small web page served by this device on the home network. Scanning the QR code
 * opens it on a phone, where a playlist can be typed, pasted or picked as a file, and sent straight here.
 * Nothing goes through the internet. Only the secret address answers, and it stops after 15 minutes or
 * when closed.
 */
class PhoneAddServer(
    private val inbox: File,
    /** The tab the phone page opens on: "s" (provider message) or "a" (add-on link). */
    private val startTab: String = "s",
    private val onReceive: (Received) -> String,
) {
    /** What the phone sent. [onReceive] adds it and returns the message to show on the phone. */
    sealed interface Received {
        data class Message(val text: String) : Received
        data class M3u(val name: String, val url: String, val guide: String) : Received
        data class Xtream(val name: String, val server: String, val username: String, val password: String, val guide: String) : Received
        data class M3uFile(val name: String, val file: File) : Received
        /** A screenshot or photo of the provider's message, to read the details from. */
        data class Image(val file: File) : Received
        /** A Stremio add-on's install link (manifest.json, stremio:// or its set-up page). */
        data class Addon(val url: String) : Received
    }

    private var server: ServerSocket? = null
    private val token = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** Starts and returns the address for the QR code, or null without a Wi-Fi or wired network. */
    fun start(): String? {
        val ip = localAddress() ?: return null
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true, name = "phone-add") {
            val until = System.currentTimeMillis() + 15 * 60_000L
            s.soTimeout = 1_000
            while (!s.isClosed && System.currentTimeMillis() < until) {
                val c = try { s.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { answer(c) }; runCatching { c.close() } }
            }
            runCatching { s.close() }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() { runCatching { server?.close() } }

    /** Reads the request line and headers, leaving the stream at the start of the body. */
    private fun head(input: InputStream): Pair<String, Map<String, String>> {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = buf.toString("ISO-8859-1").split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return lines.first() to headers
    }

    private fun answer(c: Socket) {
        c.soTimeout = 30_000
        val input = c.getInputStream()
        val (line, headers) = head(input)
        val method = line.substringBefore(' ')
        val path = line.split(' ').getOrNull(1).orEmpty()
        val out = c.getOutputStream()
        fun reply(status: String, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        fun text(msg: String, ok: Boolean) = reply("200 OK", "application/json; charset=utf-8",
            org.json.JSONObject().put("ok", ok).put("message", msg).toString().toByteArray())

        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        when {
            method == "GET" && (path == "/$token" || path == "/$token/") ->
                reply("200 OK", "text/html; charset=utf-8", page().toByteArray())
            method == "POST" && path == "/$token/send" -> {
                if (length > 200_000) return text(tr("That's too long to send."), false)
                val body = ByteArray(length.toInt()).also { readFully(input, it) }.toString(Charsets.UTF_8)
                val form = body.split('&').filter { '=' in it }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8").trim()
                }
                val received = when (form["kind"]) {
                    "message" -> Received.Message(form["text"].orEmpty())
                    "m3u" -> Received.M3u(form["name"].orEmpty(), form["url"].orEmpty(), form["guide"].orEmpty())
                    "addon" -> Received.Addon(form["url"].orEmpty())
                    "xtream" -> Received.Xtream(
                        form["name"].orEmpty(), form["server"].orEmpty(), form["username"].orEmpty(), form["password"].orEmpty(), form["guide"].orEmpty(),
                    )
                    else -> return text(tr("Something went wrong"), false)
                }
                val msg = onReceive(received)
                text(msg.removePrefix("!"), !msg.startsWith("!"))
            }
            method == "POST" && path.startsWith("/$token/image") -> {
                if (length <= 0 || length > 25L * 1024 * 1024) return text(tr("That picture is too big to send (up to 25 MB)."), false)
                inbox.mkdirs()
                val f = File(inbox, "picture-${System.currentTimeMillis()}")
                f.outputStream().use { o ->
                    val b = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(b, 0, n); left -= n
                    }
                }
                val msg = try { onReceive(Received.Image(f)) } finally { f.delete() }
                text(msg.removePrefix("!"), !msg.startsWith("!"))
            }
            method == "POST" && path.startsWith("/$token/file") -> {
                if (length <= 0 || length > MAX_FILE) return text(tr("That file is too big to send (up to 200 MB)."), false)
                inbox.mkdirs()
                val f = File(inbox, "playlist-${System.currentTimeMillis()}.m3u")
                f.outputStream().use { o ->
                    val b = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(b, 0, n); left -= n
                    }
                }
                val name = URLDecoder.decode(path.substringAfter("name=", ""), "UTF-8").substringBeforeLast('.')
                val msg = try { onReceive(Received.M3uFile(name, f)) } finally { f.delete() }
                text(msg.removePrefix("!"), !msg.startsWith("!"))
            }
            else -> reply("404 Not Found", "text/plain", ByteArray(0))
        }
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The page on the phone, in this device's language. */
    private fun page(): String {
        fun t(s: String) = esc(tr(s))
        fun on(tab: String) = if (tab == startTab) " class=\"on\"" else ""
        // For text inside the script: quoted and escaped, so an apostrophe in a translation can't break it.
        fun js(s: String) = org.json.JSONObject.quote(tr(s)).replace("</", "<\\/")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VantaView</title><style>
:root{color-scheme:dark}body{margin:0;background:#07080f;color:#e9ecf5;font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:20px 16px 40px}h1{font-size:24px;margin:4px 0 4px}p{color:#9aa3b8;margin:6px 0 16px}
.tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.tabs button{flex:1 1 auto;background:#141826;color:#e9ecf5;border:1px solid #232a3d;border-radius:12px;padding:10px 12px;font:inherit}
.tabs button.on{border-color:#00e5ff;color:#00e5ff;font-weight:700}section{display:none}section.on{display:block}
label{display:block;font-size:14px;color:#9aa3b8;margin:12px 0 4px}input,textarea{width:100%;box-sizing:border-box;background:#0f1320;color:#e9ecf5;border:1px solid #2a3248;border-radius:10px;padding:12px;font:inherit}
textarea{min-height:140px}.go{margin-top:18px;width:100%;background:linear-gradient(90deg,#6e54e6,#2f7bff);color:#fff;border:0;border-radius:12px;padding:14px;font:inherit;font-weight:700}
#msg{margin-top:16px;padding:12px;border-radius:10px;display:none}#msg.ok{display:block;background:#0f2a22;color:#19e57a}#msg.bad{display:block;background:#2a1018;color:#ff6b81}
small{display:block;color:#6f7890;margin-top:24px}
</style></head><body><main>
<h1>${t(if (startTab == "a") "Add an add-on" else "Add a playlist")}</h1><p>${t("This goes straight to VantaView on your other device, over your home network. Nothing passes through the internet.")}</p>
<div class="tabs"><button data-t="s"${on("s")}>${t("Paste message")}</button><button data-t="m"${on("m")}>${t("M3U link")}</button><button data-t="x"${on("x")}>${t("Xtream Codes")}</button><button data-t="f"${on("f")}>${t("M3U file")}</button><button data-t="i"${on("i")}>${t("Screenshot")}</button><button data-t="a"${on("a")}>${t("Add-on")}</button></div>
<section id="s"${on("s")}><label>${t("Provider message or link")}</label><textarea id="text"></textarea><button class="go" data-kind="message">${t("Send")}</button></section>
<section id="m"${on("m")}><label>${t("Playlist name (optional)")}</label><input id="mname"><label>${t("M3U link")}</label><input id="url" inputmode="url" autocapitalize="off"><label>${t("TV guide link (optional)")}</label><input id="mguide" inputmode="url" autocapitalize="off"><button class="go" data-kind="m3u">${t("Send")}</button></section>
<section id="x"${on("x")}><label>${t("Playlist name (optional)")}</label><input id="xname"><label>${t("Server address")}</label><input id="server" inputmode="url" autocapitalize="off"><label>${t("Username")}</label><input id="username" autocapitalize="off"><label>${t("Password")}</label><input id="password" type="password"><label>${t("TV guide link (optional)")}</label><input id="xguide" inputmode="url" autocapitalize="off"><button class="go" data-kind="xtream">${t("Send")}</button></section>
<section id="f"${on("f")}><label>${t("M3U file")}</label><input id="file" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/x-mpegurl,text/plain"><button class="go" data-kind="file">${t("Send")}</button></section>
<section id="i"${on("i")}><label>${t("A screenshot or photo of your provider's message")}</label><input id="picture" type="file" accept="image/*"><p>${t("Your TV reads the details from the picture, then shows them for you to check.")}</p><button class="go" data-kind="image">${t("Send")}</button></section>
<section id="a"${on("a")}><label>${t("Add-on install link")}</label><input id="addon" inputmode="url" autocapitalize="off" placeholder="https://…/manifest.json"><p>${t("Copy the install link from the add-on's page (or its Copy link button) and paste it here. Set-up page addresses work too.")}</p><button class="go" data-kind="addon">${t("Send")}</button></section>
<div id="msg"></div><small>VantaView</small></main><script>
var q=function(i){return document.getElementById(i)};
document.querySelectorAll('.tabs button').forEach(function(b){b.onclick=function(){document.querySelectorAll('.tabs button,section').forEach(function(e){e.classList.remove('on')});b.classList.add('on');q(b.dataset.t).classList.add('on')}});
function show(r){var m=q('msg');m.textContent=r.message;m.className=r.ok?'ok':'bad'}
function sending(){var m=q('msg');m.textContent=${js("Sending…")};m.className='ok'}
document.querySelectorAll('.go').forEach(function(b){b.onclick=function(){
var k=b.dataset.kind,base=location.pathname.replace(/\/$/,'');
if(k==='image'){var g=q('picture').files[0];if(!g){show({ok:false,message:${js("Choose a picture…")}});return}sending();
fetch(base+'/image',{method:'POST',body:g}).then(function(r){return r.json()}).then(show).catch(function(){show({ok:false,message:${js("Couldn't reach VantaView. Check this phone is on the same Wi-Fi.")}})});return}
if(k==='file'){var f=q('file').files[0];if(!f){show({ok:false,message:${js("Choose a file…")}});return}sending();
fetch(base+'/file?name='+encodeURIComponent(f.name),{method:'POST',body:f}).then(function(r){return r.json()}).then(show).catch(function(){show({ok:false,message:${js("Couldn't reach VantaView. Check this phone is on the same Wi-Fi.")}})});return}
var d={kind:k};if(k==='message')d.text=q('text').value;
if(k==='m3u'){d.name=q('mname').value;d.url=q('url').value;d.guide=q('mguide').value}
if(k==='addon'){d.url=q('addon').value}
if(k==='xtream'){d.name=q('xname').value;d.server=q('server').value;d.username=q('username').value;d.password=q('password').value;d.guide=q('xguide').value}
sending();fetch(base+'/send',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(d).toString()})
.then(function(r){return r.json()}).then(show).catch(function(){show({ok:false,message:${js("Couldn't reach VantaView. Check this phone is on the same Wi-Fi.")}})})}});
</script></body></html>"""
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object { const val MAX_FILE = 200L * 1024 * 1024 }
}
