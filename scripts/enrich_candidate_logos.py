#!/usr/bin/env python3
"""Attach and verify raster channel logos for candidate playlists.

Only exact IPTV-Org channel IDs are matched. Existing manually supplied logo
URLs are also checked and retained as a fallback. SVG is excluded because the
Android app currently loads logos with BitmapFactory.
"""
from __future__ import annotations

import concurrent.futures
import json
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLAYLISTS = [
    ROOT / "patch/arab-israel-candidates.m3u",
    ROOT / "patch/azerbaijan-candidates.m3u",
]
API = "https://iptv-org.github.io/api/logos.json"
UA = "Mozilla/5.0 SALAR-TV-logo-audit/1.1"
TIMEOUT = 12


def fetch_json(url: str):
    request = urllib.request.Request(
        url, headers={"User-Agent": UA, "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def image_is_usable(url: str) -> bool:
    request = urllib.request.Request(
        url, headers={"User-Agent": UA, "Range": "bytes=0-1023"}
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            data = response.read(1024)
            content_type = response.headers.get("Content-Type", "").lower()
            png = data.startswith(bytes([137, 80, 78, 71, 13, 10, 26, 10]))
            jpeg = data.startswith(bytes([255, 216, 255]))
            webp = data.startswith(b"RIFF") and data[8:12] == b"WEBP"
            gif = data.startswith((b"GIF87a", b"GIF89a"))
            avif = len(data) >= 12 and data[4:8] == b"ftyp" and b"avif" in data[8:32]
            raster_signature = png or jpeg or webp or gif or avif
            raster_mime = content_type.startswith("image/") and "svg" not in content_type
            return (
                response.status in (200, 206)
                and len(data) > 12
                and (raster_signature or raster_mime)
            )
    except Exception:
        return False


def main():
    logos = fetch_json(API)
    index: dict[str, list[dict]] = {}
    for item in logos:
        channel_id = item.get("channel")
        url = item.get("url", "")
        image_format = (item.get("format") or "").upper()
        if (
            not channel_id
            or not url.startswith("https://")
            or image_format not in {"PNG", "JPEG", "WEBP", "GIF", "AVIF", "APNG"}
        ):
            continue
        index.setdefault(channel_id, []).append(item)

    outputs = {}
    records = []
    for path in PLAYLISTS:
        source_lines = path.read_text(encoding="utf-8-sig").splitlines()
        output_lines = []
        pending_meta = None
        for line in source_lines:
            if line.startswith("#EXTINF"):
                if pending_meta is not None:
                    output_lines.append(pending_meta)
                pending_meta = line
                continue
            if pending_meta is not None and line.startswith(("http://", "https://")):
                match = re.search(r'tvg-id="([^"]*)"', pending_meta)
                channel_id = match.group(1).split("@", 1)[0] if match else ""
                options = list(index.get(channel_id, []))
                options.sort(
                    key=lambda item: (
                        bool(item.get("in_use")),
                        (item.get("width") or 0) * (item.get("height") or 0),
                        (item.get("format") or "").upper() in {"PNG", "JPEG", "WEBP"},
                    ),
                    reverse=True,
                )
                existing = re.search(r'tvg-logo="([^"]+)"', pending_meta)
                existing_url = existing.group(1) if existing else ""
                records.append(
                    {
                        "path": path,
                        "meta_index": len(output_lines),
                        "meta": pending_meta,
                        "stream": line,
                        "channel_id": channel_id,
                        "options": options,
                        "existing_url": existing_url,
                    }
                )
                output_lines.extend([pending_meta, line])
                pending_meta = None
                continue
            if pending_meta is not None:
                output_lines.append(pending_meta)
                pending_meta = None
            output_lines.append(line)
        if pending_meta is not None:
            output_lines.append(pending_meta)
        outputs[path] = output_lines

    urls = []
    for record in records:
        urls.extend(item["url"] for item in record["options"])
        if record["existing_url"]:
            urls.append(record["existing_url"])
    urls = list(dict.fromkeys(urls))
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        valid = dict(zip(urls, pool.map(image_is_usable, urls)))

    report_rows = []
    for record in records:
        selected = next(
            (
                item["url"]
                for item in record["options"]
                if valid.get(item["url"], False)
            ),
            None,
        )
        source = "iptv-org catalogue" if selected else ""
        if not selected and record["existing_url"] and valid.get(record["existing_url"], False):
            selected = record["existing_url"]
            source = "existing playlist metadata"

        meta = record["meta"]
        if selected:
            if re.search(r'tvg-logo="[^"]*"', meta):
                meta = re.sub(
                    r'tvg-logo="[^"]*"',
                    lambda _: 'tvg-logo="' + selected + '"',
                    meta,
                    count=1,
                )
            else:
                meta = meta.replace(
                    "#EXTINF:-1",
                    '#EXTINF:-1 tvg-logo="' + selected + '"',
                    1,
                )
            outputs[record["path"]][record["meta_index"]] = meta

        report_rows.append(
            {
                "playlist": record["path"].name,
                "channel_id": record["channel_id"],
                "channel": record["stream"],
                "logo": selected,
                "logo_source": source or None,
                "status": "verified" if selected else "missing_or_unusable",
            }
        )

    for path, lines in outputs.items():
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    report = {
        "source": API,
        "policy": (
            "Exact channel-ID match; in-use logo preferred; raster-only; "
            "sample bytes and image MIME/signature checked; existing logo URL "
            "retained only after the same image check."
        ),
        "total": len(report_rows),
        "logos_verified": sum(row["status"] == "verified" for row in report_rows),
        "missing_or_unusable": sum(
            row["status"] != "verified" for row in report_rows
        ),
        "results": report_rows,
    }
    (ROOT / "candidate-logo-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        f"Candidate logos: {report['logos_verified']}/{report['total']} "
        f"verified; {report['missing_or_unusable']} missing or unusable"
    )


if __name__ == "__main__":
    main()
