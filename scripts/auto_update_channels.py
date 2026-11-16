#!/usr/bin/env python3
"""Conservatively refresh SALAR TV's managed playlist from deep validation reports."""
from __future__ import annotations
import json, re, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "patch/channels.m3u"
REPORTS = ROOT / "deep-stream-reports"
SOURCES = [
    ("patch/arab-israel-candidates.m3u", "israel-arab-country-candidates.json"),
    ("patch/azerbaijan-candidates.m3u", "azerbaijan-candidates.json"),
    ("patch/radio-candidates.m3u", "radio-candidates.json"),
    ("patch/endpoint-replacement-candidates.m3u", "endpoint-replacement-candidates.json"),
    ("patch/irib-national-candidates.m3u", "irib-national-candidates.json"),
    ("patch/turkey-candidates.m3u", "turkey-candidates.json"),
    ("patch/radio-directory-candidates.m3u", "radio-directory-candidates.json"),
]
DEFINITE_DEAD = {"endpoint_missing"}  # only HTTP 404/410

def parse_m3u(text: str):
    rows, meta = [], []
    for raw in text.lstrip("\ufeff").splitlines():
        line = raw.strip()
        if not line: continue
        if line.startswith("#EXTINF"):
            if meta: raise ValueError("M3U entry missing URL")
            meta = [line]
        elif line.startswith(("http://", "https://")):
            if meta:
                rows.append((meta, line)); meta = []
        elif meta and line.startswith("#"):
            meta.append(line)
    if meta: raise ValueError("M3U final entry missing URL")
    return rows

def norm(name: str) -> str:
    value = name.split(",", 1)[-1].strip().lower()
    value = re.sub(r"\s+", " ", value)
    return re.sub(r"[\W_]+", "", value, flags=re.UNICODE)

def report_rows(path: Path):
    if not path.exists(): return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {x.get("url"): x for x in data.get("results", []) if x.get("url")}

def main():
    original = MAIN.read_text(encoding="utf-8-sig")
    current = parse_m3u(original)
    main_report = report_rows(REPORTS / "main-playlist.json")
    logo_path = ROOT / "candidate-logo-report.json"
    logo_report = json.loads(logo_path.read_text(encoding="utf-8")) if logo_path.exists() else {}
    logos = {(x.get("playlist"), x.get("channel")): x.get("logo")
             for x in logo_report.get("results", [])
             if x.get("status") == "verified" and str(x.get("logo", "")).startswith(("http://", "https://"))}

    candidates = []
    for source_name, report_name in SOURCES:
        source = ROOT / source_name
        if not source.exists(): continue
        report = report_rows(REPORTS / report_name)
        for metadata, url in parse_m3u(source.read_text(encoding="utf-8-sig")):
            if report.get(url, {}).get("status") != "healthy": continue
            logo = logos.get((source.name, url))
            if not logo: continue
            first = metadata[0]
            if re.search(r'tvg-logo="[^"]*"', first, re.I):
                first = re.sub(r'tvg-logo="[^"]*"', f'tvg-logo="{logo}"', first, count=1, flags=re.I)
            else:
                first = first.replace("#EXTINF:-1", f'#EXTINF:-1 tvg-logo="{logo}"', 1)
            candidates.append(([first, *metadata[1:]], url, source_name))

    replacement_by_name = {}
    for metadata, url, source in candidates:
        replacement_by_name.setdefault(norm(metadata[0]), (metadata, url, source))

    kept, removed, replaced = [], [], []
    old_urls = {url for _, url in current}
    for metadata, url in current:
        status = main_report.get(url, {}).get("status")
        if status not in DEFINITE_DEAD:
            kept.append((metadata, url))
            continue
        alternative = replacement_by_name.get(norm(metadata[0]))
        if alternative and alternative[1] not in old_urls:
            kept.append((alternative[0], alternative[1]))
            replaced.append({"name": metadata[0].split(",",1)[-1].strip(), "old_url": url,
                             "new_url": alternative[1], "source": alternative[2]})
            old_urls.add(alternative[1])
        else:
            removed.append({"name": metadata[0].split(",",1)[-1].strip(), "url": url, "reason": status})

    present = {url for _, url in kept}
    added = []
    for metadata, url, source in candidates:
        if url in present: continue
        joined = " ".join(metadata).lower()
        if "پخش زمان‌بندی‌شده" in joined or "scheduled" in joined: continue
        kept.append((metadata, url))
        present.add(url)
        added.append({"name": metadata[0].split(",",1)[-1].strip(), "url": url, "source": source})

    output = ["#EXTM3U"]
    for metadata, url in kept:
        output.extend(metadata)
        output.append(url)
    updated = "\n".join(output) + "\n"
    if updated != original: MAIN.write_text(updated, encoding="utf-8")
    unverified = sum(1 for _, url in current
                     if main_report.get(url, {}).get("status") not in ({None, "healthy"} | DEFINITE_DEAD))
    summary = {
        "updated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "before": len(current), "after": len(kept), "added_verified": added,
        "replaced_definitively_dead": replaced,
        "removed_definitively_dead_without_replacement": removed,
        "unverified_or_restricted_retained": unverified,
        "changed": updated != original,
        "note": "New discovery is limited to curated candidate directories; unverified and geo-restricted links are retained."
    }
    (REPORTS / "auto-update-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
