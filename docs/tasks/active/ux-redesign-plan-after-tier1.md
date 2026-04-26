# UX Redesign Plan — After Tier 1

Status: planning (decisions locked 2026-04-26)
Date: 2026-04-26
Supersedes (for forward-looking work): `docs/tasks/active/ux-redesign-plan.md`
Reference: `docs/ux-design-guide.md`, `docs/frontend-conventions.md`, `TASKS.md`, `TASKS-done.md`

---

## Why This File Exists

The original `ux-redesign-plan.md` was written before Tier 1 was built. Tier 1 is now substantially complete (H36, H37, H39, H40), and several assumptions baked into the original Tier 2 / Tier 3 / Phases sections no longer hold:

- Tier 2.1 (publication wizard) was largely absorbed into the workspace as an inline wizard (H36.9), but the standalone `publications-add-step*.html` templates are still on disk.
- Tier 2.2 (edit/detail pages) lost its user-side scope to the workspace; only admin edit pages remain candidates.
- Tier 3.1 (shared fragments) was partially built piecemeal during Tier 1 — some fragments exist, others are still ad-hoc.
- Several Option C features from Tier 1 were explicitly deferred (PDF export, admin group report parity) or dropped (what-if analysis, per-criterion breakdown charts) and need a clear home.
- The "Phase 0–5, weeks 1–14" schedule is no longer the way to think about remaining work.

This document reassesses what's left, locks in the structural decisions, and leaves option choices open only where they require a separate product call (depth/scope of an in-scope feature).

---

## Tier 1 — Status Snapshot

| Plan Section | Task | Option Built | Status |
|---|---|---|---|
| 1.1 Researcher Workspace | H36 | Option C | Done |
| 1.2 Evaluation Workspace | H37 | Option C scoped (no PDF export, no admin group parity, what-if and breakdown charts dropped) | Done |
| 1.3 Admin Operations Center | H39 | ≈ Option A (live dashboard + ops cards, no priority queue, no real-time polling) | Done |
| 1.4 Admin Data Management | H40 | Option B + 3 Option C features (bulk ops, column toggles, keyboard shortcuts) | Done |

Sidebar restructure landed with H36.12 (user) and H40.2 (admin). Old URLs redirect through controller-level `redirect:` rules. Legacy templates were deleted in H36.14, H37.10, H40.12.

What did **not** ship as part of Tier 1 and is still open is folded into **Tier 1.5 — Tier 1 Follow-Ups** below.

---

## Decisions Locked (2026-04-26)

These resolve the open structural questions. Subsections below collapse to the chosen path.

1. **Tier 1.5 — Tier 1 Follow-Ups:** **All dropped.** No PDF export, no admin group report parity, no what-if / breakdown charts, no Operations Center expansion, no H40 Option C extras. If any of these come back, they re-enter as fresh tasks referencing the original §1.2 / §1.3 / §1.4 Option C language.
2. **Tier 2.1 — Standalone publication wizard:** **Delete it.** The inline wizard (H36.9) is the only path going forward.
3. **Tier 2.2 — Admin edit pages:** **Option D — collapse simple admin edits into row-edit modals.** Keep dedicated pages only for genuinely long forms (group/individual report definitions).
4. **Tier 2.3 — Public / ranking pages:** **In scope this cycle.** Specific option (A/B/C) still to pick.
5. **Tier 3.1 — Shared fragments:** **Option C — full component library.** Lock the design system before scaling further work.
6. *(Open)* Tier 3.2-3.4 — global audits — still defaulting to in-task verification unless you want a clean compliance milestone.

The remaining option-level questions (where a depth choice is still needed) are listed at the bottom under *Remaining Open Choices*.

---

## Tier 1.5 — Tier 1 Follow-Ups — ALL DROPPED

The five candidates here are all dropped from this plan:

- **1.5.1 PDF export of the full evaluation report** — drop. Researchers can use the browser's built-in print-to-PDF if needed; no first-class PDF export until a real ask.
- **1.5.2 Admin group report parity with researcher workspace** — drop. The admin group report view stays on its current layout.
- **1.5.3 What-if analysis and per-criterion breakdown charts** — drop (re-confirms the 2026-04-18 decision).
- **1.5.4 Operations Center beyond Option A** — drop. No priority queue, operation history, real-time polling, or scheduling.
- **1.5.5 H40 Option C extras** — drop, including the two items previously kept (table-level CSV export, sidebar conflict count badge).

