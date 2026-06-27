"""
brainmap.ro UVT national-projects dump generator (Playwright).

Offline source-dump stage for H64 (canonical projects): brainmap is the ONLY programmatic source for Romanian
national (UEFISCDI/PN-III) projects. Emits one JSON object per UVT project to data/brainmap/uvt_projects.jsonl —
the source dump the app's brainmap importer ingests later. Standalone (no app/Java change).

Login reuses the validated brainmap_test.py flow (natural homepage -> "Log In" -> auth-frame submit, which defeats
brainmap's WAF 550). Credentials come from scopus-python/.env (BRAINMAP_USER / BRAINMAP_PASS) and are NEVER printed.

USAGE
  # Pass A — discovery: dump a result page + one detail page so we can derive selectors.
  python3 scopus-python/brainmap_dump.py --discover
  # Pass B — small validation run, then the full resumable run.
  python3 scopus-python/brainmap_dump.py --limit 5
  python3 scopus-python/brainmap_dump.py            # full ~341, resumes from the checkpoint
  python3 scopus-python/brainmap_dump.py --headful  # watch the browser (debug)

SAFETY / ToS
  Gentle, randomized pacing (2-5s between detail fetches) to avoid account lock; resume-by-default so a lock or crash
  loses at most one record. brainmap's ToS applies — this is a personal, rate-limited export of the user's own org's
  public project records, not a bulk redistribution. .env and the scraped data live under gitignored paths.
"""
import argparse
import json
import pathlib
import random
import re
import sys
import time
from datetime import datetime, timezone

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent
OUT_DIR = REPO / "data" / "brainmap"           # gitignored (data/*)
OUT_JSONL = OUT_DIR / "uvt_projects.jsonl"
CHECKPOINT = OUT_DIR / "_checkpoint_done.json"  # set of brainmapIds already written
FAILURES = OUT_DIR / "_failures.json"           # ids that failed extraction (retried next run)
DISCOVER_DIR = pathlib.Path("/tmp/brainmap")

ORG_ID = "O-1600-000Y-0280"  # UNIVERSITATEA DE VEST TIMISOARA (~341 projects)
BASE = "https://www.brainmap.ro"

# Romanian field labels on the project detail page → our output keys. Refined after Pass A against the real DOM.
LABEL_MAP = {
    "acronim": "acronym",
    "cod": "code",
    "cod proiect": "code",
    "contract": "contractNo",
    "nr. contract": "contractNo",
    "data contract": "contractDate",
    "plan": "plan",
    "program": "programme",
    "subprogram": "subprogramme",
    "competi": "competition",        # competiție
    "finan": "funder",               # finanțator
    "perioad": "period",             # perioada
    "data început": "startDate",
    "data sfâr": "endDate",
    "website": "website",
    "pagina web": "website",
}


# ----------------------------------------------------------------------------- creds / login (from brainmap_test.py)
def load_creds():
    env = ROOT / ".env"
    if not env.exists():
        sys.exit("ERROR: scopus-python/.env not found. Copy .env.example -> .env and fill creds.")
    user = pwd = None
    for line in env.read_text().splitlines():
        line = line.strip()
        if line.startswith("BRAINMAP_USER="):
            user = line.split("=", 1)[1].strip()
        elif line.startswith("BRAINMAP_PASS="):
            pwd = line.split("=", 1)[1].strip()
    if not user or not pwd:
        sys.exit("ERROR: BRAINMAP_USER / BRAINMAP_PASS not both set in scopus-python/.env")
    print(f"creds loaded: user={user!r}, pass=<{len(pwd)} chars hidden>")  # never print the password
    return user, pwd


def login(page, user, pwd):
    """Validated flow: navigate naturally (homepage -> Log In), submit in the auth frame. Returns True on success."""
    print("login: homepage (seed session + token chain)")
    page.goto(f"{BASE}/", wait_until="networkidle", timeout=45000)
    page.wait_for_timeout(1200)
    page.locator("text=/^log\\s*in$/i").first.click()
    page.wait_for_timeout(2500)

    auth = [f for f in page.frames if "module.org.bm2.auth" in f.url]
    frame = auth[0] if auth else page
    frame.locator("input[type=email], input[type=text]").first.fill(user)
    pw = frame.locator("input[type=password]").first
    pw.fill(pwd)
    pw.press("Enter")
    page.wait_for_load_state("networkidle", timeout=30000)
    page.wait_for_timeout(2000)

    body = page.inner_text("body").lower()
    blocked = "security issue" in body or "550" in body
    has_pw = any(f.locator("input[type=password]").count() > 0 for f in page.frames)
    ok = not blocked and not has_pw
    print(f"login: {'OK' if ok else 'FAILED/BLOCKED'} (blocked={blocked}, password-present={has_pw})")
    return ok


