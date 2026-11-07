# Engine Virtualized — guía técnica (fork de Bannerlator 3.1.5)

Flavor `engine` de Bannerlator 3.1.5 (rama `engine-virtualized`; la versión anterior, solo Joey, es la
rama `kiosk-joey`). La app abre un selector con *Yu-Gi-Oh! Power of Chaos* Joey, Yugi y Kaiba (ediciones
españolas de PC) y ejecuta el juego elegido en una máquina Windows virtualizada, sin mostrar la interfaz
de Bannerlator.

- applicationId: `com.laste.enginevirtualized`. Convive con Bannerlator (`com.winlator.banner`) y con la
  app anterior (`com.laste.yugiohjoey`), que conserva sus datos.
- Nombre: "Engine Virtualized"; icono y pantalla de carga con arte de Joey.
- Solo arm64-v8a. Capa de Wine: **Proton 11.0-2 arm64ec** (va dentro del APK); el código de 32 bits de los
  juegos corre con **WowBox64**.

## Qué hace la app

**Al abrir**: muestra el selector. Cada juego aparece como "Listo", "Importa la ISO del juego" o "El
instalador no terminó". Si quedó pendiente un instalador, primero recoge lo que instaló (ver "Importar una
ISO").

**Al pulsar Jugar** (los pasos que ya están hechos se saltan; los siguientes arranques tardan segundos):

1. Comprueba los archivos del juego en `files/<Joey|Yugi|Kaiba>/` (exe + `Data.dat` + `Voice.dat`) y el
   ID de jugador. Con `Deck.ydc`:
   - el de cualquiera de los tres juegos sirve (comparten ID). Si dos mazos llevan IDs distintos, **no
     lanza**;
   - no hay ningún mazo pero sí `system.dat`: restaura el último mazo de ese juego desde `backups/` (o,
     si solo hay copia del de otro juego, toma de ahí el ID). Sin ninguna copia **no lanza** (el juego
     reiniciaría las partidas);
   - no hay ninguno de los dos (jugador nuevo): lanza; el juego crea su ID y sus archivos, y en el siguiente
     arranque la app mueve el primer mazo de la copia interna a la carpeta del juego.
2. Instala imagefs (sistema base) y Proton 11.0-2 arm64ec.
3. Crea el contenedor "Power of Chaos Proton-11.0-2-arm64ec-14", **compartido por los tres juegos**:
   WineD3D con `renderer=gdi`, Fullscreen Off.
4. Copia los archivos de solo lectura del juego a la memoria interna (solo los que cambiaron) y, para
   Joey, pone allí el `YGO_Lanzador.exe` que va dentro del APK.
5. Monta las unidades: `E:` = copia interna del juego elegido, `G:` = `Android/data/<paquete>/files`
   (`D:` = Download y `F:` = almacenamiento, que son las de serie de Bannerlator, no se tocan).
6. Escribe en el registro tu ID de jugador (`flcrc`), `CommonDir` = `G:\Yu-Gi-Oh! Power of Chaos Common` y,
   si el juego arranca sin lanzador, su clave de instalación.
7. Importa el perfil táctil "Yu-Gi-Oh raton", crea o actualiza el acceso directo del juego (con su
   resolución), aplica `kiosk.ini`, **copia `system.dat` y el mazo a `backups/`** y lanza.

Al salir del juego se cierra la app.

| Juego | Exe | Clave del registro | Arranque | Resolución |
|---|---|---|---|---|
| Joey | `Joey_Pc.exe` | `InstallDirJ` | `E:\YGO_Lanzador.exe` (escribe la clave, `renderer=gdi`, `HODLL=wowbox64.dll`, `WINEDEBUG=-all`, `joey_pc.exe -Lspa`) | 1708x768 |
| Yugi | `yugi_pc.exe` | `InstallDir` (sin letra) | directo: `E:\yugi_pc.exe -Lspa`, emulador Box64 en el acceso directo | 800x600 |
| Kaiba | `kaiba_pc.exe` | `InstallDirK` | directo: `E:\kaiba_pc.exe -Lspa`, emulador Box64 en el acceso directo | 800x600 |

