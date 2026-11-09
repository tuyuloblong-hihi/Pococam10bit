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

class HevcRecorder(private val context: Context) {

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC

        fun isMain10EncoderAvailable(): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.contains(MIME) &&
                    info.getCapabilitiesForType(MIME).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
            }
    }

    var inputSurface: Surface? = null
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
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun prepare(size: Size, fps: Int, bitrate: Int, tenBit: Boolean, orientationHint: Int) {
        // --- Video ---
        val vf = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (tenBit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }
        videoCodec = MediaCodec.createEncoderByType(MIME).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = it.createInputSurface()
        }

        // --- Audio (AAC mono 48 kHz) ---
        val sr = 48000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        // --- Muxer -> MediaStore (Movies/PocoCam) ---
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "POCO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PocoCam")
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        pfd = context.contentResolver.openFileDescriptor(uri!!, "w")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
            it.setOrientationHint(orientationHint)
        }
    }

    fun start() {
        recording = true
        videoCodec!!.start()
        audioCodec!!.start()
        videoThread = Thread({ videoLoop() }, "video-enc").also { it.start() }
        audioThread = Thread({ audioLoop() }, "audio-enc").also { it.start() }
    }

    private fun videoLoop() {
        val vc = videoCodec!!
        val info = MediaCodec.BufferInfo()
        while (recording) {
            drain(vc, info, true, false)
            Thread.sleep(4)
        }
        vc.signalEndOfInputStream()
        drain(vc, info, true, true)
    }

    private fun audioLoop() {
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
        ar.stop()
        var i = ac.dequeueInputBuffer(100_000)
        if (i >= 0) {
            ac.queueInputBuffer(i, 0, 0, SystemClock.elapsedRealtimeNanos() / 1000,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(ac, info, false, true)
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, untilEos: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val t = muxer!!.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = t else audioTrack = t
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer!!.start(); muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) synchronized(lock) {
                        if (muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!untilEos) return
            }
        }
    }

    fun stop() {
        if (!recording) return
        recording = false
        videoThread?.join()
        audioThread?.join()
        runCatching { videoCodec?.stop() }; videoCodec?.release()
        runCatching { audioCodec?.stop() }; audioCodec?.release()
        audioRecord?.release()
        inputSurface?.release()
        synchronized(lock) {
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
        }
        pfd?.close()
    }
}
