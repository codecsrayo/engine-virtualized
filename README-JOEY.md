# Yu-Gi-Oh! Joey — APK "de un toque" (fork de Bannerlator 3.1.5)

Uso personal, no se distribuye. Flavor `joey` de Bannerlator 3.1.5 (tag `3.1.5`, rama `kiosk-joey`):
al tocar el icono arranca directamente *Yu-Gi-Oh! Power of Chaos: Joey the Passion* (edición española, HD 1708x768).

- applicationId: `com.laste.yugiohjoey` (convive con Bannerlator `com.winlator.banner`)
- Nombre: "Yu-Gi-Oh! Joey"; icono y pantalla de carga con arte del propio juego
- Solo arm64-v8a. Capa de Wine: **Proton 11.0-2 arm64ec** (va dentro del APK); el código de 32 bits del juego
  corre con **WowBox64**.

## Qué hace la app

Primer arranque (sin mostrar la interfaz de Bannerlator):

1. Pide "Acceso a todos los archivos" (necesario para leer `Download/`).
2. Comprueba que existen en `/sdcard/Download/YGO_Joey_HD/`: `YGO_Lanzador.exe`, `Joey_Pc.exe`, `Data.dat`,
   `Voice.dat`. Si falta alguno lo dice y ofrece "Reintentar". Con `Deck.ydc`:
   - existe: usa su ID de jugador (caso normal);
   - falta pero hay `system.dat`: **no lanza** (el juego reiniciaría las partidas) y explica cómo reponerlo;
   - faltan los dos (jugador nuevo, otro dispositivo): lanza; el juego crea su ID y sus archivos, y en el
     siguiente arranque la app mueve el primer mazo de la copia interna a `Download`.
3. Instala imagefs (sistema base) y Proton 11.0-2 arm64ec.
4. Crea el contenedor "Yugioh HD Proton-11.0-2-arm64ec-14": 1708x768, WineD3D, Fullscreen Off,
   D: = `/sdcard/Download`, E: = copia interna del juego (ver "Espejo interno").
5. Copia los archivos de solo lectura del juego a la memoria interna (solo los que cambiaron).
6. Escribe en el registro del contenedor tu ID de jugador (`flcrc`) y `CommonDir` (ver abajo).
7. Importa el perfil táctil "Yu-Gi-Oh raton" (clic izquierdo y derecho), aplica los ajustes de rendimiento
   y lanza `E:\YGO_Lanzador.exe`.

Siguientes arranques: los pasos 2, 5, 6 y 7 (segundos). Al salir del juego se cierra la app.

El lanzador (`YGO_Lanzador.exe`) sigue haciendo lo de siempre: InstallDirJ, `renderer=gdi`,
`HODLL=wowbox64.dll`, `WINEDEBUG=-all`, `joey_pc.exe -Lspa`.

**Partidas:** se quedan en `/sdcard/Download/Yu-Gi-Oh! Power of Chaos Common/system.dat` y
`/sdcard/Download/YGO_Joey_HD/Deck.ydc` y sobreviven a desinstalar la app. Bannerlator y esta app
comparten esos archivos (mismo ID de jugador): **no abras el juego en las dos a la vez**.

## Rendimiento: lo que se midió y por qué está así

| Configuración (teléfono, 1708x768) | Fotogramas completos/s al rotar una carta |
|---|---|
| Wine 10.0 x86_64 + Box64 (WoW64), como el contenedor original de Bannerlator | ~10-13, irregulares |
| Igual, renderizador SurfaceFlinger / preset Box64 Performance | sin cambio apreciable |
| Mismo juego a 800x600 (copia original en Bannerlator) | ~30, regulares |
| **Proton 11.0-2 arm64ec + WowBox64** | fluido (confirmado a ojo) |

El juego dibuja cada fotograma por software (DIB de 32 bits + `SetDIBitsToDevice`, sin DirectX) en código x86
de 32 bits. Con Wine x86_64, Box64 no llegaba a dibujar 1708x768 en el presupuesto del fotograma y el
limitador del juego se saltaba dibujados. WowBox64 (capa arm64ec) sí llega.

**Límite de FPS del juego:** el motor está limitado por diseño (global `0x5eb618` del exe, original 30) y
su lógica avanza por fotograma, así que quitar el límite acelera todo. Se fijó en **40** (juego un 33 % más
rápido, a gusto del usuario): un byte en el offset de archivo `0x1eb618` del `Joey_Pc.exe`
(`1E` → `28`). Para probar otros valores en vivo en el PC: `tools/ygo/fpslive.py`.

## Espejo interno (cargas instantáneas)

`/sdcard` en Android pasa por FUSE (MediaProvider). El juego lee `Data.dat` en muchos trozos pequeños y entrar
en "Construcción de deck" tardaba ~30 s, con MediaProvider al 70-97 % de CPU (instantáneo en el PC). La app
copia `YGO_Lanzador.exe`, `Joey_Pc.exe`, `Data.dat`, `Voice.dat` y `Region.dat` a su almacenamiento
interno (`files/ygo_game`, ~690 MB) y Wine lo ve como `E:`. Ahora la carga es inmediata.

