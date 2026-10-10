package com.winlator.star.kiosk

import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Internal-storage mirror of the game's read-only files.
 *
 * Shared storage (/sdcard) is served by MediaProvider over FUSE; the game reads Data.dat in many
 * small chunks, and on the phone entering the deck screen took ~30 s with MediaProvider at 70-97%
 * CPU (instant on PC). The mirror lives in the app's files dir (ext4, no FUSE) and Wine sees it as
 * E:. The user keeps patching the copies in Download/YGO_Joey_HD; a file is re-copied whenever its
 * size or mtime differs.
 *
 * Saves never move: system.dat lives in the Common folder (CommonDir in the registry, pinned to D:
 * by [PlayerIdSync.apply]), and Deck.ydc in the mirror is a symlink to the one in Download. Should
 * the game ever replace that link with a regular file (temp file + rename), the next launch copies
 * it back to Download (after backing the old one up) and restores the link.
 */
object GameMirror {
    private const val TAG = "KioskMirror"
    private val READ_ONLY = listOf("YGO_Lanzador.exe", "Joey_Pc.exe", "Data.dat", "Voice.dat", "Region.dat")
    const val DECK = "Deck.ydc"

    /** Copies changed read-only files from [src] to [dst]; [onCopy] is told which file is being copied. */
    fun sync(src: File, dst: File, onCopy: (String) -> Unit) {
        dst.mkdirs()
        for (name in READ_ONLY) {
            val from = findIgnoreCase(src, name) ?: continue
            val to = File(dst, from.name)
            if (to.isFile && to.length() == from.length() && to.lastModified() == from.lastModified()) continue
            onCopy(from.name)
            val tmp = File(dst, from.name + ".tmp")
            from.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(tmp.length() == from.length()) { "copia incompleta de ${from.name}" }
            tmp.setLastModified(from.lastModified())
            check(tmp.renameTo(to)) { "no se pudo colocar ${from.name}" }
            Log.i(TAG, "mirrored ${from.name} (${from.length()} bytes)")
        }
    }

    /**
     * Keeps [dst]/Deck.ydc a symlink to [src]/Deck.ydc (see class comment). A new player has no deck
     * until the game writes its first one, which lands in [dst] as a regular file: it is moved to [src].
     */
    fun linkDeck(src: File, dst: File, backupRoot: File) {
        var external = findEntry(src, DECK)
        if (external == null) {
            val first = findEntry(dst, DECK)
            if (first == null || Files.isSymbolicLink(first.toPath()) || !first.isFile) {
                first?.delete()   // dangling link left from a deck that was removed from Download
                return
            }
            val target = File(src, DECK)
            val tmp = File(src, "$DECK.new")
            first.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(target)) { "no se pudo mover el primer $DECK a ${src.path}" }
            first.delete()
            Log.i(TAG, "moved the new player's first $DECK to ${target.path}")
            external = target
        }
        val link = findEntry(dst, DECK) ?: File(dst, external.name)
        if (link.exists() && !Files.isSymbolicLink(link.toPath())) {
            // The game wrote a regular file here: it is the newest deck. Back up the external one, then
            // move the new one over it (write .new + rename so Download never holds a half file).
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backup = File(backupRoot, "${stamp}_deck_antes_espejo").apply { mkdirs() }
            external.copyTo(File(backup, external.name), overwrite = true)
            val tmp = File(src, external.name + ".new")
            link.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(external)) { "no se pudo devolver $DECK a ${src.path}" }
            link.delete()
            Log.w(TAG, "game replaced the $DECK link; copied it back to ${external.path}, old one in ${backup.path}")
        }
        if (!Files.isSymbolicLink(link.toPath())) Os.symlink(external.absolutePath, link.absolutePath)
        else if (Files.readSymbolicLink(link.toPath()).toString() != external.absolutePath) {
            link.delete()
            Os.symlink(external.absolutePath, link.absolutePath)
        }
    }

    private fun findIgnoreCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }

    /** Any directory entry named [name] ignoring case (regular file or symlink, even dangling). */
    fun findEntry(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
