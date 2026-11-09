
package com.example.pococam

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface

class HevcRecorder(
    private val context: Context,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC

        fun isMain10EncoderAvailable(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.contains(MIME) &&
                    info.getCapabilitiesForType(MIME).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
            }
        }.getOrDefault(false)
    }

    var inputSurface: Surface? = null
        private set
    var hasAudio = false
        private set

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val lock = Object()
    @Volatile private var recording = false
    @Volatile private var stopped = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private fun videoFormat(size: Size, fps: Int, bitrate: Int, tenBit: Boolean, extras: Boolean) =
        MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (extras) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            }
            if (tenBit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }

    fun prepare(size: Size, fps: Int, bitrate: Int, tenBit: Boolean, orientationHint: Int) {
        // --- Video (coba dengan operating rate, jika ditolak ulangi tanpa itu) ---
        var codec = MediaCodec.createEncoderByType(MIME)
        try {
            codec.configure(videoFormat(size, fps, bitrate, tenBit, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            runCatching { codec.release() }
            codec = MediaCodec.createEncoderByType(MIME)
            videoCodec = codec
            codec.configure(videoFormat(size, fps, bitrate, tenBit, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoCodec = codec
        inputSurface = codec.createInputSurface()

        // --- Audio (opsional: jika gagal, rekam video saja) ---
        val sr = 48000
        for (src in intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) throw IllegalStateException("buffer audio tidak valid")
                val ar = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
                if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); throw IllegalStateException("AudioRecord gagal") }
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
                }
                val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioRecord = ar
                audioCodec = ac
                hasAudio = true
                break
            } catch (e: Exception) {
                audioRecord = null; audioCodec = null; hasAudio = false
            }
        }

        // --- Muxer -> MediaStore (Movies/PocoCam) ---
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "POCO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PocoCam")
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore menolak membuat file")
        pfd = context.contentResolver.openFileDescriptor(uri!!, "w")
            ?: throw IllegalStateException("Tidak bisa membuka file output")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
            it.setOrientationHint(orientationHint)
        }
    }

    fun start() {
        recording = true
        videoCodec!!.start()
        audioCodec?.start()
        videoThread = Thread({ videoLoop() }, "video-enc").also { it.start() }
        if (hasAudio) audioThread = Thread({ audioLoop() }, "audio-enc").also { it.start() }
    }

    private fun fail(where: String, e: Throwable) {
        recording = false
        onError("$where: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
    }

    private fun videoLoop() {
        try {
            val vc = videoCodec!!
            val info = MediaCodec.BufferInfo()
            while (recording) {
                drain(vc, info, true, false)
                Thread.sleep(4)
            }
            vc.signalEndOfInputStream()
            drain(vc, info, true, true)
        } catch (e: Throwable) { fail("Encoder video", e) }
    }

    private fun audioLoop() {
        try {
            val ac = audioCodec!!
            val ar = audioRecord!!
            val info = MediaCodec.BufferInfo()
            ar.startRecording()
            while (recording) {
                val i = ac.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = ac.getInputBuffer(i)!!
                    buf.clear()
                    val n = ar.read(buf, buf.capacity())
                    val pts = SystemClock.elapsedRealtimeNanos() / 1000
                    ac.queueInputBuffer(i, 0, maxOf(n, 0), pts, 0)
                }
                drain(ac, info, false, false)
            }
            runCatching { ar.stop() }
            val i = ac.dequeueInputBuffer(100_000)
            if (i >= 0) {
                ac.queueInputBuffer(i, 0, 0, SystemClock.elapsedRealtimeNanos() / 1000,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(ac, info, false, true)
        } catch (e: Throwable) {
            // audio gagal: lanjut tanpa menjatuhkan rekaman video
            synchronized(lock) { hasAudio = false; tryStartMuxer() }
        }
    }

    private fun tryStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (!hasAudio || audioTrack >= 0)) {
            muxer!!.start(); muxerStarted = true
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, untilEos: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos || SystemClock.elapsedRealtime() > deadline) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    if (!muxerStarted) {
                        val t = muxer!!.addTrack(codec.outputFormat)
                        if (isVideo) videoTrack = t else audioTrack = t
                        tryStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0) synchronized(lock) {
                        if (muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            runCatching {
                                muxer!!.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!untilEos) return
            }
        }
    }

    /** Aman dipanggil kapan saja, termasuk jika prepare() gagal di tengah jalan. */
    fun stop() {
        if (stopped) return
        stopped = true
        recording = false
        runCatching { videoThread?.join(4000) }
        runCatching { audioThread?.join(4000) }
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        runCatching { audioRecord?.release() }
        runCatching { inputSurface?.release() }
        synchronized(lock) {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        runCatching { pfd?.close() }
        if (!muxerStarted) {
            uri?.let { u -> runCatching { context.contentResolver.delete(u, null, null) } }
        }
    }
}
