"""
brainmap.ro login + Advanced Projects Search smoke test (Playwright).

Reads credentials from scopus-python/.env (BRAINMAP_USER / BRAINMAP_PASS) — never prints the password.
Goal of this first run: confirm we can (1) log in, (2) reach the Advanced Projects Search and filter an
organization, observing the result count. Heavily instrumented (screenshots + page-text dumps to /tmp)
because the login fields are JS-rendered and the search flow is not yet known — we iterate from what it finds.

Run:  python3 scopus-python/brainmap_test.py
"""
import os
import re
import sys
import pathlib

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
OUT = pathlib.Path("/tmp/brainmap")
OUT.mkdir(exist_ok=True)
ORG_ID = "O-1600-000Y-0280"  # UNIVERSITATEA DE VEST TIMISOARA (expected ~341 projects)


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


def dump(page, tag):
    try:
        page.screenshot(path=str(OUT / f"{tag}.png"), full_page=True)
        (OUT / f"{tag}.txt").write_text(page.inner_text("body")[:8000], encoding="utf-8")
        print(f"  [dump] {tag}: url={page.url}")
    except Exception as e:
        print(f"  [dump] {tag} failed: {e}")


def main():
    user, pwd = load_creds()
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(locale="ro-RO", viewport={"width": 1400, "height": 1000})
        page = ctx.new_page()

        # IMPORTANT: navigate naturally (homepage -> click "Log In"). Jumping straight to the
        # ?we=module.org.bm2.auth URL skips brainmap's token chain and trips its WAF
        # ("Security issue detected (550)"), which withholds the login form.
        print("1) homepage (seed session + token chain)")
        page.goto("https://www.brainmap.ro/", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1200)
        page.locator("text=/^log\\s*in$/i").first.click()
        page.wait_for_timeout(2500)
        dump(page, "01_login_form")

        print("2) fill creds in the auth frame + submit")
        auth = [f for f in page.frames if "module.org.bm2.auth" in f.url]
        frame = auth[0] if auth else page
        frame.locator("input[type=email], input[type=text]").first.fill(user)
        pw = frame.locator("input[type=password]").first
        pw.fill(pwd)
        pw.press("Enter")
        page.wait_for_load_state("networkidle", timeout=30000)
        page.wait_for_timeout(2000)
        dump(page, "02_after_login")

        body = page.inner_text("body").lower()
        blocked = "security issue" in body or "550" in body
        has_pw = any(f.locator("input[type=password]").count() > 0 for f in page.frames)
        print(f"   LOGIN {'OK' if (not blocked and not has_pw) else 'FAILED/BLOCKED'} (blocked={blocked}, password-present={has_pw})")

        print("3) open Advanced Projects Search")
        try:
            page.locator("text=/advanced projects search/i").first.click(timeout=8000)
            page.wait_for_load_state("networkidle", timeout=30000)
            page.wait_for_timeout(2000)
            dump(page, "03_advanced_search")
            print(f"   reached search module: {'searchAdvanced' in page.url}")
            # TODO(next): fill the organization filter ({ORG_ID}), submit, paginate, extract rows.
        except Exception as e:
            print(f"   could not open Advanced Projects Search: {e}")

        browser.close()
    print(f"\nDone. Inspect screenshots + text in {OUT}/ to see how far we got.")


if __name__ == "__main__":
    main()
