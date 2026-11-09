package com.example.pococam

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CameraScreen() }
    }
}

@Composable
fun CameraScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var hevc10Bit by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* TODO: cek hasil izin */ }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // TODO: pasang preview Camera2 (TextureView/SurfaceView) di sini

        // Indikator status (atas)
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x66000000))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Spacer(Modifier.width(8.dp))
                Text("REC 00:00", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            } else {
                Text(
                    if (hevc10Bit) "HEVC 10-bit" else "HEVC 8-bit",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
            }
        }

        // Switch 10-bit (kanan atas)
        Row(
            Modifier.align(Alignment.TopEnd).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("10-bit", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = hevc10Bit,
                onCheckedChange = { if (!isRecording) hevc10Bit = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759))
            )
        }

        // Tombol Record besar ala iOS (kanan tengah, mode landscape)
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(7.dp)
                .clickable { isRecording = !isRecording },
            contentAlignment = Alignment.Center
        ) {
            val size = if (isRecording) 28.dp else 62.dp
            val shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape
            Box(Modifier.size(size).clip(shape).background(Color(0xFFFF3B30)))
        }
    }
}
