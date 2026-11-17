package com.salar.tv

import androidx.media3.common.C
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Stateful streaming converter for the AI sidecar.
 *
 * Accepts interleaved signed little-endian PCM16 from Media3, downmixes all
 * channels to mono, then linearly resamples to 24 kHz. The last input frame is
 * retained between calls so interpolation remains continuous across chunks.
 * Unsupported PCM encodings are rejected rather than silently misinterpreted.
 */
class SalarPcm16MonoResampler(
    private val targetSampleRateHz: Int = 24_000
) {
    private val pendingSamples = ArrayList<Float>()
    private var inputSampleRateHz = 0
    private var nextOutputPosition = 0.0

    init {
        require(targetSampleRateHz > 0) { "Target sample rate must be positive" }
    }

    @Synchronized
    fun convert(chunk: SalarPcmChunk): SalarPcmChunk? {
        if (chunk.encoding != C.ENCODING_PCM_16BIT) return null
        if (chunk.sampleRateHz <= 0 || chunk.channelCount <= 0) return null

        if (inputSampleRateHz != chunk.sampleRateHz) {
            reset()
            inputSampleRateHz = chunk.sampleRateHz
        }

        val frameSize = chunk.channelCount * 2
        val completeBytes = chunk.pcm.size - (chunk.pcm.size % frameSize)
        if (completeBytes <= 0) return null

        var offset = 0
        while (offset < completeBytes) {
            var sum = 0f
            repeat(chunk.channelCount) {
                val lo = chunk.pcm[offset].toInt() and 0xff
                val hi = chunk.pcm[offset + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                sum += sample / 32768f
                offset += 2
            }
            pendingSamples.add((sum / chunk.channelCount).coerceIn(-1f, 1f))
        }

        if (pendingSamples.size < 2) return null

        val step = inputSampleRateHz.toDouble() / targetSampleRateHz.toDouble()
        val output = ByteArrayOutputStream()
        while (nextOutputPosition + 1.0 < pendingSamples.size.toDouble()) {
            val leftIndex = floor(nextOutputPosition).toInt()
            val fraction = (nextOutputPosition - leftIndex).toFloat()
            val left = pendingSamples[leftIndex]
            val right = pendingSamples[leftIndex + 1]
            val value = (left + (right - left) * fraction).coerceIn(-1f, 1f)
            val pcm16 = (value * 32767f).roundToInt().coerceIn(-32768, 32767)
            output.write(pcm16 and 0xff)
            output.write((pcm16 shr 8) and 0xff)
            nextOutputPosition += step
        }

        // Retain one sample before the next interpolation point.
        val discard = minOf(floor(nextOutputPosition).toInt(), pendingSamples.size - 1)
        if (discard > 0) {
            repeat(discard) { pendingSamples.removeAt(0) }
            nextOutputPosition -= discard.toDouble()
        }

        val bytes = output.toByteArray()
        if (bytes.isEmpty()) return null
        return SalarPcmChunk(
            pcm = bytes,
            sampleRateHz = targetSampleRateHz,
            channelCount = 1,
            encoding = C.ENCODING_PCM_16BIT
        )
    }

    @Synchronized
    fun reset() {
        pendingSamples.clear()
        inputSampleRateHz = 0
        nextOutputPosition = 0.0
    }
}
