package com.salar.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Raw PCM frame copied from the primary Media3 audio sink. */
data class SalarPcmChunk(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int
)

/**
 * Bounded, non-blocking PCM tap. The render thread only copies complete frames;
 * conversion, network I/O and AI work must run on a separate consumer thread.
 * Capture is opt-in and disabled by default.
 */
@OptIn(UnstableApi::class)
class SalarAiAudioCapture(
    private val queueCapacity: Int = 32,
    private val maxChunkBytes: Int = 64 * 1024
) {
    private val queue = ArrayBlockingQueue<SalarPcmChunk>(queueCapacity)
    private val dropped = AtomicLong(0)

    @Volatile var enabled: Boolean = false
        private set
    @Volatile private var sampleRateHz: Int = 0
    @Volatile private var channelCount: Int = 0
    @Volatile private var encoding: Int = 0

    val processor = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@SalarAiAudioCapture.sampleRateHz = sampleRateHz
            this@SalarAiAudioCapture.channelCount = channelCount
            this@SalarAiAudioCapture.encoding = encoding
            queue.clear()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!enabled || !buffer.hasRemaining()) return
            val rate = sampleRateHz
            val channels = channelCount
            val pcmEncoding = encoding
            if (rate <= 0 || channels <= 0) return

            // Keep PCM16 chunks frame-aligned for safe multi-channel decoding.
            val frameSize = if (pcmEncoding == C.ENCODING_PCM_16BIT) channels * 2 else 1
            val source = buffer.duplicate()
            while (source.remaining() >= frameSize) {
                val available = minOf(source.remaining(), maxChunkBytes)
                val count = available - (available % frameSize)
                if (count <= 0) break
                val bytes = ByteArray(count)
                source.get(bytes)
                val chunk = SalarPcmChunk(bytes, rate, channels, pcmEncoding)
                if (!queue.offer(chunk)) {
                    queue.poll()
                    dropped.incrementAndGet()
                    if (!queue.offer(chunk)) dropped.incrementAndGet()
                }
            }
        }
    })

    fun start() {
        queue.clear()
        enabled = true
    }

    fun stop() {
        enabled = false
        queue.clear()
    }

    fun pollChunk(timeoutMs: Long = 0): SalarPcmChunk? =
        if (timeoutMs <= 0) queue.poll() else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun droppedChunkCount(): Long = dropped.get()

    fun clear() {
        stop()
        processor.reset()
    }
}

/** Installs the PCM tap in the primary player's audio sink without changing video rendering. */
@OptIn(UnstableApi::class)
class SalarAiRenderersFactory(
    context: Context,
    private val capture: SalarAiAudioCapture
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessors(arrayOf(capture.processor))
            .build()
    }
}
