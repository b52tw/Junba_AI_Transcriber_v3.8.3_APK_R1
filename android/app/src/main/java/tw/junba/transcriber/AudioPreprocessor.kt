package tw.junba.transcriber

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local-only audio preparation for whisper-android.
 *
 * whisper-android already decodes/resamples WAV/MP3/FLAC internally, so those
 * formats bypass conversion entirely.  Other common Android audio containers
 * (M4A/AAC/OGG/Opus/MP4) are decoded with the platform MediaCodec stack to a
 * PCM16 WAV.  No FFmpegKit dependency is required.
 */
object AudioPreprocessor {
    data class PreparedAudio(val file: File, val temporary: Boolean)

    @JvmStatic
    fun prepareForWhisper(context: Context, inputPath: String): PreparedAudio {
        val input = File(inputPath)
        require(input.isFile) { "找不到音檔：$inputPath" }
        val n = input.name.lowercase()
        if (n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".flac")) {
            return PreparedAudio(input, false)
        }
        return PreparedAudio(decodeWithMediaCodec(context, input), true)
    }

    private fun decodeWithMediaCodec(context: Context, input: File): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = File(context.cacheDir, "jba_pcm_${System.nanoTime()}.wav")
        try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) error("找不到可解碼的音訊軌")
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("音訊格式缺少 MIME")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val raf = RandomAccessFile(out, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44)) // reserve WAV header
            var pcmBytes = 0L

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex) ?: error("無法取得解碼輸入緩衝區")
                            inBuf.clear()
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && info.size > 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val written = writePcm16(raf, outBuf.slice().order(ByteOrder.nativeOrder()), pcmEncoding)
                                pcmBytes += written
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }

                if (pcmBytes <= 0L) error("Android 原生解碼沒有產生 PCM 音訊")
                writeWavHeader(raf, sampleRate, channels.coerceAtLeast(1), pcmBytes)
            } finally {
                raf.close()
            }
            if (!out.isFile || out.length() <= 44L) error("音訊轉換失敗")
            return out
        } catch (t: Throwable) {
            out.delete()
            throw IllegalStateException("Android 原生音訊解碼失敗：${t.message ?: t::class.java.simpleName}", t)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun writePcm16(raf: RandomAccessFile, src: ByteBuffer, encoding: Int): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = src.remaining() / 4
                val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) {
                    val f = src.float.coerceIn(-1.0f, 1.0f)
                    out.putShort((f * 32767.0f).toInt().toShort())
                }
                val bytes = out.array()
                raf.write(bytes)
                bytes.size
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val out = ByteArray(src.remaining() * 2)
                var p = 0
                while (src.hasRemaining()) {
                    val u = src.get().toInt() and 0xFF
                    val s = ((u - 128) shl 8).toShort().toInt()
                    out[p++] = (s and 0xFF).toByte()
                    out[p++] = ((s ushr 8) and 0xFF).toByte()
                }
                raf.write(out)
                out.size
            }
            else -> {
                // Android audio decoders normally emit PCM16 when no float mode is requested.
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                raf.write(bytes)
                bytes.size
            }
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, sampleRate: Int, channels: Int, pcmBytes: Long) {
        val byteRate = sampleRate.toLong() * channels * 2L
        val blockAlign = channels * 2
        val riffSize = pcmBytes + 36L
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt(riffSize.coerceAtMost(0xFFFFFFFFL).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(byteRate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        h.putShort(blockAlign.toShort())
        h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(pcmBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        raf.seek(0)
        raf.write(h.array())
    }
}
