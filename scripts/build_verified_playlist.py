#!/usr/bin/env python3
"""Promote only deeply validated, unscheduled candidate streams into the app playlist."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "deep-stream-reports"
MAIN = ROOT / "patch/channels.m3u"
SOURCES = [
    ROOT / "patch/arab-israel-candidates.m3u",
    ROOT / "patch/azerbaijan-candidates.m3u",
    ROOT / "patch/radio-candidates.m3u",
    ROOT / "patch/endpoint-replacement-candidates.m3u",
    ROOT / "patch/irib-national-candidates.m3u",
    ROOT / "patch/turkey-candidates.m3u",
    ROOT / "patch/radio-directory-candidates.m3u",
    ROOT / "patch/discovered-country-candidates.m3u",
]


def read_report(path: Path) -> set[str]:
    if not path.exists():
        print(f"Skipping candidate group without completed deep-validation report: {path.name}")
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        row["url"]
        for row in data.get("results", [])
        if row.get("status") == "healthy" and row.get("url", "").startswith(("http://", "https://"))
    }


def parse_entries(path: Path):
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    block: list[str] = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            if block:
                raise SystemExit(f"Malformed M3U in {path}: EXTINF without URL")
            block = [line]
        elif line.startswith(("http://", "https://")):
            if not block:
                continue
            yield block, line
            block = []
        elif block and line.startswith("#"):
            block.append(line)
    if block:
        raise SystemExit(f"Malformed M3U in {path}: final EXTINF has no URL")


def main():
    # The deep-validation step may be intentionally skipped; still create the
    # report directory so an empty promotion report can be written safely.
    REPORTS.mkdir(parents=True, exist_ok=True)
    report_files = {
        SOURCES[0]: REPORTS / "israel-arab-country-candidates.json",
        SOURCES[1]: REPORTS / "azerbaijan-candidates.json",
        SOURCES[2]: REPORTS / "radio-candidates.json",
        SOURCES[3]: REPORTS / "endpoint-replacement-candidates.json",
        SOURCES[4]: REPORTS / "irib-national-candidates.json",
        SOURCES[5]: REPORTS / "turkey-candidates.json",
        SOURCES[6]: REPORTS / "radio-directory-candidates.json",
        SOURCES[7]: REPORTS / "discovered-country-candidates.json",
    }
    if not MAIN.exists():
        raise SystemExit(f"Main playlist not found: {MAIN}")
    original = MAIN.read_text(encoding="utf-8-sig")
    existing = set(re.findall(r"(?m)^https?://\S+", original))
    logo_report_path = ROOT / "candidate-logo-report.json"
    if not logo_report_path.exists():
        raise SystemExit("Missing candidate-logo-report.json; refusing to promote unverified logos")
    logo_report = json.loads(logo_report_path.read_text(encoding="utf-8"))
    verified_logos = {
        (row.get("playlist"), row.get("channel")): row.get("logo")
        for row in logo_report.get("results", [])
        if row.get("status") == "verified" and row.get("logo", "").startswith(("http://", "https://"))
    }
    additions: list[str] = []
    promoted: dict[str, int] = {}
    skipped_scheduled = 0
    skipped_duplicate = 0
    skipped_missing_logo = 0
    promoted_total = 0

    for source, report_path in report_files.items():
        healthy = read_report(report_path)
        count = 0
        for metadata, url in parse_entries(source):
            if url not in healthy:
                continue
            joined = " ".join(metadata)
            if "پخش زمان‌بندی‌شده" in joined or "scheduled" in joined.lower():
                skipped_scheduled += 1
                continue
            verified_logo = verified_logos.get((source.name, url))
            if not verified_logo:
                skipped_missing_logo += 1
                continue
            if re.search(r'tvg-logo="[^"]*"', metadata[0], re.IGNORECASE):
                metadata[0] = re.sub(r'tvg-logo="[^"]*"', f'tvg-logo="{verified_logo}"', metadata[0], count=1, flags=re.IGNORECASE)
            else:
                metadata[0] = metadata[0].replace("#EXTINF:-1", f'#EXTINF:-1 tvg-logo="{verified_logo}"', 1)
            if url in existing:
                skipped_duplicate += 1
                continue
            additions.extend(metadata)
            additions.append(url)
            existing.add(url)
            count += 1
            promoted_total += 1
        promoted[source.name] = count

    if additions:
        base = original.rstrip()
        if not base.startswith("#EXTM3U"):
            raise SystemExit("Main playlist is missing #EXTM3U header")
        MAIN.write_text(base + "\n" + "\n".join(additions) + "\n", encoding="utf-8")

    report = {
        "promoted_by_source": promoted,
        "promoted_total": promoted_total,
        "skipped_scheduled": skipped_scheduled,
        "skipped_duplicate": skipped_duplicate,
        "skipped_missing_logo": skipped_missing_logo,
        "final_entries": len(re.findall(r"(?m)^#EXTINF", MAIN.read_text(encoding="utf-8"))),
    }
    (REPORTS / "promotion-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
