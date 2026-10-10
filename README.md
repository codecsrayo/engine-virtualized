<h1 align="center">Yu-Gi-Oh! Joey para Android</h1>
<p align="center"><b><i>Yu-Gi-Oh! Power of Chaos: Joey the Passion</i> (PC, edición española, HD 1708x768) en Android, con un solo toque.</b></p>

<p align="center">
  <a href="https://github.com/codecsrayo/yugioh-joey-android/releases/latest">
    <img src="https://img.shields.io/badge/⬇%20Descargar-APK-ff2d9b?style=for-the-badge&logo=android&logoColor=white" alt="Descargar APK">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20arm64-7a4cff?style=for-the-badge" alt="Android 8.0+ arm64">
  <img src="https://img.shields.io/badge/Licencia-GPL--3.0-2d9bff?style=for-the-badge" alt="GPL-3.0">
</p>

<p align="center">
  <img src="docs/joey/menu.jpg" width="49%" alt="Menú principal">
  <img src="docs/joey/deck.jpg" width="49%" alt="Construcción de deck">
</p>
<p align="center">
  <img src="docs/joey/duelo.jpg" width="98%" alt="Duelo">
</p>

Al tocar el icono, la app prepara todo sola la primera vez (Wine, contenedor, controles) y abre el juego
directamente, sin pasar por ninguna interfaz de emulador. Al salir del juego se cierra la app.

Es un *fork* de [Bannerlator](https://github.com/The412Banner/Bannerlator) 3.1.5 (a su vez basado en
Winlator) con un modo kiosco. **No incluye el juego**: necesitas tu propia copia.

## Requisitos mínimos

| | Mínimo | Probado / recomendado |
|---|---|---|
| Sistema | Android 8.0 (API 26) | Android 16 (HyperOS 3) |
| Arquitectura | **arm64-v8a** (no funciona en x86 ni en el emulador de Android Studio) | |
| CPU | 64 bits ARMv8.2 de gama media | Snapdragon 7s Gen 3 (1×2,5 + 3×2,4 + 4×1,8 GHz) o superior para ir fluido a 1708x768 |
| GPU | Vulkan 1.1 (el juego no usa 3D) | Adreno 810 |
| RAM | 4 GB (el juego usa ~0,5 GB) | 6 GB o más |
| Almacenamiento | ~3,5 GB libres: APK ~0,7 GB, datos de la app ~2 GB (sistema base, Proton y copia interna del juego) y juego en `Download` ~0,7 GB | |
| Kernel | Páginas de 4 KB (la mayoría de móviles; en kernels de 16 KB Box64/Wine pueden fallar) | |
| Permisos | "Acceso a todos los archivos" (para leer el juego y las partidas en `Download`) | |

Solo se ha probado en un Redmi Note 14 Pro+ 5G. En CPUs más débiles el juego funciona, pero las
animaciones irán menos fluidas.

## Instalación

1. Descarga `YuGiOh-Joey-…-release.apk` y `YGO_Lanzador.exe` de la [última release](https://github.com/codecsrayo/yugioh-joey-android/releases/latest).
2. Copia al teléfono, en `Download/YGO_Joey_HD/`:
   - de tu instalación del juego: `Joey_Pc.exe`, `Data.dat`, `Voice.dat`, `Region.dat` y, si ya tienes partida, `Deck.ydc`;
   - `YGO_Lanzador.exe` (de la release; su código está en [`tools/ygo/YGO_Lanzador.c`](tools/ygo/YGO_Lanzador.c)).
3. Si ya tienes partida, copia también la carpeta `Yu-Gi-Oh! Power of Chaos Common` (con `system.dat`) a `Download/`.
4. Instala el APK, ábrelo y concede "Acceso a todos los archivos". La primera vez tarda un poco en preparar todo.

Estructura esperada:

```
Download/
├── YGO_Joey_HD/
│   ├── YGO_Lanzador.exe   Joey_Pc.exe   Data.dat   Voice.dat   Region.dat
│   └── Deck.ydc           (tu mazo; lo crea el juego si empiezas de cero)
└── Yu-Gi-Oh! Power of Chaos Common/
    └── system.dat         (tus cartas y progreso)
```

El juego original (800x600) también arranca, pero la app está pensada para la versión parcheada a 1708x768.
Las partidas se quedan siempre en `Download`: sobreviven a desinstalar la app y se pueden compartir con
Bannerlator (no abras el juego en las dos a la vez).

## Qué tiene de especial

- **Un toque**: crea el contenedor (Proton 11 arm64ec, que va dentro del APK), importa el perfil táctil
  (clic izquierdo y derecho) y lanza el juego sin interfaz.
- **Tus partidas a salvo**: el juego guarda un ID de jugador cifrado en el registro (`flcrc`). En un prefijo
  de Wine nuevo inventa otro y **reinicia las partidas**. La app lo reconstruye desde tu `Deck.ydc` antes de
  cada arranque, y si falta el mazo pero hay partidas, no abre el juego.
- **Fluido**: con Proton arm64ec + WowBox64, el dibujado por software del juego a 1708x768 cabe en el
  presupuesto de cada fotograma (con Wine x86_64 + Box64 iba a ~10-13 FPS).
- **Cargas instantáneas**: `/sdcard` pasa por FUSE y entrar en "Construcción de deck" tardaba ~30 s. La app
  mantiene una copia interna de los archivos de solo lectura del juego y la actualiza sola cuando cambian.
- **Ajustable sin recompilar** con `Download/YGO_Joey_HD/kiosk.ini`.

Los detalles técnicos, las mediciones y cómo compilar están en [README-JOEY.md](README-JOEY.md).

## Compilar

```bash
export JAVA_HOME="<Android Studio>/jbr"   # JDK 21
./gradlew assembleJoeyRelease
```

Requiere Android SDK (platform 34, build-tools 35, NDK 29.0.14206865, CMake 3.22.1). La primera compilación
descarga y verifica por sha256 el sistema base de Bannerlator y Proton 11.0-2 arm64ec. Para firmar con tu
propia clave crea `keystore/joey-signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`);
sin ella el APK se firma con la clave de pruebas de AOSP.

## Créditos y licencia

- [Bannerlator](https://github.com/The412Banner/Bannerlator) (The412Banner) y [Winlator](https://github.com/brunodev85/winlator)
  (brunodev85), GPL-3.0. Este repositorio conserva su código, su historial y su licencia
  ([LICENSE](LICENSE), [README original](README-BANNERLATOR.md)).
- Proton 11.0-2 arm64ec y la capa base: publicados por The412Banner en
  [winlator-contents](https://github.com/The412Banner/winlator-contents).
- *Yu-Gi-Oh! Power of Chaos* © Kazuki Takahashi / Konami. Proyecto no oficial, sin relación con Konami.
  No se distribuye el juego: usa tu propia copia.
