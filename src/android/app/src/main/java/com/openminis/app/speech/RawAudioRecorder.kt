package com.openminis.app.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * Captures microphone PCM without routing through SpeechRecognizer.
 * The resulting 16 kHz mono WAV is suitable for pronunciation analysis and
 * for providers that accept OpenAI-style input_audio with format=wav.
 */
class RawAudioRecorder(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var outputFile: File? = null
    @Volatile private var recording = false

    val isRecording: Boolean get() = recording

    @Synchronized
    fun start(): File {
        check(!recording) { "Already recording" }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Microphone format is not supported" }
        val bufferSize = max(minBuffer, SAMPLE_RATE * 2)
        val dir = File(context.filesDir, "voice-staging").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "Unable to initialize microphone"
        }

        try {
            RandomAccessFile(file, "rw").use { wav ->
                writeWavHeader(wav, 0)
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                file.delete()
                error("Microphone did not start")
            }
        } catch (t: Throwable) {
            runCatching { record.release() }
            file.delete()
            throw t
        }

        audioRecord = record
        outputFile = file
        recording = true
        worker = Thread {
            val buffer = ByteArray(bufferSize)
            var pcmBytes = 0L
            try {
                RandomAccessFile(file, "rw").use { wav ->
                    wav.seek(44)
                    while (recording) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            wav.write(buffer, 0, read)
                            pcmBytes += read
                        }
                    }
                    wav.seek(0)
                    writeWavHeader(wav, pcmBytes)
                }
            } catch (_: Throwable) {
                // stop()/cancel() owns cleanup; a partially written file is
                // rejected by stop() when it has no valid PCM payload.
            }
        }.apply {
            name = "MinisRawAudioRecorder"
            start()
        }
        return file
    }

    @Synchronized
    fun stop(): File? {
        if (!recording) return null
        recording = false
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { worker?.join(2_000) }
        worker = null
        val file = outputFile
        outputFile = null
        return file?.takeIf { it.exists() && it.length() > 44 }
            ?.also { patchWavSize(it) }
            ?: run { file?.delete(); null }
    }

    @Synchronized
    fun cancel() {
        if (!recording) {
            outputFile?.delete()
            outputFile = null
            return
        }
        recording = false
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { worker?.join(2_000) }
        worker = null
        outputFile?.delete()
        outputFile = null
    }

    private fun patchWavSize(file: File) {
        runCatching {
            RandomAccessFile(file, "rw").use { wav ->
                val dataSize = (wav.length() - 44).coerceAtLeast(0)
                wav.seek(4)
                writeLeInt(wav, (36 + dataSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                wav.seek(40)
                writeLeInt(wav, dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
    }

    private fun writeWavHeader(wav: RandomAccessFile, dataSize: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        wav.writeBytes("RIFF")
        writeLeInt(wav, (36 + dataSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        wav.writeBytes("WAVE")
        wav.writeBytes("fmt ")
        writeLeInt(wav, 16)
        writeLeShort(wav, 1)
        writeLeShort(wav, CHANNELS)
        writeLeInt(wav, SAMPLE_RATE)
        writeLeInt(wav, byteRate)
        writeLeShort(wav, blockAlign)
        writeLeShort(wav, BITS_PER_SAMPLE)
        wav.writeBytes("data")
        writeLeInt(wav, dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun writeLeInt(wav: RandomAccessFile, value: Int) {
        wav.write(value and 0xff)
        wav.write((value ushr 8) and 0xff)
        wav.write((value ushr 16) and 0xff)
        wav.write((value ushr 24) and 0xff)
    }

    private fun writeLeShort(wav: RandomAccessFile, value: Int) {
        wav.write(value and 0xff)
        wav.write((value ushr 8) and 0xff)
    }
}
