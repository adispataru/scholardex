"""
JCR (jcr.clarivate.com) discovery pass — Pass A of the JIF 2020–2025 harvest.

Goal: learn the SPA's internal JSON endpoints (the journals-browse grid, its pagination shape, and the
JCR-year filter parameter) so Pass B can page through the full journal list per year. Nothing is harvested
here beyond a handful of sample responses.

Flow (headful — YOU do the login):
  1. Opens jcr.clarivate.com in a visible Chromium window.
  2. You complete the institutional sign-in (Anelis/UVT SSO) in that window.
  3. Back in the terminal, press ENTER — the script then navigates to the Journals browse page,
     records every JSON API call (URL, method, request body, response sample), clicks to the next
     page of the grid if it can find the control, and tries to open one journal profile.
  4. Everything lands in /tmp/jcr/: requests.jsonl (the API index), body_NNN.json (response samples),
     *.png screenshots. The session is saved to scopus-python/_state/jcr_state.json so Pass B can
     reuse it without a fresh login (state file is git-ignored; do not commit it).

Run:  python3 scopus-python/jcr_discover.py
Subscriber-use note: we only observe the same requests the UI makes; the harvest itself (Pass B) stays
gently paced and for internal evaluation use.
"""
import json
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
OUT = pathlib.Path("/tmp/jcr")
OUT.mkdir(exist_ok=True)
STATE_DIR = ROOT / "_state"
STATE_DIR.mkdir(exist_ok=True)
STATE_FILE = STATE_DIR / "jcr_state.json"

HOME_URL = "https://jcr.clarivate.com/jcr/home"
BROWSE_URL = "https://jcr.clarivate.com/jcr/browse-journals"

MAX_BODY_BYTES = 300_000
captured = []


def dump(page, tag):
    try:
        page.screenshot(path=str(OUT / f"{tag}.png"), full_page=False)
        (OUT / f"{tag}.txt").write_text(page.inner_text("body")[:8000], encoding="utf-8")
        print(f"  [dump] {tag}: url={page.url}")
    except Exception as e:
        print(f"  [dump] {tag} failed: {e}")


def interesting(url: str) -> bool:
    if "clarivate.com" not in url:
        return False
    # skip static assets and telemetry noise
    if re.search(r"\.(js|css|png|jpg|svg|woff2?|ico|map)(\?|$)", url):
        return False
    if any(t in url for t in ("pendo", "analytics", "hotjar", "googletag", "newrelic", "usage")):
        return False
    return True


def on_response(response):
    try:
        url = response.url
        if not interesting(url):
            return
        ctype = (response.headers or {}).get("content-type", "")
        if "json" not in ctype and "text" not in ctype:
            return
        idx = len(captured)
        body = ""
        try:
            body = response.text()[:MAX_BODY_BYTES]
        except Exception:
            pass
        post_data = None
        try:
            post_data = response.request.post_data
        except Exception:
            pass
        record = {
            "idx": idx,
            "method": response.request.method,
            "url": url,
            "status": response.status,
            "contentType": ctype,
            "postData": post_data[:4000] if post_data else None,
            "bodyBytes": len(body),
        }
        captured.append(record)
        (OUT / f"body_{idx:03d}.json").write_text(body, encoding="utf-8")
        with (OUT / "requests.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(f"  [api {idx:03d}] {record['method']} {url[:120]} -> {response.status} ({len(body)}B)")
    except Exception as e:
        print(f"  [capture error] {e}")


def try_click(page, selectors, label):
    for sel in selectors:
        try:
            el = page.locator(sel).first
            if el.count() > 0 and el.is_visible():
                print(f"3.x) clicking {label} via {sel!r}")
                el.click()
                page.wait_for_timeout(4000)
                return True
        except Exception:
            continue
    print(f"  [skip] no visible control found for {label} (fine — Pass A just records what it can)")
    return False


def main():
    (OUT / "requests.jsonl").write_text("", encoding="utf-8")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False)
        ctx = browser.new_context(viewport={"width": 1500, "height": 1000})
        page = ctx.new_page()

        print(f"1) opening {HOME_URL} — complete the institutional sign-in in the browser window")
        page.goto(HOME_URL, wait_until="domcontentloaded", timeout=90_000)
        input("   ... when you are signed in and see the JCR home page, press ENTER here: ")
        dump(page, "01_home_after_login")
        ctx.storage_state(path=str(STATE_FILE))
        print(f"   session saved to {STATE_FILE} (git-ignored; Pass B reuses it)")

        # only now start recording — login traffic (and your credentials) are never captured
        page.on("response", on_response)

        print(f"2) navigating to the journals browse: {BROWSE_URL}")
        page.goto(BROWSE_URL, wait_until="domcontentloaded", timeout=90_000)
        page.wait_for_timeout(8000)  # let the grid issue its data calls
        dump(page, "02_browse_journals")

        # try to page the grid once — the pagination request is the key discovery
        try_click(page, [
            "button[aria-label='Next Page']",
            "button[aria-label='Next page']",
            "button.mat-paginator-navigation-next",
            "[data-testid='pagination-next']",
            "li.pagination-next a",
        ], "grid next-page")
        dump(page, "03_after_next_page")

        # try to open the JCR-year selector so its request shape shows up
        try_click(page, [
            "text=JCR Year",
            "[aria-label*='JCR Year']",
            "button:has-text('2024')",
            "button:has-text('2025')",
        ], "JCR year filter")
        dump(page, "04_after_year_filter")

        # open the first journal profile — profiles carry the multi-year JIF trend in one response
        try_click(page, [
            "table a[href*='journal-profile']",
            "a[href*='journal-profile']",
            "table tbody tr td a",
        ], "first journal profile")
        page.wait_for_timeout(6000)
        dump(page, "05_journal_profile")

        print(f"\nDone. Captured {len(captured)} API responses in {OUT}/")
        print("Leave the window open if you want to poke around; closing it ends the session recording.")
        input("Press ENTER to close the browser: ")
        ctx.storage_state(path=str(STATE_FILE))
        browser.close()


if __name__ == "__main__":
    main()
