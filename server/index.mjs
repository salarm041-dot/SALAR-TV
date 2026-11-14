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
    return send(res, 200, { ok: true, service: "salar-tv-ai-broker" });
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
