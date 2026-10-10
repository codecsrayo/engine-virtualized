package com.winlator.star.kiosk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.star.MainActivity
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.StringUtils
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.xenvironment.ImageFs
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Kiosk mode ("joey" flavor): one tap on the icon starts Yu-Gi-Oh! Power of Chaos: Joey the Passion.
 *
 * First run prepares, without showing the Bannerlator UI, exactly what the user's "Yugioh HD"
 * container had: the bundled Wine 10.0 x86_64 layer, a container with a 1708x768 desktop and
 * WineD3D, the "Yu-Gi-Oh raton" touch profile and a shortcut to YGO_Lanzador.exe. Every run then
 * checks the game files and launches the shortcut straight into XServerDisplayActivity.
 *
 * The game and its saves stay on shared storage (Download/YGO_Joey_HD and
 * "Download/Yu-Gi-Oh! Power of Chaos Common"), reached through the container's D: drive, so they
 * survive uninstalling this app. Nothing here ever writes to that folder.
 */
class KioskBootstrap(private val activity: MainActivity) {

    data class UiState(
        val message: String = "Preparando…",
        val progress: Int = -1,          // -1 = indeterminate
        val error: Boolean = false,
        val needsAllFiles: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile private var running = false
    @Volatile private var launched = false

    /** Called from onCreate and onResume; idempotent while a step is in flight. */
    fun run() {
        if (running || launched) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            _state.value = UiState(
                message = "Para leer el juego en Download/${KioskConfig.GAME_DIR_NAME} la app necesita " +
                        "\"Acceso a todos los archivos\".",
                needsAllFiles = true,
            )
            return
        }

        val missing = missingGameFiles()
        if (missing.isNotEmpty()) {
            _state.value = UiState(
                message = "Faltan archivos del juego en ${gameDir().absolutePath}:\n" + missing.joinToString("\n"),
                error = true,
            )
            return
        }
        // No Deck.ydc but saves exist: launching would make the game mint a new player ID and reset
        // system.dat. A brand-new player (neither file) may launch; so may the second launch of one,
        // whose first deck is still in the internal mirror (GameMirror.linkDeck moves it out).
        val deckMissing = GameMirror.findEntry(gameDir(), GameMirror.DECK) == null
        val firstDeckInMirror = GameMirror.findEntry(mirrorDir(), GameMirror.DECK)?.let { it.isFile } == true
        if (deckMissing && systemDat().isFile && !firstDeckInMirror) {
            _state.value = UiState(
                message = "Falta Deck.ydc en ${gameDir().absolutePath} pero hay partidas en\n" +
                        "${systemDat().parent}.\nSin el mazo el juego reiniciaría tus partidas, así que no se abre.\n" +
                        "Copia tu Deck.ydc (o el de una copia en Download/YGO_backups) y pulsa Reintentar.",
                error = true,
            )
            return
        }

        running = true
        val imageFs = ImageFs.find(activity)
        if (!imageFs.isValid || imageFs.version < ImageFsInstaller.LATEST_VERSION) {
            _state.value = UiState("Instalando el sistema base (solo la primera vez)…", 0)
            ImageFsInstaller.installFromAssetsWithCallback(activity,
                { pct -> _state.value = _state.value.copy(progress = pct) },
                { running = false; run() })
            return
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                ImageFsInstaller.stageBundledComponents(activity.applicationContext, imageFs)
                // Fresh install: XServerDisplayActivity reads this pref with default=true when it arms
                // the 5 s auto-hide, but only attaches the touch-to-reshow listener when it is
                // explicitly true, so the touch controls vanish for good. Keep them always visible;
                // a later choice from the in-game menu is respected.
                PreferenceManager.getDefaultSharedPreferences(activity).let { p ->
                    if (!p.contains("touchscreen_timeout_enabled"))
                        p.edit().putBoolean("touchscreen_timeout_enabled", false).apply()
                }
                val ini = KioskTuning.load(File(gameDir(), "kiosk.ini"))
                val wineEntry = KioskTuning.opt(ini, "wine", KioskConfig.WINE_ENTRY)
                val direct = KioskTuning.opt(ini, "launch", "launcher") == "direct"
                val contents = ContentsManager(activity)
                contents.syncContents()
                ensureWine(contents, wineEntry, KioskTuning.opt(ini, "wineWcp", ""))
                val manager = ContainerManager(activity)
                val container = ensureContainer(manager, contents, wineEntry)
                // Read-only game files go to internal storage (no FUSE), mounted as E:; saves stay in Download.
                val mirrorDir = mirrorDir()
                GameMirror.sync(gameDir(), mirrorDir) { name ->
                    _state.value = UiState("Copiando $name a la memoria interna (solo cuando cambia)…")
                }
                GameMirror.linkDeck(gameDir(), mirrorDir,
                    File(Environment.getExternalStorageDirectory(), "Download/YGO_backups"))
                ensureMirrorDrive(container, mirrorDir)
                // Every launch: the registry's player ID must match the saves, or the game resets them.
                // A new player has no deck yet (checked above): the game then mints its own ID.
                val deck = GameMirror.findEntry(gameDir(), GameMirror.DECK)
                val playerId = deck?.let {
                    PlayerIdSync.readPlayerId(it)
                        ?: throw IllegalStateException("No se pudo leer Deck.ydc; no se lanza el juego para no tocar las partidas")
                }
                PlayerIdSync.apply(container, playerId, if (direct) KioskConfig.INSTALL_DIR_DOS else null)
                val profileId = ensureControlsProfile()
                val shortcut = ensureShortcut(container, imageFs, profileId, direct)
                KioskTuning.apply(container, shortcut, ini)
                activity.runOnUiThread { launch(container, shortcut) }
            } catch (e: Exception) {
                Log.e(TAG, "kiosk setup failed", e)
                _state.value = UiState("No se pudo preparar el juego:\n${e.message}", error = true)
            } finally {
                running = false
            }
        }
    }

    fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:${activity.packageName}")
        activity.startActivity(intent)
    }

    private fun gameDir() = File(Environment.getExternalStorageDirectory(), "Download/${KioskConfig.GAME_DIR_NAME}")

    private fun mirrorDir() = File(activity.filesDir, "ygo_game")

    private fun systemDat() = File(Environment.getExternalStorageDirectory(), "Download/Yu-Gi-Oh! Power of Chaos Common/system.dat")

    /** Required files, matched case-insensitively (Wine resolves joey_pc.exe to Joey_Pc.exe). */
    private fun missingGameFiles(): List<String> {
        val present = gameDir().list()?.map { it.lowercase() }?.toSet() ?: emptySet()
        return KioskConfig.REQUIRED_FILES.filter { it.lowercase() !in present }
    }

    /** Installs [entry] from the APK asset (default layer) or from [wcpPath] (kiosk.ini experiments). */
    private fun ensureWine(contents: ContentsManager, entry: String, wcpPath: String) {
        if (contents.getProfileByEntryName(entry) != null) return
        _state.value = UiState("Instalando $entry (solo la primera vez)…")
        // Extract into the contents tmp dir (the default layer straight from the APK asset, the path
        // imagefs uses), then let ContentsManager move it into place.
        ContentsManager.cleanTmpDir(activity)
        val tmpDir = ContentsManager.getTmpDir(activity)
        val ok = if (entry == KioskConfig.WINE_ENTRY) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, KioskConfig.WINE_ASSET, tmpDir)
        } else {
            if (wcpPath.isEmpty() || !File(wcpPath).isFile) throw IllegalStateException("$entry: falta wineWcp en kiosk.ini")
            // A path starting with "/" is opened as a plain file by TarCompressorUtils.
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, Uri.parse(wcpPath), tmpDir)
                || TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, Uri.parse(wcpPath), tmpDir)
        }
        if (!ok) throw IllegalStateException("$entry (no se pudo extraer)")
        val profile = contents.readProfile(File(tmpDir, "profile.json"))
            ?: throw IllegalStateException("$entry (profile.json inválido)")
        var error: String? = null
        contents.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                error = reason.toString()
            }
            override fun onSucceed(profile: ContentProfile) {}
        })
        error?.let { throw IllegalStateException("$entry (instalar: $it)") }
        contents.syncContents()
        checkNotNull(contents.getProfileByEntryName(entry)) { "$entry no quedó instalado (¿nombre distinto en profile.json?)" }
    }

    /** One container per Wine layer, so trying another layer never touches the default one. */
    private fun ensureContainer(manager: ContainerManager, contents: ContentsManager, entry: String): Container {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        // The first builds (Wine 10 x86_64) stored their container under the bare key.
        val prefKey = if (entry == KioskConfig.LEGACY_WINE_ENTRY) PREF_CONTAINER_ID else "${PREF_CONTAINER_ID}_$entry"
        manager.getContainerById(prefs.getInt(prefKey, 0))?.let { return it }
        val name = "${KioskConfig.CONTAINER_NAME} $entry"
        val arm64ec = entry.contains("arm64ec", ignoreCase = true)

        _state.value = UiState("Creando el contenedor \"$name\"…")
        // Same path the container editor takes: a template carrying the built-in defaults, with the
        // fields the user changed for "Yugioh HD" on top.
        val template = Container(0, manager)
        template.setName(name)
        template.setScreenSize(KioskConfig.SCREEN_SIZE)
        template.setDXWrapper(KioskConfig.DXWRAPPER)
        template.setBox64Version(if (arm64ec) KioskConfig.WOWBOX64_VERSION else KioskConfig.BOX64_VERSION)
        template.setFullscreenMode(Container.FULLSCREEN_OFF)
        val data = template.data.apply {
            put("wineVersion", entry)
            put("runAsAdmin", true)
        }
        val container = manager.createContainerSync(data, contents)
            ?: throw IllegalStateException("no se pudo crear el contenedor")
        prefs.edit().putInt(prefKey, container.id).apply()
        return container
    }

    /** Maps the internal mirror as E: (kept on every launch; other drives untouched). */
    private fun ensureMirrorDrive(container: Container, mirrorDir: File) {
        val others = StringBuilder()
        for (d in container.drivesIterator()) {
            if (!d[0].equals(KioskConfig.MIRROR_DRIVE, ignoreCase = true)) others.append(d[0]).append(':').append(d[1])
        }
        val drives = others.append(KioskConfig.MIRROR_DRIVE).append(':').append(mirrorDir.absolutePath).toString()
        if (drives != container.drives) {
            container.drives = drives
            container.saveData()
        }
    }

    /** Imports the bundled profile once; afterwards the user's in-game edits to it are kept. */
    private fun ensureControlsProfile(): Int {
        val manager = InputControlsManager(activity)
        val json = JSONObject(activity.assets.open(KioskConfig.CONTROLS_ASSET).bufferedReader().use { it.readText() })
        val name = json.getString("name")
        manager.getProfiles(true).firstOrNull { it.name == name }?.let { return it.id }
        val imported = manager.importProfile(json)
            ?: throw IllegalStateException("no se pudo importar el perfil de controles \"$name\"")
        return imported.id
    }

    /**
     * [direct] runs Joey_Pc.exe -Lspa without YGO_Lanzador.exe (the launcher forces
     * HODLL=wowbox64.dll, which would hide the container's FEXCore/WowBox64 choice); InstallDirJ is
     * then written by [PlayerIdSync.apply].
     */
    private fun ensureShortcut(container: Container, imageFs: ImageFs, profileId: Int, direct: Boolean): File {
        val dir = container.desktopDir.apply { mkdirs() }
        val file = File(dir, if (direct) "${KioskConfig.SHORTCUT_NAME} (directo).desktop" else "${KioskConfig.SHORTCUT_NAME}.desktop")
        val exec = "Exec=env WINEPREFIX=\"" + imageFs.wineprefix + "\" wine " +
            StringUtils.escapeFileDOSPath(if (direct) KioskConfig.GAME_EXE_DOS_PATH else KioskConfig.LAUNCHER_DOS_PATH)
        if (file.isFile) {
            val lines = file.readLines()
            if (lines.none { it == exec }) file.writeText(lines.joinToString("\n") { if (it.startsWith("Exec=")) exec else it } + "\n")
        } else {
            file.writeText(buildString {
                append("[Desktop Entry]\n")
                append("Name=").append(KioskConfig.SHORTCUT_NAME).append('\n')
                append(exec).append('\n')
                append("Type=Application\n")
                append("StartupNotify=True\n")
                append("\n[Extra Data]\n")
                append("controlsProfile=").append(profileId).append('\n')
                if (direct) append("execArgs=-Lspa\n")
            })
        }
        // Launch-screen art: the game's own HD title screen instead of the Bannerlator logo.
        val cover = File(activity.filesDir, "kiosk/cover.jpg")
        if (!cover.isFile) {
            cover.parentFile?.mkdirs()
            activity.assets.open(KioskConfig.COVER_ASSET).use { input -> cover.outputStream().use { input.copyTo(it) } }
        }
        val shortcut = Shortcut(container, file)
        if (shortcut.customCoverArtPath.isNullOrEmpty()) shortcut.customCoverArtPath = cover.absolutePath
        return file
    }

    private fun launch(container: Container, shortcut: File) {
        launched = true
        val intent = Intent(activity, XServerDisplayActivity::class.java)
        intent.putExtra("container_id", container.id)
        intent.putExtra("shortcut_path", shortcut.absolutePath)
        intent.putExtra("shortcut_name", KioskConfig.SHORTCUT_NAME)
        activity.startActivity(intent)
        activity.finish()
    }

    companion object {
        private const val TAG = "Kiosk"
        private const val PREF_CONTAINER_ID = "kiosk_container_id"
    }
}

