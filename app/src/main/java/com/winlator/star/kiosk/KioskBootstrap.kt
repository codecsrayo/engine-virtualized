package com.winlator.star.kiosk

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.star.MainActivity
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.StringUtils
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.xenvironment.ImageFs
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Kiosk mode ("engine" flavor): Yu-Gi-Oh! Power of Chaos (Joey, Yugi, Kaiba) on a virtualized
 * Windows machine (Wine/Proton on Box64), without the Bannerlator UI.
 *
 * The app opens on a game picker. Each game is set up from its CD image only: an ISO picked with
 * the system file picker, unpacked into the app's data folder. Playing prepares, the first time, the bundled Proton
 * layer, one container shared by the three games, the "Yu-Gi-Oh raton" touch profile and a shortcut
 * per game, then goes straight into XServerDisplayActivity.
 *
 * The games and saves live in Android/data/<package>/files (see [KioskPaths]), reached through the
 * container's G: drive; the read-only game files run from an internal mirror on E:.
 */
class KioskBootstrap(activity: MainActivity) {

    enum class Status { READY, MISSING, INSTALLER }

    data class GameState(val game: GameProfile, val status: Status, val detail: String, val hasDeck: Boolean = false)

    data class UiState(
        val message: String = "Preparando…",
        val progress: Int = -1,          // -1 = indeterminate
        val busy: Boolean = true,
        val error: Boolean = false,
        val games: List<GameState> = emptyList(),
        /** Shown above the picker after an export or import went well. */
        val notice: String? = null,
    )

    val state: StateFlow<UiState> = _state

    init {
        host = activity
    }

    /** The current MainActivity: a step started by an earlier one (since recreated) launches from this one. */
    private val activity: MainActivity get() = checkNotNull(host)
    // Lazy, so a missing external storage ends on the error screen instead of crashing onCreate.
    private val paths by lazy { KioskPaths(activity.applicationContext) }
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(activity)

    /** Called from onCreate and onResume: refreshes the picker; idempotent while a step is in flight. */
    fun run() {
        if (running || launched || _state.value.error) return
        work("Buscando juegos…") { refresh() }
    }

    /** "Volver" on an error: back to the picker. */
    fun dismissError() {
        _state.value = _state.value.copy(error = false)
        run()
    }

    fun play(game: GameProfile) = work("Preparando ${game.title}…") {
        val gameDir = paths.gameDir(game)
        val problems = game.problems(gameDir)
        check(problems.isEmpty()) { "${game.title} no se puede abrir: " + problems.joinToString("; ") + ".\nImporta su ISO otra vez." }
        val exe = checkNotNull(game.findExe(gameDir))
        // No deck but saves exist: launching would make the game mint a new player ID and reset
        // system.dat. A brand-new player (neither file) may launch; so may the second launch of one,
        // whose first deck is still in the internal mirror (GameMirror.linkDeck moves it out).
        val mirrorDir = paths.mirrorDir(game)
        val firstDeckInMirror = GameMirror.findEntry(mirrorDir, GameMirror.DECK)?.let { it.isFile } == true
        var playerId = sharedPlayerId()
        if (playerId == null && paths.systemDat.isFile && !firstDeckInMirror) playerId = restoreDeck(game)
        check(playerId != null || !paths.systemDat.isFile || firstDeckInMirror) {
            "No hay ningún Deck.ydc ni copia de él, pero hay partidas en\n${paths.commonDir.path}.\n" +
                "Sin el mazo el juego reiniciaría tus partidas, así que no se abre."
        }
        // A deck but no system.dat: the game would start the progress over. Bring the last copy back.
        if (playerId != null && !paths.systemDat.exists()) restoreSystemDat()
        checkSpace(activity.filesDir, GameMirror.bytesToSync(gameDir, mirrorDir, game.mirrorFiles) + (32L shl 20),
            "copiar ${game.title} a la memoria interna")

        val ini = KioskTuning.load(File(gameDir, "kiosk.ini"))
        val direct = !game.usesLauncher || KioskTuning.opt(ini, "launch", "launcher") == "direct"
        val (imageFs, container) = prepareEngine(ini)
        // Read-only game files go to internal storage (no FUSE), mounted as E:; saves stay in G:.
        GameMirror.sync(gameDir, mirrorDir, game.mirrorFiles) { name ->
            _state.value = UiState("Copiando $name a la memoria interna (solo cuando cambia)…")
        }
        if (!direct) installLauncher(mirrorDir)
        GameMirror.linkDeck(gameDir, mirrorDir, paths.backupDir)
        ensureDrives(container, mirrorDir)
        // Every launch: the registry's player ID must match the saves, or the game resets them.
        PlayerIdSync.apply(container, playerId, game.installDirKey,
            if (direct) KioskPaths.INSTALL_DIR_DOS else null, KioskPaths.COMMON_DIR_DOS)
        val profileId = ensureControlsProfile()
        val shortcut = ensureShortcut(container, imageFs, profileId, game, exe.name, direct, ini)
        KioskTuning.apply(container, shortcut, ini)
        backupSaves(game)
        activity.runOnUiThread { launch(container, shortcut, game.title) }
    }

