package com.winlator.star.kiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deck checks and the player-ID rules of deck / saves import, on decks with the real .ydc layout. */
class SaveTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val me = hex("01c70d7b1ab1c56a")
    private val other = hex("0100d800fd72c56a")

    /** A deck as the game writes it: ID, then main / 1-card / side sections of u16 card ids. */
    private fun deck(id: ByteArray, main: Int = 40, one: Int = 1, side: Int = 15): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id)
        for (n in intArrayOf(main, one, side)) {
            out.write(n and 0xff); out.write(n shr 8)
            repeat(n) { out.write(0x13); out.write(0x02) }
        }
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z -> for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return bytes.toByteArray()
    }

    private fun assertFails(block: () -> Unit): String {
        try { block() } catch (e: IllegalStateException) { return e.message ?: "" }
        fail("expected a refusal")
        return ""
    }

    // --- deck layout ---

    @Test fun realDeckSizesPass() {
        assertEquals(126, deck(me).size)                          // 40 + 1 + 15, the phone's
        SaveTransfer.checkDeck(deck(me), "x")
        SaveTransfer.checkDeck(deck(other, 42, 1, 0), "x")        // 100 bytes, the PC's
        SaveTransfer.checkDeck(deck(me, 42, 1, 14), "x")          // 128 bytes
    }

    @Test fun anythingElseIsNotADeck() {
        assertFails { SaveTransfer.checkDeck(ByteArray(5), "x") }
        assertFails { SaveTransfer.checkDeck(deck(me) + byteArrayOf(0), "x") }          // trailing byte
        assertFails { SaveTransfer.checkDeck(deck(me).copyOf(120), "x") }               // cut short
        assertFails { SaveTransfer.checkDeck(me + byteArrayOf(0xff.toByte(), 0x7f), "x") } // absurd count
        assertFails { SaveTransfer.checkDeck("PK\u0003\u0004 not a deck at all".toByteArray(), "x") }
    }

    // --- one deck ---

    @Test fun aForeignDeckGetsTheUsersId() {
        val (d, adopted) = SaveTransfer.adoptDeck(deck(other), me)
        assertTrue(adopted)
        assertArrayEquals(me, d.copyOf(8))
        assertArrayEquals("cards untouched", deck(other).copyOfRange(8, 126), d.copyOfRange(8, 126))
    }

    @Test fun theUsersOwnDeckAndANewPlayersAreLeftAsTheyAre() {
        assertFalse(SaveTransfer.adoptDeck(deck(me), me).second)
        val (d, adopted) = SaveTransfer.adoptDeck(deck(other), null)
        assertFalse(adopted)
        assertArrayEquals(other, d.copyOf(8))
    }

    // --- .zip ---

    @Test fun exportThenImportGivesTheSameSaves() {
        val joey = File(tmp.root, "Deck.ydc").apply { writeBytes(deck(me)) }
        val sys = File(tmp.root, "system.dat").apply { writeBytes(ByteArray(5062) { 7 }) }
        val out = ByteArrayOutputStream()
        SaveTransfer.exportZip(out, mapOf(GameProfile.JOEY to joey), sys)
        val saves = SaveTransfer.readZip(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(deck(me), saves.decks[GameProfile.JOEY])
        assertArrayEquals(sys.readBytes(), saves.systemDat)
    }

    @Test fun nothingToExportIsSaid() {
        assertFails { SaveTransfer.exportZip(ByteArrayOutputStream(), emptyMap(), null) }
    }

    @Test fun zipNamesAreMatchedLooselyAndJunkIsIgnored() {
        val saves = SaveTransfer.readZip(ByteArrayInputStream(zip(
            "backup/YUGI/deck.ydc" to deck(me),
            "Yu-Gi-Oh! Power of Chaos Common\\SYSTEM.DAT" to ByteArray(10) { 1 },
            "readme.txt" to "hi".toByteArray(),
            "Otro/Deck.ydc" to deck(other))))
        assertEquals(setOf(GameProfile.YUGI), saves.decks.keys)
        assertEquals(10, saves.systemDat!!.size)
    }

    @Test fun brokenZipsAreRefused() {
        assertFails { SaveTransfer.readZip(ByteArrayInputStream(zip("readme.txt" to "hi".toByteArray()))) }
        assertFails { SaveTransfer.readZip(ByteArrayInputStream(zip("Joey/Deck.ydc" to ByteArray(30)))) }
        assertFails { SaveTransfer.readZip(ByteArrayInputStream(zip("system.dat" to ByteArray(0)))) }
        assertFails { SaveTransfer.readZip(ByteArrayInputStream(zip("a/system.dat" to ByteArray(4), "b/system.dat" to ByteArray(4)))) }
        assertFails { SaveTransfer.readZip(ByteArrayInputStream(zip("Joey/Deck.ydc" to ByteArray(SaveTransfer.MAX_DECK_BYTES + 1)))) }
    }

    @Test fun aZipWithoutSystemDatJoinsTheUsersPlayer() {
        val saves = SaveTransfer.Saves(mapOf(GameProfile.JOEY to deck(other), GameProfile.KAIBA to deck(me)), null)
        val plan = SaveTransfer.planZip(saves, me)
        assertEquals(setOf(GameProfile.JOEY), plan.adopted)
        assertArrayEquals(me, plan.playerId)
        plan.decks.values.forEach { assertArrayEquals(me, it.copyOf(8)) }
        assertNull(plan.systemDat)
    }

    @Test fun aZipWithItsSystemDatIsAnotherPlayerAndKeepsItsIds() {
        val saves = SaveTransfer.Saves(mapOf(GameProfile.JOEY to deck(other)), ByteArray(5062))
        val plan = SaveTransfer.planZip(saves, me)
        assertTrue(plan.adopted.isEmpty())
        assertArrayEquals(other, plan.playerId)
        assertArrayEquals(other, plan.decks[GameProfile.JOEY]!!.copyOf(8))
    }

    @Test fun anotherPlayersZipMustAgreeOnOneId() {
        val mixed = mapOf(GameProfile.JOEY to deck(other), GameProfile.YUGI to deck(me))
        assertFails { SaveTransfer.planZip(SaveTransfer.Saves(mixed, ByteArray(10)), me) }
        assertFails { SaveTransfer.planZip(SaveTransfer.Saves(mixed, null), null) }
    }

    @Test fun aNewPlayerTakesTheZipAsItIs() {
        val plan = SaveTransfer.planZip(SaveTransfer.Saves(mapOf(GameProfile.JOEY to deck(other)), null), null)
        assertArrayEquals(other, plan.playerId)
        assertTrue(plan.adopted.isEmpty())
    }
}
