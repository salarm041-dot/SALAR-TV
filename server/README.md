# SALAR TV AI Broker

This small server keeps the permanent OpenAI API key off the Android APK and issues short-lived Realtime client secrets.

## Endpoints

- `GET /health`
- `POST /v1/live/subtitle-session`
- `POST /v1/live/persian-dub-session`

### Source-language live captions

`POST /v1/live/subtitle-session` creates a short-lived `gpt-live-transcribe` transcription session. Optional JSON body:

```json
{
  "languages": ["en", "tr", "az", "ar", "fa"],
  "installId": "non-secret-installation-id"
}
```

The Android client must connect to the Realtime transcription WebSocket using the returned short-lived secret and stream **24 kHz mono PCM16** audio. The dedicated transcription session intentionally omits `turn_detection`: `gpt-live-transcribe` does not support server VAD or semantic VAD. The client must commit audio turns itself when needed, and should handle both incremental `conversation.item.input_audio_transcription.delta` and final `conversation.item.input_audio_transcription.completed` events.

### Persian live dubbing session

`POST /v1/live/persian-dub-session` creates a short-lived `gpt-realtime-2.1` session configured to interpret incoming broadcast speech into Iranian Persian, with PCM16 audio output and optional source-language transcript events.

Optional JSON body:

```json
{
  "languages": ["en", "tr", "az", "ar", "fa", "he"],
  "installId": "non-secret-installation-id"
}
```

The endpoint returns an ephemeral client secret, expiry, session ID, model name, and the expected 24 kHz mono PCM16 output format. The permanent API key is never returned to the client.

The dedicated `gpt-realtime-translate` model is not used for Persian output: its currently documented target languages do not include Persian. The Persian route therefore uses a Realtime speech session with explicit interpreter instructions. Translation quality and timing still require testing with real broadcast audio.

## Remaining Android integration work

The server endpoints are implemented, but live captions and dubbing are **not yet end-to-end app features**. These Android components remain to be completed and tested:

1. Capture decoded audio from SALAR TV's own Media3 player (not the phone microphone).
2. Resample/downmix to 24 kHz mono PCM16 and stream continuously to the Realtime WebSocket.
3. Receive and order transcript deltas/final events by `item_id`.
4. Translate source-language transcript text into Persian for subtitle display.
5. Receive translated audio and transcript events for dubbing.
6. Play translated PCM while ducking/mixing the original player audio.
7. Display translated subtitle deltas and provide independent controls for subtitles, dubbed audio, original audio, and volume.

The Android app also needs a configured broker URL. Do not put an OpenAI API key in the APK.

## Run locally

Node.js 20+:

```sh
export OPENAI_API_KEY="..."
node server/index.mjs
```

Do not commit API keys. Configure `OPENAI_API_KEY` as a secret in the hosting provider. Set `ALLOWED_ORIGIN` to the actual app/web origin if browser clients are used.