    /**
     * The decks are gone but the saves are not: puts back [game]'s newest deck from backups/ and
     * returns the player ID, which any game's backed-up deck also gives (they share it). A deck is
     * only restored into its own game: the three games may not read each other's.
     */
    private fun restoreDeck(game: GameProfile): ByteArray? {
        val decks = paths.backupDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
            ?.mapNotNull { dir -> GameMirror.findEntry(dir, GameMirror.DECK)?.takeIf { it.isFile }?.let { dir to it } }
            ?: return null
        decks.firstOrNull { it.first.name.endsWith("_antes_${game.id}") }?.let { (_, deck) ->
            _state.value = UiState("Restaurando tu mazo desde ${deck.parentFile?.name}…")
            GameMirror.copyFile(deck, File(paths.gameDir(game), GameMirror.DECK))
            Log.w(TAG, "restored ${game.id} deck from ${deck.path}")
            return sharedPlayerId()
        }
        return decks.firstNotNullOfOrNull { PlayerIdSync.readPlayerId(it.second) }
    }

    /** Puts back the newest system.dat from backups/ (any game's: they share it). */
    private fun restoreSystemDat() {
        val copy = paths.backupDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { dir -> File(dir, "system.dat").takeIf { it.isFile } } ?: return
        _state.value = UiState("Restaurando tus partidas desde ${copy.parentFile?.name}…")
        GameMirror.copyFile(copy, paths.systemDat)
        Log.w(TAG, "restored system.dat from ${copy.path}")
    }

    /**
     * Copies system.dat and the game's deck to backups/<date>_antes_<game> before every launch,
     * keeping the last [KEEP_BACKUPS]. The three games share system.dat, and flcrc's cipher is only
     * verified against Joey's exe: should another game not accept the player ID and reset the
     * saves, the copy from just before is there.
     */
    private fun backupSaves(game: GameProfile) {
        val saves = listOfNotNull(paths.systemDat.takeIf { it.isFile },
            GameMirror.findEntry(paths.gameDir(game), GameMirror.DECK)?.takeIf { it.isFile })
        if (saves.isEmpty()) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = File(paths.backupDir, "${stamp}_antes_${game.id}")
        for (f in saves) GameMirror.copyFile(f, File(dir, f.name))
        // Only these launch backups rotate; GameMirror's "_deck_antes_espejo" ones are kept.
        val launchBackups = GameProfile.values().map { "_antes_${it.id}" }
        paths.backupDir.listFiles { f -> f.isDirectory && launchBackups.any { f.name.endsWith(it) } }
            ?.sortedByDescending { it.name }?.drop(KEEP_BACKUPS)?.forEach { it.deleteRecursively() }
    }

