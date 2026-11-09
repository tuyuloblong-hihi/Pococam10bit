package com.example.pococam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

data class VideoMode(val width: Int, val height: Int, val fps: Int) {
    val label: String get() = (if (height >= 2160) "4K" else "${height}p") + " ${fps}fps"
    val bitrate: Int
        get() = when {
            height >= 2160 && fps >= 60 -> 80_000_000
            height >= 2160 -> 50_000_000
            fps >= 60 -> 30_000_000
            else -> 20_000_000
        }
}

data class ProSettings(
    val autoExposure: Boolean = true,
    val iso: Int = 100,
    val shutterNs: Long = 16_666_666L,
    val autoWb: Boolean = true,
    val kelvin: Int = 5500
)

data class RecInfo(val active10Bit: Boolean, val summary: String)

class CameraController(private val context: Context) {

    private val TAG = "PocoCam"
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var cameraId: String? = null
    private var hlgSupported = false
    private var sensorOrientation = 90
    private var fpsRanges: List<Range<Int>> = emptyList()

    // Kapabilitas yang dibaca UI
    var modes: List<VideoMode> = listOf(VideoMode(1920, 1080, 30)); private set
    var mode: VideoMode = modes.first()
    var proSupported = false; private set
    var wbSupported = false; private set
    var isoRange: Range<Int> = Range(100, 3200); private set
    var expRange: Range<Long> = Range(125_000L, 33_333_333L); private set

    // Nilai auto terakhir (untuk titik awal slider saat pindah ke manual)
    @Volatile var liveIso = 100; private set
    @Volatile var liveExp = 16_666_666L; private set
    @Volatile private var autoGains: RggbChannelVector? = null
    @Volatile private var ccm: ColorSpaceTransform? = null

    @Volatile private var pro = ProSettings()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var recorder: HevcRecorder? = null

    private var activeTemplate = CameraDevice.TEMPLATE_PREVIEW
    private var activeTargets: List<Surface> = emptyList()
    private var activeFps = 30

    /** Panggil sekali (tidak butuh izin kamera). */
    fun load() {
        if (cameraId != null) return
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            if (fallback == null) fallback = id
            if (Build.VERSION.SDK_INT >= 33) {
                val p = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                if (p != null && p.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) {
                    cameraId = id; hlgSupported = true; break
                }
            }
        }
        if (cameraId == null) cameraId = fallback
        val chars = manager.getCameraCharacteristics(cameraId!!)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        proSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        wbSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }
        fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaCodec::class.java).toList()
        val found = listOf(
            Triple(3840, 2160, 30), Triple(3840, 2160, 60),
            Triple(1920, 1080, 60), Triple(1920, 1080, 30)
        ).mapNotNull { (w, h, f) ->
            val s = Size(w, h)
            if (!sizes.contains(s)) return@mapNotNull null
            val d = map.getOutputMinFrameDuration(
