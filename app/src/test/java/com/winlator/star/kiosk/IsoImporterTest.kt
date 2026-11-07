package com.winlator.star.kiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The kiosk's ISO handling on plain folders laid out like the unpacked CDs it was written for
 * (the Spanish Yugi and Kaiba CDs, the "listo" repacks and the Joey HD image), plus the edge cases.
 */
class IsoImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exe(f: File): File = f.apply { parentFile?.mkdirs(); writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)) }
    private fun data(f: File, size: Long = GameProfile.MIN_DATA_BYTES + 1): File =
        f.apply { parentFile?.mkdirs(); RandomAccessFile(this, "rw").use { it.setLength(size) } }
    private fun text(f: File, s: String = "x"): File = f.apply { parentFile?.mkdirs(); writeText(s) }

    /** Data1.cab + Crack\<exe> + the installer, as on the retail CDs. */
    private fun retailCd(root: File, exeName: String, msiName: String): File {
        text(File(root, "Data1.cab"))
        exe(File(root, "Crack/$exeName"))
        text(File(root, msiName))
        text(File(root, "setup.exe"))
        text(File(root, "Setup.ini"))
        return root
    }

    private fun looseGame(dir: File, exeName: String): File {
        exe(File(dir, exeName))
        data(File(dir, "Data.dat"))
        data(File(dir, "Voice.dat"))
        text(File(dir, "Region.dat"))
        return dir
    }

    // --- identify / inspect ---

    @Test fun joeyHdImageIsLooseFiles() {
        val root = looseGame(tmp.newFolder("joeyhd"), "Joey_Pc.exe")
        val (game, found) = IsoImporter.identify(root, GameProfile.JOEY)!!
        assertEquals(GameProfile.JOEY, game)
        assertEquals(File(root, "Joey_Pc.exe"), (found as IsoImporter.Found.Files).exe)
    }

    @Test fun yugiRetailCdIsItsCabinetWithTheNoCdExe() {
        val root = retailCd(tmp.newFolder("yugi"), "yugi_pc.exe", "Yu-Gi-Oh! Power of Chaos YUGI THE DESTINY 2.0.msi")
        val (game, found) = IsoImporter.identify(root, GameProfile.YUGI)!!
        assertEquals(GameProfile.YUGI, game)
        found as IsoImporter.Found.Cabinet
        assertEquals(File(root, "Data1.cab"), found.cab)
        assertEquals(File(root, "Crack/yugi_pc.exe"), found.noCdExe)
    }

    @Test fun kaibaRetailCdPickedFromYugisCardLandsInKaiba() {
        val root = retailCd(tmp.newFolder("kaiba"), "kaiba_pc.exe", "Yu-Gi-Oh! Power of Chaos KAIBA THE REVENGE.msi")
        text(File(root, "Yu-Gi-Oh! Power of Chaos Common/system.dat"))
        val (game, found) = IsoImporter.identify(root, GameProfile.YUGI)!!
        assertEquals(GameProfile.KAIBA, game)
        assertTrue(found is IsoImporter.Found.Cabinet)
    }

    @Test fun readyRepackPickedFromJoeysCardLandsInYugi() {
        val root = looseGame(tmp.newFolder("yugilisto"), "yugi_pc.exe")
        val (game, found) = IsoImporter.identify(root, GameProfile.JOEY)!!
        assertEquals(GameProfile.YUGI, game)
        assertTrue(found is IsoImporter.Found.Files)
    }

    @Test fun cabinetIsTakenByTheMsiNameWithoutACrackFolder() {
        val root = tmp.newFolder("kaibanocrack")
        text(File(root, "Data1.cab"))
        text(File(root, "Yu-Gi-Oh! Power of Chaos KAIBA THE REVENGE.msi"))
        val (game, found) = IsoImporter.identify(root, GameProfile.JOEY)!!
        assertEquals(GameProfile.KAIBA, game)
        assertNull((found as IsoImporter.Found.Cabinet).noCdExe)
    }

    @Test fun aCabinetWithNoHintOfItsGameIsLeftToTheInstaller() {
        val root = tmp.newFolder("anon")
        text(File(root, "Data1.cab"))
        text(File(root, "SETUP.EXE"))
        val (game, found) = IsoImporter.identify(root, GameProfile.KAIBA)!!
        assertEquals(GameProfile.KAIBA, game)
        assertEquals(File(root, "SETUP.EXE"), (found as IsoImporter.Found.Installer).setup)
    }

    @Test fun anInstallerOnlyCdCountsAsThePickedGame() {
        val root = tmp.newFolder("setuponly")
        text(File(root, "disk1/setup.exe"))
        val (game, found) = IsoImporter.identify(root, GameProfile.YUGI)!!
        assertEquals(GameProfile.YUGI, game)
        assertTrue(found is IsoImporter.Found.Installer)
    }

    @Test fun anUnrelatedDiscIsRejected() {
        val root = tmp.newFolder("other")
        text(File(root, "readme.txt"))
        exe(File(root, "game.exe"))
        data(File(root, "Data.dat"))
        assertNull(IsoImporter.identify(root, GameProfile.JOEY))
    }

    @Test fun aGameExeWithoutItsDataIsNotAGame() {
        val root = tmp.newFolder("crackonly")
        exe(File(root, "Crack/yugi_pc.exe"))
        assertNull(IsoImporter.identify(root, GameProfile.YUGI))
    }

    @Test fun upperCaseIso9660NamesAndSubfoldersAreFound() {
        val root = tmp.newFolder("upper")
        exe(File(root, "JOEY_HD/JOEY_PC.EXE"))
        data(File(root, "JOEY_HD/DATA.DAT"))
        val found = IsoImporter.identify(root, GameProfile.JOEY)!!.second as IsoImporter.Found.Files
        assertEquals("JOEY_PC.EXE", found.exe.name)
    }

    @Test fun gameExeMatchingIgnoresInstallersAndOtherGames() {
        assertTrue(GameProfile.YUGI.isGameExe("YUGI_PC.EXE"))
        assertFalse(GameProfile.YUGI.isGameExe("kaiba_pc.exe"))
        assertFalse(GameProfile.YUGI.isGameExe("yugi_pc.exe.bak"))
        assertFalse(GameProfile.JOEY.isGameExe("setup.exe"))
    }

    // --- installFrom ---

    @Test fun installMovesTheGameAndNeverTakesSavesFromTheCd() {
        val src = looseGame(tmp.newFolder("src"), "yugi_pc.exe")
        text(File(src, "Deck.ydc"), "someone else's deck")
        text(File(src, "system.dat"), "someone else's saves")
        text(File(src, "kiosk.ini"), "theirs")
        val gameDir = tmp.newFolder("Yugi")
        text(File(gameDir, "kiosk.ini"), "mine")

        IsoImporter.installFrom(File(src, "yugi_pc.exe"), gameDir)

        assertTrue(File(gameDir, "yugi_pc.exe").isFile)
        assertTrue(File(gameDir, "Data.dat").isFile)
        assertFalse(File(gameDir, "Deck.ydc").exists())
        assertFalse(File(gameDir, "system.dat").exists())
        assertEquals("mine", File(gameDir, "kiosk.ini").readText())
        assertFalse(File(gameDir, IsoImporter.INSTALLING_MARK).exists())
        assertFalse("moved, not copied", File(src, "Data.dat").exists())
        assertEquals(emptyList<String>(), GameProfile.YUGI.problems(gameDir))
    }

    @Test fun installKeepsTheUsersOwnDeck() {
        val src = looseGame(tmp.newFolder("src2"), "Joey_Pc.exe")
        val gameDir = tmp.newFolder("Joey")
        text(File(gameDir, "Deck.ydc"), "my deck")
        IsoImporter.installFrom(File(src, "Joey_Pc.exe"), gameDir)
        assertEquals("my deck", File(gameDir, "Deck.ydc").readText())
    }

    @Test fun theNoCdExeReplacesTheCabinetOne() {
        val cab = looseGame(tmp.newFolder("cab"), "kaiba_pc.exe")
        val crack = File(tmp.newFolder("Crack"), "kaiba_pc.exe").apply { writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 9, 9)) }
        val gameDir = tmp.newFolder("Kaiba")
        IsoImporter.installFrom(File(cab, "kaiba_pc.exe"), gameDir, crack)
        assertArrayEquals(crack.readBytes(), File(gameDir, "kaiba_pc.exe").readBytes())
    }

    @Test fun aFileUnderAnotherCaseIsReplacedNotDuplicated() {
        val src = looseGame(tmp.newFolder("src3"), "kaiba_pc.exe")
        val gameDir = tmp.newFolder("Kaiba2")
        text(File(gameDir, "data.dat"), "old")
        IsoImporter.installFrom(File(src, "kaiba_pc.exe"), gameDir)
        val datas = gameDir.listFiles()!!.filter { it.name.equals("data.dat", ignoreCase = true) }
        assertEquals(1, datas.size)
        assertTrue(datas[0].length() > GameProfile.MIN_DATA_BYTES)
    }

    // --- problems ---

    @Test fun problemsOfACompleteInstallAreNone() {
        assertEquals(emptyList<String>(), GameProfile.JOEY.problems(looseGame(tmp.newFolder("ok"), "Joey_Pc.exe")))
    }

    @Test fun problemsNameWhatIsWrong() {
        val empty = tmp.newFolder("empty")
        assertEquals(listOf("no está el juego"), GameProfile.KAIBA.problems(empty))

        val dir = looseGame(tmp.newFolder("bad"), "kaiba_pc.exe")
        File(dir, "Voice.dat").delete()
        data(File(dir, "Data.dat"), 10)
        File(dir, "kaiba_pc.exe").writeText("not a program")
        text(File(dir, IsoImporter.INSTALLING_MARK), "")
        val p = GameProfile.KAIBA.problems(dir)
        assertTrue(p.toString(), "la instalación no terminó" in p)
        assertTrue(p.toString(), "kaiba_pc.exe no es un programa de Windows" in p)
        assertTrue(p.toString(), "Data.dat está incompleto" in p)
        assertTrue(p.toString(), "falta Voice.dat" in p)
    }

    // --- disc image sniffing and space ---

    @Test fun discImagesAreRecognisedByTheirVolumeDescriptor() {
        fun withId(offset: Int, id: String) = ByteArray(IsoImporter.HEAD_BYTES).also { id.toByteArray().copyInto(it, offset) }
        assertTrue("ISO 9660", IsoImporter.looksLikeDiscImage(withId(16 * 2048 + 1, "CD001")))
        assertTrue("UDF", IsoImporter.looksLikeDiscImage(withId(16 * 2048 + 1, "BEA01")))
        assertTrue("raw mode 1", IsoImporter.looksLikeDiscImage(withId(16 * 2352 + 17, "CD001")))
        assertTrue("raw mode 2", IsoImporter.looksLikeDiscImage(withId(16 * 2352 + 25, "CD001")))
        assertFalse("zip", IsoImporter.looksLikeDiscImage(byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(IsoImporter.HEAD_BYTES)))
        assertFalse("too short", IsoImporter.looksLikeDiscImage(ByteArray(100)))
        assertEquals(IsoImporter.HEAD_BYTES, IsoImporter.readHead(ByteArray(1 shl 20).inputStream()).size)
        assertEquals(10, IsoImporter.readHead(ByteArray(10).inputStream()).size)
    }

    @Test fun anImportAsksForMoreThanTwiceTheIso() {
        val iso = 400L shl 20
        assertTrue(IsoImporter.spaceNeeded(iso) > 2 * iso)
    }

    @Test fun onlyNewOrChangedFilesCountForTheMirror() {
        val src = looseGame(tmp.newFolder("msrc"), "yugi_pc.exe")
        val dst = tmp.newFolder("mdst")
        File(src, "Region.dat").copyTo(File(dst, "Region.dat")).setLastModified(File(src, "Region.dat").lastModified())
        val names = GameProfile.YUGI.mirrorFiles
        val expected = listOf("yugi_pc.exe", "Data.dat", "Voice.dat").sumOf { File(src, it).length() }
        assertEquals(expected, GameMirror.bytesToSync(src, dst, names))
    }

    // --- player ID ---

    @Test fun flcrcCipherMatchesARealInstall() {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertArrayEquals(hex("82ed564501002000800b142f2a"), PlayerIdSync.encrypt(hex("0100d800fd72c56a")))
    }
}