    /**
     * Copies the ISO picked for [game] into the data folder and unpacks it. A CD with the game's
     * files is installed right away (into the game it turns out to be, see [IsoImporter.identify]);
     * a CD with only an installer runs it in the container (see [collectInstalled] for the way back).
     * Nothing is copied before the file is known to be a disc image and to fit; whatever fails, the
     * unpacked files are removed, and a game already installed is never replaced.
     */
    fun importIso(game: GameProfile, uri: Uri) = work("Comprobando la ISO…") {
        val head = activity.contentResolver.openInputStream(uri)?.use { IsoImporter.readHead(it) }
            ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")
        check(IsoImporter.looksLikeDiscImage(head)) { "El archivo elegido no es una imagen de disco (.iso)." }
        val size = IsoImporter.size(activity, uri)
        cleanStaging(game)
        paths.root.mkdirs()
        if (size > 0) checkSpace(paths.root, IsoImporter.spaceNeeded(size), "importar la ISO")
        var keepStaging = false
        try {
            val isoDir = paths.isoDir(game)
            val iso = isoFile(game)
            IsoImporter.copy(activity, uri, size, iso) { pct -> _state.value = UiState("Copiando la ISO…", pct) }
            _state.value = UiState("Descomprimiendo la ISO…", 0)
            IsoImporter.extract(activity, iso, isoDir) { pct -> _state.value = UiState("Descomprimiendo la ISO…", pct) }
            iso.delete()
            val (target, found) = IsoImporter.identify(isoDir, game)
                ?: throw IllegalStateException("La ISO no tiene ningún Power of Chaos ni un setup.exe. ¿Es el CD correcto?")
            val targetDir = paths.gameDir(target)
            check(target.findExe(targetDir) == null || target.problems(targetDir).isNotEmpty()) {
                "Esta ISO es de ${target.title}, que ya está instalado; no se reemplaza."
            }
            when (found) {
                is IsoImporter.Found.Files -> {
                    _state.value = UiState("Instalando ${target.title}…")
                    IsoImporter.installFrom(found.exe, targetDir)
                }
                is IsoImporter.Found.Cabinet -> {
                    val cabDir = File(isoDir, "_cab")
                    _state.value = UiState("Descomprimiendo ${found.cab.name}…", 0)
                    IsoImporter.extract(activity, found.cab, cabDir) { pct -> _state.value = UiState("Descomprimiendo ${found.cab.name}…", pct) }
                    val exe = target.findExe(cabDir)
                        ?: throw IllegalStateException("${found.cab.name} no trae ${target.title}. ¿Es el CD correcto?")
                    _state.value = UiState("Instalando ${target.title}…")
                    // The CD's own exe wants the disc; the one beside the cabinet (Crack\) does not.
                    IsoImporter.installFrom(exe, targetDir, found.noCdExe)
                }
                is IsoImporter.Found.Installer -> {
                    keepStaging = true
                    return@work runInstaller(game, found.setup)
                }
            }
            val problems = target.problems(targetDir)
            check(problems.isEmpty()) { "La ISO de ${target.title} está incompleta: " + problems.joinToString("; ") + "." }
            prefs.edit().remove(pendingKey(target)).apply()
            if (target != game) Log.i(TAG, "ISO picked for ${game.id} was ${target.id}; installed there")
        } finally {
            if (!keepStaging) cleanStaging(game)
        }
        refresh()
    }

    private fun isoFile(game: GameProfile) = File(paths.isoDir(game).parentFile, "${game.id}.iso")

    /** Removes an import's leftovers for [game]: the copied ISO, its temp file and the unpacked files. */
    private fun cleanStaging(game: GameProfile) {
        paths.isoDir(game).deleteRecursively()
        isoFile(game).delete()
        File(isoFile(game).path + ".tmp").delete()
    }

    /** Throws a readable message when [dir]'s storage has less than [needed] bytes free. */
    private fun checkSpace(dir: File, needed: Long, what: String) {
        val free = dir.usableSpace
        check(free >= needed) {
            "No hay espacio para $what: hacen falta ${needed shr 20} MB y quedan ${free shr 20} MB libres."
        }
    }

    /** A game's deck: in its folder, or a new player's first one still in the internal mirror. */
    private fun currentDeck(game: GameProfile): File? =
        GameMirror.findEntry(paths.gameDir(game), GameMirror.DECK)?.takeIf { it.isFile }
            ?: GameMirror.findEntry(paths.mirrorDir(game), GameMirror.DECK)
                ?.takeIf { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }

