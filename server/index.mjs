import http from "node:http";
import crypto from "node:crypto";

const PORT = Number(process.env.PORT || 8787);
const OPENAI_API_KEY = process.env.OPENAI_API_KEY;

function send(res, status, body) {
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "access-control-allow-origin": process.env.ALLOWED_ORIGIN || "https://salar-tv.app",
    "access-control-allow-methods": "POST, OPTIONS, GET",
    "access-control-allow-headers": "content-type, authorization"
  });
  res.end(JSON.stringify(body));
}

async function readJson(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 8192) throw new Error("Request body too large");
  }
  return raw ? JSON.parse(raw) : {};
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") return send(res, 204, {});
  if (req.method === "GET" && req.url === "/health") {
    return send(res, 200, {
      ok: true,
      service: "salar-tv-ai-broker",
      transcription_ready: Boolean(OPENAI_API_KEY),
      persian_dubbing_ready: Boolean(OPENAI_API_KEY)
    });
  }
  if (req.method === "POST" && req.url === "/v1/live/persian-dub-session") {
    if (!OPENAI_API_KEY) {
      return send(res, 503, { error: "server_not_configured", message: "OPENAI_API_KEY is not configured on the server." });
    }
    try {
      const body = await readJson(req);
      const allowedLanguages = new Set(["en", "tr", "az", "ar", "fa", "he", "de", "ru"]);
      const languages = Array.isArray(body.languages)
        ? [...new Set(body.languages.filter((x) => allowedLanguages.has(x)))].slice(0, 8)
        : ["en", "tr", "az", "ar", "fa", "he", "de", "ru"];
      const safetyId = crypto.createHash("sha256")
        .update(String(body.installId || "salar-tv-anonymous"))
        .digest("hex");
      const upstream = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
        method: "POST",
        headers: {
          authorization: `Bearer ${OPENAI_API_KEY}`,
          "content-type": "application/json",
          "OpenAI-Safety-Identifier": safetyId
        },
        body: JSON.stringify({
          expires_after: { anchor: "created_at", seconds: 600 },
          session: {
            type: "realtime",
            model: "gpt-realtime-2.1",
            output_modalities: ["audio"],
            instructions: "You are a professional live broadcast interpreter. Translate every spoken segment into natural Iranian Persian (Farsi) only. Do not answer questions, summarize, explain, or add commentary. Preserve names, numbers, dates, and technical terms. Speak clearly and concisely, synchronized as closely as possible with the source speaker. If the source is already Persian, keep it in Persian without translating it into another language. Use a neutral Iranian Persian accent.",
            max_output_tokens: 2048,
            audio: {
              input: {
                format: { type: "audio/pcm", rate: 24000 },
                transcription: { model: "gpt-live-transcribe", languages },
                turn_detection: { type: "server_vad", threshold: 0.5, prefix_padding_ms: 300, silence_duration_ms: 500 }
              },
              output: {
                format: { type: "audio/pcm", rate: 24000 },
                voice: "marin",
                speed: 1.0
              }
            }
          }
        })
      });
      const result = await upstream.json();
      if (!upstream.ok) {
        return send(res, upstream.status, { error: "upstream_error", details: result });
      }
      return send(res, 200, {
        client_secret: result.value,
        expires_at: result.expires_at,
        session_id: result.session?.id,
        model: "gpt-realtime-2.1",
        target_language: "fa",
        output_format: "pcm_s16le_24000_mono"
      });
    } catch (error) {
      return send(res, 400, { error: "invalid_request", message: error.message });
    }
  }

  if (req.method !== "POST" || req.url !== "/v1/live/subtitle-session") {
    return send(res, 404, { error: "not_found" });
  }
  if (!OPENAI_API_KEY) {
    return send(res, 503, { error: "server_not_configured", message: "OPENAI_API_KEY is not configured on the server." });
  }

  try {
    const body = await readJson(req);
    const allowedLanguages = new Set(["en", "tr", "az", "ar", "fa"]);
    const languages = Array.isArray(body.languages)
      ? [...new Set(body.languages.filter((x) => allowedLanguages.has(x)))].slice(0, 5)
      : ["en", "tr", "az", "ar", "fa"];

    const upstream = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: {
        authorization: `Bearer ${OPENAI_API_KEY}`,
        "content-type": "application/json",
        "OpenAI-Safety-Identifier": crypto.createHash("sha256")
          .update(String(body.installId || "salar-tv-anonymous"))
          .digest("hex")
      },
      body: JSON.stringify({
        expires_after: { anchor: "created_at", seconds: 600 },
        session: {
          type: "transcription",
          audio: {
            input: {
              format: { type: "audio/pcm", rate: 24000 },
              transcription: { model: "gpt-live-transcribe", languages },
              turn_detection: { type: "server_vad", threshold: 0.5, prefix_padding_ms: 300, silence_duration_ms: 500 }
            }
          }
        }
      })
    });

    const result = await upstream.json();
    if (!upstream.ok) {
      return send(res, upstream.status, { error: "upstream_error", details: result });
    }
    return send(res, 200, {
      client_secret: result.value,
      expires_at: result.expires_at,
      session_id: result.session?.id,
      model: "gpt-live-transcribe"
    });
  } catch (error) {
    return send(res, 400, { error: "invalid_request", message: error.message });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`SALAR TV AI broker listening on :${PORT}`);
});
