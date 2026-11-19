package com.salar.tv

import androidx.media3.common.C
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Stateful streaming converter for the AI sidecar.
 *
 * Accepts interleaved signed little-endian PCM16 from Media3, downmixes all
 * channels to mono, then linearly resamples to 24 kHz. One input sample is
 * retained between calls so interpolation remains continuous across chunks.
 * Unsupported PCM encodings are rejected rather than silently misinterpreted.
 *
 * The backing buffer uses a moving read head and compacts at most once per
 * input chunk. This avoids ArrayList.removeAt(0), which shifted the remaining
 * samples repeatedly and made large audio chunks quadratic-time.
 */
class SalarPcm16MonoResampler(
    private val targetSampleRateHz: Int = 24_000
) {
    private var samples = FloatArray(4096)
    private var head = 0
    private var sampleCount = 0
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

        val incomingFrames = completeBytes / frameSize
        ensureCapacity(sampleCount + incomingFrames)

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
            samples[head + sampleCount] =
                (sum / chunk.channelCount).coerceIn(-1f, 1f)
            sampleCount++
        }

        if (sampleCount < 2) return null

        val step = inputSampleRateHz.toDouble() / targetSampleRateHz.toDouble()
        val output = ByteArrayOutputStream()
        while (nextOutputPosition + 1.0 < sampleCount.toDouble()) {
            val leftIndex = floor(nextOutputPosition).toInt()
            val fraction = (nextOutputPosition - leftIndex).toFloat()
            val left = samples[head + leftIndex]
            val right = samples[head + leftIndex + 1]
            val value = (left + (right - left) * fraction).coerceIn(-1f, 1f)
            val pcm16 = (value * 32767f).roundToInt().coerceIn(-32768, 32767)
            output.write(pcm16 and 0xff)
            output.write((pcm16 shr 8) and 0xff)
            nextOutputPosition += step
        }

        // Keep one sample before the next interpolation point for continuity.
        val discard = minOf(floor(nextOutputPosition).toInt(), sampleCount - 1)
        if (discard > 0) {
            head += discard
            sampleCount -= discard
            nextOutputPosition -= discard.toDouble()
        }
        compactIfNeeded()

        val bytes = output.toByteArray()
        if (bytes.isEmpty()) return null
        return SalarPcmChunk(
            pcm = bytes,
            sampleRateHz = targetSampleRateHz,
            channelCount = 1,
            encoding = C.ENCODING_PCM_16BIT
        )
    }

    private fun ensureCapacity(required: Int) {
        if (head + required <= samples.size) return
        if (required <= samples.size) {
            System.arraycopy(samples, head, samples, 0, sampleCount)
            head = 0
            return
        }
        var newSize = samples.size
        while (newSize < required) newSize = newSize * 2
        val expanded = FloatArray(newSize)
        System.arraycopy(samples, head, expanded, 0, sampleCount)
        samples = expanded
        head = 0
    }

    private fun compactIfNeeded() {
        if (head == 0) return
        if (head >= 4096 || head + sampleCount == samples.size) {
            System.arraycopy(samples, head, samples, 0, sampleCount)
            head = 0
        }
    }

    @Synchronized
    fun reset() {
        head = 0
        sampleCount = 0
        inputSampleRateHz = 0
        nextOutputPosition = 0.0
    }
}
