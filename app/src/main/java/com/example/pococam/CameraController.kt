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
            val d = map.getOutputMinFrameDuration(MediaCodec::class.java, s)
            val maxFps = if (d > 0) 1_000_000_000.0 / d else 30.0
            if (maxFps + 0.5 < f) return@mapNotNull null
            if (fpsRanges.none { it.lower <= f && it.upper >= f }) return@mapNotNull null
            VideoMode(w, h, f)
        }
        if (found.isNotEmpty()) { modes = found; mode = found.first() }
    }

    /** null = HLG10 bisa dipakai; selain itu alasan kenapa tidak. */
    fun hdrReason(): String? = when {
        Build.VERSION.SDK_INT < 33 -> "Android di bawah 13"
        !hlgSupported -> "kamera tidak mengekspos HLG10"
        !HevcRecorder.isMain10EncoderAvailable() -> "encoder HEVC Main10 tidak tersedia"
        else -> null
    }

    private fun rangeFor(fps: Int): Range<Int>? =
        fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: fpsRanges.firstOrNull { it.upper >= fps }

    @SuppressLint("MissingPermission")
    fun open(previewSurface: Surface) {
        preview = previewSurface
        load()
        manager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; startPreview() }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error $error"); camera.close()
            }
        }, handler)
    }

    private fun createSession(surfaces: List<Surface>, hlg: Boolean, onReady: (CameraCaptureSession) -> Unit) {
        val dev = device ?: return
        val configs = surfaces.map { s ->
            OutputConfiguration(s).also {
                if (hlg && Build.VERSION.SDK_INT >= 33) it.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }
        }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; onReady(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "Session configure failed") }
        }
        dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, cb))
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val p = pro
            if (p.autoExposure) {
                res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExp = it }
            }
            if (p.autoWb) res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { autoGains = it }
            res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { ccm = it }
        }
    }

    private fun buildRequest(): CaptureRequest {
        val b = device!!.createCaptureRequest(activeTemplate)
        activeTargets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        rangeFor(activeFps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        val p = pro
        if (!p.autoExposure && proSupported) {
            val frameNs = 1_000_000_000L / activeFps
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, p.iso.coerceIn(isoRange.lower, isoRange.upper))
            b.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                p.shutterNs.coerceIn(expRange.lower, expRange.upper).coerceAtMost(frameNs)
            )
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        }
        if (!p.autoWb && wbSupported) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsFor(p.kelvin))
            ccm?.let { b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        }
        return b.build()
    }

    /** Terapkan pengaturan PRO ke request yang sedang berjalan (preview maupun rekam). */
    fun updatePro(p: ProSettings) {
        pro = p
        handler.post {
            runCatching { session?.setRepeatingRequest(buildRequest(), captureCb, handler) }
                .onFailure { Log.e(TAG, "updatePro gagal", it) }
        }
    }

    private fun startPreview() {
        val p = preview ?: return
        activeTemplate = CameraDevice.TEMPLATE_PREVIEW
        activeTargets = listOf(p)
        activeFps = 30
        createSession(listOf(p), false) { s ->
            s.setRepeatingRequest(buildRequest(), captureCb, handler)
        }
    }

    fun startRecording(want10Bit: Boolean): RecInfo? {
        val p = preview ?: return null
        if (device == null) return null
        val hlg = want10Bit && hdrReason() == null
        val m = mode
        return try {
            val rec = HevcRecorder(context)
            val hint = (sensorOrientation - 90 + 360) % 360 // asumsi landscape
            rec.prepare(Size(m.width, m.height), m.fps, m.bitrate, hlg, hint)
            recorder = rec
            val rs = rec.inputSurface!!
            activeTemplate = CameraDevice.TEMPLATE_RECORD
            activeTargets = listOf(p, rs)
            activeFps = m.fps
            createSession(listOf(p, rs), hlg) { s ->
                rec.start()
                s.setRepeatingRequest(buildRequest(), captureCb, handler)
            }
            RecInfo(hlg, "${m.label} · HEVC " + if (hlg) "10-bit HLG" else "8-bit SDR")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording gagal", e)
            recorder?.stop(); recorder = null
            null
        }
    }

    fun stopRecording() {
        runCatching { session?.stopRepeating() }
        recorder?.stop()
        recorder = null
        startPreview()
    }

    fun close() {
        recorder?.stop(); recorder = null
        session?.close(); session = null
        device?.close(); device = null
    }

    // --- White balance: estimasi gain relatif terhadap nilai auto saat AWB dimatikan ---
    private fun gainsFor(k: Int): RggbChannelVector {
        val base = autoGains ?: RggbChannelVector(2.0f, 1f, 1f, 1.8f)
        val ref = kelvinRgb(5500)
        val t = kelvinRgb(k)
        val fr = (ref[0] / t[0]) / (ref[1] / t[1])
        val fb = (ref[2] / t[2]) / (ref[1] / t[1])
        return RggbChannelVector(base.red * fr, base.greenEven, base.greenOdd, base.blue * fb)
    }

    private fun kelvinRgb(k: Int): FloatArray {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * Math.pow(t - 60.0, -0.1332047592)
        val g = if (t <= 66) 99.4708025861 * Math.log(t) - 161.1195681661
        else 288.1221695283 * Math.pow(t - 60.0, -0.0755148492)
        val b = if (t >= 66) 255.0 else if (t <= 19) 1.0
        else 138.5177312231 * Math.log(t - 10.0) - 305.0447927307
        fun n(v: Double) = (v / 255.0).coerceIn(0.02, 1.0).toFloat()
        return floatArrayOf(n(r), n(g), n(b))
    }
}
