package com.winlator.star.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The only UI the kiosk shows: first-run progress, or what is missing before the game can start. */
@Composable
fun KioskScreen(bootstrap: KioskBootstrap) {
    val s by bootstrap.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Yu-Gi-Oh! Joey", color = Color.White, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        Text(s.message, color = if (s.error) Color(0xFFFF8A80) else Color.White,
            fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        when {
            s.needsAllFiles -> Button(onClick = { bootstrap.openAllFilesSettings() }) { Text("Conceder acceso") }
            s.error -> Button(onClick = { bootstrap.run() }) { Text("Reintentar") }
            s.progress >= 0 -> LinearProgressIndicator(
                progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth(0.6f))
            else -> CircularProgressIndicator()
        }
    }
}
