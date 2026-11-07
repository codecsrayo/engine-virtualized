package com.winlator.star.kiosk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.winlator.star.core.unpack.SevenZip
import java.io.File
import java.io.InputStream

/**
 * Turns a game CD image picked with the system file picker into a game folder.
 *
 * The ISO is copied into the app's data folder (7-Zip needs a real path, and a content:// Uri from
 * the picker is not one), unpacked there with the bundled 7zz, and then, in this order:
 *  - the game's files are loose on the CD (exe next to Data.dat): that folder is moved in, or
 *  - they are in Data1.cab, a plain Microsoft cabinet next to the installer's .msi (the Spanish
 *    Yugi and Kaiba CDs: <game>_pc.exe, Data.dat, Voice.dat, Region.dat): 7zz unpacks it too, or
 *  - there is only an installer (setup.exe): the caller runs it inside the Wine container once and
 *    later collects what it installed with [collectInstalled].
 * A game exe found on the CD outside those files (e.g. Crack\yugi_pc.exe, the one that runs without
 * the disc) replaces the installed exe.
 *
 * Everything but [copy], [size] and [extract] works on plain files, so it is covered by IsoImporterTest.
 */
object IsoImporter {
    private const val TAG = "KioskIso"

    /** Present in a game folder while files are being put in; a folder that keeps it is incomplete. */
    const val INSTALLING_MARK = ".instalando"

    /** Save files, never installed from a CD (see [installFrom]). */
    private val SAVES = setOf(GameMirror.DECK.lowercase(), "system.dat")

    /** Bytes [looksLikeDiscImage] needs: sector 16 of a raw (2352-byte sector) image. */
    const val HEAD_BYTES = 16 * 2352 + 24 + 8

    sealed class Found {
        data class Files(val exe: File) : Found()
        data class Cabinet(val cab: File, val noCdExe: File?) : Found()
        data class Installer(val setup: File) : Found()
    }

    /**
     * True for an ISO 9660 / UDF image: the volume descriptor at sector 16 says "CD001" (ISO 9660)
     * or "BEA01" (UDF), with 2048-byte sectors (.iso) or raw 2352-byte ones (.bin/.img, mode 1 or
     * mode 2). Checked on the picked file's first bytes before copying hundreds of MB.
     */
    fun looksLikeDiscImage(head: ByteArray): Boolean {
        val offsets = intArrayOf(16 * 2048 + 1, 16 * 2352 + 16 + 1, 16 * 2352 + 24 + 1)
        val ids = listOf("CD001", "BEA01").map { it.toByteArray(Charsets.US_ASCII) }
        return offsets.any { off ->
            ids.any { id -> off + id.size <= head.size && head.copyOfRange(off, off + id.size).contentEquals(id) }
        }
    }

    /** Peak space an import of an [isoSize]-byte image takes: the copy, its unpacked files, and a cabinet's. */
    fun spaceNeeded(isoSize: Long): Long = isoSize / 2 * 5 + (64L shl 20)

    /** The first [HEAD_BYTES] of [input] (fewer when it is shorter). */
    fun readHead(input: InputStream): ByteArray {
        val head = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        return head.copyOf(n)
    }

