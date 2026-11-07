package com.winlator.star.kiosk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The only UI the kiosk shows: the game picker, a step's progress, or what went wrong. */
@Composable
fun KioskScreen(bootstrap: KioskBootstrap) {
    val s by bootstrap.state.collectAsState()
    // An ISO import or the first setup takes minutes: keep the screen (and so the app) awake meanwhile.
    val view = LocalView.current
    DisposableEffect(s.busy) {
        view.keepScreenOn = s.busy
        onDispose { view.keepScreenOn = false }
    }
    // The game whose ISO the system file picker is open for.
    val isoFor = remember { mutableStateOf<GameProfile?>(null) }
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = isoFor.value
        isoFor.value = null
        if (uri != null && game != null) bootstrap.importIso(game, uri)
    }
    // Saves: one game's deck (deckFor) or everything in a .zip.
    val deckFor = remember { mutableStateOf<GameProfile?>(null) }
    val exportDeck = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val game = deckFor.value
        deckFor.value = null
        if (uri != null && game != null) bootstrap.exportDeck(game, uri)
    }
    val importDeck = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = deckFor.value
        deckFor.value = null
        if (uri != null && game != null) bootstrap.importDeck(game, uri)
    }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) bootstrap.exportAll(uri)
    }
    val importAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) bootstrap.importAll(uri)
    }
    val today = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Engine Virtualized", color = Color.White, fontSize = 24.sp)
        Text("Yu-Gi-Oh! Power of Chaos en una máquina Windows virtualizada (Wine/Proton)",
            color = Color(0xFFB0B0C0), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        when {
            s.busy -> {
                Text(s.message, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                if (s.progress >= 0) LinearProgressIndicator(
                    progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth(0.6f))
                else CircularProgressIndicator()
            }
            s.error -> {
                Text(s.message, color = Color(0xFFFF8A80), fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { bootstrap.dismissError() }) { Text("Volver") }
            }
            else -> {
                s.notice?.let {
                    Text(it, color = Color(0xFF8AE29A), fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                }
                for (g in s.games) {
                    GameCard(g,
                        onPlay = { bootstrap.play(g.game) },
                        onIso = {
                            isoFor.value = g.game
                            pickIso.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*"))
                        },
                        onRetryInstaller = { bootstrap.retryInstaller(g.game) },
                        onDiscardInstaller = { bootstrap.discardInstaller(g.game) },
                        onExportDeck = {
                            deckFor.value = g.game
                            exportDeck.launch("Deck_${g.game.dirName}_$today.ydc")
                        },
                        onImportDeck = {
                            deckFor.value = g.game
                            importDeck.launch(arrayOf("application/octet-stream", "*/*"))
                        })
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF16182C), RoundedCornerShape(12.dp)).padding(16.dp),
                ) {
                    Text("Partidas", color = Color.White, fontSize = 18.sp)
                    Text("Los tres mazos y system.dat en un .zip: para guardarlas o pasarlas a otro teléfono.",
                        color = Color(0xFFB0B0C0), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { exportAll.launch("PowerOfChaos_partidas_$today.zip") }) { Text("Exportar todo") }
                        OutlinedButton(onClick = { importAll.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Importar todo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    g: KioskBootstrap.GameState,
    onPlay: () -> Unit,
    onIso: () -> Unit,
    onRetryInstaller: () -> Unit,
    onDiscardInstaller: () -> Unit,
    onExportDeck: () -> Unit,
    onImportDeck: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF16182C), RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        Text("Yu-Gi-Oh! ${g.game.title}", color = Color.White, fontSize = 18.sp)
        Text(g.detail, color = if (g.status == KioskBootstrap.Status.READY) Color(0xFF8AE29A) else Color(0xFFB0B0C0),
            fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (g.status) {
                // No reinstall from here: a CD would overwrite a patched exe (Joey's HD one).
                KioskBootstrap.Status.READY -> Button(onClick = onPlay) { Text("Jugar") }
                KioskBootstrap.Status.INSTALLER -> {
                    Button(onClick = onRetryInstaller) { Text("Abrir instalador") }
                    OutlinedButton(onClick = onDiscardInstaller) { Text("Descartar") }
                }
                KioskBootstrap.Status.MISSING -> Button(onClick = onIso) { Text("Importar ISO") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (g.hasDeck) OutlinedButton(onClick = onExportDeck) { Text("Exportar mazo") }
            OutlinedButton(onClick = onImportDeck) { Text("Importar mazo") }
        }
    }
}
