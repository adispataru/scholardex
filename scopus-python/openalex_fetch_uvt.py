#!/usr/bin/env python3
"""Fetch ALL UVT works from OpenAlex as FULL records.

Safe by construction:
- writes to a .tmp file, atomic-renames on success (never truncates a good corpus)
- cursor pagination, 200/page, full records (no select trimming)
- API key from .env (paid prepaid balance) -> bypasses the 1000/10h free wall
- surfaces x-ratelimit-* / x-cost headers each page so we watch the $10 burn
- honors retry-after on 429
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROR = "0583a0t97"  # West University of Timisoara
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "openalex", "uvt_works.jsonl")
OUT = os.path.normpath(OUT)
TMP = OUT + ".tmp"
PER_PAGE = 200
BASE = "https://api.openalex.org/works"


def load_api_key():
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
    key = os.environ.get("OPENALEX_API_KEY")
    if key:
        return key
    if os.path.isfile(env_path):
        for line in open(env_path, encoding="utf-8"):
            line = line.strip()
            if line.startswith("OPENALEX_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return None


API_KEY = load_api_key()
if not API_KEY:
    print("FATAL: OPENALEX_API_KEY not found in env or .env", file=sys.stderr)
    sys.exit(1)


def fetch_page(cursor):
    params = {
        "filter": f"institutions.ror:{ROR}",
        "per-page": str(PER_PAGE),
        "cursor": cursor,
        "api_key": API_KEY,
        "mailto": "adi.spataru92@gmail.com",
    }
    url = BASE + "?" + urllib.parse.urlencode(params)
    for attempt in range(6):
        req = urllib.request.Request(url, headers={"User-Agent": "uvt-scholardex/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                hdr = resp.headers
                meta_cost = {
                    "remaining_req": hdr.get("x-ratelimit-remaining"),
                    "prepaid_usd": hdr.get("x-ratelimit-prepaid-remaining-usd"),
                    "cost_usd": hdr.get("x-ratelimit-cost-usd"),
                }
                return json.loads(resp.read().decode("utf-8")), meta_cost
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = int(e.headers.get("retry-after", "30"))
                print(f"  429 rate-limited; retry-after={wait}s (attempt {attempt+1})", file=sys.stderr)
                if wait > 120:
                    print(f"  retry-after {wait}s too long; aborting to preserve balance/time", file=sys.stderr)
                    raise
                time.sleep(wait + 1)
                continue
            body = e.read().decode("utf-8", "replace")[:300]
            print(f"  HTTP {e.code}: {body}", file=sys.stderr)
            raise
    raise RuntimeError("exhausted retries")


def main():
    cursor = "*"
    total = None
    written = 0
    pages = 0
    with open(TMP, "w", encoding="utf-8") as out:
        while cursor:
            data, cost = fetch_page(cursor)
            if total is None:
                total = data["meta"]["count"]
                print(f"Total UVT works reported: {total}")
            results = data["results"]
            for r in results:
                out.write(json.dumps(r, ensure_ascii=False) + "\n")
            written += len(results)
            pages += 1
            cursor = data["meta"].get("next_cursor")
            print(f"  page {pages}: +{len(results)} (total {written}/{total}) "
                  f"prepaid_usd={cost['prepaid_usd']} remaining_req={cost['remaining_req']}")
            if not results:
                break
    if total and written < total * 0.95:
        print(f"WARN: only {written}/{total} fetched (<95%); NOT renaming, leaving {TMP}", file=sys.stderr)
        sys.exit(2)
    os.replace(TMP, OUT)
    print(f"DONE: {written} full records -> {OUT} ({pages} pages = {pages} requests)")


if __name__ == "__main__":
    main()