    fun exportDeck(game: GameProfile, uri: Uri) = work("Exportando el mazo de ${game.title}…") {
        val deck = currentDeck(game) ?: throw IllegalStateException("${game.title} todavía no tiene mazo: juega una partida primero.")
        writeTo(uri) { out -> deck.inputStream().use { it.copyTo(out) } }
        done("Mazo de ${game.title} exportado.")
    }

    /** Every deck and system.dat in one .zip (the data folder's layout). */
    fun exportAll(uri: Uri) = work("Exportando las partidas…") {
        val decks = GameProfile.values().mapNotNull { g -> currentDeck(g)?.let { g to it } }.toMap()
        val systemDat = paths.systemDat.takeIf { it.isFile }
        writeTo(uri) { SaveTransfer.exportZip(it, decks, systemDat) }
        done("Partidas exportadas: " + (decks.keys.map { "mazo de ${it.title}" } + listOfNotNull(systemDat?.let { "system.dat" }))
            .joinToString(", ") + ".")
    }

    /**
     * One game's Deck.ydc from a file, checked against the .ydc layout and given the user's player
     * ID when it carries another (see [SaveTransfer.adoptDeck]); the current saves are backed up first.
     */
    fun importDeck(game: GameProfile, uri: Uri) = work("Importando el mazo de ${game.title}…") {
        val bytes = readFrom(uri) { SaveTransfer.readLimited(it, SaveTransfer.MAX_DECK_BYTES, "El archivo elegido") }
        SaveTransfer.checkDeck(bytes, "El archivo elegido")
        val (deck, adopted) = SaveTransfer.adoptDeck(bytes, knownPlayerId())
        backupBeforeImport()
        putDeck(game, deck)
        done("Mazo de ${game.title} importado" + (if (adopted) " (era de otro jugador: ahora lleva tu ID)" else "") +
            ". El anterior está en ${paths.backupDir.name}/.")
    }

    /**
     * Saves from a .zip made by [exportAll]: with its system.dat it replaces the player; without it,
     * its decks get the user's player ID (see [SaveTransfer.planZip]). Decks of another player that
     * the .zip leaves in place are set aside (they would block every launch), after everything is
     * backed up.
     */
    fun importAll(uri: Uri) = work("Importando las partidas…") {
        val plan = SaveTransfer.planZip(readFrom(uri) { SaveTransfer.readZip(it) }, knownPlayerId())
        backupBeforeImport()
        for ((game, bytes) in plan.decks) putDeck(game, bytes)
        plan.playerId?.let { id ->
            for (game in GameProfile.values().filter { it !in plan.decks }) {
                val deck = currentDeck(game) ?: continue
                if (PlayerIdSync.readPlayerId(deck)?.contentEquals(id) != true) deck.delete()
            }
        }
        plan.systemDat?.let { writeAtomic(it, paths.systemDat) }
        val adopted = if (plan.adopted.isEmpty()) "" else
            " (" + plan.adopted.joinToString(", ") { it.title } + ": eran de otro jugador, ahora llevan tu ID)"
        done("Partidas importadas$adopted. Las anteriores están en ${paths.backupDir.name}/.")
    }

