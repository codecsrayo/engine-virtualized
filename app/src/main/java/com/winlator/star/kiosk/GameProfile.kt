package com.winlator.star.kiosk

import android.content.Context
import java.io.File

/**
 * The three Power of Chaos games the kiosk can run. They share one Wine container, the player ID
 * (flcrc) and the Common folder (system.dat); each has its own folder, exe and InstallDir? key.
 *
 * Joey is the one tested on the phone (Spanish edition, HD 1708x768 patch) and keeps running
 * through YGO_Lanzador.exe, which is hard-wired to joey_pc.exe. Yugi and Kaiba start their exe
 * directly (KioskBootstrap writes InstallDir? itself). Yugi's and Kaiba's values come from their
 * Spanish CDs: the .msi writes InstallDir (Yugi, no letter) or InstallDirK, and the exe reads
 * deck.ydc, CommonDir and flcrc and takes -Lspa, like joey_pc.exe.
 */
enum class GameProfile(
    val id: String,
    val title: String,
    val dirName: String,
    private val exePattern: Regex,
    val installDirKey: String,
    val usesLauncher: Boolean,
    val screenSize: String,
    /** Files checked before launching, besides the exe. */
    val requiredFiles: List<String>,
    /** Read-only files mirrored to internal storage; null = every file in the folder but the saves. */
    val mirrorFiles: List<String>?,
    val coverAsset: String?,
) {
    JOEY("joey", "Joey the Passion", "Joey", Regex("joey_pc\\.exe", RegexOption.IGNORE_CASE), "InstallDirJ",
        usesLauncher = true, screenSize = "1708x768",
        requiredFiles = listOf("Data.dat", "Voice.dat"),
        mirrorFiles = listOf("Joey_Pc.exe", "Data.dat", "Voice.dat", "Region.dat"),
        coverAsset = "kiosk/cover.jpg"),
    YUGI("yugi", "Yugi the Destiny", "Yugi", Regex("yugi_pc\\.exe", RegexOption.IGNORE_CASE), "InstallDir",
        usesLauncher = false, screenSize = "800x600",
        requiredFiles = listOf("Data.dat", "Voice.dat"),
        mirrorFiles = listOf("yugi_pc.exe", "Data.dat", "Voice.dat", "Region.dat"),
        coverAsset = null),
    KAIBA("kaiba", "Kaiba the Revenge", "Kaiba", Regex("kaiba_pc\\.exe", RegexOption.IGNORE_CASE), "InstallDirK",
        usesLauncher = false, screenSize = "800x600",
        requiredFiles = listOf("Data.dat", "Voice.dat"),
        mirrorFiles = listOf("kaiba_pc.exe", "Data.dat", "Voice.dat", "Region.dat"),
        coverAsset = null);

    /** The game's own exe, not its installer or uninstaller. */
    fun isGameExe(name: String): Boolean =
        exePattern.matches(name) && !name.contains("setup", ignoreCase = true) && !name.contains("unins", ignoreCase = true)

    fun findExe(dir: File): File? = dir.listFiles()?.firstOrNull { it.isFile && isGameExe(it.name) }

    /**
     * What keeps [dir] from being a playable install (empty when it is one): no exe, an exe that is
     * not a Windows program, a required file missing or cut short, or an install that never finished.
     */
    fun problems(dir: File): List<String> {
        val exe = findExe(dir) ?: return listOf("no está el juego")
        val out = mutableListOf<String>()
        if (File(dir, IsoImporter.INSTALLING_MARK).exists()) out += "la instalación no terminó"
        val head = ByteArray(2)
        val read = runCatching { exe.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read != 2 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) out += "${exe.name} no es un programa de Windows"
        for (name in requiredFiles) {
            val f = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
            when {
                f == null -> out += "falta $name"
                f.length() < MIN_DATA_BYTES -> out += "$name está incompleto"
            }
        }
        return out
    }

    companion object {
        /** Data.dat and Voice.dat are 78-600 MB in every edition seen; anything this small is broken. */
        const val MIN_DATA_BYTES = 1L shl 20

        fun byId(id: String?): GameProfile? = values().firstOrNull { it.id == id }
    }

}

/**
 * Where everything lives. The games and saves go to the app's own external folder
 * (Android/data/<package>/files), mapped in Wine as [KioskPaths.DATA_DRIVE]; ISOs are copied and
 * unpacked there too, never in Download. Android deletes this folder when the app is uninstalled.
 */
class KioskPaths(private val context: Context) {
    val root: File = checkNotNull(context.getExternalFilesDir(null)) { "almacenamiento externo no disponible" }

    fun gameDir(game: GameProfile) = File(root, game.dirName)

    /** Internal (ext4, no FUSE) copy of the game's read-only files, mapped as [MIRROR_DRIVE]. */
    fun mirrorDir(game: GameProfile) = File(context.filesDir, "ygo_${game.id}")

    val commonDir get() = File(root, COMMON_DIR_NAME)
    val systemDat get() = File(commonDir, "system.dat")
    fun isoDir(game: GameProfile) = File(root, "ISO/${game.id}")
    val backupDir get() = File(root, "backups")

    companion object {
        const val COMMON_DIR_NAME = "Yu-Gi-Oh! Power of Chaos Common"
        // D: and F: are the container defaults (Download and the storage root): left as they are.
        const val MIRROR_DRIVE = "E"
        const val DATA_DRIVE = "G"
        // system.dat's folder: pinned so it never follows the install dir into the internal mirror.
        const val COMMON_DIR_DOS = "G:\\$COMMON_DIR_NAME"
        const val INSTALL_DIR_DOS = "E:\\"
        fun isoDos(game: GameProfile, relPath: String) = "G:\\ISO\\${game.id}\\" + relPath.replace('/', '\\')
    }
}
