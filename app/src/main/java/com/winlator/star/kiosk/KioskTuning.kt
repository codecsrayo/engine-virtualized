package com.winlator.star.kiosk

import com.winlator.star.box64.Box64Preset
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.DefaultVersion
import com.winlator.star.fexcore.FEXCorePreset
import java.io.File
import java.util.Properties

/**
 * Settings re-applied on every launch, tunable without a rebuild through an optional `kiosk.ini`
 * in each game's folder (Android/data/<package>/files/<Joey|Yugi|Kaiba>/kiosk.ini), e.g.
 *
 *     box64Preset=PERFORMANCE     # STABILITY | COMPATIBILITY | INTERMEDIATE | PERFORMANCE
 *     cpuList=4,5,6,7             # cores Wine may use; empty = all
 *     priorityBoost=1
 *     showFps=0
 *     emulator=FEXCore            # arm64ec layers only: FEXCore | Box64 (= WowBox64)
 *     wine=Proton-11.0-2-arm64ec-14                       # other Wine layer, own container
 *     wineWcp=/sdcard/Download/YGO_kiosk/proton-11.0-2-arm64ec.wcp
 *     launch=direct               # Joey: Joey_Pc.exe -Lspa without YGO_Lanzador.exe (Yugi/Kaiba always)
 *     args=-Lspa                  # game arguments when launched directly
 *     screenSize=800x600          # this game's desktop size (default: GameProfile.screenSize)
 *     extra.renderer=surfaceflinger   # any shortcut [Extra Data] key; empty value removes it
 *
 * Defaults: the game renders every frame on the CPU (GDI DIB blits, no DirectX) in x86 code under
 * Box64, so the dynarec preset matters most. Compatibility (Bannerlator's default) adds a memory
 * barrier to every access (STRONGMEM=1) and disables big blocks / call-return; Performance drops
 * those. cpuList pins Wine to the 2.4-2.5 GHz cores of the Snapdragon 7s Gen 3 (cpu4-7);
 * Bannerlator's own "prefer big cores" counts the 1.8 GHz cores in too (72% of peak >= 70%).
 */
object KioskTuning {
    private val PRESETS = setOf(Box64Preset.STABILITY, Box64Preset.COMPATIBILITY,
        Box64Preset.INTERMEDIATE, Box64Preset.PERFORMANCE)

    fun load(iniFile: File): Properties {
        val ini = Properties()
        if (iniFile.isFile) iniFile.reader().use { ini.load(it) }
        return ini
    }

    fun opt(ini: Properties, key: String, default: String): String =
        ini.getProperty(key, default).substringBefore('#').trim()

    fun apply(container: Container, shortcutFile: File, ini: Properties) {
        val preset = opt(ini, "box64Preset", Box64Preset.PERFORMANCE).uppercase()
        container.setBox64Preset(if (preset in PRESETS) preset else Box64Preset.PERFORMANCE)
        container.setShowFPS(opt(ini, "showFps", "0") == "1")
        // The container editor always fills these; a null FEXCore version crashes the arm64ec
        // launch path (GuestProgramLauncherComponent.extractEmulatorsDlls).
        if (container.getFEXCoreVersion().isNullOrEmpty()) container.setFEXCoreVersion(DefaultVersion.FEXCORE)
        if (container.getFEXCorePreset().isNullOrEmpty()) container.setFEXCorePreset(FEXCorePreset.INTERMEDIATE)
        if (container.getEmulator().isNullOrEmpty()) container.setEmulator(Container.DEFAULT_EMULATOR)
        when (opt(ini, "emulator", "").lowercase()) {
            "fexcore" -> container.setEmulator("FEXCore")
            "box64", "wowbox64" -> container.setEmulator("Box64")
        }
        container.saveData()

        val shortcut = Shortcut(container, shortcutFile)
        val cpuList = opt(ini, "cpuList", "4,5,6,7")
        shortcut.putExtra("cpuList", cpuList.ifEmpty { null })
        shortcut.putExtra("perfPriorityBoost", if (opt(ini, "priorityBoost", "1") == "1") "1" else null)
        // Any other per-game override, for experiments: extra.<key>=<value> (empty value removes it),
        // e.g. extra.renderer=surfaceflinger, extra.presentMode=mailbox.
        for (name in ini.stringPropertyNames().filter { it.startsWith("extra.") }) {
            shortcut.putExtra(name.removePrefix("extra."), opt(ini, name, "").ifEmpty { null })
        }
        shortcut.saveData()
    }
}
