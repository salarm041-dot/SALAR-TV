#!/usr/bin/env python3
"""Discover Persian-language radio streams, sample them, and build a candidate M3U."""
from __future__ import annotations
import concurrent.futures, json, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
REPORT=ROOT/"radio-directory-report.json"
OUTPUT=ROOT/"patch/radio-directory-candidates.m3u"
BASE="https://de1.api.radio-browser.info/json/stations/"
HEADERS={"User-Agent":"SALAR-TV/1.4 (radio directory validation)","Accept":"application/json"}
GENERIC={"go","امبدد","parsa","faz","caltexmusic"}

def fetch_json(path):
    req=urllib.request.Request(BASE+path,headers=HEADERS)
    with urllib.request.urlopen(req,timeout=15) as res:
        return json.loads(res.read().decode("utf-8"))

def candidate_urls(station):
    values=[]
    for key in ("url","url_resolved"):
        u=(station.get(key) or "").strip()
        if u.startswith(("http://","https://")) and u not in values: values.append(u)
    return sorted(values,key=lambda u:(bool(urllib.parse.urlsplit(u).query),not u.startswith("https://")))

def probe(url):
    req=urllib.request.Request(url,headers={"User-Agent":"SALAR-TV/1.4","Accept":"*/*","Range":"bytes=0-8191"})
    try:
        with urllib.request.urlopen(req,timeout=15) as res:
            data=res.read(8192); ct=res.headers.get("Content-Type","").lower()
            path=urllib.parse.urlsplit(url).path.lower()
            hls=data.lstrip().startswith(b"#EXTM3U") or "mpegurl" in ct
            audio=ct.startswith("audio/") or "octet-stream" in ct or any(path.endswith(e) for e in (".mp3",".aac",".m4a",".ogg",".opus",".flac",".wav",".pls"))
            html_body=data.lstrip().lower().startswith((b"<!doctype html",b"<html"))
            ok=200 <= getattr(res,"status",200) < 300 and bool(data) and (hls or audio) and not html_body
            return {"status":"healthy" if ok else "invalid_or_incomplete_stream","http_status":getattr(res,"status",200),
                    "content_type":ct,"bytes_sampled":len(data),"protocol":"hls" if hls else "audio" if audio else "unknown",
                    "detail":"sample accepted" if ok else "response was not a playable audio/HLS sample"}
    except urllib.error.HTTPError as e:
        return {"status":"restricted_or_forbidden" if e.code in (401,403,451) else "http_error","http_status":e.code,"detail":str(e)}
    except Exception as e:
        msg=str(e)
        return {"status":"timeout" if "timed out" in msg.lower() else "network_error","detail":msg}

def validate(station):
    last={"status":"no_stream_url","detail":"no HTTP stream URL"}
    urls=candidate_urls(station)
    for url in urls:
        result=probe(url); last=result
        if result["status"]=="healthy":
            return {"stationuuid":station.get("stationuuid",""),"name":(station.get("name") or "").strip(),"url":url,
                    "homepage":station.get("homepage",""),"favicon":station.get("favicon",""),
                    "country":station.get("country",""),"language":station.get("language",""),
                    "codec":station.get("codec",""),"bitrate":station.get("bitrate",0),**result}
    return {"stationuuid":station.get("stationuuid",""),"name":(station.get("name") or "").strip(),
            "url":urls[0] if urls else "","homepage":station.get("homepage",""),"favicon":station.get("favicon",""),
            "country":station.get("country",""),"language":station.get("language",""),**last}

def clean(s): return (s or "").replace('"',"'").replace("\n"," ").replace("\r"," ").strip()

def main():
    stations={}
    for endpoint in ("bylanguage/persian?limit=100","bycountry/iran?limit=100"):
        try:
            for st in fetch_json(endpoint):
                key=st.get("stationuuid") or st.get("url_resolved") or st.get("url")
                if key: stations[key]=st
        except Exception as e: print("Radio Browser query failed:",endpoint,e)
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results=list(pool.map(validate,stations.values()))
    report={"generated_at":int(time.time()),"total":len(results),
            "healthy":sum(x.get("status")=="healthy" for x in results),"results":results}
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["#EXTM3U","# Persian radio candidates; app promotion requires deep validation and a verified raster logo."]
    seen_urls=set(); seen_names=set(); count=0
    for row in results:
        if row.get("status")!="healthy": continue
        name=clean(row.get("name","")); country=(row.get("country") or "").lower(); lang=(row.get("language") or "").lower()
        if not name or name.lower() in GENERIC: continue
        if any(w in country or w in name.lower() for w in ("afghanistan","tajikistan","افغان","تاجیک")): continue
        if "persian" not in lang and "فارسی" not in lang and "iran" not in country and "iranian" not in lang: continue
        url=(row.get("url") or "").strip()
        if not url or url in seen_urls or name.lower() in seen_names: continue
        logo=(row.get("favicon") or "").strip()
        if not logo.startswith(("http://","https://")):
            host=urllib.parse.urlsplit(row.get("homepage") or url).hostname
            logo=f"https://www.google.com/s2/favicons?domain={host}&sz=128" if host else ""
        group="ایران / رادیو" if "iran" in country else "عراق / رادیو فارسی" if "iraq" in country else "فارسی‌زبان خارج از ایران / رادیو"
        logo_attr=f' tvg-logo="{clean(logo)}"' if logo else ""
        lines.extend((f'#EXTINF:-1 tvg-name="{name}"{logo_attr} group-title="{group}" tvg-language="fas" tvg-quality="Audio",{name}',url))
        seen_urls.add(url); seen_names.add(name.lower()); count+=1
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    OUTPUT.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(json.dumps({"directory_stations":len(results),"healthy_samples":report["healthy"],"radio_candidates_written":count},ensure_ascii=False))

if __name__=="__main__": main()
