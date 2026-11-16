// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.torrent

import android.content.Context
import android.util.Log
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.debrid.DebridFile
import com.vantaview.app.data.debrid.FilePicker
import com.vantaview.app.ui.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays torrents inside VantaView, without a debrid account. A torrent is downloaded in order, from the
 * start of the chosen file, into the app's cache, and [TorrentServer] hands it to the player as an ordinary
 * address on this device. While it plays it shares the pieces it has with other people, as all torrents do;
 * when the player closes, the torrent is removed and its file deleted, so nothing is shared afterwards.
 */
@Singleton
class TorrentEngine @Inject constructor(@ApplicationContext private val context: Context) {

    /** A torrent being played: its handle, the file in it, and where that file is on disk. */
    class Playing(
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val fileIndex: Int,
        val file: File,
        val fileOffset: Long,
        val size: Long,
        val dir: File,
    ) {
        val pieceLength: Int = info.pieceLength()
        fun pieceAt(pos: Long): Int = ((fileOffset + pos) / pieceLength).toInt()
        val firstPiece: Int get() = pieceAt(0)
        val lastPiece: Int get() = pieceAt((size - 1).coerceAtLeast(0))
    }

    private val root = File(context.cacheDir, "torrents")
    private var session: SessionManager? = null
    private val playing = HashMap<String, Playing>()
    /** Torrents being started; the session isn't shut down while any are. */
    private var starting = 0
    private val server by lazy { TorrentServer(this) }

    init {
        // Anything left from last time (the app was closed mid-film) is deleted.
        runCatching { root.deleteRecursively() }
    }

    @Synchronized
    private fun session(): SessionManager = session?.takeIf { it.isRunning } ?: SessionManager().also {
        // Low-memory mode: fewer connections at once (each needs its own buffers).
        if (com.vantaview.app.data.LowMemory.active) it.start(org.libtorrent4j.SessionParams(org.libtorrent4j.SettingsPack().connectionsLimit(60)))
        else it.start()
        session = it
    }

    internal fun find(key: String): Playing? = synchronized(this) { playing[key] }

    /**
     * Starts [s] and waits until enough of it is here to start playing, reporting what's happening through
     * [progress]. Returns the address to play, or throws with a message for the viewer.
     */
    suspend fun prepare(s: AddonStream, season: Int?, episode: Int?, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val hash = s.infoHash ?: throw IllegalStateException(tr("Not a torrent"))
        synchronized(this@TorrentEngine) { starting++ }
        try { start(s, hash, season, episode, progress) } finally { synchronized(this@TorrentEngine) { starting-- } }
    }

