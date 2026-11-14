# SALAR TV AI Broker

This small server keeps the permanent OpenAI API key off the Android APK and issues short-lived Realtime client secrets.

## Current endpoint

- `GET /health`
- `POST /v1/live/subtitle-session`

The session endpoint creates a short-lived `gpt-live-transcribe` transcription session for live captions. It accepts an optional JSON body:

```json
{
  "languages": ["en", "tr", "az", "ar", "fa"],
  "installId": "non-secret-installation-id"
}
```

It returns only the ephemeral client secret, expiry and session ID. The Android client must connect to the Realtime transcription WebSocket using that short-lived secret and stream **24 kHz mono PCM16** audio. The permanent API key must exist only as the server environment variable `OPENAI_API_KEY`.

## Important product limitation

Live transcription produces source-language text; it does not translate it to Persian or generate Persian speech. The separate Persian text-translation and TTS stages, plus the Media3 audio tap and playback mixer, are not implemented yet.

The dedicated `gpt-realtime-translate` model currently lists 13 output languages and Persian is not among them. Therefore SALAR TV must not send `fa` as its output language to that model. The intended Persian path is:

1. Capture decoded audio from the app's own Media3 player (not the phone microphone).
2. Stream PCM16/24 kHz mono to `gpt-live-transcribe`.
3. Translate transcript segments to Persian with a text model.
4. For dubbing, synthesize Persian audio and mix it with the original player output.
5. Display translated subtitle deltas independently of the dubbing toggle.

## Run locally

Node.js 20+:

```sh
export OPENAI_API_KEY="..."
node server/index.mjs
```

Do not commit API keys. Configure `OPENAI_API_KEY` as a secret in the hosting provider. Set `ALLOWED_ORIGIN` to the actual app/web origin if browser clients are used.
