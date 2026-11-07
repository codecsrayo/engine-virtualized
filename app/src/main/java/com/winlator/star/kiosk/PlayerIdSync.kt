package com.winlator.star.kiosk

import com.winlator.star.container.Container
import com.winlator.star.core.WineRegistryEditor
import java.io.File

/**
 * Keeps the game's player ID in this container's registry equal to the one the saves belong to.
 *
 * Joey the Passion stores an 8-byte player ID, encrypted, in
 * HKLM\Software\KONAMI\Yu-Gi-Oh! Power Of Chaos\system\flcrc (Wow6432Node: 32-bit game). When the
 * value is missing it generates a new ID from time(), and saves stamped with another ID
 * (Deck.ydc's 8-byte header, system.dat) are treated as foreign and reset. A fresh Wine prefix
 * has no flcrc, so without this the first launch wipes the deck and progress the user built in
 * Bannerlator. The ID is read from Deck.ydc (never written) and encrypted with the game's own
 * cipher, ported from joey_pc.exe (0x439c90 + checksum 0x439e50; RSA-style b^5 mod 299, 9-bit
 * packing). Validated against the game's code under emulation and against a real PC registry.
 */
object PlayerIdSync {
    private const val KEY = "Software\\Wow6432Node\\KONAMI\\Yu-Gi-Oh! Power Of Chaos\\system"

    /** Player ID = first 8 bytes of Deck.ydc, or null when the file is missing/short. */
    fun readPlayerId(deck: File): ByteArray? {
        if (!deck.isFile) return null
        val id = ByteArray(8)
        deck.inputStream().use { if (it.read(id) != 8) return null }
        return id
    }

    fun encrypt(id: ByteArray): ByteArray {
        val n = id.size
        val out = ByteArray(4 + ((n + 7) / 8) * 9)
        for (j in 0 until n step 8) {
            val o = 4 + (j / 8) * 9
            for (i in 0 until minOf(8, n - j)) {
                val v = modPow(id[j + i].toInt() and 0xff, 5, 299) shl i
                out[o + i] = (out[o + i].toInt() or (v and 0xff)).toByte()
                out[o + i + 1] = (out[o + i + 1].toInt() or ((v shr 8) and 0xff)).toByte()
            }
        }
        // The game passes the plain length as the checksum span.
        val init = intArrayOf(0x7d, 0x13, 0x8a, 0xbb)
        for (k in 0 until 4) {
            var s = init[k]
            var i = 4 + k
            while (i < n) { s += out[i].toInt() and 0xff; i += 4 }
            out[k] = (-s).toByte()
        }
        return out
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun modPow(b: Int, e: Int, m: Int): Int {
        var r = 1
        repeat(e) { r = r * b % m }
        return r
    }

    /**
     * Writes flcrc, CommonDir (and, when the game is launched without YGO_Lanzador.exe, its
     * [installDirKey], e.g. InstallDirJ) into the container's system.reg. The three games share the
     * key, so one player ID serves them all. Run only while the container's wineserver is down.
     */
    fun apply(container: Container, id: ByteArray?, installDirKey: String, installDir: String?, commonDir: String) {
        // Known answer from a real PC install (VirtualStore flcrc of a working save); a wrong value
        // here would make the game reset the saves, so refuse rather than guess.
        check(encrypt(hex("0100d800fd72c56a")).contentEquals(hex("82ed564501002000800b142f2a"))) {
            "flcrc: el cifrado no supera la prueba conocida"
        }
        WineRegistryEditor(File(container.rootDir, ".wine/system.reg")).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            if (id != null) reg.setHexValue(KEY, "flcrc", encrypt(id))
            if (installDir != null) reg.setStringValue(KEY, installDirKey, installDir)
            // The game uses CommonDir as-is when present and only derives it from InstallDirJ when it
            // is missing (joey_pc.exe 0x410bfa). Pin it to the data folder so system.dat never follows
            // the install dir into the internal mirror.
            reg.setStringValue(KEY, "CommonDir", commonDir)
        }
    }
}
