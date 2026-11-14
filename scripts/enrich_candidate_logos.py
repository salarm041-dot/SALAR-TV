#!/usr/bin/env python3
"""Attach verified raster channel logos from the public iptv-org logo catalogue.

Only channel IDs that match exactly are considered. SVGs are deliberately skipped
because SALAR TV currently renders logos through Android BitmapFactory.
"""
from __future__ import annotations
import concurrent.futures
import json
import pathlib
import re
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLAYLISTS = [ROOT / "patch/arab-israel-candidates.m3u", ROOT / "patch/azerbaijan-candidates.m3u"]
API = "https://iptv-org.github.io/api/logos.json"
UA = "Mozilla/5.0 SALAR-TV-logo-audit/1.0"
TIMEOUT = 12

def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))

def image_is_usable(url: str) -> bool:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-1023"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            data = response.read(1024)
            ctype = response.headers.get("Content-Type", "").lower()
            raster = (data.startswith(b"\\x89PNG\\r\\n\\x1a\\n") or
                      data.startswith(b"\\xff\\xd8\\xff") or
                      (data.startswith(b"RIFF") and data[8:12] == b"WEBP"))
            return response.status in (200, 206) and len(data) > 12 and (raster or ctype.startswith("image/") and "svg" not in ctype)
    except Exception:
        return False

def main():
    logos = fetch_json(API)
    index: dict[str, list[dict]] = {}
    for item in logos:
        channel = item.get("channel")
        url = item.get("url", "")
        fmt = (item.get("format") or "").upper()
        if not channel or not url.startswith("https://") or fmt not in {"PNG", "JPEG", "WEBP", "GIF", "AVIF", "APNG"}:
            continue
        index.setdefault(channel, []).append(item)

    candidates = []
    for path in PLAYLISTS:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        output = []
        pending = None
        records = []
        for line in lines:
            if line.startswith("#EXTINF"):
                pending = line
                continue
            if pending is not None and line.startswith(("http://", "https://")):
                match = re.search(r'tvg-id="([^"]+)"', pending)
                channel_id = match.group(1).split("@", 1)[0] if match else ""
                options = index.get(channel_id, [])
                options.sort(key=lambda x: (
                    bool(x.get("in_use")),
                    (x.get("width") or 0) * (x.get("height") or 0),
                    (x.get("format") or "").upper() in {"PNG", "JPEG", "WEBP"}
                ), reverse=True)
                records.append((len(records), path, pending, line, options))
                output.extend([pending, line])
                pending = None
            else:
                if pending is not None:
                    output.append(pending)
                    pending = None
                output.append(line)
        if pending is not None:
            output.append(pending)
        candidates.extend(records)

    urls = list(dict.fromkeys(option["url"] for row in candidates for option in row[4]))
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        valid = dict(zip(urls, pool.map(image_is_usable, urls)))

    results = []
    by_path = {p: p.read_text(encoding="utf-8-sig").splitlines() for p in PLAYLISTS}
    for _, path, meta, stream, options in candidates:
        logo = next((option for option in options if valid.get(option["url"], False)), None)
        selected = logo is not None
        if selected:
            if 'tvg-logo="' in meta:
                meta = re.sub(r'tvg-logo="[^"]*"', 'tvg-logo="' + logo["url"] + '"', meta, count=1)
            else:
                meta = meta.replace("#EXTINF:-1", '#EXTINF:-1 tvg-logo="' + logo["url"] + '"', 1)
        results.append({"playlist": path.name, "channel_id": re.search(r'tvg-id="([^"]+)"', meta).group(1) if re.search(r'tvg-id="([^"]+)"', meta) else "", "logo": logo["url"] if selected else None})
        lines = by_path[path]
        try:
            pos = lines.index(next(x for x in lines if x == stream))
            # Match the immediately preceding EXTINF record, even for repeated stream URLs.
            while pos > 0 and not lines[pos-1].startswith("#EXTINF"):
                pos -= 1
            if pos > 0 and lines[pos-1].startswith("#EXTINF"):
                lines[pos-1] = meta
        except (StopIteration, ValueError):
            pass

    for path, lines in by_path.items():
        path.write_text("\\n".join(lines) + "\\n", encoding="utf-8")
    report = {
        "source": API,
        "policy": "Exact channel-ID match; in-use logo preferred; raster formats only; image URL sampled before insertion.",
        "total": len(results),
        "logos_attached": sum(1 for x in results if x["logo"]),
        "missing_or_unusable": sum(1 for x in results if not x["logo"]),
        "results": results,
    }
    (ROOT / "candidate-logo-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Candidate logos: {report['logos_attached']}/{report['total']} matched and image-checked; {report['missing_or_unusable']} missing/unusable")

if __name__ == "__main__":
    main()
