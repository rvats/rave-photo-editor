#!/usr/bin/env python3
"""Downloads a CC0 sample photo from Wikimedia Commons for the emulator screenshots.

The licence is read from each file's own Commons metadata and only CC0 or public-domain
files are used. Writes the photo and a credit file naming the source page, author and licence.

Usage: sample_photo.py <out.jpg> <credit.txt>
"""
import html
import json
import re
import sys
import urllib.parse
import urllib.request

UA = "RavePhotoEditorCI/1.0 (https://github.com/rvats/rave-photo-editor)"
API = "https://commons.wikimedia.org/w/api.php"
QUERIES = [
    "golden retriever dog grass",
    "dog sitting grass",
    "dog portrait outdoors",
    "puppy grass",
]
FREE = ("cc0", "public domain", "pdm")


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def search(query: str):
    params = {
        "action": "query",
        "format": "json",
        "formatversion": "2",
        "generator": "search",
        "gsrnamespace": "6",
        "gsrlimit": "40",
        "gsrsearch": f"{query} incategory:CC-zero filemime:image/jpeg",
        "prop": "imageinfo",
        "iiprop": "url|size|extmetadata",
        "iiurlwidth": "1600",
        "iiextmetadatafilter": "LicenseShortName|Artist|ObjectName",
    }
    data = json.loads(get(API + "?" + urllib.parse.urlencode(params)))
    pages = data.get("query", {}).get("pages", [])
    pages.sort(key=lambda p: p.get("index", 0))
    for page in pages:
        info = (page.get("imageinfo") or [{}])[0]
        yield page.get("title", ""), info


def text(meta: dict, key: str) -> str:
    raw = meta.get(key, {}).get("value", "")
    return html.unescape(re.sub(r"<[^>]+>", "", str(raw))).strip()


def main() -> int:
    out_jpg, out_credit = sys.argv[1], sys.argv[2]
    for query in QUERIES:
        try:
            results = list(search(query))
        except Exception as e:  # network or API trouble: try the next query
            print(f"search failed for {query!r}: {e}")
            continue
        for title, info in results:
            meta = info.get("extmetadata", {})
            licence = text(meta, "LicenseShortName")
            w, h = info.get("width", 0), info.get("height", 0)
            if not licence or not any(f in licence.lower() for f in FREE):
                continue
            if min(w, h) < 1200 or not (0.6 <= h / max(w, 1) <= 1.0):
                continue  # small, portrait or panoramic
            url = info.get("thumburl") or info.get("url")
            try:
                data = get(url)
            except Exception as e:
                print(f"download failed for {title}: {e}")
                continue
            with open(out_jpg, "wb") as f:
                f.write(data)
            with open(out_credit, "w") as f:
                f.write(
                    f"Sample photo: {title}\n"
                    f"Source: {info.get('descriptionurl', '')}\n"
                    f"Author: {text(meta, 'Artist')}\n"
                    f"Licence: {licence}\n"
                    f"Original size: {w}x{h}; downloaded: {url}\n"
                    f"Search: {query}\n"
                )
            print(open(out_credit).read())
            return 0
    print("No suitable CC0 photo found")
    return 1


if __name__ == "__main__":
    sys.exit(main())
