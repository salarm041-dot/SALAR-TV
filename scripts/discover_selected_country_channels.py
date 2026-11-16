#!/usr/bin/env python3
"""Fetch fresh IPTV-Org country playlists for SALAR TV's selected country groups.

This creates candidates only. A candidate is not shipped until the deep stream
validator and raster-logo audit both approve it.
"""
from __future__ import annotations
import concurrent.futures, re, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "patch/discovered-country-candidates.m3u"
# Keep these groups aligned with the countries/categories enabled in SALAR TV.
COUNTRIES = {
    "ir": "ایران", "tr": "ترکیه", "az": "آذربایجان", "il": "اسرائیل",
    "sa": "عربستان", "ae": "امارات", "qa": "قطر", "kw": "کویت",
    "bh": "بحرین", "om": "عمان", "iq": "عراق", "jo": "اردن",
    "lb": "لبنان", "sy": "سوریه", "ps": "فلسطین", "eg": "مصر",
    "ma": "مراکش", "tn": "تونس", "dz": "الجزایر", "ly": "لیبی",
    "us": "آمریکا", "gb": "بریتانیا", "de": "آلمان", "fr": "فرانسه",
    "ca": "کانادا", "au": "استرالیا", "ru": "روسیه", "ua": "اوکراین",
}
BASE = "https://iptv-org.github.io/iptv/countries/{}.m3u"
UA = "Mozilla/5.0 SALAR-TV-country-discovery/1.0"
TIMEOUT = 25
MAX_BYTES = 12 * 1024 * 1024

def fetch(code, label):
    req = urllib.request.Request(BASE.format(code), headers={"User-Agent": UA, "Accept": "text/plain,*/*"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise ValueError("country playlist exceeds size limit")
        text = body.decode("utf-8-sig", "replace")
        if not text.lstrip().startswith("#EXTM3U"):
            raise ValueError("not an M3U playlist")
        return code, label, text, None
    except Exception as exc:
        return code, label, "", str(exc)[:240]

def main():
    rows, errors = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        fetched = list(pool.map(lambda x: fetch(*x), COUNTRIES.items()))
    seen_urls, seen_ids = set(), set()
    for code, label, text, error in fetched:
        if error:
            errors.append({"country": code, "error": error})
            continue
        meta = None
        for raw in text.splitlines():
            line = raw.strip()
            if line.startswith("#EXTINF"):
                meta = line
            elif line.startswith(("http://", "https://")) and meta:
                url = line
                channel_id = re.search(r'tvg-id="([^"]*)"', meta, re.I)
                channel_id = channel_id.group(1).strip() if channel_id else ""
                key = (channel_id.lower(), url)
                if url not in seen_urls and key not in seen_ids:
                    group = f"کشور / {label}"
                    if re.search(r'group-title="[^"]*"', meta, re.I):
                        meta = re.sub(r'group-title="[^"]*"', f'group-title="{group}"', meta, count=1, flags=re.I)
                    else:
                        meta = meta.replace("#EXTINF:-1", f'#EXTINF:-1 group-title="{group}"', 1)
                    rows.extend((meta, url))
                    seen_urls.add(url); seen_ids.add(key)
                meta = None
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("#EXTM3U\n" + "\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")
    print(f"Country discovery: {len(seen_urls)} unique candidates from {len(COUNTRIES)-len(errors)}/{len(COUNTRIES)} country lists; {len(errors)} fetch errors.")
    for item in errors:
        print("COUNTRY LIST UNAVAILABLE", item["country"], item["error"])

if __name__ == "__main__":
    main()
