package com.emg.wirelessmic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            MicController.update {
                it.copy(message = "Permessi mancanti: microfono e Bluetooth sono obbligatori.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MicScreen(
                        onRequestPermissions = { requestNeededPermissions() },
                        onStart = { startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_START)) },
                        onStop = { startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP)) }
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
fun MicScreen(
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val state by MicController.state.collectAsStateWithLifecycle()
    var permissionsAsked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!permissionsAsked) {
            onRequestPermissions()
            permissionsAsked = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Microfono Wireless BT", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Instradamento via Bluetooth SCO (qualità chiamata, bassa latenza, AEC attivo)",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(32.dp))
        StatusBadge(state.status)

        Spacer(Modifier.height(16.dp))
        if (state.message.isNotBlank()) {
            Text(state.message, fontSize = 13.sp, color = Color.DarkGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
        VuMeter(level = state.level, active = state.status == MicStatus.STREAMING)

        Spacer(Modifier.height(24.dp))
        Row {
            EffectChip("AEC", state.aecActive && state.status == MicStatus.STREAMING)
            Spacer(Modifier.width(8.dp))
            EffectChip("NS", state.nsActive && state.status == MicStatus.STREAMING)
        }

        Spacer(Modifier.weight(1f))

        val isRunning = state.status == MicStatus.STREAMING || state.status == MicStatus.CONNECTING_SCO
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFC62828) else Color(0xFF1565C0)
            )
        ) {
            Text(if (isRunning) "FERMA" else "AVVIA MICROFONO", fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Assicurati che l'altoparlante Bluetooth sia accoppiato e in modalità vivavoce/chiamata prima di avviare.",
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun StatusBadge(status: MicStatus) {
    val (text, color) = when (status) {
        MicStatus.IDLE -> "Inattivo" to Color.Gray
        MicStatus.CONNECTING_SCO -> "Connessione Bluetooth..." to Color(0xFFF9A825)
        MicStatus.STREAMING -> "In streaming" to Color(0xFF2E7D32)
        MicStatus.ERROR_NO_SCO -> "Errore: nessun dispositivo SCO" to Color(0xFFC62828)
        MicStatus.ERROR_AUDIO -> "Errore audio" to Color(0xFFC62828)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EffectChip(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color.LightGray.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (active) Color(0xFF2E7D32) else Color.Gray,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun VuMeter(level: Float, active: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.LightGray.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (active) level.coerceIn(0.02f, 1f) else 0f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (level > 0.85f) Color(0xFFC62828)
                    else if (level > 0.6f) Color(0xFFF9A825)
                    else Color(0xFF2E7D32)
                )
        )
    }
}