object KioskConfig {
    const val GAME_DIR_NAME = "YGO_Joey_HD"
    val REQUIRED_FILES = listOf("YGO_Lanzador.exe", "Joey_Pc.exe", "Data.dat", "Voice.dat")  // Deck.ydc: see run()
    // D: is the container's Download folder (Container.DEFAULT_DRIVES).
    // The game runs from the internal mirror (see GameMirror), mapped as E:.
    const val MIRROR_DRIVE = "E"
    const val LAUNCHER_DOS_PATH = "E:\\YGO_Lanzador.exe"
    const val GAME_EXE_DOS_PATH = "E:\\Joey_Pc.exe"
    const val INSTALL_DIR_DOS = "E:\\"
    // system.dat's folder, on shared storage (Download): pinned so it never follows the install dir.
    const val COMMON_DIR_DOS = "D:\\Yu-Gi-Oh! Power of Chaos Common"
    const val SHORTCUT_NAME = "Yu-Gi-Oh! Joey"

    const val CONTAINER_NAME = "Yugioh HD"
    const val SCREEN_SIZE = "1708x768"
    const val DXWRAPPER = "wined3d"
    const val BOX64_VERSION = "0.4.5-gst"   // first entry of box64_version_entries = editor default for x86_64
    const val WOWBOX64_VERSION = "0.3.7"    // wowbox64_version_entries default for arm64ec layers

    // proton-11.0-2-arm64ec.wcp: type Proton, versionName "11.0-2-arm64ec", versionCode 14. The
    // game's 32-bit code runs through WowBox64 (YGO_Lanzador.exe sets HODLL=wowbox64.dll): ~30 FPS
    // at 1708x768 on the Snapdragon 7s Gen 3, where Wine 10 x86_64 (Box64 WoW64) drew ~10-13.
    const val WINE_ASSET = "kiosk/proton-11.0-2-arm64ec.wcp"
    const val WINE_ENTRY = "Proton-11.0-2-arm64ec-14"
    const val LEGACY_WINE_ENTRY = "Wine-10.0-X86_64-1"
    const val CONTROLS_ASSET = "kiosk/yugioh_raton.icpx"
    const val COVER_ASSET = "kiosk/cover.jpg"
}