    private suspend fun start(s: AddonStream, hash: String, season: Int?, episode: Int?, progress: (String) -> Unit): String {
        val sm = session()
        progress(tr("Finding people sharing this torrent…"))
        val trackers = (s.trackers + PUBLIC_TRACKERS).distinct()
        val magnet = "magnet:?xt=urn:btih:$hash" + trackers.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        root.mkdirs()
        val data = sm.fetchMagnet(magnet, 45, root)
            ?: throw IllegalStateException(tr("Nobody is sharing this torrent right now. Try another stream."))
        val ti = TorrentInfo.bdecode(data)
        val fs = ti.files()
        val files = (0 until fs.numFiles()).map { DebridFile(it.toString(), fs.filePath(it), fs.fileSize(it)) }
        val idx = s.fileIdx?.takeIf { it in files.indices && FilePicker.isVideo(files[it].name) }
            ?: FilePicker.pick(files, s.filename, season, episode)?.id?.toInt()
            ?: throw IllegalStateException(tr("No video file in this torrent"))
        val size = fs.fileSize(idx)
        val free = root.usableSpace
        if (size > free - 300L * 1024 * 1024) {
            throw IllegalStateException(tr("Not enough free space for this stream ({0} needed). Pick a smaller one.", gb(size)))
        }

        val dir = File(root, hash).apply { mkdirs() }
        val priorities = Array(fs.numFiles()) { if (it == idx) Priority.DEFAULT else Priority.IGNORE }
        sm.download(ti, dir, null, priorities, ArrayList(), TorrentFlags.SEQUENTIAL_DOWNLOAD)
        var th: TorrentHandle? = null
        repeat(50) { if (th == null) { th = sm.find(ti.infoHash()); if (th == null) delay(100) } }
        val handle = th?.takeIf { it.isValid } ?: throw IllegalStateException(tr("The torrent couldn't be started."))

        val p = Playing(handle, ti, idx, File(dir, fs.filePath(idx)), fs.fileOffset(idx), size, dir)
        val key = "$hash-$idx"
        synchronized(this@TorrentEngine) { playing[key] = p }

        // The start of the file, and its last pieces (some formats keep their index at the end), come first.
        val first = p.firstPiece
        val startPieces = (first until (first + START_PIECES).coerceAtMost(p.lastPiece + 1)).toList()
        val endPieces = listOf(p.lastPiece - 1, p.lastPiece).filter { it >= first }.distinct()
        (startPieces + endPieces).forEachIndexed { i, piece -> handle.setPieceDeadline(piece, 1000 + i * 300) }

        val need = (startPieces + endPieces).distinct()
        val started = System.currentTimeMillis()
        while (true) {
            if (!handle.isValid) throw IllegalStateException(tr("The torrent couldn't be started."))
            val have = need.count { handle.havePiece(it) }
            if (have == need.size) break
            val st = handle.status()
            val pct = have * 100 / need.size
            progress(tr("Torrent: {0} people sharing, {1}/s. Buffering {2}%", st.numPeers(), rate(st.downloadRate().toLong()), pct))
            if (System.currentTimeMillis() - started > 120_000 && st.numPeers() == 0) {
                stop(key)
                throw IllegalStateException(tr("Nobody is sharing this torrent right now. Try another stream."))
            }
            delay(500)
        }
        return server.address(key, fs.fileName(idx))
    }

    /** Status line for the stream info box while a torrent plays. */
    fun status(key: String): String? {
        val p = find(key) ?: return null
        val st = runCatching { p.handle.status() }.getOrNull() ?: return null
        return tr("Torrent: {0} people sharing, down {1}/s, up {2}/s", st.numPeers(), rate(st.downloadRate().toLong()), rate(st.uploadRate().toLong()))
    }

    /** Stops [key] (from an address made by [prepare]) and deletes its file, so it's no longer shared. */
    fun stop(key: String) {
        val p = synchronized(this) { playing.remove(key) } ?: return
        runCatching { session?.remove(p.handle) }
        Thread {
            // Give libtorrent a moment to close the file before it's deleted.
            Thread.sleep(1500)
            runCatching { p.dir.deleteRecursively() }
            synchronized(this) {
                if (playing.isEmpty() && starting == 0) {
                    runCatching { session?.stop() }
                    session = null
                    server.close()
                }
            }
        }.start()
    }

    /** Stops whichever torrent [address] belongs to; anything else is ignored. */
    fun stopAddress(address: String?) {
        server.keyOf(address)?.let { stop(it) }
    }

    /** Asks for the pieces from [piece] onwards soonest, for a seek or the next stretch of playing. */
    internal fun want(p: Playing, piece: Int) {
        val last = p.lastPiece
        for (i in 0 until AHEAD) {
            val n = piece + i
            if (n > last) break
            if (!p.handle.havePiece(n)) runCatching { p.handle.setPieceDeadline(n, 300 + i * 200) }
        }
    }

    companion object {
        private const val TAG = "TorrentEngine"
        private const val START_PIECES = 6
        private const val AHEAD = 12
        fun isLocal(url: String?) = url?.startsWith("http://127.0.0.1:") == true && url.contains("/bt/")

        // Well-known open trackers, as most torrent players add, to find people sharing faster than DHT alone.
        private val PUBLIC_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
        )

        private fun gb(b: Long) = if (b >= 1_073_741_824L) "%.1f GB".format(b / 1_073_741_824.0) else "${b / 1_048_576L} MB"
        private fun rate(b: Long) = if (b >= 1_048_576L) "%.1f MB".format(b / 1_048_576.0) else "${b / 1024} KB"
        internal fun log(msg: String) = Log.i(TAG, msg)
    }
}
