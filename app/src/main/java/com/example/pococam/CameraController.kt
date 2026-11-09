package com.example.pococam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
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

class CameraController(private val context: Context) {

    private val TAG = "PocoCam"
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var cameraId: String? = null
    private var hlgSupported = false
    private var sensorOrientation = 90
    private var videoSize = Size(1920, 1080)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var recorder: HevcRecorder? = null
    private val fps = 30

    private fun pickCamera() {
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
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(MediaCodec::class.java)
        videoSize = if (sizes.contains(Size(3840, 2160))) Size(3840, 2160) else Size(1920, 1080)
    }

    @SuppressLint("MissingPermission")
    fun open(previewSurface: Surface) {
        preview = previewSurface
        if (cameraId == null) pickCamera()
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

    private fun startPreview() {
        val dev = device ?: return
        val p = preview ?: return
        createSession(listOf(p), false) { s ->
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(p)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        }
    }

    /** Return label mode yang dipakai, atau null jika gagal. */
    fun startRecording(want10Bit: Boolean): String? {
        val dev = device ?: return null
        val p = preview ?: return null
        val hlg = want10Bit && hlgSupported && HevcRecorder.isMain10EncoderAvailable()
        return try {
            val rec = HevcRecorder(context)
            val bitrate = if (videoSize.width >= 3840) 50_000_000 else 20_000_000
            // Asumsi UI landscape (ROTATION_90)
            val hint = (sensorOrientation - 90 + 360) % 360
            rec.prepare(videoSize, fps, bitrate, hlg, hint)
            recorder = rec
            val rs = rec.inputSurface!!
            createSession(listOf(p, rs), hlg) { s ->
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(p); addTarget(rs)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                }
                rec.start()
                s.setRepeatingRequest(req.build(), null, handler)
            }
            "${videoSize.width}x${videoSize.height} HEVC " + if (hlg) "10-bit HLG" else "8-bit"
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e); recorder?.stop(); recorder = null; null
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
}