- Tú sigues actualizando los archivos en `Download/YGO_Joey_HD`; al abrir, la app recopia los que cambiaron
  de tamaño o fecha (~2 s para todo).
- `E:\Deck.ydc` es un **enlace simbólico** a `Download/YGO_Joey_HD/Deck.ydc`: el juego guarda el mazo
  directamente en tu archivo externo (verificado). Si algún día el juego sustituyera el enlace por un archivo,
  el siguiente arranque lo devuelve a `Download` (con copia previa en `YGO_backups/<fecha>_deck_antes_espejo`)
  y rehace el enlace.
- `CommonDir` se fija en el registro a `D:\Yu-Gi-Oh! Power of Chaos Common`: el juego lo usa tal cual cuando
  existe (`joey_pc.exe` 0x410bfa), así que `system.dat` sigue en `Download`.

## ID de jugador (`flcrc`): por qué hace falta

El juego guarda un ID de jugador de 8 bytes cifrado en
`HKLM\Software\Wow6432Node\KONAMI\Yu-Gi-Oh! Power Of Chaos\system\flcrc`. Las partidas llevan ese
ID (cabecera de 8 bytes de `Deck.ydc`). Si el valor falta (un prefijo de Wine nuevo), el juego inventa un
ID con la hora y **reinicia `system.dat` y `Deck.ydc`**. Pasó en la primera prueba (restaurado desde la copia).

La app lee la cabecera de `Deck.ydc` (sin escribirlo), la cifra con el algoritmo del juego
(`joey_pc.exe` 0x439c90: b^5 mod 299, empaquetado de 9 bits y suma de control 0x439e50) y la escribe en el
registro del contenedor antes de cada lanzamiento. Lleva una prueba con respuesta conocida (si falla, no lanza).
Herramienta del PC: `tools/ygo/flcrc.py` (`enc`/`dec`, emula el exe con Unicorn).

## Ajustes sin recompilar: `kiosk.ini`

Archivo opcional `Download/YGO_Joey_HD/kiosk.ini` (si no existe, se usan los valores por defecto):

```ini
box64Preset=PERFORMANCE        # STABILITY | COMPATIBILITY | INTERMEDIATE | PERFORMANCE (defecto)
cpuList=4,5,6,7                # núcleos para Wine (defecto: los de 2,4-2,5 GHz); vacío = todos
priorityBoost=1
showFps=0
emulator=FEXCore               # capas arm64ec: FEXCore | Box64 (= WowBox64)
wine=Proton-11.0-2-arm64ec-14  # otra capa (crea su propio contenedor)
wineWcp=/sdcard/Download/YGO_kiosk/otra-capa.wcp
launch=direct                  # lanza Joey_Pc.exe -Lspa sin el lanzador (el lanzador fuerza WowBox64)
extra.renderer=surfaceflinger  # cualquier extra del acceso directo; valor vacío lo quita
```

## Cambios sobre Bannerlator (todo lo demás es upstream)

| Archivo | Cambio |
|---|---|
| `app/build.gradle` | flavor `joey`, `BuildConfig.KIOSK`, firma propia (opcional), descarga de Proton con sha256, `noCompress 'wcp'` |
| `app/src/main/java/com/winlator/star/kiosk/` | `KioskBootstrap.kt` (preparación y lanzamiento), `GameMirror.kt` (espejo interno), `PlayerIdSync.kt` (ID de jugador y `CommonDir`), `KioskTuning.kt` (`kiosk.ini`), `KioskScreen.kt` (pantalla de estado) |
| `MainActivity.kt` | en modo kiosco delega en `KioskBootstrap` |
| `ContainerManager.java` | `createContainerSync()` |
| `AppUtils.java` | `restartApplication()` cierra la app en modo kiosco (si no, relanzaría el juego en bucle) |
| `app/src/joey/` | icono, `splash_logo`, `shutdown_bg`, portada, perfil `.icpx` |
| `tools/ygo/` | código del lanzador (`YGO_Lanzador.c`), `flcrc.py` (ID de jugador), `fpslive.py` (límite de FPS en vivo, PC) |

## Requisitos para compilar (versiones probadas)

- Windows 11, Git Bash
- JDK: JBR 21.0.10 de Android Studio (`C:\Program Files\Android\Android Studio\jbr`)
- Gradle 8.10.2 (wrapper), AGP 8.8.0, Kotlin 2.0.21
- Android SDK: platform `android-34`, build-tools `35.0.0`, NDK `29.0.14206865`, CMake `3.22.1`
  (instalar con `cmdline-tools\latest\bin\sdkmanager.bat`)
