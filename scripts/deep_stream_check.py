#!/usr/bin/env python3
"""Deep, conservative validation of SALAR TV HLS and direct-audio endpoints.

A stream is only marked healthy when the manifest/playlist and up to three
media segments (or direct-audio bytes) are actually retrieved and checked.
403, TLS, timeout and server errors remain unverified/restricted, not dead.
"""
from __future__ import annotations
import concurrent.futures
import json
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "deep-stream-reports"
OUT.mkdir(exist_ok=True)
UA = "Mozilla/5.0 (compatible; SALAR-TV-DeepValidator/1.0)"
TIMEOUT = 18
MAX_MANIFEST = 1024 * 1024
MAX_SEGMENT = 384 * 1024
SEGMENTS_TO_SAMPLE = 3

def fetch(url: str, limit: int, use_range: bool = False):
    headers = {
        "User-Agent": UA,
        "Accept": "application/vnd.apple.mpegurl, application/x-mpegURL, audio/*, video/*, */*",
        "Icy-MetaData": "1",
    }
    # Do not send Range for manifests: several broadcasters answer 206 with
    # a truncated or non-zero-offset playlist. Range is useful only for media
    # samples where bounded transfer matters.
    if use_range:
        headers["Range"] = f"bytes=0-{limit-1}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = r.read(limit)
        return r.status, r.headers.get("Content-Type", ""), r.geturl(), data

def classify_exception(exc):
    if isinstance(exc, urllib.error.HTTPError):
        code = exc.code
        if code == 403:
            return "restricted_or_forbidden", f"HTTP 403; geo/IP/token restriction possible"
        if code in (404, 410):
            return "endpoint_missing", f"HTTP {code}; endpoint not found/gone at check time"
        if 500 <= code <= 599:
            return "temporary_server_error", f"HTTP {code}; server-side failure"
        return "http_error", f"HTTP {code}"
    msg = str(exc)
    low = msg.lower()
    if "timed out" in low or "timeout" in low:
        return "timeout", msg[:240]
    if "certificate" in low or "ssl" in low or "tls" in low:
        return "tls_error", msg[:240]
    if "name or service not known" in low or "temporary failure in name resolution" in low or "nodename nor servname" in low:
        return "dns_error", msg[:240]
    if "connection refused" in low or "connection reset" in low:
        return "connection_error", msg[:240]
    return "network_error", msg[:240]

def is_manifest(data):
    return data.lstrip(b"\xef\xbb\xbf\r\n \t").startswith(b"#EXTM3U")

def parse_master(text):
    lines = [x.strip() for x in text.splitlines() if x.strip()]
    variants = []
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            attrs = line.partition(":")[2]
            bw = re.search(r"(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\d+)", attrs)
            uri = next((x for x in lines[i+1:] if not x.startswith("#")), None)
            if uri:
                variants.append((int(bw.group(1)) if bw else 10**12, uri))
    return sorted(variants, key=lambda x: x[0])

def parse_media(text):
    segments = []
    init_maps = []
    for line in (x.strip() for x in text.splitlines()):
        if line.startswith("#EXT-X-MAP:"):
            m = re.search(r'URI="([^"]+)"', line)
            if m: init_maps.append(m.group(1))
        elif line and not line.startswith("#"):
            segments.append(line)
    return init_maps, segments

def check_segment(base_url, uri):
    url = urllib.parse.urljoin(base_url, uri)
    status, ctype, final_url, data = fetch(url, MAX_SEGMENT, use_range=True)
    if status not in (200, 206) or len(data) < 32:
        raise RuntimeError(f"segment response invalid: HTTP {status}, {len(data)} bytes")
    # Recognize common MPEG-TS and fragmented MP4 signatures; for other valid
    # segment containers retain the byte-count check and report the MIME.
    ts = len(data) >= 377 and data[0] == 0x47 and data[188] == 0x47 and data[376] == 0x47
    fmp4 = any(x in data[:128] for x in (b"ftyp", b"moof", b"styp", b"sidx"))
    return {"url": final_url, "http": status, "content_type": ctype,
            "bytes_sampled": len(data), "container_signature": "mpeg-ts" if ts else ("fmp4" if fmp4 else "nonempty-segment")}

def check_hls(url):
    status, ctype, final_url, body = fetch(url, MAX_MANIFEST)
    if status not in (200, 206) or not is_manifest(body):
        raise ValueError(f"response is not a valid HLS manifest (HTTP {status}, MIME {ctype})")
    text = body.decode("utf-8", "replace")
    variants = parse_master(text)
    variant_url = None
    variant_http = None
    if variants:
        # Lowest-bandwidth variant reduces test cost while still validating a
        # real media playlist and media segments.
        variant_url = urllib.parse.urljoin(final_url, variants[0][1])
        variant_http, variant_ct, variant_final, variant_body = fetch(variant_url, MAX_MANIFEST)
        if variant_http not in (200, 206) or not is_manifest(variant_body):
            raise ValueError(f"master manifest OK but child playlist invalid: HTTP {variant_http}, MIME {variant_ct}")
        variant_url = variant_final
        media_text = variant_body.decode("utf-8", "replace")
    else:
        media_text = text
        variant_http = status
        variant_url = final_url
    maps, segments = parse_media(media_text)
    if not segments:
        raise ValueError("manifest received but contains no media segment URI")
    map_results = []
    segment_results = []
    errors = []
    for uri in maps[:1]:
        try:
            map_results.append(check_segment(variant_url, uri))
        except Exception as e:
            errors.append("init map: " + str(e)[:200])
    for uri in segments[:SEGMENTS_TO_SAMPLE]:
        try:
            segment_results.append(check_segment(variant_url, uri))
        except Exception as e:
            errors.append("media segment: " + str(e)[:200])
    required = min(2, len(segments))
    if len(segment_results) < required:
        # A 451 response indicates a legal/geographic restriction, not a dead
        # stream. Preserve that distinction instead of downgrading it to a
        # generic malformed/incomplete playlist.
        if any("HTTP Error 451" in e for e in errors):
            raise PermissionError("HTTP 451 on media segments; geographic/legal restriction possible: " + "; ".join(errors))
        raise ValueError("media playlist received but too few actual media segments validated: " + "; ".join(errors))
    return {
        "protocol": "HLS", "manifest_http": status, "manifest_mime": ctype,
        "master_playlist": bool(variants), "child_playlist_http": variant_http,
        "media_playlist_url": variant_url, "segments_listed": len(segments),
        "init_maps_checked": map_results, "segments_checked": segment_results,
        "segment_errors": errors,
        "status": "healthy",
        "detail": "manifest, media playlist and actual sample media segments fetched"
    }

