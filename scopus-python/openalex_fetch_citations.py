#!/usr/bin/env python3
"""Fetch the incoming-citation graph for UVT works from OpenAlex.

Reads data/openalex/uvt_works.jsonl (full records), then for every UVT work
with cited_by_count>0 fetches the works that CITE it.

Two-track batching (request count scales with union size / 200, not paper count):
  - TAIL (1..200 cites): batch K work-ids per `cites:Wa|Wb|...` filter, paginate
    the union; attribute edges via each returned work's referenced_works.
  - HEAD (>200 cites): per-paper cursor pagination (their citer sets are large).

Output: data/openalex/uvt_citing_works.jsonl  (one full citing-work record per line,
deduped across batches by work id). Safe: writes .tmp, atomic-renames on success.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORKS = os.path.normpath(os.path.join(HERE, "..", "data", "openalex", "uvt_works.jsonl"))
OUT = os.path.normpath(os.path.join(HERE, "..", "data", "openalex", "uvt_citing_works.jsonl"))
TMP = OUT + ".tmp"
PER_PAGE = 200
BATCH = 50          # UVT work-ids OR'd per `cites:` filter (tail track)
HEAD_THRESHOLD = 200
BASE = "https://api.openalex.org/works"


def load_api_key():
    key = os.environ.get("OPENALEX_API_KEY")
    if key:
        return key
    env_path = os.path.join(HERE, "..", ".env")
    if os.path.isfile(env_path):
        for line in open(env_path, encoding="utf-8"):
            line = line.strip()
            if line.startswith("OPENALEX_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return None


API_KEY = load_api_key()
if not API_KEY:
    print("FATAL: OPENALEX_API_KEY not found", file=sys.stderr)
    sys.exit(1)


def short_id(full):
    # https://openalex.org/W123 -> W123
    return full.rsplit("/", 1)[-1]


def fetch(filter_expr, cursor):
    params = {
        "filter": filter_expr,
        "per-page": str(PER_PAGE),
        "cursor": cursor,
        "api_key": API_KEY,
        "mailto": "adi.spataru92@gmail.com",
    }
    url = BASE + "?" + urllib.parse.urlencode(params)
    for attempt in range(6):
        req = urllib.request.Request(url, headers={"User-Agent": "uvt-scholardex/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                prepaid = resp.headers.get("x-ratelimit-prepaid-remaining-usd")
                remaining = resp.headers.get("x-ratelimit-remaining")
                return json.loads(resp.read().decode("utf-8")), prepaid, remaining
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = int(e.headers.get("retry-after", "30"))
                if wait > 120:
                    print(f"  retry-after {wait}s too long; aborting to preserve time", file=sys.stderr)
                    raise
                print(f"  429; retry-after={wait}s (attempt {attempt+1})", file=sys.stderr)
                time.sleep(wait + 1)
                continue
            print(f"  HTTP {e.code}: {e.read().decode('utf-8','replace')[:300]}", file=sys.stderr)
            raise
    raise RuntimeError("exhausted retries")


def paginate(filter_expr, out, seen, req_counter):
    """Stream every page of filter_expr; write new citing works; return (#new, prepaid)."""
    cursor = "*"
    new = 0
    prepaid = None
    while cursor:
        data, prepaid, remaining = fetch(filter_expr, cursor)
        req_counter[0] += 1
        results = data["results"]
        for r in results:
            wid = r.get("id")
            if wid and wid not in seen:
                seen.add(wid)
                out.write(json.dumps(r, ensure_ascii=False) + "\n")
                new += 1
        cursor = data["meta"].get("next_cursor")
        if not results:
            break
    return new, prepaid, remaining


def main():
    head_ids, tail_ids = [], []
    with open(WORKS) as f:
        for line in f:
            r = json.loads(line)
            c = r.get("cited_by_count", 0) or 0
            if c <= 0:
                continue
            sid = short_id(r["id"])
            (head_ids if c > HEAD_THRESHOLD else tail_ids).append(sid)

    print(f"cited works: head={len(head_ids)} (>200), tail={len(tail_ids)} (1..200)")
    seen = set()
    req = [0]
    prepaid = remaining = None

    with open(TMP, "w", encoding="utf-8") as out:
        # HEAD: per paper
        for i, sid in enumerate(head_ids, 1):
            n, prepaid, remaining = paginate(f"cites:{sid}", out, seen, req)
            print(f"  [head {i}/{len(head_ids)}] {sid}: +{n} new (total {len(seen)}) "
                  f"reqs={req[0]} prepaid_usd={prepaid}")

        # TAIL: batched OR
        n_batches = (len(tail_ids) + BATCH - 1) // BATCH
        for bi in range(n_batches):
            chunk = tail_ids[bi * BATCH:(bi + 1) * BATCH]
            expr = "cites:" + "|".join(chunk)
            n, prepaid, remaining = paginate(expr, out, seen, req)
            if bi % 10 == 0 or bi == n_batches - 1:
                print(f"  [tail batch {bi+1}/{n_batches}] +{n} new (total {len(seen)}) "
                      f"reqs={req[0]} prepaid_usd={prepaid} remaining_req={remaining}")

    os.replace(TMP, OUT)
    print(f"DONE: {len(seen)} unique citing works -> {OUT}")
    print(f"      total requests: {req[0]}  prepaid_usd_remaining: {prepaid}")


if __name__ == "__main__":
    main()
