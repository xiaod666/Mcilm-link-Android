package wasai.link.mcilm.top.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import wasai.link.mcilm.top.link.MciLmNative
import kotlin.math.max

/**
 * MciLm-link 语音引擎宿主侧实现。
 * 格式恒定：16kHz / 单声道 / 16-bit LE PCM。
 */
object AudioEngine {
    private const val TAG = "AudioEngine"
    private const val SAMPLE_RATE = 16000
    private const val FRAME_BYTES = 640   // 20ms * 16000Hz * 2byte
    private const val BUFFER_FRAMES = 20  // 约 400ms 缓冲

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val recBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (recBuf <= 0) {
                Log.e(TAG, "invalid AudioRecord minBuf=$recBuf")
                return false
            }
            val recTotal = max(recBuf, FRAME_BYTES * BUFFER_FRAMES)

            // 【兼容性优化】尝试 VOICE_COMMUNICATION，失败则降级为 MIC
            var record = createAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recTotal)
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                MciLmNative.logApp("VOICE_COMMUNICATION 初始化失败，尝试降级使用 MIC 源")
                record?.release()
                record = createAudioRecord(MediaRecorder.AudioSource.MIC, recTotal)
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                MciLmNative.logApp("AudioRecord 彻底初始化失败，无法开启语音")
                record?.release()
                return false
            }
            record.startRecording()
            audioRecord = record

            val trackBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (trackBuf <= 0) {
                Log.e(TAG, "invalid AudioTrack minBuf=$trackBuf")
                stop()
                return false
            }
            val trackTotal = max(trackBuf, FRAME_BYTES * BUFFER_FRAMES)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackTotal)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack state=${track.state}")
                track.release()
                stop()
                return false
            }
            track.play()
            audioTrack = track

            running = true
            Log.d(TAG, "AudioEngine started (16k/mono/16bit)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            stop()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createAudioRecord(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        Log.d(TAG, "AudioEngine stopped")
    }

    fun isRunning(): Boolean = running

    @JvmStatic
    fun onAudioRead(buf: ByteArray): Int {
        val rec = audioRecord ?: return -1
        if (!running) return -1
        return try {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) n else -1
        } catch (t: Throwable) {
            Log.e(TAG, "onAudioRead failed", t)
            -1
        }
    }

    @JvmStatic
    fun onAudioWrite(buf: ByteArray, len: Int): Int {
        val track = audioTrack ?: return 0
        if (!running) return 0
        val safeLen = minOf(len, buf.size)
        if (safeLen <= 0) return 0
        return try {
            val written = track.write(buf, 0, safeLen, AudioTrack.WRITE_BLOCKING)
            if (written > 0) written else 0
        } catch (t: Throwable) {
            Log.e(TAG, "onAudioWrite failed", t)
            0
        }
    }
}