    /**
     * The user's player ID: the decks' (when they agree), else the newest backed-up deck's;
     * null for a brand-new player.
     */
    private fun knownPlayerId(): ByteArray? =
        runCatching { sharedPlayerId() }.getOrNull()
            ?: GameProfile.values().firstNotNullOfOrNull { g -> currentDeck(g)?.let { PlayerIdSync.readPlayerId(it) } }
            ?: paths.backupDir.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.name.equals(GameMirror.DECK, ignoreCase = true) }
                .sortedByDescending { it.path }
                .firstNotNullOfOrNull { PlayerIdSync.readPlayerId(it) }

    /** Writes [bytes] as [game]'s deck; a new player's first deck left in the mirror would otherwise win. */
    private fun putDeck(game: GameProfile, bytes: ByteArray) {
        writeAtomic(bytes, File(paths.gameDir(game), GameMirror.DECK))
        GameMirror.findEntry(paths.mirrorDir(game), GameMirror.DECK)
            ?.takeIf { !java.nio.file.Files.isSymbolicLink(it.toPath()) }?.delete()
    }

    private fun writeAtomic(bytes: ByteArray, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(to)) { "no se pudo escribir ${to.name}" }
    }

    /** Every current save, before an import replaces any: backups/<date>_antes_importar/. */
    private fun backupBeforeImport() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = File(paths.backupDir, "${stamp}_antes_importar")
        for (game in GameProfile.values()) currentDeck(game)?.let { GameMirror.copyFile(it, File(dir, SaveTransfer.deckEntry(game))) }
        paths.systemDat.takeIf { it.isFile }?.let { GameMirror.copyFile(it, File(dir, SaveTransfer.SYSTEM_DAT)) }
    }

    private fun <T> readFrom(uri: Uri, read: (java.io.InputStream) -> T): T =
        (activity.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")).use(read)

    private fun writeTo(uri: Uri, write: (java.io.OutputStream) -> Unit) =
        // "wt": truncate, in case the user picked an existing, longer file.
        (activity.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("No se pudo crear el archivo.")).use(write)

    private fun done(notice: String) {
        refresh()
        _state.value = _state.value.copy(notice = notice)
    }

    /** Runs the installer left in the ISO folder again (the previous run did not finish). */
    fun retryInstaller(game: GameProfile) = work("Abriendo el instalador de ${game.title}…") {
        val setup = IsoImporter.findSetup(paths.isoDir(game))?.setup
            ?: throw IllegalStateException("Ya no está el instalador; importa la ISO otra vez.")
        runInstaller(game, setup)
    }

    fun discardInstaller(game: GameProfile) = work("Borrando la ISO descomprimida…") {
        cleanStaging(game)
        prefs.edit().remove(pendingKey(game)).apply()
        refresh()
    }

    /** Runs [block] on the worker thread with a busy screen; any exception ends on the error screen. */
    private fun work(message: String, block: () -> Unit) {
        synchronized(KioskBootstrap) {
            if (running || launched) return
            running = true
        }
        _state.value = UiState(message)
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "kiosk step failed", e)
                _state.value = UiState(friendlyMessage(e), busy = false, error = true)
            } finally {
                running = false
            }
        }
    }

    /** The exception's message, or a plain one for a full disk (Java's says "ENOSPC (No space left on device)"). */
    private fun friendlyMessage(e: Exception): String {
        val chain = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }
        if (chain.any { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) })
            return "No queda espacio en el teléfono. Libera espacio y vuelve a intentarlo."
        return e.message ?: e.toString()
    }

    /** Collects finished installs, then shows the picker. Worker thread. */
    private fun refresh() {
        val games = GameProfile.values().map { game ->
            val pending = prefs.getBoolean(pendingKey(game), false)
            // Leftovers of an import the app did not live to finish; an installer's files stay.
            if (pending) collectInstalled(game) else cleanStaging(game)
            val dir = paths.gameDir(game)
            val problems = game.problems(dir)
            when {
                problems.isEmpty() -> {
                    if (pending) { cleanStaging(game); prefs.edit().remove(pendingKey(game)).apply() }
                    GameState(game, Status.READY, "Listo")
                }
                game.findExe(dir) != null -> GameState(game, Status.MISSING,
                    problems.joinToString("; ").replaceFirstChar { it.uppercase() } + ". Importa la ISO otra vez")
                pending -> GameState(game, Status.INSTALLER, "El instalador no terminó: ábrelo otra vez o descarta la ISO")
                else -> GameState(game, Status.MISSING, "Importa la ISO del juego")
            }.copy(hasDeck = currentDeck(game) != null)
        }
        _state.value = UiState(message = "", busy = false, games = games)
    }

    /**
     * The player ID the saves belong to, from the games' Deck.ydc (first 8 bytes). The three games
     * share flcrc and system.dat, so every deck must carry the same ID; null when there is no deck.
     */
    private fun sharedPlayerId(): ByteArray? {
        val ids = GameProfile.values().mapNotNull { game ->
            val deck = GameMirror.findEntry(paths.gameDir(game), GameMirror.DECK) ?: return@mapNotNull null
            val id = PlayerIdSync.readPlayerId(deck)
                ?: throw IllegalStateException("No se pudo leer ${deck.path}; no se lanza el juego para no tocar las partidas")
            game to id
        }
        val distinct = ids.distinctBy { it.second.toList() }
        check(distinct.size <= 1) {
            "Los mazos de " + distinct.joinToString(" y ") { it.first.title } +
                " son de jugadores distintos; comparten partidas, así que no se lanza ninguno."
        }
        return ids.firstOrNull()?.second
    }

    /** Base system, Wine layer and the shared container. Worker thread. */
    private fun prepareEngine(ini: Properties): Pair<ImageFs, Container> {
        val imageFs = ImageFs.find(activity)
        if (!imageFs.isValid || imageFs.version < ImageFsInstaller.LATEST_VERSION) {
            _state.value = UiState("Instalando el sistema base (solo la primera vez)…", 0)
            val done = CountDownLatch(1)
            activity.runOnUiThread {
                ImageFsInstaller.installFromAssetsWithCallback(activity,
                    { pct -> _state.value = _state.value.copy(progress = pct) },
                    { done.countDown() })
            }
            done.await()
            check(imageFs.isValid) { "no se pudo instalar el sistema base" }
        }
        ImageFsInstaller.stageBundledComponents(activity.applicationContext, imageFs)
        // Fresh install: XServerDisplayActivity reads this pref with default=true when it arms the
        // 5 s auto-hide, but only attaches the touch-to-reshow listener when it is explicitly true,
        // so the touch controls vanish for good. Keep them always visible; a later choice from the
        // in-game menu is respected.
        if (!prefs.contains("touchscreen_timeout_enabled"))
            prefs.edit().putBoolean("touchscreen_timeout_enabled", false).apply()
        val wineEntry = KioskTuning.opt(ini, "wine", KioskConfig.WINE_ENTRY)
        val contents = ContentsManager(activity)
        contents.syncContents()
        ensureWine(contents, wineEntry, KioskTuning.opt(ini, "wineWcp", ""))
        return imageFs to ensureContainer(ContainerManager(activity), contents, wineEntry)
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

    /**
     * One container per Wine layer, so trying another layer never touches the default one. The
     * three games share it: each shortcut carries its own desktop size.
     */
    private fun ensureContainer(manager: ContainerManager, contents: ContentsManager, entry: String): Container {
        val prefKey = "${PREF_CONTAINER_ID}_$entry"
        val container = manager.getContainerById(prefs.getInt(prefKey, 0)) ?: run {
            val name = "${KioskConfig.CONTAINER_NAME} $entry"
            val arm64ec = entry.contains("arm64ec", ignoreCase = true)
            _state.value = UiState("Creando el contenedor \"$name\"…")
            // Same path the container editor takes: a template carrying the built-in defaults, with
            // the fields the user changed for "Yugioh HD" on top.
            val template = Container(0, manager)
            template.setName(name)
            template.setScreenSize(GameProfile.JOEY.screenSize)
            template.setDXWrapper(KioskConfig.DXWRAPPER)
            template.setBox64Version(if (arm64ec) KioskConfig.WOWBOX64_VERSION else KioskConfig.BOX64_VERSION)
            template.setFullscreenMode(Container.FULLSCREEN_OFF)
            val data = template.data.apply {
                put("wineVersion", entry)
                put("runAsAdmin", true)
            }
            manager.createContainerSync(data, contents)?.also { prefs.edit().putInt(prefKey, it.id).apply() }
                ?: throw IllegalStateException("no se pudo crear el contenedor")
        }
        // The games draw with GDI. YGO_Lanzador.exe forces renderer=gdi for Joey; the games started
        // directly get it from WINE_D3D_CONFIG, which Bannerlator builds from this config.
        val config = KeyValueSet(container.getDXWrapperConfig().ifEmpty { Container.DEFAULT_DXWRAPPERCONFIG })
        if (config.get("renderer") != "gdi") {
            config.put("renderer", "gdi")
            container.setDXWrapperConfig(config.toString())
            container.saveData()
        }
        return container
    }

    /** Maps the game's internal mirror as E: and the data folder as G: (other drives untouched). */
    private fun ensureDrives(container: Container, mirrorDir: File) {
        val mine = mapOf(KioskPaths.MIRROR_DRIVE to mirrorDir.absolutePath, KioskPaths.DATA_DRIVE to paths.root.absolutePath)
        val drives = StringBuilder()
        for (d in container.drivesIterator()) {
            if (mine.keys.none { it.equals(d[0], ignoreCase = true) }) drives.append(d[0]).append(':').append(d[1])
        }
        for ((letter, path) in mine) drives.append(letter).append(':').append(path)
        if (drives.toString() != container.drives) {
            container.drives = drives.toString()
            container.saveData()
        }
    }

    /** Puts the launcher bundled in the APK next to Joey's exe in the mirror (when it changed). */
    private fun installLauncher(mirrorDir: File) {
        val target = File(mirrorDir, KioskConfig.LAUNCHER_NAME)
        // ~85 KB, and a compressed asset has no openFd() length: compare the bytes.
        val bundled = activity.assets.open(KioskConfig.LAUNCHER_ASSET).use { it.readBytes() }
        if (target.isFile && target.readBytes().contentEquals(bundled)) return
        val tmp = File(mirrorDir, KioskConfig.LAUNCHER_NAME + ".tmp")
        tmp.writeBytes(bundled)
        check(tmp.renameTo(target)) { "no se pudo colocar ${KioskConfig.LAUNCHER_NAME}" }
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
     * [direct] runs the game's exe (with -Lspa or kiosk.ini's args) without YGO_Lanzador.exe; its
     * InstallDir? key is then written by [PlayerIdSync.apply]. The launcher forces
     * HODLL=wowbox64.dll; a direct shortcut asks for Box64 (= WowBox64 on arm64ec) itself unless
     * kiosk.ini picks an emulator for the container.
     */
    private fun ensureShortcut(container: Container, imageFs: ImageFs, profileId: Int, game: GameProfile,
                               exeName: String, direct: Boolean, ini: Properties): File {
        val title = "Yu-Gi-Oh! ${game.title}"
        val exec = "Exec=env WINEPREFIX=\"" + imageFs.wineprefix + "\" wine " +
            StringUtils.escapeFileDOSPath(KioskPaths.INSTALL_DIR_DOS + if (direct) exeName else KioskConfig.LAUNCHER_NAME)
        val file = writeShortcut(container, if (direct) "$title (directo)" else title, title, exec, profileId)
        val shortcut = Shortcut(container, file)
        shortcut.putExtra("screenSize", KioskTuning.opt(ini, "screenSize", game.screenSize))
        shortcut.putExtra("execArgs", if (direct) KioskTuning.opt(ini, "args", "-Lspa") else null)
        shortcut.putExtra("emulator", if (direct && KioskTuning.opt(ini, "emulator", "").isEmpty()) "Box64" else null)
        shortcut.saveData()
        // Launch-screen art: the game's own HD title screen instead of the Bannerlator logo.
        game.coverAsset?.let { asset ->
            val cover = File(activity.filesDir, "kiosk/${game.id}_cover.jpg")
            if (!cover.isFile) {
                cover.parentFile?.mkdirs()
                activity.assets.open(asset).use { input -> cover.outputStream().use { input.copyTo(it) } }
            }
            if (shortcut.customCoverArtPath.isNullOrEmpty()) shortcut.customCoverArtPath = cover.absolutePath
        }
        return file
    }

    /** A .desktop file in the container; an existing one only gets its Exec line updated. */
    private fun writeShortcut(container: Container, fileName: String, name: String, exec: String, profileId: Int): File {
        val dir = container.desktopDir.apply { mkdirs() }
        val file = File(dir, "$fileName.desktop")
        if (file.isFile) {
            val lines = file.readLines()
            if (lines.none { it == exec }) file.writeText(lines.joinToString("\n") { if (it.startsWith("Exec=")) exec else it } + "\n")
        } else {
            file.writeText(buildString {
                append("[Desktop Entry]\n")
                append("Name=").append(name).append('\n')
                append(exec).append('\n')
                append("Type=Application\n")
                append("StartupNotify=True\n")
                append("\n[Extra Data]\n")
                append("controlsProfile=").append(profileId).append('\n')
            })
        }
        return file
    }

    /**
     * Runs the CD's setup.exe in the container (from G:\ISO\<game>). The app closes with the
     * installer like it does with a game; the next start finds the installed exe in C: and copies
     * it into the game's folder ([collectInstalled]).
     */
    private fun runInstaller(game: GameProfile, setup: File) {
        val (imageFs, container) = prepareEngine(Properties())
        ensureDrives(container, paths.mirrorDir(game))
        val title = "Instalar ${game.title}"
        val rel = setup.relativeTo(paths.isoDir(game)).path
        val exec = "Exec=env WINEPREFIX=\"" + imageFs.wineprefix + "\" wine " +
            StringUtils.escapeFileDOSPath(KioskPaths.isoDos(game, rel))
        val file = writeShortcut(container, title, title, exec, ensureControlsProfile())
        Shortcut(container, file).apply { putExtra("screenSize", "1024x768"); saveData() }
        prefs.edit().putBoolean(pendingKey(game), true).apply()
        activity.runOnUiThread { launch(container, file, title) }
    }

    /** After the installer ran: copies what it installed into the game's folder. Worker thread. */
    private fun collectInstalled(game: GameProfile) {
        val manager = ContainerManager(activity)
        val entry = KioskConfig.WINE_ENTRY
        val container = manager.getContainerById(prefs.getInt("${PREF_CONTAINER_ID}_$entry", 0)) ?: return
        val exe = IsoImporter.collectInstalled(File(container.rootDir, ".wine/drive_c"), game) ?: return
        _state.value = UiState("Copiando ${game.title} instalado…")
        IsoImporter.installFrom(exe, paths.gameDir(game))
        // Done only when complete: otherwise the installer stays available to run again.
        if (game.problems(paths.gameDir(game)).isEmpty()) {
            cleanStaging(game)
            prefs.edit().remove(pendingKey(game)).apply()
        }
    }

    private fun pendingKey(game: GameProfile) = "kiosk_pending_install_${game.id}"

    private fun launch(container: Container, shortcut: File, name: String) {
        launched = true
        val intent = Intent(activity, XServerDisplayActivity::class.java)
        intent.putExtra("container_id", container.id)
        intent.putExtra("shortcut_path", shortcut.absolutePath)
        intent.putExtra("shortcut_name", name)
        activity.startActivity(intent)
        activity.finish()
    }

    /**
     * Process-wide: a MainActivity recreated while a step runs gets a new KioskBootstrap that must
     * see that step (and not start a second one over the same files).
     */
    companion object {
        private val _state = MutableStateFlow(UiState())
        private val worker = Executors.newSingleThreadExecutor()
        @Volatile private var running = false
        @Volatile private var launched = false
        @Volatile private var host: MainActivity? = null

        private const val TAG = "Kiosk"
        private const val PREF_CONTAINER_ID = "kiosk_container_id"
        private const val KEEP_BACKUPS = 20
    }
}