Las claves se sacaron de los `.msi` de los CDs. Los tres exes leen `deck.ydc`, `CommonDir` y `flcrc` en
`HKLM\Software\KONAMI\Yu-Gi-Oh! Power Of Chaos\system`. El lanzador está escrito para `joey_pc.exe`. Para
Yugi y Kaiba, la app hace lo mismo que el lanzador: escribe la clave, `renderer=gdi` va en la configuración
de WineD3D del contenedor y WowBox64 es el emulador del acceso directo.

## Importar una ISO

El selector de archivos de Android devuelve un `content://`, y 7-Zip necesita una ruta. Por eso la ISO se
copia a `files/ISO/<juego>.iso`, se descomprime con el `7zz` de Bannerlator en `files/ISO/<juego>/` y se
borra la copia. La app identifica el juego probando primero el de la tarjeta desde la que se importó y
luego los otros dos (`IsoImporter.identify`), así que una ISO importada desde la tarjeta equivocada se
instala en su juego. Para cada juego, en este orden:

1. **Archivos sueltos**: si hay un exe del juego junto a un `Data.dat` (en cualquier carpeta, hasta 4
   niveles), se copia esa carpeta a la del juego.
2. **`Data1.cab`** en la raíz, si el CD trae el exe de ese juego fuera del CAB o el `.msi` lleva su nombre:
   es un CAB normal de Microsoft junto al `.msi` del instalador. En los CDs
   españoles de Yugi y Kaiba trae el exe, `Data.dat`, `Voice.dat` y `Region.dat`. Se descomprime con 7-Zip
   (sin ejecutar el instalador). Si en el CD hay otro exe del juego fuera del CAB (`Crack\yugi_pc.exe`,
   `Crack\kaiba_pc.exe`, los que funcionan sin disco), sustituye al del CAB.