def check_audio(url):
    status, ctype, final_url, data = fetch(url, 8192, use_range=True)
    ct = ctype.lower()
    sigs = (b"ID3", b"\xff\xfb", b"\xff\xf3", b"\xff\xf2", b"OggS", b"fLaC", b"\x1aE\xdf\xa3")
    signature = any(data.startswith(s) for s in sigs)
    mime_audio = "audio/" in ct or "aac" in ct or "mpeg" in ct or "ogg" in ct
    if status not in (200, 206) or len(data) < 128 or not (mime_audio or signature):
        raise ValueError(f"not verified as direct audio: HTTP {status}, MIME {ctype}, bytes {len(data)}")
    return {"protocol": "audio/direct", "http": status, "content_type": ctype,
            "bytes_sampled": len(data), "audio_signature_detected": signature,
            "status": "healthy", "detail": "direct audio MIME/signature and sample bytes verified"}

def parse_m3u(path):
    rows, meta = [], None
    if not path.exists():
        return rows
    for raw in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            meta = line
        elif line.startswith(("http://", "https://")):
            name = meta.split(",", 1)[-1].strip() if meta else pathlib.Path(path).name
            group = re.search(r'group-title="([^"]*)"', meta or "")
            rows.append({"name": name, "category": group.group(1) if group else "نامشخص",
                         "url": line, "source_file": str(path.relative_to(ROOT))})
            meta = None
    return rows

def run_group(name, items, mode):
    def one(item):
        url = item["url"]
        try:
            if mode == "audio":
                result = check_audio(url)
            elif mode == "hls":
                result = check_hls(url)
            else:
                # Primary list contains both TV and radio; use group-title to
                # determine whether to test HLS or direct audio.
                result = check_hls(url) if ".m3u8" in urllib.parse.urlparse(url).path.lower() else check_audio(url)
            return {**item, **result, "checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        except Exception as e:
            status, detail = classify_exception(e)
            if isinstance(e, PermissionError):
                status, detail = "restricted_or_forbidden", str(e)[:300]
            elif isinstance(e, ValueError):
                status, detail = "invalid_or_incomplete_stream", str(e)[:300]
            return {**item, "status": status, "detail": detail,
                    "checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(one, items))
    out = {"group": name, "checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "total": len(results), "healthy": sum(x.get("status") == "healthy" for x in results),
           "unverified_or_failed": sum(x.get("status") != "healthy" for x in results),
           "results": results}
    safe = re.sub(r"[^a-z0-9-]+", "-", name.lower()).strip("-")
    (OUT / f"{safe}.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out

def main():
    primary = parse_m3u(ROOT / "patch/channels.m3u")
    radio = parse_m3u(ROOT / "patch/radio-candidates.m3u")
    az = parse_m3u(ROOT / "patch/azerbaijan-candidates.m3u")
    arab_israel = parse_m3u(ROOT / "patch/arab-israel-candidates.m3u")
    replacements = parse_m3u(ROOT / "patch/endpoint-replacement-candidates.m3u")
    irib_national = parse_m3u(ROOT / "patch/irib-national-candidates.m3u")
    turkey = parse_m3u(ROOT / "patch/turkey-candidates.m3u")
    # TRT candidate URLs are checked separately from the main playlist.
    trt = [
        {"name": "TRT Belgesel candidate 1", "category": "ترکیه / نامزد", "url": "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8", "source_file": "workflow candidate"},
        {"name": "TRT Belgesel candidate 2", "category": "ترکیه / نامزد", "url": "https://tv-trtbelgesel-dai.medya.trt.com.tr/master.m3u8", "source_file": "workflow candidate"},
        {"name": "TRT Belgesel candidate 3", "category": "ترکیه / نامزد", "url": "https://d2xwn494f2lep.cloudfront.net/v1/master/80dbfc318ab6b980679b32095ba497236de6d2f9/TRT-Belgesel/master.m3u8", "source_file": "workflow candidate"},
    ]
    groups = [
        run_group("main-playlist", primary, "auto"),
        run_group("radio-candidates", radio, "auto"),
        run_group("azerbaijan-candidates", az, "hls"),
        run_group("israel-arab-country-candidates", arab_israel, "hls"),
        run_group("endpoint-replacement-candidates", replacements, "hls"),
        run_group("irib-national-candidates", irib_national, "hls"),
        run_group("turkey-candidates", turkey, "hls"),
        run_group("trt-candidates", trt, "hls"),
    ]
    summary = {"checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "groups": [{k: g[k] for k in ("group", "total", "healthy", "unverified_or_failed")} for g in groups]}
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    for g in groups:
        print(f"{g['group']}: {g['healthy']}/{g['total']} fully validated; {g['unverified_or_failed']} unverified/failed")
    print("Reports:", OUT)

if __name__ == "__main__":
    main()
