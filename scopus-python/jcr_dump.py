"""
JCR journals harvest — Pass B. Pages the jcr.clarivate.com journals-browse API (the same
`search-result` endpoint the UI grid uses, discovered by jcr_discover.py) for each requested JCR year
and writes one raw JSONL per year under data/jcr-web/. Resumable (per year+offset checkpoint),
gently paced (2–4s jitter), subscriber-session based (reuses scopus-python/_state/jcr_state.json;
run jcr_discover.py again if the session has expired).

The POSTs are issued from INSIDE an authenticated page (same-origin fetch), so whatever cookies or
headers the SPA relies on are the browser's own — we never reverse-engineer auth material.

Run:  python3 scopus-python/jcr_dump.py [--years 2020-2025] [--page-size 200] [--limit N]
Then: python3 scopus-python/jcr_convert.py   (raw JSONL -> per-year/edition wos-json ingest files)
"""
import argparse
import json
import pathlib
import random
import sys
import time

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent
STATE_FILE = ROOT / "_state" / "jcr_state.json"
CHECKPOINT_FILE = ROOT / "_checkpoint" / "jcr_done.json"
OUT_DIR = REPO / "data" / "jcr-web"

BROWSE_URL = "https://jcr.clarivate.com/jcr/browse-journals"
API_PATH = "/api/jcr3/bwjournal/v1/search-result"


def load_checkpoint():
    if CHECKPOINT_FILE.exists():
        return json.loads(CHECKPOINT_FILE.read_text())
    return {}


def save_checkpoint(cp):
    CHECKPOINT_FILE.parent.mkdir(exist_ok=True)
    CHECKPOINT_FILE.write_text(json.dumps(cp, indent=1))


def request_body(year: int, start: int, count: int) -> dict:
    # exactly the UI's request, with an ISSN-stable sort so pagination never shuffles between pages
    return {
        "journalFilterParameters": {
            "query": "", "journals": [], "categories": [], "publishers": [], "countryRegions": [],
            "citationIndexes": ["SCIE", "SSCI", "AHCI", "ESCI"],
            "jcrYear": year, "categorySchema": "WOS", "openAccess": "N",
            "jifQuartiles": [], "jifRanges": [], "jifNA": False, "jifPercentileRanges": [],
            "jciRanges": [], "oaRanges": [], "issnJ20s": [],
        },
        "retrievalParameters": {"start": start, "count": count, "sortBy": "journalName", "sortOrder": "ASC"},
    }


def fetch_page(page, year: int, start: int, count: int):
    result = page.evaluate(
        """async ({apiPath, body}) => {
            const res = await fetch(apiPath, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body),
            });
            const text = await res.text();
            return {status: res.status, text};
        }""",
        {"apiPath": API_PATH, "body": request_body(year, start, count)},
    )
    if result["status"] != 200:
        raise RuntimeError(f"HTTP {result['status']} for year={year} start={start}: {result['text'][:300]}")
    payload = json.loads(result["text"])
    if payload.get("status") != "Success":
        raise RuntimeError(f"API status={payload.get('status')} for year={year} start={start}")
    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--years", default="2020-2025", help="inclusive range, e.g. 2020-2025 or a single year")
    parser.add_argument("--page-size", type=int, default=200)
    parser.add_argument("--limit", type=int, default=0, help="stop after N pages total (smoke runs)")
    args = parser.parse_args()

    if "-" in args.years:
        lo, hi = args.years.split("-", 1)
        years = list(range(int(lo), int(hi) + 1))
    else:
        years = [int(args.years)]

    if not STATE_FILE.exists():
        sys.exit(f"ERROR: {STATE_FILE} missing — run jcr_discover.py first (it saves the login session).")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    checkpoint = load_checkpoint()
    pages_fetched = 0

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(storage_state=str(STATE_FILE), viewport={"width": 1400, "height": 900})
        page = ctx.new_page()
        print(f"1) opening {BROWSE_URL} with the saved session")
        page.goto(BROWSE_URL, wait_until="domcontentloaded", timeout=90_000)
        page.wait_for_timeout(5000)
        if "login" in page.url or "access.clarivate" in page.url:
            sys.exit("ERROR: session expired (redirected to login). Re-run jcr_discover.py to refresh it.")

        # probe: confirms auth + the effective page size the API tolerates
        probe = fetch_page(page, years[0], 1, args.page_size)
        got = len(probe.get("data", []))
        page_size = args.page_size if got == args.page_size else max(got, 25)
        print(f"   probe ok: totalCount({years[0]})={probe.get('totalCount')} pageSize={page_size}")

        for year in years:
            out_file = OUT_DIR / f"jcr-journals-{year}.jsonl"
            year_cp = checkpoint.get(str(year), {"nextStart": 1, "totalCount": None, "done": False})
            if year_cp.get("done"):
                print(f"2) year {year}: already complete, skipping")
                continue
            print(f"2) year {year}: starting at offset {year_cp['nextStart']}")
            with out_file.open("a", encoding="utf-8") as out:
                while True:
                    if args.limit and pages_fetched >= args.limit:
                        print(f"   page limit {args.limit} reached — stopping (resume later)")
                        save_checkpoint(checkpoint)
                        return
                    start = year_cp["nextStart"]
                    payload = fetch_page(page, year, start, page_size)
                    rows = payload.get("data", [])
                    total = int(payload.get("totalCount", 0))
                    for row in rows:
                        row["_jcrYear"] = year
                        out.write(json.dumps(row, ensure_ascii=False) + "\n")
                    out.flush()
                    pages_fetched += 1
                    year_cp["nextStart"] = start + len(rows)
                    year_cp["totalCount"] = total
                    checkpoint[str(year)] = year_cp
                    save_checkpoint(checkpoint)
                    print(f"   year {year}: {min(year_cp['nextStart'] - 1, total)}/{total}")
                    if len(rows) == 0 or year_cp["nextStart"] > total:
                        year_cp["done"] = True
                        save_checkpoint(checkpoint)
                        print(f"   year {year}: DONE ({total} journals)")
                        break
                    time.sleep(random.uniform(2.0, 4.0))

        browser.close()
    print("All requested years complete.")


if __name__ == "__main__":
    main()