SEARCH_MODULE = "module.org.bm2.management.searchAdvanced"
RESULTS_MODULE = "module.org.bm2.management.resultsregistry"


def follow_module_link(page, module_substr, label):
    """
    Navigate to an in-app module by following the page's own token-bearing link (?we=<module>&wtkps=…&wchk=…).
    A bare ?we= GET hits module.org.bm2.error — the jas framework requires the per-link state token/checksum.
    """
    link = page.locator(f"a[href*='{module_substr}']").first
    if link.count() == 0:
        print(f"{label}: WARNING no token link for {module_substr} on this page (url={page.url[:80]})")
        return False
    href = link.get_attribute("href")
    page.goto(absolute(href), wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(2800)
    err = "module.org.bm2.error" in page.content()
    print(f"{label}: followed link -> url={page.url[:90]} (error_page={err})")
    return not err


def open_advanced_search(page):
    return follow_module_link(page, "searchAdvanced", "search")


ORG_NAME = "Universitatea de Vest din Timişoara"


def dump(page, tag):
    DISCOVER_DIR.mkdir(exist_ok=True)
    page.screenshot(path=str(DISCOVER_DIR / f"{tag}.png"), full_page=True)
    (DISCOVER_DIR / f"{tag}.html").write_text(page.content(), encoding="utf-8")
    print(f"  [dump] {tag}")


def set_org_filter_and_submit(page, org_name=ORG_NAME):
    """
    Advanced projects search: type the institution name into the autocomplete (search.instNumeA), pick the suggestion,
    then click 'Caută' (btnOpen). The org filter is institution-name-based (not the O-… id).
    """
    inst = page.locator("input[id$='instNumeA']").first
    if inst.count() == 0:
        print("search: WARNING institution autocomplete (instNumeA) not found")
    else:
        inst.click()
        inst.press_sequentially(org_name, delay=90)  # jQuery UI autocomplete needs real keystrokes, not fill()
        try:
            page.wait_for_selector("ul.ui-autocomplete li", state="visible", timeout=10000)
        except PWTimeout:
            pass
        sugg = page.locator("ul.ui-autocomplete li:visible").filter(has_text="Universitatea de Vest")
        print(f"search: autocomplete suggestions matching UVT = {sugg.count()}")
        if sugg.count() > 0:
            sugg.first.click()
        else:
            inst.press("ArrowDown")
            inst.press("Enter")
        page.wait_for_timeout(1200)
    btn = page.locator("input[id$='btnOpen']").first
    if btn.count() > 0:
        btn.click()
    else:
        print("search: WARNING Caută (btnOpen) not found")
    page.wait_for_load_state("networkidle", timeout=30000)
    page.wait_for_timeout(3000)


# ----------------------------------------------------------------------------- result list + pagination
def collect_project_links(page):
    """Collect (id, url) for every project detail link across all result pages. Selectors refined after Pass A."""
    seen, links = set(), []
    pageno = 1
    while True:
        anchors = page.locator("a[href*='project'], a[href*='proiect'], a[href*='we=']")
        n = anchors.count()
        added = 0
        for i in range(n):
            href = anchors.nth(i).get_attribute("href") or ""
            pid = project_id_from_href(href)
            if pid and pid not in seen:
                seen.add(pid)
                links.append((pid, absolute(href)))
                added += 1
        print(f"list: page {pageno}: +{added} links (total {len(links)})")
        # Next page.
        nxt = page.locator("a[rel=next], a:has-text('»'), li.next a, button:has-text('Next')")
        if nxt.count() == 0 or added == 0:
            break
        try:
            nxt.first.click(timeout=8000)
            page.wait_for_load_state("networkidle", timeout=30000)
            page.wait_for_timeout(random.uniform(1.5, 3.0))
            pageno += 1
        except PWTimeout:
            break
    return links


def project_id_from_href(href):
    if not href:
        return None
    m = re.search(r"(?:project|proiect|pid)[=/_-]?([A-Za-z0-9_-]{4,})", href)
    if m:
        return m.group(1)
    m = re.search(r"we=([A-Za-z0-9_.-]+)", href)
    return m.group(1) if m else None


def absolute(href):
    if href.startswith("http"):
        return href
    return f"{BASE}/{href.lstrip('/')}"


# ----------------------------------------------------------------------------- detail extraction
def extract_detail(page, pid, url):
    """
    Best-effort extraction of the brainmap project detail fields. Uses label→value proximity + section parsing; keeps
    raw_text as a fallback so even a pre-refinement run yields usable records. Refined after Pass A.
    """
    page.goto(url, wait_until="networkidle", timeout=45000)
    page.wait_for_timeout(random.uniform(1.0, 2.0))
    text = page.inner_text("body")

    rec = {
        "brainmapId": pid, "url": url, "orgId": ORG_ID,
        "acronym": None, "title_ro": None, "title_en": None, "code": None,
        "contractNo": None, "contractDate": None, "plan": None, "programme": None,
        "subprogramme": None, "competition": None, "funder": None, "domains": [],
        "startDate": None, "endDate": None, "website": None,
        "abstract_ro": None, "abstract_en": None,
        "coordinator": None, "partners": [], "director": None,
        "scrapedAt": datetime.now(timezone.utc).isoformat(),
    }

    # Label rows: brainmap detail pages render "Label\nValue" or "Label: Value". Map known labels.
    for raw_label, key in LABEL_MAP.items():
        val = value_after_label(text, raw_label)
        if val and not rec.get(key):
            rec[key] = val

    # Title (usually the page H1/H2).
    for sel in ["h1", "h2", ".project-title", "[class*=title]"]:
        loc = page.locator(sel)
        if loc.count() > 0:
            t = loc.first.inner_text().strip()
            if t and len(t) > 5:
                rec["title_ro"] = t
                break

    # Abstract / rezumat: capture the block after the "Rezumat"/"Abstract" heading.
    rec["abstract_ro"] = block_after(text, ["rezumat"]) or rec["abstract_ro"]
    rec["abstract_en"] = block_after(text, ["abstract", "summary"]) or rec["abstract_en"]

    # Partners + director: parsed from the partners section (a table/list of org + role; director is a person+role).
    # Selector-specific — left as a structured TODO refined after Pass A; raw_text preserves the data meanwhile.
    rec["raw_text"] = text[:6000]
    return rec


def value_after_label(text, label):
    pat = re.compile(rf"{re.escape(label)}[^\S\n]*[:\n]\s*(.+)", re.IGNORECASE)
    m = pat.search(text)
    if not m:
        return None
    v = m.group(1).strip().splitlines()[0].strip()
    return v or None


def block_after(text, headings):
    for h in headings:
        m = re.search(rf"\n{re.escape(h)}\b\s*[:\n]+(.+?)(?:\n[A-ZĂÂÎȘȚ][^\n]{{0,40}}:|\Z)", text, re.IGNORECASE | re.DOTALL)
        if m:
            b = m.group(1).strip()
            if len(b) > 30:
                return b
    return None


# ----------------------------------------------------------------------------- checkpoint / output
def load_done():
    if CHECKPOINT.exists():
        return set(json.loads(CHECKPOINT.read_text()))
    return set()


def save_done(done):
    CHECKPOINT.write_text(json.dumps(sorted(done)))


def append_record(rec):
    with OUT_JSONL.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


# ----------------------------------------------------------------------------- results-list parse (H64 core fields)
def parse_result_rows(page):
    """
    Parse the current results page. Each project is a row whose fields are id'd <prefix>_list.<field>@<row>:
    pkXProiectId (hidden input), pTitluOficial (<a> title + goToProiect detail href), codDepunere, plan, competitieD,
    dpPrenume/dpNume/dpRol (director person+role), coordonator, numeInstFin (funder), anulInceperii/Incheierii.
    """
    html = page.content()
    m = re.search(r"(\d+)_list\.", html)
    if not m:
        return []
    prefix = m.group(1)
    rows = sorted({int(r) for r in re.findall(rf"{prefix}_list\.[A-Za-z0-9]+@(\d+)", html) if int(r) >= 0})

    def el(field, row):
        loc = page.locator(f"[id='{prefix}_list.{field}@{row}']")
        return loc.first if loc.count() > 0 else None

    def txt(field, row):
        e = el(field, row)
        try:
            return e.inner_text().strip() if e else None
        except Exception:
            return None

    out = []
    for r in rows:
        pk = el("pkXProiectId", r)
        title = el("pTitluOficial", r)
        rec = {
            "pkXProiectId": pk.get_attribute("value") if pk else None,
            "title": txt("pTitluOficial", r),
            "detailHref": (title.get_attribute("href") if title else None),
            "code": txt("codDepunere", r),
            "plan": txt("plan", r),
            "competition": txt("competitieD", r),
            "directorFirst": txt("dpPrenume", r),
            "directorLast": txt("dpNume", r),
            "directorRole": txt("dpRol", r),
            "coordinator": txt("coordonator", r),
            "funder": txt("numeInstFin", r),
            "startYear": txt("anulInceperii", r),
            "endYear": txt("anulIncheierii", r),
            "orgId": ORG_ID,
            "scrapedAt": datetime.now(timezone.utc).isoformat(),
        }
        if rec["pkXProiectId"] or rec["code"]:
            out.append(rec)
    return out


def goto_next_page(page):
    """Advance the results pager (jas Table pageSelector links). Returns True if it moved to the next page."""
    sel = page.locator(".tablePageNumberSelected").first
    if sel.count() == 0:
        return False
    try:
        cur = int(sel.inner_text().strip())
    except (ValueError, Exception):
        return False
    links = page.locator("a[id$='pageSelector']")
    for i in range(links.count()):
        try:
            if links.nth(i).inner_text().strip() == str(cur + 1):
                links.nth(i).click()
                page.wait_for_load_state("networkidle", timeout=25000)
                page.wait_for_timeout(random.uniform(1.5, 2.8))
                return True
        except Exception:
            continue
    return False


def harvest_results(page, limit=0):
    """Walk every results page, parsing the list rows. Dedups by pkXProiectId. Returns the project records."""
    seen, records = set(), []
    pageno = 1
    while True:
        rows = parse_result_rows(page)
        added = 0
        for rec in rows:
            key = rec.get("pkXProiectId") or rec.get("code")
            if key and key not in seen:
                seen.add(key)
                records.append(rec)
                added += 1
        print(f"harvest: page {pageno}: +{added} (total {len(records)})")
        if limit and len(records) >= limit:
            return records[:limit]
        if added == 0 or not goto_next_page(page):
            break
        pageno += 1
    return records


# ----------------------------------------------------------------------------- modes
def run_discover(page):
    DISCOVER_DIR.mkdir(exist_ok=True)
    if not open_advanced_search(page):
        return
    dump(page, "search_form")
    # Fill the institution autocomplete + dump the suggestion dropdown (to derive the suggestion selector).
    inst = page.locator("input[id$='instNumeA']").first
    if inst.count() > 0:
        inst.click()
        inst.press_sequentially(ORG_NAME, delay=90)  # jQuery UI autocomplete needs real keystrokes, not fill()
        try:
            page.wait_for_selector("ul.ui-autocomplete li", state="visible", timeout=10000)
        except PWTimeout:
            print("discover: autocomplete dropdown did not appear")
        dump(page, "autocomplete")
        sugg = page.locator("ul.ui-autocomplete li:visible").filter(has_text="Universitatea de Vest")
        print(f"discover: UVT autocomplete matches = {sugg.count()}")
        if sugg.count() > 0:
            sugg.first.click()
        else:
            inst.press("ArrowDown")
            inst.press("Enter")
        page.wait_for_timeout(1200)
    btn = page.locator("input[id$='btnOpen']").first
    if btn.count() > 0:
        btn.click()
        page.wait_for_load_state("networkidle", timeout=30000)
        page.wait_for_timeout(3000)
    dump(page, "results")
    links = collect_project_links(page)
    print(f"discover: collected {len(links)} project links (expected ~341)")
    if links:
        pid, url = links[0]
        page.goto(url, wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1500)
        page.screenshot(path=str(DISCOVER_DIR / f"detail_{pid}.png"), full_page=True)
        (DISCOVER_DIR / f"detail_{pid}.html").write_text(page.content(), encoding="utf-8")
        print(f"discover: dumped detail for {pid} -> {DISCOVER_DIR}/detail_{pid}.html")
    print(f"\nInspect {DISCOVER_DIR}/ to derive selectors, then run without --discover.")


def run_dump(page, limit):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    open_advanced_search(page)
    set_org_filter_and_submit(page, ORG_ID)
    links = collect_project_links(page)
    done = load_done()
    todo = [(pid, url) for pid, url in links if pid not in done]
    if limit:
        todo = todo[:limit]
    print(f"dump: {len(links)} found, {len(done)} already done, fetching {len(todo)}")

    failures = []
    for i, (pid, url) in enumerate(todo, 1):
        try:
            rec = extract_detail(page, pid, url)
            append_record(rec)
            done.add(pid)
            save_done(done)
            print(f"  [{i}/{len(todo)}] {pid}: {rec.get('acronym') or rec.get('title_ro') or 'ok'}")
        except Exception as e:
            failures.append({"id": pid, "url": url, "error": str(e)[:200]})
            print(f"  [{i}/{len(todo)}] {pid}: FAILED ({str(e)[:80]})")
        time.sleep(random.uniform(2.0, 5.0))  # polite pacing

    if failures:
        FAILURES.write_text(json.dumps(failures, ensure_ascii=False, indent=2))
    print(f"\ndump: done={len(done)} failed={len(failures)} -> {OUT_JSONL}")


def run_manual(page, wait_s, extract, limit):
    """
    Human-in-the-loop: log in + open the advanced search, then WAIT while the user picks UVT and clicks 'Caută' in the
    visible browser (the jQuery-UI institution autocomplete is fragile to script, trivial for a human). After the wait
    the script takes over: dumps the real results + first detail page (for selector work) and, with --extract, runs the
    full paginated extraction.
    """
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if not open_advanced_search(page):
        sys.exit("manual: could not open the advanced search.")
    print("\n" + "=" * 72)
    print("MANUAL STEP — in the opened browser window:")
    print("  1) type the institution name and pick  UNIVERSITATEA DE VEST DIN TIMIŞOARA")
    print("  2) click 'Caută' and wait until the project results are shown")
    print(f"  The script continues automatically in {wait_s}s (use --wait to change).")
    print("=" * 72 + "\n")
    for left in range(wait_s, 0, -5):
        print(f"  ...{left}s")
        page.wait_for_timeout(5000)

    dump(page, "results")  # snapshot for reference
    # Harvest the project fields straight off the results list (no per-detail navigation). Without --extract, only
    # the first ~2 pages are parsed as a quick validation; --extract walks all ~35 pages and writes the JSONL.
    records = harvest_results(page, limit if limit else (0 if extract else 20))
    print(f"manual: harvested {len(records)} projects (expected ~341 for the full run)")
    if records:
        s = records[0]
        print(f"  sample[0]: code={s.get('code')} | title={(s.get('title') or '')[:50]!r}")
        print(f"             director={s.get('directorFirst')} {s.get('directorLast')} ({s.get('directorRole')}) "
              f"| coord={(s.get('coordinator') or '')[:34]!r} | funder={(s.get('funder') or '')[:30]!r}")

    if not extract:
        print("\nmanual: validation only (no write). If the sample looks right, re-run with --extract to write all ~341.")
        return

    done = load_done()
    written = 0
    for rec in records:
        key = rec.get("pkXProiectId") or rec.get("code")
        if not key or key in done:
            continue
        append_record(rec)
        done.add(key)
        written += 1
    save_done(done)
    print(f"\nmanual: wrote {written} new records ({len(done)} total) -> {OUT_JSONL}")


def main():
    global ORG_ID
    ap = argparse.ArgumentParser(description="brainmap UVT projects dump generator")
    ap.add_argument("--discover", action="store_true", help="Pass A: dump a result page + one detail page to /tmp/brainmap")
    ap.add_argument("--manual", action="store_true", help="headful; pause for the user to pick UVT + Caută, then take over")
    ap.add_argument("--extract", action="store_true", help="(with --manual) run the full extraction after the manual search")
    ap.add_argument("--wait", type=int, default=30, help="(with --manual) seconds to wait for the manual selection")
    ap.add_argument("--limit", type=int, default=0, help="fetch at most N new projects (small validation run)")
    ap.add_argument("--headful", action="store_true", help="show the browser (debug)")
    ap.add_argument("--org", default=ORG_ID, help="brainmap organization id (default = UVT)")
    args = ap.parse_args()
    ORG_ID = args.org

    user, pwd = load_creds()
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not (args.headful or args.manual))  # --manual implies headful
        ctx = browser.new_context(locale="ro-RO", viewport={"width": 1400, "height": 1000})
        page = ctx.new_page()
        try:
            if not login(page, user, pwd):
                sys.exit("Aborting: login failed/blocked. Inspect the flow with --headful.")
            if args.manual:
                run_manual(page, args.wait, args.extract, args.limit)
            elif args.discover:
                run_discover(page)
            else:
                run_dump(page, args.limit)
        finally:
            browser.close()


if __name__ == "__main__":
    main()