3. **Solo `setup.exe`** (no se puede saber de qué juego es: cuenta como el de la tarjeta): se ejecuta en el contenedor desde `G:\ISO\<juego>\`. Al terminar se cierra la app.
   En el siguiente arranque la app busca el exe instalado en `C:` y copia su carpeta. Sin probar con un CD
   real.

Nunca se sobrescriben un `Deck.ydc` ni un `kiosk.ini` que ya estén en la carpeta del juego. El
`system.dat` que traen algunas ISOs (la de Kaiba) se ignora. No se ofrece reinstalar un juego que ya está
listo, porque un CD pisaría un exe parcheado (el HD de Joey).

La ISO es la única forma de meter un juego: la app no lee `Download` ni pide "Acceso a todos los
archivos" (el permiso sigue declarado en el manifiesto común de Bannerlator, pero el modo kiosco nunca lo
solicita).

## Rendimiento: lo que se midió y por qué está así

| Configuración (teléfono, Joey a 1708x768) | Fotogramas completos/s al rotar una carta |
|---|---|
| Wine 10.0 x86_64 + Box64 (WoW64), como el contenedor original de Bannerlator | ~10-13, irregulares |
| Igual, renderizador SurfaceFlinger / preset Box64 Performance | sin cambio apreciable |
| Mismo juego a 800x600 (copia original en Bannerlator) | ~30, regulares |
| **Proton 11.0-2 arm64ec + WowBox64** | fluido (confirmado a ojo) |

El juego dibuja cada fotograma por software (DIB de 32 bits + `SetDIBitsToDevice`, sin DirectX) en código x86
de 32 bits. Con Wine x86_64, Box64 no llegaba a dibujar 1708x768 en el presupuesto del fotograma y el
limitador del juego se saltaba dibujados. WowBox64 (capa arm64ec) sí llega.

**Límite de FPS de Joey:** el motor está limitado por diseño (global `0x5eb618` del exe, original 30) y
su lógica avanza por fotograma, así que quitar el límite acelera todo. Se fijó en **40** (juego un 33 % más
rápido, a gusto del usuario): un byte en el offset de archivo `0x1eb618` del `Joey_Pc.exe`
(`1E` → `28`). Para probar otros valores en vivo en el PC: `tools/ygo/fpslive.py`. En Yugi y Kaiba el
offset es otro y no se ha buscado.

## Espejo interno (cargas instantáneas)

`/sdcard` en Android pasa por FUSE (MediaProvider). Joey lee `Data.dat` en muchos trozos pequeños y entrar
en "Construcción de deck" tardaba ~30 s, con MediaProvider al 70-97 % de CPU (instantáneo en el PC). La app
copia el exe, `Data.dat`, `Voice.dat` y `Region.dat` de cada juego a su almacenamiento interno
(`files/ygo_<juego>`) y Wine lo ve como `E:`. Ahora la carga es inmediata.

- Tú sigues actualizando los archivos en `Android/data/<paquete>/files/<juego>/`; al abrir, la app recopia
  los que cambiaron de tamaño o fecha.
- `E:\Deck.ydc` es un **enlace simbólico** al `Deck.ydc` de la carpeta del juego: el juego guarda el mazo
  directamente ahí. Si algún día el juego sustituyera el enlace por un archivo, el siguiente arranque lo
  devuelve (con copia previa en `backups/<fecha>_deck_antes_espejo`) y rehace el enlace.
- `CommonDir` se fija en el registro a `G:\Yu-Gi-Oh! Power of Chaos Common`: el juego lo usa tal cual cuando
  existe (`joey_pc.exe` 0x410bfa), así que `system.dat` no acaba en la copia interna.

## ID de jugador (`flcrc`): por qué hace falta

El juego guarda un ID de jugador de 8 bytes cifrado en
`HKLM\Software\Wow6432Node\KONAMI\Yu-Gi-Oh! Power Of Chaos\system\flcrc`. Las partidas llevan ese
ID (cabecera de 8 bytes de `Deck.ydc`). Si el valor falta (un prefijo de Wine nuevo), el juego inventa un
ID con la hora y **reinicia `system.dat` y `Deck.ydc`**. Pasó en la primera prueba (restaurado desde la copia).

La app lee la cabecera de `Deck.ydc` (sin escribirlo), la cifra con el algoritmo del juego
(`joey_pc.exe` 0x439c90: b^5 mod 299, empaquetado de 9 bits y suma de control 0x439e50) y la escribe en el
registro del contenedor antes de cada lanzamiento. Lleva una prueba con respuesta conocida (si falla, no lanza).
Herramienta del PC: `tools/ygo/flcrc.py` (`enc`/`dec`, emula el exe con Unicorn).

**Yugi y Kaiba:** usan la misma clave, `system.dat` y `deck.ydc`, y su código también usa el módulo 299.
Pero sus exes están compilados de otra forma y la función no coincide byte a byte con la de Joey, así que
**el cifrado no está verificado para ellos**. Por eso, antes de cada partida se copian `system.dat` y el mazo
a `backups/<fecha>_antes_<juego>/` (se guardan las últimas 20). Para verificarlo: localizar la función en
`yugi_pc.exe` / `kaiba_pc.exe` y emularla con `flcrc.py`.

## Exportar e importar partidas (`SaveTransfer.kt`)

Formato de `Deck.ydc`, deducido de 14 mazos de dos jugadores (teléfono y PC); no lleva suma de control:

```
[8 bytes ID de jugador]
[u16 n][n × u16 carta]   mazo principal (40-42 vistos)
[u16 n][n × u16 carta]   sección de 1 carta
[u16 n][n × u16 carta]   mazo auxiliar (0-15 vistos)        (little endian; el archivo termina ahí)
```

- **Importar mazo**: se valida esa estructura (tres secciones que acaban justo al final y contadores
  ≤ 100). Si el ID no es el del usuario, se sobrescriben los 8 primeros bytes con el suyo
  (`SaveTransfer.adoptDeck`). El ID del usuario se toma de sus mazos o, si no tiene, del mazo más
  reciente de `backups/`. Un jugador nuevo (sin ID) conserva el del mazo.
- **Importar todo** (`.zip`: `Joey/Deck.ydc`, `Yugi/Deck.ydc`, `Kaiba/Deck.ydc`,
  `Yu-Gi-Oh! Power of Chaos Common/system.dat`; se aceptan en cualquier carpeta y sin distinguir
  mayúsculas): con `system.dat` es otro jugador, y sus mazos deben compartir un ID que se conserva. Sin él,
  sus mazos reciben el ID del usuario (`SaveTransfer.planZip`). Los mazos que el `.zip` no sustituye y
  llevan otro ID se apartan (quedan en la copia), porque bloquearían el arranque.
- Antes de cualquier importación: copia de todo en `backups/<fecha>_antes_importar/`.
- Si hay un primer mazo de jugador nuevo en la copia interna (archivo normal, no enlace), se borra al
  importar; si no, `GameMirror.linkDeck` lo devolvería encima del importado.

`system.dat` también lleva algo ligado al jugador, pero su formato no se conoce (los 4 primeros bytes
cambian en cada guardado), así que no se valida ni se modifica.

## Ajustes sin recompilar: `kiosk.ini`

Archivo opcional en la carpeta de cada juego (`files/<juego>/kiosk.ini`; si no existe, se usan los valores
por defecto):

```ini
box64Preset=PERFORMANCE        # STABILITY | COMPATIBILITY | INTERMEDIATE | PERFORMANCE (defecto)
cpuList=4,5,6,7                # núcleos para Wine (defecto: los de 2,4-2,5 GHz); vacío = todos
priorityBoost=1
showFps=0
emulator=FEXCore               # capas arm64ec: FEXCore | Box64 (= WowBox64)
wine=Proton-11.0-2-arm64ec-14  # otra capa (crea su propio contenedor)
wineWcp=/sdcard/Download/YGO_kiosk/otra-capa.wcp
launch=direct                  # Joey: Joey_Pc.exe -Lspa sin el lanzador (Yugi y Kaiba, siempre)
args=-Lspa                     # argumentos del juego cuando arranca directo
screenSize=800x600             # resolución de este juego
extra.renderer=surfaceflinger  # cualquier extra del acceso directo; valor vacío lo quita
```

Los ajustes del contenedor (`box64Preset`, `showFps`, `emulator`) los comparten los tres juegos: se
aplican los del último que se abrió.

## Cambios sobre Bannerlator (todo lo demás es upstream)

| Archivo | Cambio |
|---|---|
| `app/build.gradle` | flavor `engine`, `BuildConfig.KIOSK`, firma propia (opcional), descarga de Proton con sha256, `noCompress 'wcp'` |
| `app/src/main/java/com/winlator/star/kiosk/` | `GameProfile.kt` (los tres juegos y las rutas), `KioskBootstrap.kt` (selector, importación, preparación y lanzamiento), `IsoImporter.kt` (ISO → carpeta del juego), `GameMirror.kt` (espejo interno), `PlayerIdSync.kt` (ID de jugador y `CommonDir`), `KioskTuning.kt` (`kiosk.ini`), `KioskScreen.kt` (selector y estado) |
| `MainActivity.kt` | en modo kiosco delega en `KioskBootstrap` |
| `ContainerManager.java` | `createContainerSync()` |
| `AppUtils.java` | `restartApplication()` cierra la app en modo kiosco (si no, relanzaría el juego en bucle) |
| `app/src/engine/` | icono, `splash_logo`, `shutdown_bg`, portada, perfil `.icpx`, `YGO_Lanzador.exe` compilado |
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
./gradlew assembleEngineRelease
# APK: app/build/outputs/apk/engine/release/app-engine-release.apk  (~665 MB)
```

