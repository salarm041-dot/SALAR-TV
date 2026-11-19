package com.salar.tv

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket transport for the dedicated Realtime transcription session.
 * The caller supplies a short-lived secret from the SALAR TV broker;
 * the permanent OpenAI API key must never be placed in the Android app.
 */
class SalarRealtimeTranscriptionClient(
    private val clientSecret: String,
    private val onPartialTranscript: (itemId: String, text: String) -> Unit,
    private val onFinalTranscript: (itemId: String, text: String) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    private val transcriptLock = Any()
    private val committedItems = ArrayDeque<String>()
    private val completedItems = HashMap<String, String>()

    fun connect() {
        if (clientSecret.isBlank()) {
            onError("کلید موقت نشست زیرنویس خالی است")
            return
        }
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $clientSecret")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    webSocket.close(1000, "closed")
                    return
                }
                onState("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "conversation.item.input_audio_transcription.delta" -> {
                            onPartialTranscript(
                                event.optString("item_id"),
                                event.optString("delta")
                            )
                        }
                        "input_audio_buffer.committed" -> {
                            val itemId = event.optString("item_id")
                            if (itemId.isNotBlank()) {
                                val ready = synchronized(transcriptLock) {
                                    committedItems.addLast(itemId)
                                    drainCompletedInOrder()
                                }
                                ready.forEach { onFinalTranscript(it.first, it.second) }
                            }
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val itemId = event.optString("item_id")
                            val transcript = event.optString("transcript")
                            if (itemId.isNotBlank()) {
                                val ready = synchronized(transcriptLock) {
                                    completedItems[itemId] = transcript
                                    drainCompletedInOrder()
                                }
                                ready.forEach { onFinalTranscript(it.first, it.second) }
                            }
                        }
                        "conversation.item.input_audio_transcription.failed" -> {
                            val itemId = event.optString("item_id")
                            if (itemId.isNotBlank()) {
                                val ready = synchronized(transcriptLock) {
                                    completedItems[itemId] = ""
                                    drainCompletedInOrder()
                                }
                                ready.forEach { onFinalTranscript(it.first, it.second) }
                            }
                            val detail = event.optJSONObject("error")?.optString("message")
                            onError(detail?.takeIf { it.isNotBlank() } ?: "تشخیص گفتار برای یک بخش ناموفق بود")
                        }
                        "error" -> {
                            val error = event.optJSONObject("error")
                            onError(error?.optString("message") ?: "خطای سرویس زیرنویس")
                        }
                    }
                } catch (e: Exception) {
                    onError("پاسخ نامعتبر از سرویس زیرنویس: ${e.message ?: "unknown"}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) onError(t.message ?: "ارتباط زیرنویس قطع شد")
                onState("disconnected")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("disconnected")
            }
        })
    }

    /**
     * Completed transcript events can arrive out of order. Release them only
     * after the corresponding committed-item order is known and earlier items
     * have completed, so Persian translation follows broadcast chronology.
     */
    private fun drainCompletedInOrder(): List<Pair<String, String>> {
        val ready = ArrayList<Pair<String, String>>()
        while (committedItems.isNotEmpty()) {
            val nextId = committedItems.peekFirst()
            if (!completedItems.containsKey(nextId)) break
            committedItems.removeFirst()
            val transcript = completedItems.remove(nextId).orEmpty()
            if (transcript.isNotBlank()) ready.add(nextId to transcript)
        }
        return ready
    }

    /** Send already-resampled, mono, signed 16-bit little-endian PCM at 24 kHz. */
    fun appendPcm16(pcm: ByteArray): Boolean {
        if (closed.get() || pcm.isEmpty()) return false
        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val event = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", encoded)
        return socket?.send(event.toString()) == true
    }

    /** Dedicated transcription sessions have no server VAD; caller commits speech turns. */
    fun commitAudioTurn(): Boolean {
        if (closed.get()) return false
        return socket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString()) == true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "SALAR TV subtitle session ended")
        socket = null
        http.dispatcher.executorService.shutdown()
    }
}