    /** Size of [uri] from the provider, or -1 when it does not say. */
    fun size(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L

    /** Copies [uri] ([size] bytes, or -1) to [dest], reporting 0..100 (or -1 when the size is unknown). */
    fun copy(context: Context, uri: Uri, size: Long, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no se pudo abrir la ISO")
        input.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var last = -2
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (size > 0) (done * 100 / size).toInt() else -1
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        if (size > 0) check(tmp.length() == size) { "copia incompleta de la ISO" }
        check(tmp.renameTo(dest)) { "no se pudo colocar la ISO" }
    }

    /** Unpacks [archive] (an ISO or a cabinet) into [destDir] with the bundled 7zz; throws on a 7-Zip error. */
    fun extract(context: Context, archive: File, destDir: File, report: (Int) -> Unit) {
        check(SevenZip.isAvailable(context)) { "7-Zip no está disponible en este dispositivo" }
        val result = SevenZip.extract(context, archive, destDir,
            mmt = Runtime.getRuntime().availableProcessors(), bufferBytes = 1 shl 20,
            listener = object : SevenZip.Listener {
                override fun onProgress(percent: Int, currentFile: String?) = report(percent)
                override fun onFile(name: String) {}
            },
            onProcess = {})
        // 0 = ok, 1 = warnings (e.g. a file that could not get its timestamp); 2+ = failed.
        check(result.exitCode <= 1) { "7-Zip no pudo descomprimir ${archive.name} (código ${result.exitCode}): ${result.stderrTail.takeLast(300)}" }
        Log.i(TAG, "extracted ${archive.name} into ${destDir.path} (exit ${result.exitCode})")
    }

    /**
     * Which game an unpacked CD is and what it holds, trying [preferred] (the game the user picked
     * the ISO for) first, so an ISO imported from the wrong game's card still lands in its own
     * game. A CD with only an installer cannot be told apart: it is taken as [preferred].
     */
    fun identify(root: File, preferred: GameProfile): Pair<GameProfile, Found>? {
        for (game in listOf(preferred) + GameProfile.values().filter { it != preferred })
            inspect(root, game)?.let { return game to it }
        return findSetup(root)?.let { preferred to it }
    }

    /**
     * The game's files on an unpacked CD: loose (exe next to Data.dat, as in an ISO made from an
     * installed game) or in Data1.cab. The cabinet is taken as [game]'s when the CD carries its exe
     * outside it (Crack\) or the installer's .msi is named after it.
     */
    fun inspect(root: File, game: GameProfile): Found? {
        val exes = root.walkTopDown().maxDepth(4).filter { it.isFile && game.isGameExe(it.name) }.toList()
        exes.firstOrNull { exe -> exe.parentFile?.list()?.any { it.equals("Data.dat", ignoreCase = true) } == true }
            ?.let { return Found.Files(it) }
        val files = root.listFiles()?.filter { it.isFile } ?: return null
        val cab = files.firstOrNull { it.name.equals("Data1.cab", ignoreCase = true) } ?: return null
        val msiNamed = files.any { it.name.endsWith(".msi", ignoreCase = true) && it.name.contains(game.id, ignoreCase = true) }
        return if (exes.isNotEmpty() || msiNamed) Found.Cabinet(cab, exes.firstOrNull()) else null
    }

    fun findSetup(root: File): Found.Installer? =
        root.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name.equals("setup.exe", ignoreCase = true) }
            .minByOrNull { it.relativeTo(root).path.length }
            ?.let { Found.Installer(it) }

    /**
     * Puts the folder holding [exe] (subfolders too) into [gameDir], moving the files (same
     * filesystem: no second copy of a 600 MB Data.dat) and copying only where a move fails.
     * Saves are never taken from a CD: a Deck.ydc or system.dat on it belongs to whoever made the
     * image, and its player ID would make the game reset the user's saves. A kiosk.ini already in
     * [gameDir] is kept. [INSTALLING_MARK] stays in [gameDir] until every file is in, so an install
     * cut short is never taken for a finished one. Then [noCdExe], when given, replaces the
     * installed exe.
     */
    fun installFrom(exe: File, gameDir: File, noCdExe: File? = null) {
        val src = checkNotNull(exe.parentFile)
        gameDir.mkdirs()
        val mark = File(gameDir, INSTALLING_MARK)
        mark.writeText("")
        for (from in src.walkTopDown().filter { it.isFile }.toList()) {
            if (from.name.lowercase() in SAVES) continue
            val to = File(gameDir, from.relativeTo(src).path)
            if (to.exists() && to.name.equals("kiosk.ini", ignoreCase = true)) continue
            to.parentFile?.mkdirs()
            // On a case-sensitive filesystem an old data.dat would stay beside the new Data.dat.
            to.parentFile?.listFiles()?.filter { it.name != to.name && it.name.equals(to.name, ignoreCase = true) }?.forEach { it.delete() }
            if (!from.renameTo(to)) GameMirror.copyFile(from, to)
        }
        noCdExe?.let { GameMirror.copyFile(it, File(gameDir, exe.name)) }
        mark.delete()
    }

    /** After the installer ran in the container: finds the installed exe in its C: drive. */
    fun collectInstalled(driveC: File, game: GameProfile): File? =
        driveC.walkTopDown()
            .onEnter { !it.name.equals("windows", ignoreCase = true) && !it.name.equals("users", ignoreCase = true) }
            .maxDepth(6)
            .firstOrNull { it.isFile && game.isGameExe(it.name) }
}
