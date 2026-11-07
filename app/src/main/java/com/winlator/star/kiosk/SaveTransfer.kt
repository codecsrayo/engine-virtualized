package com.winlator.star.kiosk

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves in and out of the app through the system file picker: one game's Deck.ydc on its own, or
 * every save in a .zip (each game's deck under its folder name plus the shared system.dat, the
 * same layout as the data folder).
 *
 * The rules that keep an import from wiping progress live here so SaveTransferTest covers them:
 * the games share one player ID (a deck's first 8 bytes, written to flcrc before every launch), and
 * a deck carrying another ID makes the game reset the saves. So an imported deck is checked against
 * the .ydc layout and given the user's player ID ([withPlayerId]); only a .zip that brings its own
 * system.dat changes player.
 *
 * Deck.ydc layout, from 14 decks of two players (Joey, PC and phone), no checksum:
 *   8 bytes player ID, then 3 sections of [u16 count][count x u16 card id] (little endian):
 *   main deck (40-42 seen), a 1-card section, side deck (0-15 seen); the file ends right there.
 */
object SaveTransfer {
    const val SYSTEM_DAT = "system.dat"
    /** Decks seen are ~126 bytes; system.dat ~5 KB. Anything far bigger is not a save. */
    const val MAX_DECK_BYTES = 1 shl 20
    const val MAX_SYSTEM_BYTES = 16 shl 20

    data class Saves(val decks: Map<GameProfile, ByteArray>, val systemDat: ByteArray?)

    fun deckEntry(game: GameProfile) = "${game.dirName}/${GameMirror.DECK}"
    fun systemEntry() = "${KioskPaths.COMMON_DIR_NAME}/$SYSTEM_DAT"

    /** The player ID a deck carries, or null when it is too short to be one. */
    fun playerId(deck: ByteArray): ByteArray? = if (deck.size >= 8) deck.copyOf(8) else null

    /** Section counts above this are no deck (a Power of Chaos deck holds at most 60 + 15 cards). */
    private const val MAX_SECTION = 100

    /** Throws unless [bytes] has the Deck.ydc layout (see the class comment). */
    fun checkDeck(bytes: ByteArray, what: String) {
        val bad = "$what no es un mazo de Power of Chaos"
        check(bytes.size in 8 + 3 * 2..MAX_DECK_BYTES) { "$bad (tamaño ${bytes.size} bytes)." }
        var off = 8
        repeat(3) { section ->
            check(off + 2 <= bytes.size) { "$bad (termina antes de tiempo)." }
            val n = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)
            check(n <= MAX_SECTION) { "$bad (sección ${section + 1} con $n cartas)." }
            off += 2 + 2 * n
        }
        check(off == bytes.size) { "$bad (sobran o faltan ${bytes.size - off} bytes)." }
    }

    /** [deck] with its first 8 bytes set to [id]. */
    fun withPlayerId(deck: ByteArray, id: ByteArray): ByteArray = deck.copyOf().also { id.copyInto(it, 0, 0, 8) }

    /** Reads at most [max] bytes of [input]; throws when there is more (not a save, or a broken one). */
    fun readLimited(input: InputStream, max: Int, what: String): ByteArray {
        val bytes = input.readNBytesCompat(max + 1)
        check(bytes.size <= max) { "$what es demasiado grande para ser una partida." }
        return bytes
    }

    /** Writes the given saves as a .zip; [decks] and [systemDat] are the files that exist. */
    fun exportZip(out: OutputStream, decks: Map<GameProfile, File>, systemDat: File?) {
        check(decks.isNotEmpty() || systemDat != null) { "No hay partidas que exportar." }
        ZipOutputStream(out).use { zip ->
            for ((game, deck) in decks) {
                zip.putNextEntry(ZipEntry(deckEntry(game)))
                deck.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            systemDat?.let { f ->
                zip.putNextEntry(ZipEntry(systemEntry()))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a saves .zip. Only `<Joey|Yugi|Kaiba>/Deck.ydc` and `system.dat` (at the root or in any
     * folder) are taken, by name and ignoring case; anything else in it is ignored. Throws on a zip
     * with none of them, a deck that is not one, an empty system.dat, or the same save twice.
     */
    fun readZip(input: InputStream): Saves {
        val decks = mutableMapOf<GameProfile, ByteArray>()
        var systemDat: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                val name = parts.lastOrNull() ?: continue
                val folder = parts.getOrNull(parts.size - 2)
                when {
                    name.equals(GameMirror.DECK, ignoreCase = true) -> {
                        val game = GameProfile.values().firstOrNull { it.dirName.equals(folder, ignoreCase = true) } ?: continue
                        check(game !in decks) { "El .zip trae dos mazos de ${game.title}." }
                        val bytes = readLimited(zip, MAX_DECK_BYTES, "El mazo de ${game.title}")
                        checkDeck(bytes, "El mazo de ${game.title}")
                        decks[game] = bytes
                    }
                    name.equals(SYSTEM_DAT, ignoreCase = true) -> {
                        check(systemDat == null) { "El .zip trae dos system.dat." }
                        val bytes = readLimited(zip, MAX_SYSTEM_BYTES, SYSTEM_DAT)
                        check(bytes.isNotEmpty()) { "El system.dat del .zip está vacío." }
                        systemDat = bytes
                    }
                }
            }
        }
        check(decks.isNotEmpty() || systemDat != null) {
            "El .zip no tiene partidas de Power of Chaos (Joey/Deck.ydc, Yugi/Deck.ydc, Kaiba/Deck.ydc o system.dat)."
        }
        return Saves(decks, systemDat)
    }

    /** The one player ID of [decks] (null when there are none); throws when they disagree. */
    fun sharedId(decks: Map<GameProfile, ByteArray>): ByteArray? {
        val ids = decks.mapValues { checkNotNull(playerId(it.value)) }
        val distinct = ids.values.distinctBy { it.toList() }
        check(distinct.size <= 1) {
            "Los mazos de " + ids.keys.joinToString(", ") { it.title } + " son de jugadores distintos; no se importan."
        }
        return distinct.firstOrNull()
    }

    /**
     * A lone deck ready to go in: given [playerId] (the user's, null when there is none yet) when it
     * carries another. Second: whether its ID was changed.
     */
    fun adoptDeck(deck: ByteArray, playerId: ByteArray?): Pair<ByteArray, Boolean> {
        val own = checkNotNull(playerId(deck))
        if (playerId == null || own.contentEquals(playerId)) return deck to false
        return withPlayerId(deck, playerId) to true
    }

    data class Plan(val decks: Map<GameProfile, ByteArray>, val systemDat: ByteArray?, val playerId: ByteArray?, val adopted: Set<GameProfile>)

    /**
     * What importing [saves] over the user's (player [currentId], null when unknown) writes. With
     * its own system.dat the .zip is a whole other player: its decks must agree on one ID and keep
     * it. Without one, its decks join the user's player and get [currentId] (or, when there is none,
     * must agree among themselves).
     */
    fun planZip(saves: Saves, currentId: ByteArray?): Plan {
        if (saves.systemDat != null || currentId == null)
            return Plan(saves.decks, saves.systemDat, sharedId(saves.decks), emptySet())
        val adopted = saves.decks.filterValues { !checkNotNull(playerId(it)).contentEquals(currentId) }.keys
        return Plan(saves.decks.mapValues { withPlayerId(it.value, currentId) }, null, currentId, adopted)
    }

    /** InputStream.readNBytes is Java 11 / Android 13; this reads up to [n] bytes on any level. */
    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }
}