If any of these resurface as a real ask, treat them as fresh tasks referencing the relevant section of the original `ux-redesign-plan.md` (§1.2 Option C, §1.3 Option B/C, §1.4 Option C).

---

## Tier 2 — Reassessed Surface-Level Improvements

### 2.1 Standalone publication wizard — DELETE

**Decided.** Drop the three templates (`user/publications-add-step1.html`, `step2.html`, `step3.html`) and their controller routes. The workspace inline wizard (H36.9) is the only "Add Publication" path. The "Add Publication" button stops needing the JS-fallback `href` to `/user/publications/add`; replace with a direct trigger of the inline wizard.

**Effort:** ~1-2 days. Includes: delete templates, drop the `GET /user/publications/add` route(s), remove progressive-enhancement fallback from the workspace, drop `verify-template-assets` allowlist entries, redirect `/user/publications/add` → `/user/workspace#publications` for any external bookmark.

### 2.2 Admin edit pages — OPTION D (modals where short, dedicated pages only where long)

**Decided.** Apply the H40.3 row-edit-modal pattern to short admin edit pages. Keep dedicated pages only for genuinely long forms (group report definition, individual report definition, possibly indicators-edit if it's complex).

**Candidate forms for modal conversion (short):**
- `admin/scholardex-editForum.html` → row-edit modal on `admin/scholardex-forums.html`
- `admin/scholardex-editAffiliation.html` → row-edit modal on `admin/scholardex-affiliations.html`
- `admin/edit-institutions.html` → row-edit modal on `admin/institutions.html`
- `admin/edit-group.html` → row-edit modal on `admin/groups.html` *(check field count first)*
- `admin/domains-edit.html` → row-edit modal on `admin/domains.html`
- `admin/activities-edit.html` (admin) → row-edit modal on `admin/activities.html` *(check field count first)*

**Likely keep as dedicated pages (long):**
- `admin/edit-individualReport.html` — report definition with criteria builder
- `admin/edit-groupReport.html` — same
- `admin/indicators-edit.html` — indicator definition with scoring rules
- `admin/group-individualReport-view.html` — read-only view, separate concern (see Tier 1.5.2)
- `admin/activity-indicators.html` — likely needs a dedicated surface

**For pages that stay dedicated:** apply original §2.2 Option A baseline (labels above inputs, breadcrumbs, readonly distinct, consistent Save/Cancel placement, section headings, helper text) using the new shared `admin-form` fragment from Tier 3.1 Option C.

**Effort:** ~7-10 days for modal conversions; ~3-4 days for the form-baseline pass on the long-form holdouts. Total ~10-14 days.

> **Open choice:** for each form on the "candidate" list, a quick field-count check might bump it from modal to dedicated page. We can decide that during implementation rather than upfront.

### 2.3 Public / ranking pages — IN SCOPE

**Decided in scope.** Specific depth still to pick.

**Templates affected:** `publications/list.html`, `forums/list.html`, `forums/detail.html`, `core/rankings.html`, `core/ranking-detail.html`, `universities/list.html`, `universities/detail.html`, `wos/categories.html`, `wos/category-detail.html`, `events/list.html`. None have been touched by the redesign.

**Option A — Visual alignment only (Medium).** ScholarDex typography, card, and table patterns. Light/dark theme via system preference. Empty states on lists. Card layout with hero metrics on detail pages. ~5-8 days.

**Option B — Branded public experience (Medium-high).** Option A plus public navigation header (separate from the authenticated shell), responsive design, summary stat cards on detail pages, structured profile layouts, SEO structured data. ~8-12 days.

**Option C — Discovery portal (High).** Option B plus cross-entity search, interactive ranking filters, university comparison, forum impact visualization, share/bookmark. ~16-22 days.

> **Open choice:** depth (A/B/C). Default suggestion: **B** — once we touch these pages, a public navigation header pays for itself; a full discovery portal (C) is a separate product effort.

### 2.4 Login page

`login.html` — never touched.

- **Option A (~1d):** ScholarDex card, palette, errors, wordmark, light/dark, "Forgot password?" placeholder.
- **Option B (~2-3d):** Two-panel layout, themed illustration, responsive collapse.
- **Option C (~5-7d):** Institution selector, SSO area, animated state transitions, onboarding panel.

> **Open choice:** depth (A/B/C). Default suggestion: **A** unless SSO is on the near-term roadmap.

### 2.5 Error pages

`errors/error-403.html`, `errors/error-404.html`, `errors/error-500.html`, `errors/error.html`, plus `shared/not-found.html`.

- **Option A (~1-2d):** Stay inside app shell when authenticated, recovery buttons, error-type icons, both themes.
- **Option B (~2-3d):** Context-specific messages, 403 permission guidance, 500 retry + timestamp, 404 search suggestion.
- **Option C (~5-7d):** "Did you mean…?" on 404, auto-retry on 500, error logging, "Report this issue" link.

> **Open choice:** depth (A/B/C). Default suggestion: **B** — the cost premium over A is small and the UX gain is real.

---

## Tier 3 — Cross-Cutting Infrastructure

### 3.1 Shared fragments — OPTION C (full component library)

**Decided.** Lock the design system before scaling further work. This becomes the prerequisite phase for Tier 1.5, Tier 2, and any future UX work.

**Already built (carry forward, document in `frontend-conventions.md`):**
- `tab-bar(tabs, callbacksRef)` — workspace tabs.
- `admin-breadcrumb(items)` — admin breadcrumb nav.
- `admin-empty-state(...)` and `admin-empty-row(...)` — empty states.
- `admin-status-badge(label, tone)`.
- `admin-table-section(...)`.
- Skeleton loader (`shared-skeleton.css` + `workspacePanelLoader.js`).
- Shortcuts cheat-sheet overlay (`shared-shortcuts.css` + `workspaceShortcuts.js`, `adminShortcuts.js`).
- Bulk select infrastructure (`adminBulkSelect.js`).
- Column visibility toggle (`adminColumnToggle.js`).

**To build (Option C scope):**

| Fragment / Component | Purpose | Notes |
|---|---|---|
| `confirmation-dialog` fragment + JS API | Destructive-action confirmation with danger-colored button per §6.7 | Extracts current ad-hoc dialogs from conflicts/bulk/delete flows |
| Toast system | Ephemeral feedback after actions | Shared queue, accessibility-correct (`aria-live="polite"`), dismissible, optional action-link |
| `pagination` fragment | Prev/next + page position + page-size selector per §6.3 | Variants for server-side and client-side; reuse across catalog, conflicts, citations, workspace |
| `filter-panel` fragment | Integrated filter controls per §6.3 | Already prototyped via `.app-queue-filter` and catalog filter; promote to shared |
| `stat-card` fragment | Hero metric + label + optional context line + accent per §6.2 | Replaces ad-hoc `.app-summary-grid` cards |
| Generic `breadcrumb` fragment | Non-admin breadcrumb (workspace, public pages) | Mirrors `admin-breadcrumb` shape |
| `admin-form` fragment | Shared admin form shell for the dedicated edit pages that survive Tier 2.2 | Header, sections, helper text, sticky Save/Cancel, error placement |
| `modal-shell` fragment | Reusable modal frame with focus trap, Escape close, ARIA `role="dialog"` | Used by row-edit modals from Tier 2.2 and confirmation dialogs |
| Button group / icon-button conventions | Documented baseline (we already have `app-admin-icon-btn`) | Documentation more than new code |
| `search-input` fragment | Input with optional `kbd` shortcut hint and clear button | Mirrors `app-ws-search__field` |

**Effort:** ~9-12 days. Treat this as a single task with subtasks per fragment, ending with documentation in `docs/frontend-conventions.md` and a one-off pass to migrate existing ad-hoc usages to the new fragments where the diff is small.

### 3.2 Accessibility baseline

**Status.** H36.13, H37.9, H40.11 covered the workspace, evaluation, and admin surfaces. Login, error pages, public/ranking pages, and admin edit pages have not been audited.

> **Open choice:** Do this in-task as Tier 2 surfaces are reworked (default), or run a standalone global sweep (~5-7d) for a clean compliance milestone?

### 3.3 Theme parity audit

Same situation: workspace and admin surfaces are theme-aware. Public pages, login, errors, admin edit pages — not swept. ~2-3 days for a global pass; otherwise audit-as-you-go.

### 3.4 Responsive audit

Same: workspace and admin surfaces verified. Remaining surfaces — not verified. ~3-4 days global; otherwise audit-as-you-go.

> **Open choice (3.2-3.4 together):** in-task vs. dedicated audit phase.

---

## Sidebar / Navigation — Loose Ends

Both sidebars were restructured in Tier 1 (H36.12, H40.2). The only remaining navigation work:

- **Public navigation header** — if Tier 2.3 lands at Option B or C, the public surfaces need their own header rather than reusing the authenticated shell.

---

## Suggested Phasing (Locked)

The phases below are ordered by dependency and quick-win bias. Each is independently shippable.

### Phase A — Quick wins (~1 week)
- **Tier 2.1** — delete the standalone publication wizard.
- **Tier 2.4** — Login, default Option A.
- **Tier 2.5** — Error pages, default Option B.

Approximate total: ~4-7 days. Closes loose ends without blocking anything else.

### Phase B — Tier 3.1 component library (Option C) (~2 weeks)
- Build all missing fragments (confirmation dialog, toast, pagination, filter panel, stat card, generic breadcrumb, admin-form, modal-shell, search-input).
- Document in `frontend-conventions.md`.
- Lightweight migration pass for existing ad-hoc usages where the diff is small.

Approximate total: ~9-12 days. **This is a prerequisite for Phase C and Phase D.**

### Phase C — Admin form modernization (Tier 2.2 Option D) (~2 weeks)
- Convert short admin edit pages to row-edit modals using the new `modal-shell` fragment.
- Apply the `admin-form` baseline to the long-form holdouts.
- Per-form field-count check at the top of each form to confirm modal vs. dedicated.

Approximate total: ~10-14 days.

### Phase D — Public surfaces (Tier 2.3) (~2-3 weeks)
- All 10 public templates aligned to ScholarDex visual system at chosen depth (A/B/C).
- Public navigation header if Option B or C.
- Includes its own a11y / responsive / theme sweep for those templates as part of the work.

Approximate total: ~5-22 days depending on Option A vs C.

### Phase E — Global audits (optional, ~1-2 weeks)
- Tier 3.2-3.4 if a clean compliance milestone is wanted; otherwise rolled into the phases above.

Approximate total: ~10-14 days, only if scoped in.

---

## Effort Estimates Summary (Locked Scope)

| Area | Locked / cheapest | Default / mid | Most ambitious |
|---|---|---|---|
| **Tier 1.5 — Tier 1 Follow-Ups** | **0d (all dropped)** | — | — |
| **Tier 2 — Surface** | | | |
| 2.1 Publication wizard | **~1-2d (delete, locked)** | — | — |
| 2.2 Admin edit pages (Option D, locked) | **~10-14d** | — | — |
| 2.3 Public pages (in scope, depth open) | ~5-8d | ~8-12d | ~16-22d |
| 2.4 Login (depth open) | ~1d | ~2-3d | ~5-7d |
| 2.5 Error pages (depth open) | ~1-2d | ~2-3d | ~5-7d |
| **Tier 3 — Infrastructure** | | | |
| 3.1 Shared fragments (Option C, locked) | **~9-12d** | — | — |
| 3.2-3.4 Global audits (open) | 0d (in-task) | ~10-14d | — |

**Locked-only floor (depth-A on every open item, in-task audits):** ~27-39 days.
**Default mid path (B on public pages, A on login, B on errors, in-task audits):** ~33-50 days.
**Maximalist (C on every depth choice, dedicated audit phase):** ~63-90 days.

---

## Remaining Open Choices

The structural decisions are locked. These are the depth-level choices still pending; each is a self-contained product call.

1. **Tier 2.3 Public pages** — A (visual alignment), B (branded public experience), or C (discovery portal)? Default B.
2. **Tier 2.4 Login** — A, B, or C? Default A unless SSO is near-term.
3. **Tier 2.5 Error pages** — A, B, or C? Default B.
4. **Tier 3.2-3.4 Audits** — in-task as Tier 2 surfaces are reworked, or dedicated global audit phase?

Tell me the answers when convenient and I'll fold them in and tighten Phase A's effort number accordingly.

---

## Cross-References

- Original plan: `docs/tasks/active/ux-redesign-plan.md`
- UX guide: `docs/ux-design-guide.md`
- Frontend conventions: `docs/frontend-conventions.md`
- Tier 1 task entries: `TASKS.md` (H36, H37, H40), `TASKS-done.md` (H39)
