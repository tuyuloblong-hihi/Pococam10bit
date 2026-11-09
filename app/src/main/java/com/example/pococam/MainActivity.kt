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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)
private val Amber = Color(0xFFFFCC00)
private val Glass = Color(0x99000000)

private fun toLog(t: Float, min: Double, max: Double) =
    Math.exp(Math.log(min) + t * (Math.log(max) - Math.log(min)))

private fun fromLog(v: Double, min: Double, max: Double) =
    ((Math.log(v) - Math.log(min)) / (Math.log(max) - Math.log(min))).toFloat().coerceIn(0f, 1f)

@Composable
fun Pill(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Amber else Glass)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun ProRow(
    label: String, valueText: String, auto: Boolean, autoAvailable: Boolean,
    onAuto: () -> Unit, value: Float, onValue: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        Slider(
            value = value,
            onValueChange = onValue,
            enabled = !auto && autoAvailable,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber)
        )
        Text(
            if (auto) "AUTO" else valueText,
            color = Color.White, fontSize = 12.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp)
        )
        Pill("A", selected = auto, enabled = autoAvailable) { onAuto() }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val controller = remember { CameraController(context.applicationContext).also { it.load() } }
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var surface by remember { mutableStateOf<Surface?>(null) }

    var isRecording by remember { mutableStateOf(false) }
    var want10Bit by remember { mutableStateOf(true) }
    var recInfo by remember { mutableStateOf<RecInfo?>(null) }
    var seconds by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    var mode by remember { mutableStateOf(controller.mode) }
    var proOpen by remember { mutableStateOf(false) }
    var pro by remember { mutableStateOf(ProSettings()) }

    fun setPro(p: ProSettings) { pro = p; controller.updatePro(p) }

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

    // Teks status HLG10 / 10-bit
    val reason = controller.hdrReason()
    val active10 = recInfo?.active10Bit == true
    val hdrText = when {
        isRecording -> if (active10) "● HLG10 · HEVC 10-bit AKTIF" else "● SDR · HEVC 8-bit"
        !want10Bit -> "SDR · HEVC 8-bit (dipilih)"
        reason == null -> "HLG10 siap · HEVC 10-bit"
        else -> "Turun ke SDR 8-bit: $reason"
    }
    val hdrColor = when {
        isRecording && active10 -> Green
        isRecording -> Amber
        want10Bit && reason == null -> Green
        else -> Amber
    }

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

        // Status atas tengah: REC/mode + status HLG10
        Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecording) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Red))
                    Spacer(Modifier.width(8.dp))
                    Text("REC %02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    Text(mode.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                hdrText, color = hdrColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 12.dp, vertical = 4.dp)
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Red, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }

        // Kiri atas: tombol PRO
        Box(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Pill("PRO", selected = proOpen, enabled = controller.proSupported) { proOpen = !proOpen }
        }

        // Kanan atas: switch 10-bit
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("10-bit", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = want10Bit,
                onCheckedChange = { if (!isRecording) want10Bit = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Green)
            )
        }

        // Bawah: panel PRO + pemilih mode
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).fillMaxWidth(0.62f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (proOpen && controller.proSupported) {
                val isoMin = controller.isoRange.lower.toDouble().coerceAtLeast(50.0)
                val isoMax = controller.isoRange.upper.toDouble()
                val expMin = controller.expRange.lower.toDouble().coerceAtLeast(125_000.0)
                val expMax = minOf(controller.expRange.upper.toDouble(), 1e9 / mode.fps)

                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Glass).padding(10.dp)
                ) {
                    ProRow(
                        "ISO", "${pro.iso}", pro.autoExposure, true,
                        onAuto = {
                            if (pro.autoExposure) {
                                setPro(pro.copy(autoExposure = false,
                                    iso = controller.liveIso.coerceIn(isoMin.toInt(), isoMax.toInt()),
                                    shutterNs = controller.liveExp.toDouble().coerceIn(expMin, expMax).toLong()))
                            } else setPro(pro.copy(autoExposure = true))
                        },
                        value = fromLog(pro.iso.toDouble().coerceIn(isoMin, isoMax), isoMin, isoMax),
                        onValue = { setPro(pro.copy(iso = toLog(it, isoMin, isoMax).roundToInt())) }
                    )
                    ProRow(
                        "SHUTTER", "1/${(1e9 / pro.shutterNs).roundToInt()}", pro.autoExposure, true,
                        onAuto = {
                            if (pro.autoExposure) {
                                setPro(pro.copy(autoExposure = false,
                                    iso = controller.liveIso.coerceIn(isoMin.toInt(), isoMax.toInt()),
                                    shutterNs = controller.liveExp.toDouble().coerceIn(expMin, expMax).toLong()))
                            } else setPro(pro.copy(autoExposure = true))
                        },
                        value = fromLog(pro.shutterNs.toDouble().coerceIn(expMin, expMax), expMin, expMax),
                        onValue = { setPro(pro.copy(shutterNs = toLog(it, expMin, expMax).toLong())) }
                    )
                    ProRow(
                        "WB", "${pro.kelvin}K", pro.autoWb, controller.wbSupported,
                        onAuto = { setPro(pro.copy(autoWb = !pro.autoWb)) },
                        value = (pro.kelvin - 2500) / 6500f,
                        onValue = { setPro(pro.copy(kelvin = (2500 + it * 6500).roundToInt())) }
                    )
                    if (!controller.wbSupported) {
                        Text("WB manual tidak didukung kamera ini", color = Amber, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Pemilih resolusi & fps
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controller.modes.forEach { m ->
                    Pill(m.label, selected = m == mode, enabled = !isRecording) {
                        mode = m; controller.mode = m; error = null
                        // Slider shutter dibatasi 1/fps, sesuaikan jika perlu
                        val maxNs = (1e9 / m.fps).toLong()
                        if (pro.shutterNs > maxNs) setPro(pro.copy(shutterNs = maxNs))
                    }
                }
            }
        }

        // Tombol Record
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).size(76.dp)
                .border(4.dp, Color.White, CircleShape).padding(7.dp)
                .clickable(enabled = granted) {
                    if (!isRecording) {
                        controller.mode = mode
                        val info = controller.startRecording(want10Bit)
                        if (info != null) { recInfo = info; isRecording = true; error = null }
                        else error = "Gagal memulai ${mode.label}. Coba mode lain."
                    } else {
                        controller.stopRecording()
                        isRecording = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val s: Dp = if (isRecording) 28.dp else 62.dp
            val shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape
            Box(Modifier.size(s).clip(shape).background(Red))
        }
    }
}