object KioskConfig {
    const val LAUNCHER_NAME = "YGO_Lanzador.exe"
    // Built from tools/ygo/YGO_Lanzador.c; hard-wired to joey_pc.exe and InstallDirJ.
    const val LAUNCHER_ASSET = "kiosk/YGO_Lanzador.exe"

    const val CONTAINER_NAME = "Power of Chaos"
    const val DXWRAPPER = "wined3d"
    const val BOX64_VERSION = "0.4.5-gst"   // first entry of box64_version_entries = editor default for x86_64
    const val WOWBOX64_VERSION = "0.3.7"    // wowbox64_version_entries default for arm64ec layers

    // proton-11.0-2-arm64ec.wcp: type Proton, versionName "11.0-2-arm64ec", versionCode 14. The
    // game's 32-bit code runs through WowBox64 (YGO_Lanzador.exe sets HODLL=wowbox64.dll): ~30 FPS
    // at 1708x768 on the Snapdragon 7s Gen 3, where Wine 10 x86_64 (Box64 WoW64) drew ~10-13.
    const val WINE_ASSET = "kiosk/proton-11.0-2-arm64ec.wcp"
    const val WINE_ENTRY = "Proton-11.0-2-arm64ec-14"
    const val CONTROLS_ASSET = "kiosk/yugioh_raton.icpx"
}