Usa siempre **release** para jugar: la debug compila el código nativo sin optimizar y va notablemente peor.
La primera compilación tarda ~10 min (código nativo); las siguientes ~5 min. Si el daemon de Kotlin se cae
por memoria, añade `-Pkotlin.compiler.execution.strategy=in-process`.
La compilación original de Bannerlator sigue funcionando: `./gradlew assembleStandardDebug`.

Firma: `keystore/engine-signing.properties` (o el antiguo `keystore/joey-signing.properties`; `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`) apunta a tu clave. Ninguno de los dos archivos está en el repo, y
sin ellos el APK se firma con la clave de pruebas de AOSP. **Guarda tu clave**: las actualizaciones solo se
instalan encima si van firmadas con la misma. Para crear una: `keytool -genkeypair -keystore
keystore/joey-debug.p12 -storetype PKCS12 -alias joey -keyalg RSA -keysize 2048 -validity 10000`.

## Instalar / actualizar la app

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
# Juego cerrado (instalar mata la app); antes, copia de las partidas (abajo)
"$ADB" -s <SERIAL> install -r app/build/outputs/apk/engine/release/app-engine-release.apk
```

`install -r` conserva el contenedor, el perfil, el espejo y la carpeta `Android/data`. **Desinstalar borra
`Android/data/com.laste.enginevirtualized`, partidas incluidas.**

## Actualizar un exe / Data.dat (parches del PC)

No hace falta recompilar: copia los archivos a la carpeta del juego y la app refresca su espejo interno al
abrirse. Recuerda que el exe de Joey del teléfono lleva el límite a 40 FPS (byte `0x1eb618` = `28`).

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
F=/sdcard/Android/data/com.laste.enginevirtualized/files
# 1. Juego cerrado: no debe salir *_pc.exe ni wineserver
"$ADB" -s <SERIAL> shell 'ps -A -o NAME | grep -iE "_pc.exe|wineserver"'
# 2. Copia de seguridad con md5
D=$(date +%Y%m%d_%H%M); B=$F/backups/${D}_antes_parche
"$ADB" -s <SERIAL> shell "mkdir -p $B && cp '$F/Yu-Gi-Oh! Power of Chaos Common/system.dat' $F/Joey/Deck.ydc $F/Joey/Joey_Pc.exe $F/Joey/Data.dat $B/ && md5sum $B/*"
# 3. Subir como .new y renombrar (nunca a medias)
"$ADB" -s <SERIAL> push joey_pc.exe $F/Joey/Joey_Pc.exe.new
"$ADB" -s <SERIAL> push Data.dat   $F/Joey/Data.dat.new
"$ADB" -s <SERIAL> shell "cd $F/Joey && mv Joey_Pc.exe.new Joey_Pc.exe && mv Data.dat.new Data.dat && md5sum Joey_Pc.exe Data.dat"
# 4. Comparar con md5sum de los archivos del PC; system.dat y Deck.ydc deben seguir iguales
```