- `local.properties` (raíz y `app/`): `sdk.dir=<ruta del Android SDK>`
- Red en la primera compilación: descarga `imagefs.tar.zst` (Bannerlator) y `proton-11.0-2-arm64ec.wcp`
  (github.com/The412Banner/winlator-contents, release `bionic-layers-20261007-v14`), ambos verificados con sha256.

## Compilar

```bash
cd <repo>
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleJoeyRelease
# APK: app/build/outputs/apk/joey/release/app-joey-release.apk  (~665 MB)
```

Usa siempre **release** para jugar: la debug compila el código nativo sin optimizar y va notablemente peor.
La primera compilación tarda ~10 min (código nativo); las siguientes ~5 min.
La compilación original de Bannerlator sigue funcionando: `./gradlew assembleStandardDebug`.

Firma: `keystore/joey-signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) apunta
a tu clave; ninguno de los dos archivos está en el repo. Sin ellos el APK se firma con la clave de pruebas
de AOSP. **Guarda tu clave**: las actualizaciones solo se instalan encima si van firmadas con la misma.
Para crear una: `keytool -genkeypair -keystore keystore/joey-debug.p12 -storetype PKCS12 -alias joey
-keyalg RSA -keysize 2048 -validity 10000`.

## Instalar / actualizar la app

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
# Juego cerrado (instalar mata la app); antes, copia de las partidas (abajo)
"$ADB" -s <SERIAL> install -r app/build/outputs/apk/joey/release/app-joey-release.apk
```

`install -r` conserva el contenedor, el perfil y el espejo.

## Actualizar Joey_Pc.exe / Data.dat (parches del PC)

No hace falta recompilar: copia los archivos a `/sdcard/Download/YGO_Joey_HD` como siempre; la app refresca
su espejo interno al abrirse. Recuerda que el exe del teléfono lleva el límite a 40 FPS (byte `0x1eb618` = `28`).

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
# 1. Juego cerrado: no debe salir joey_pc.exe ni wineserver
"$ADB" -s <SERIAL> shell 'ps -A -o NAME | grep -iE "joey_pc|wineserver"'
# 2. Copia de seguridad con md5
D=$(date +%Y%m%d_%H%M); B=/sdcard/Download/YGO_backups/${D}_antes_parche
"$ADB" -s <SERIAL> shell "mkdir -p $B && cp '/sdcard/Download/Yu-Gi-Oh! Power of Chaos Common/system.dat' /sdcard/Download/YGO_Joey_HD/Deck.ydc /sdcard/Download/YGO_Joey_HD/Joey_Pc.exe /sdcard/Download/YGO_Joey_HD/Data.dat $B/ && md5sum $B/*"
# 3. Subir como .new y renombrar (nunca a medias)
"$ADB" -s <SERIAL> push joey_pc.exe /sdcard/Download/YGO_Joey_HD/Joey_Pc.exe.new
"$ADB" -s <SERIAL> push Data.dat   /sdcard/Download/YGO_Joey_HD/Data.dat.new
"$ADB" -s <SERIAL> shell 'cd /sdcard/Download/YGO_Joey_HD && mv Joey_Pc.exe.new Joey_Pc.exe && mv Data.dat.new Data.dat && md5sum Joey_Pc.exe Data.dat'
# 4. Comparar con md5sum de los archivos del PC; system.dat y Deck.ydc deben seguir iguales
```

## Otros dispositivos

El APK no lleva archivos del juego ni partidas. Necesita Android arm64 (8+), la carpeta
`Download/YGO_Joey_HD` con los archivos del juego y conceder "Acceso a todos los archivos". Solo se ha probado
en el Redmi Note 14 Pro+ (Snapdragon 7s Gen 3 / Adreno 810); en CPUs más débiles irá menos fluido, y en
kernels con páginas de 16 KB Box64/Wine podrían fallar. No funciona en x86 ni en el emulador del PC.

## Problemas conocidos

- No abras el juego en Bannerlator y en esta app a la vez (comparten partidas).
- Con la app recién instalada, Bannerlator ocultaba los controles táctiles a los 5 s y no los devolvía
  (`touchscreen_timeout_enabled` sin valor). El primer arranque lo deja en `false`.
- Al crear un contenedor arm64ec sin la interfaz hay que rellenar `fexcoreVersion`, `fexcorePreset` y
  `emulator` (si no, NullPointerException al lanzar); `KioskTuning` lo hace.
- **Emulador Android de PC (x86_64): no funciona.** La imagen Android 14 "Google APIs" x86_64 trae
  traducción ARM (`libndk_translation`): la app arranca, instala todo y llega a "Launching Windows",
  pero `box64` muere con `Illegal instruction` (también con `BOX64_DYNAREC=0`). Hace falta un
  dispositivo arm64 real.
- La pantalla de error de lanzamiento de Bannerlator sugiere "Settings → Logs", que no es accesible en modo
  kiosco. Para depurar, usa `adb logcat` (etiquetas `Kiosk`, `KioskMirror`, `XServerDisplayActivity`).
