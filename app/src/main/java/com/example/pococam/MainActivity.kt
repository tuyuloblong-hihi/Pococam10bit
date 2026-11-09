package com.example.pococam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CameraScreen() }
    }
}

private fun hasPermissions(c: Context) =
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val controller = remember { CameraController(context.applicationContext) }
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var want10Bit by remember { mutableStateOf(true) }
    var modeLabel by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> granted = r.values.all { it } }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(granted, surface) {
        val s = surface
        if (granted && s != null) controller.open(s)
    }
    LaunchedEffect(isRecording) {
        seconds = 0
        while (isRecording) { delay(1000); seconds++ }
    }
    DisposableEffect(Unit) { onDispose { controller.close() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(16f / 9f),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFixedSize(1920, 1080)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { surface = null; controller.close() }
                    })
                }
            }
        )

        // Indikator status
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                .clip(RoundedCornerShape(50)).background(Color(0x66000000))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Spacer(Modifier.width(8.dp))
                Text("REC %02d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            } else {
                Text(modeLabel ?: if (want10Bit) "HEVC 10-bit" else "HEVC 8-bit",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Switch 10-bit
        Row(Modifier.align(Alignment.TopEnd).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("10-bit", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = want10Bit,
                onCheckedChange = { if (!isRecording) want10Bit = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759))
            )
        }

        // Tombol Record
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).size(76.dp)
                .border(4.dp, Color.White, CircleShape).padding(7.dp)
                .clickable(enabled = granted) {
                    if (!isRecording) {
                        modeLabel = controller.startRecording(want10Bit)
                        isRecording = modeLabel != null
                    } else {
                        controller.stopRecording()
                        isRecording = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val s = if (isRecording) 28.dp else 62.dp
            val shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape
            Box(Modifier.size(s).clip(shape).background(Color(0xFFFF3B30)))
        }
    }
}