## Otros dispositivos

El APK no lleva archivos de los juegos ni partidas. Necesita Android arm64 (8+) y las ISOs de los juegos.
Solo se ha probado en el Redmi Note 14 Pro+ (Snapdragon 7s Gen 3 / Adreno 810); en CPUs más débiles irá menos
fluido, y en kernels con páginas de 16 KB Box64/Wine podrían fallar. No funciona en x86 ni en el emulador del PC.

## Problemas conocidos

- Yugi y Kaiba están sin probar en el teléfono, y su cifrado de `flcrc` sin verificar (ver arriba).
- El instalador en Wine (CDs sin `Data1.cab`) está sin probar.
- Con la app recién instalada, Bannerlator ocultaba los controles táctiles a los 5 s y no los devolvía
  (`touchscreen_timeout_enabled` sin valor). El primer arranque lo deja en `false`.
- Al crear un contenedor arm64ec sin la interfaz hay que rellenar `fexcoreVersion`, `fexcorePreset` y
  `emulator` (si no, NullPointerException al lanzar); `KioskTuning` lo hace.
- **Emulador Android de PC (x86_64): no funciona.** La imagen Android 14 "Google APIs" x86_64 trae
  traducción ARM (`libndk_translation`): la app arranca, instala todo y llega a "Launching Windows",
  pero `box64` muere con `Illegal instruction` (también con `BOX64_DYNAREC=0`). Hace falta un
  dispositivo arm64 real.
- La pantalla de error de lanzamiento de Bannerlator sugiere "Settings → Logs", que no es accesible en modo
  kiosco. Para depurar, usa `adb logcat` (etiquetas `Kiosk`, `KioskMirror`, `KioskIso`, `XServerDisplayActivity`).
