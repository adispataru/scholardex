# UX Redesign Plan — Aligning Pages to the Design Guide

Status: planning
Date: 2026-04-04
Reference: `docs/ux-design-guide.md`, `docs/frontend-conventions.md`

---

## Overview

This plan takes a **workspace-first** approach to the UX redesign. Rather than polishing each of the 69 templates individually, it identifies the natural user mental models and proposes consolidating fragmented pages into cohesive workspaces. The biggest UX wins come from structural rethinking — merging pages that share data, eliminating unnecessary navigation hops, and building integrated views that match how users actually think about their work.

The plan is organized into three tiers:

- **Tier 1 — Structural Redesigns:** Workspace consolidation and information architecture changes that fundamentally improve how users navigate the product.
- **Tier 2 — Surface-Level Improvements:** Template polish, component standardization, and visual alignment for pages that don't need structural rethinking.
- **Tier 3 — Cross-Cutting Infrastructure:** Shared fragments, accessibility, theme parity, and responsive behavior that underpin everything.

Each section offers 3 UX options (A/B/C) from conservative to ambitious.

---

## Current Architecture Problems

Before diving into solutions, here's what the data analysis revealed:

1. **Navigation fragmentation.** A researcher has 8+ sidebar entries when their mental model really contains 3-4 activities: "manage my research output," "check my scores," "update my profile," and "sync my data."

2. **Template duplication.** The three `indicators-apply-*.html` templates are ~85% identical — same filter panel, same chart, same table structure — diverging only in the data source columns. This is maintenance debt and inconsistency risk.

3. **Shallow pages pointing to deep pages.** `indicators.html` is just a catalog table whose only action is "Apply" (which navigates to the real page). `individual-reports.html` is the same — a thin list whose only purpose is clicking through to the report view. These aren't useful pages; they're extra clicks.

4. **Disconnected data.** The dashboard is empty. Publications, citations, activities, and profile all live in separate pages despite sharing the same researcher context. The user mentally connects "my publications" with "my citations" with "my h-index" with "my Scopus IDs," but the UI forces them to navigate between 4+ pages to see this picture.

5. **Inconsistent interaction patterns.** Publications → Citations is a page navigation. But creating an activity uses a modal on the same page. Editing a publication is a separate page, but editing user roles is inline in a table row. Same product, three different patterns for "edit a record."

---

## Tier 1 — Structural Redesigns

These are the high-impact changes that restructure how users experience the product.

### 1.1 Researcher Workspace (Consolidating Dashboard + Profile + Publications + Activities)

**Current state:** Four separate pages that all answer "what is my research status?"
- `user/dashboard.html` — empty shell, no content
- `user/profile.html` — researcher identity, Scopus/WoS IDs, affiliations
- `user/publications.html` — publication list with stat cards (count, citations, h-index), author summary panel
- `user/activities.html` — activity instances list with stat cards, doughnut chart, modal creation form

**Data overlap:** All four pages share the same researcher context. Publications already shows citation count and h-index (which are profile-level metrics). Activities tracks the same researcher's non-publication work. The dashboard would need to aggregate all of this anyway.

**The core insight:** A researcher logging in wants to see one integrated view of "my research life" — identity, output metrics, publications, activities — not four disconnected pages.

#### Option A — Tabbed Workspace (Medium effort)

Merge the four pages into a single `/user/workspace` page with a tab bar:

**Tab 1: Overview (replaces dashboard)**
- Profile identity card at top (name, institution, Scopus/WoS IDs, avatar)
- Stat card grid: publications count, citations, h-index, activities count, pending reports
- Recent activity feed (last 10 changes across publications and activities)
- Quick actions: Add Publication, Add Activity, View Reports, Sync Scopus
- Empty state for new users with onboarding checklist

**Tab 2: Publications (replaces publications page)**
- Same table and stat cards, but within the workspace context
- Publications → Citations drill-down happens inline (row expand or side panel) instead of page navigation
- Author summary panel visible above the table

**Tab 3: Activities (replaces activities page)**
- Same table, chart, and creation modal
- Activity editing stays as modal/inline rather than separate page

**Tab 4: Profile & Sync (replaces profile + tasks pages)**
- Profile editing form
- Scopus ID management
- Sync task history and trigger buttons
- Affiliation management

**Sidebar navigation:** The 4 sidebar items (Dashboard, Profile, Publications, Activities) collapse into 1: "My Workspace." Deep links (`/user/workspace#publications`) allow direct navigation to a specific tab.

**User experience:** One page answers all researcher questions. Fewer navigation hops. The tab bar provides orientation within a cohesive context. The overview tab is genuinely useful on day one.

**Technical approach:** Single Thymeleaf template with tab content loaded via fragments. Controller aggregates data from existing services. Tabs can lazy-load (fetch table data only when the tab is activated) to avoid a heavy initial page load.

**Effort:** ~8-12 days. Controller refactoring, combined template, tab JS, lazy loading.

#### Option B — Integrated Workspace with Master-Detail (Medium-high effort)

Everything in Option A, plus:

- **Publications tab** uses a master-detail layout: publication list on the left/top, and selecting a row reveals a detail panel (on the right or expanding below) showing the publication's citations, edit form, and linked indicators. No separate citations or edit pages needed for common operations.
- **Activities tab** uses the same pattern: list + inline detail/edit panel.
- **Overview tab** includes mini-charts (publications over time, citation trend, activity type distribution) from §6.9.
- **Profile tab** includes a "completeness score" showing what's missing (no Scopus ID? no affiliations?) with direct action links.
- Tab state is preserved in the URL hash so browser back/forward and bookmarks work naturally.

**User experience:** The workspace feels like a modern SaaS app (think Linear or Notion). Users can explore their data without ever navigating away from the workspace. Detail is available on demand through progressive disclosure.

**Effort:** ~14-18 days. Master-detail layout, inline edit forms, charts, completeness logic.

#### Option C — Adaptive Research Hub (High effort)

Everything in Option B, plus:

- **Smart overview** adapts to the researcher's situation: a new user sees onboarding guidance, an active researcher sees recent changes and actionable items, a researcher during reporting season sees report-readiness status.
- **Unified search** across publications, activities, and citations within the workspace.
- **Drag-and-drop reordering** of overview cards to personalize the dashboard.
- **Notification center** showing what changed since last visit (new citations found, sync completed, report available).
- **Inline publication creation** — the add-publication multi-step wizard runs as a panel within the workspace instead of a separate page flow.
- **Skeleton loading** for all async data, smooth tab transitions, keyboard shortcut navigation between tabs.

**User experience:** The workspace is the product for researchers. It handles everything from first login to annual reporting without navigating away.

**Effort:** ~22-28 days. Adaptive logic, search, notifications, inline wizard, preferences.

---

### 1.2 Indicator & Report Workspace (Consolidating Indicators + Apply Views + Reports)

**Current state:** Five+ separate pages for what is one analytical workflow:
- `user/indicators.html` — thin catalog listing indicators with an "Apply" button
- `user/indicators-apply-publications.html` — scored publication list for one indicator
- `user/indicators-apply-activities.html` — scored activity list for one indicator
- `user/indicators-apply-citations.html` — scored citation list for one indicator
- `user/individual-reports.html` — thin catalog listing reports with an "Apply" button
- `user/individual-report-view.html` — the actual report with criterion cards, threshold visualization

**Data overlap:** The three `indicators-apply-*` templates share ~85% of their structure (same filter panel, same chart layout, same score column pattern). The indicator catalog is just a click-through to the apply views. The reports catalog is the same. The report view references indicators that link back to the apply views.

**The core insight:** A researcher doing evaluation work thinks in terms of "how am I doing on my report?" — not "let me find the indicators catalog, then apply one indicator, then go back, then find the reports catalog, then view a report." The entire indicator-report workflow should be one surface.

#### Option A — Unified Score Explorer (Medium effort)

Consolidate the three `indicators-apply-*` templates into a single `indicators-apply.html` that dynamically renders the correct table columns based on `indicator.outputType`. Merge the indicator catalog into the report view as a sidebar list or dropdown selector (eliminating the catalog-only page). Merge the reports catalog similarly.

**Resulting structure:**
- `/user/reports` — report list (kept as entry point, but simplified)
- `/user/reports/view/{id}` — full report view with embedded indicator detail
- `/user/indicators/apply/{id}` — unified indicator apply page (one template, not three)

**Changes:**
- Single `indicators-apply.html` template with conditional column rendering based on output type
- Report view gets a "drill down to indicator" action that navigates to the apply page with breadcrumbs back
- Indicator catalog page either becomes a section in the report view sidebar or a lightweight selection dropdown
- Common filter/chart/table components extracted into shared fragments

**User experience:** Fewer pages to maintain, fewer pages to navigate. The apply pages feel consistent because they literally are the same page.

**Effort:** ~6-8 days. Template consolidation, conditional rendering, fragment extraction.

#### Option B — Integrated Evaluation Dashboard (Medium-high effort)

Everything in Option A, plus:

- **Report view becomes the central hub.** Clicking a criterion card in the report view expands inline to show the indicator's scored data (what currently requires navigating to the apply page). No page navigation needed for the most common flow.
- **Indicator selector** is a left sidebar or tab within the report view, not a separate page. User can switch between indicators without losing report context.
- **Aggregate progress visualization** at the top of the report view: overall score, criteria met/unmet, career-level threshold progress bar.
- **Quick refresh** actions per-indicator and all-at-once, with loading states.
- **Export** the full report (all indicators) as Excel or printable view.

**User experience:** The entire evaluation workflow — from seeing your report, to understanding each criterion, to drilling into the scored data — happens in one page with progressive disclosure.

**Effort:** ~12-16 days. Inline expand, sidebar navigator, aggregate visualization, export.

#### Option C — Analytical Evaluation Suite (High effort)

Everything in Option B, plus:

- **Period comparison** — see how scores changed between report runs.
- **What-if analysis** — "if I add one more Q1 publication, how does my score change?"
- **Visual score breakdown charts** per criterion (contribution by publication, by activity).
- **Saved report snapshots** — bookmark a report state for later comparison.
- **PDF export** with ScholarDex branding and formatting.
- **Admin group report view** follows the same patterns for consistency.

**User experience:** A full research evaluation analytics platform. Researchers don't just see their score — they understand it and can plan to improve it.

**Effort:** ~20-26 days. Comparison backend, what-if calculator, chart integration, PDF generation.

---

### 1.3 Admin Operations Center (Consolidating Dashboard + Operations Pages)

**Current state:** Four separate pages:
- `admin/dashboard.html` — placeholder stat cards, no live data
- `admin/initialization.html` — system initialization actions
- `admin/wos-enrichment.html` — WoS data enrichment
- `admin/incremental-updates.html` — incremental data updates

**The core insight:** The admin dashboard should BE the operations center, not link to separate operations pages. An admin's first question is "is the system healthy?" and their second is "what should I do about it?" Both questions should be answered in one place.

#### Option A — Consolidated Operations Dashboard (Medium effort)

Merge the dashboard with summary views of all operations:

- **Health section:** Live stat cards for open conflicts, pending triage items, total researchers, total publications, recent sync status. Semantic accents per §6.2.
- **Operations section:** Status cards for each operation type (initialization, WoS enrichment, incremental updates) showing last-run time, result, and a "Run Now" action. Confirmation dialogs before triggering.
- **Recent activity feed:** Last 10 system events.
- **Quick links:** Most common admin destinations (conflicts, researchers, catalog).

Keep the individual operation pages but make them detail views accessible from the dashboard, not primary navigation targets.

**User experience:** Admin lands on a useful, live dashboard. Operations are one click away, not a sidebar hunt.

**Effort:** ~5-7 days. Aggregation endpoints, template merge, confirmation dialogs.

#### Option B — Segmented Operations Hub (Medium-high effort)

Everything in Option A, plus:

- **Priority queue** at the top: items needing attention sorted by urgency (open conflicts > stale enrichments > pending triage).
- **Operation history** panels: each operation type shows its last 5 runs with status badges.
- **Progress indicators** for running operations.
- **Sectioned layout:** "Data Health," "Content Volume," "System Operations," "Recent Activity" — each collapsible.
- **Individual operation pages** become detail drawers or expanded sections within the dashboard, not separate navigation.

**User experience:** Dense but organized command center. Admin scans top-to-bottom in priority order.

**Effort:** ~10-14 days. Priority logic, operation history, progress indicators.

#### Option C — Live Monitoring Console (High effort)

Everything in Option B, plus:

- **Real-time status** via polling or WebSocket for running operations.
- **24-hour timeline** of system events.
- **Batch operation sequencing** (run enrichment, then update, then refresh).
- **Operation scheduling** (run at 2am).
- **Dependency visualization** between operations.
- **Notification on completion.**

**User experience:** Enterprise operations management. Admin can fire-and-forget long operations.

**Effort:** ~18-24 days. Real-time updates, scheduling, dependency graph, notifications.

---

### 1.4 Admin Data Management Workspaces

**Current state:** 21+ separate admin table pages for managing entities (users, researchers, institutions, groups, forums, authors, affiliations, publications, citations, conflicts, triage, indicators, domains, reports, activities).

**The core insight:** These pages cluster into natural workspaces by domain:

- **People & Access:** users, researchers, groups
- **Catalog (ScholarDex Data):** forums, authors, affiliations, publications, citations, publication search
- **Evaluation Config:** indicators, domains, activities, activity-indicators, individual reports, group reports
- **Data Quality:** conflicts, user-defined triage, source links

Some of these (like users, researchers, groups) share entity relationships and could benefit from integrated views. Others (like conflicts and triage) are workflow-oriented and should feel like queues, not generic CRUD tables.

#### Option A — Consistent Admin Tables with Domain Grouping (Medium effort)

Don't merge pages, but standardize them aggressively:

- All admin tables use the shared ScholarDex table pattern: no vertical borders, subtle alternating rows, row hover, compact icon-button actions with `aria-label`, semantic status badges.
- Fix the users page: replace inline per-row role checkboxes with a proper edit modal.
- Add explicit empty states to every table.
- Add stat cards above high-traffic tables (conflicts: open/resolved/dismissed; researchers: total/active).
- Add breadcrumbs on all sub-list pages (institution publications, group publications, group report views).
- Reorganize sidebar grouping to match the four domains above (People, Catalog, Evaluation, Data Quality).
- Standardize modal creation forms across all "create new" flows.

**User experience:** Admin tables feel like one product. Sidebar navigation matches mental models. Every table is scannable, accessible, and states are visible at a glance.

**Effort:** ~10-14 days. High volume but repetitive. Reusable table/badge/empty-state fragments.

#### Option B — Domain Workspaces with Integrated Filters (Medium-high effort)

Everything in Option A, plus:

- **Conflicts page becomes a queue:** sorted by priority/recency, with decision badges (resolve/dismiss/investigate), batch operations, and a filter panel. Queue UX instead of generic table UX.
- **Triage page** follows the same queue pattern.
- **Catalog pages** (forums, authors, affiliations, publications) get integrated filter panels and cross-linking (click an author → see their publications; click a forum → see its publications).
- **Institution and group pages** get summary stat cards and integrated sub-entity views (institution → tab for researchers → tab for publications, instead of separate pages).
- **Server-side pagination** for high-volume catalog tables (publications, citations, authors).

**User experience:** Admin pages feel purpose-built for operational workflows. Conflicts feel like a work queue. Catalog feels explorable. Institutions feel like profile pages.

**Effort:** ~18-24 days. Queue UX, cross-linking, server-side pagination, integrated sub-views.

#### Option C — Advanced Admin Workbench (High effort)

Everything in Option B, plus:

- **Bulk operations** on high-volume tables (select multiple researchers, assign to group; select multiple publications, reassign forum).
- **Column visibility toggles** for wide tables.
- **Saved filter presets** for frequently-used views.
- **Export** (Excel, CSV) from any table toolbar.
- **Keyboard shortcuts** for common operations (next row, open edit, resolve conflict).
- **Row expansion** for inline detail previews.
- **Real-time conflict count** badges in the sidebar.

**User experience:** A true admin workbench. Handles large datasets, supports power-user workflows, scales with institutional growth.

**Effort:** ~28-36 days. Bulk operations backend, export, keyboard nav, real-time badges.

---

## Tier 2 — Surface-Level Improvements

These pages need UX polish but not structural rethinking.

### 2.1 Multi-Step Publication Workflow

**Templates:** `publications-add-step1.html`, `publications-add-step2.html`, `publications-add-step3.html`

**Note:** If Tier 1.1 Option C is chosen, this workflow may be embedded inline in the researcher workspace. Otherwise, it remains a standalone flow that needs polish.

#### Option A — Polished Step Indicator (Low-medium effort)
Labeled steps ("Select Forum," "Add Authors," "Review & Submit") with clear current/completed/upcoming states. Visual progress bar. Labeled back/next buttons. ScholarDex form styling on all inputs. Smooth disclosure transitions for "create new forum."

**Effort:** ~2-3 days.

#### Option B — Data-Preserving Workflow (Medium effort)
Option A plus: visible confirmation of previous-step selections, inline author search with list-plus-add pattern (§6.4), true review step at the end, data preserved on back navigation, per-step validation.

**Effort:** ~5-7 days.

#### Option C — Intelligent Workflow (High effort)
Option B plus: duplicate detection, author autocomplete from ScholarDex data, DOI lookup for pre-population, skip-to-review when data is complete.

**Effort:** ~10-14 days.

---

### 2.2 Edit / Detail Pages

**Templates:** 12 edit pages across user and admin contexts.

**Note:** Some user edit pages (publications-edit, activities-edit, profile) may be absorbed into the researcher workspace (Tier 1.1) as inline edit panels. The admin edit pages remain standalone.

#### Option A — Consistent Form Baseline (Medium effort)
Labels above inputs, comfortable sizing, breadcrumbs back to parent, readonly fields visually distinct with explanations, consistent Save/Cancel placement, section headings for long forms, helper text on non-obvious fields.

**Effort:** ~5-7 days.

#### Option B — Guided Editing (Medium-high effort)
Option A plus: blur validation with inline errors, required/optional marking, sticky Save/Cancel, contextual help tooltips, auto-save drafts.

**Effort:** ~8-12 days.

#### Option C — Smart Forms (High effort)
Option B plus: collapsible advanced sections, dependent field logic, inline entity creation, change tracking with diff preview, post-save undo via toast.

**Effort:** ~14-18 days.

---

### 2.3 Public / Ranking Pages

**Templates:** 10 public-facing templates (rankings, forums, universities, WoS categories, events, publications).

#### Option A — Product Visual Alignment (Medium effort)
ScholarDex typography, card, and table patterns. Light/dark theme via system preference. Ranking tables use shared classes. Empty states. Detail pages use card layout with hero metrics.

**Effort:** ~5-8 days.

#### Option B — Branded Public Experience (Medium-high effort)
Option A plus: public navigation header, responsive design, summary stat cards on detail pages, structured profile layouts, SEO structured data.

**Effort:** ~8-12 days.

#### Option C — Discovery Portal (High effort)
Option B plus: cross-entity search, interactive ranking filters, university comparison, forum impact visualization, share/bookmark functionality.

**Effort:** ~16-22 days.

---

### 2.4 Login Page

**Template:** `login.html`

#### Option A — Visual Polish (Low effort)
ScholarDex color palette, card pattern from §6.1, styled inline errors, wordmark, light/dark support via system preference, "Forgot password?" placeholder.

**Effort:** ~1 day.

#### Option B — Branded Welcome (Medium effort)
Two-panel layout (brand + form), full form pattern, responsive collapse, themed illustration.

**Effort:** ~2-3 days.

#### Option C — Contextual Login (High effort)
Option B plus: institution selector, SSO area, animated state transitions (login/forgot/register), onboarding panel.

**Effort:** ~5-7 days.

---

### 2.5 Error Pages

**Templates:** 5 error templates.

#### Option A — Friendly Recovery (Low effort)
Stay inside app shell (authenticated), center content, recovery buttons ("Go back", "Dashboard"), error-type icons, both themes.

**Effort:** ~1-2 days.

#### Option B — Contextual Errors (Low-medium effort)
Option A plus: context-specific messages, permission guidance on 403, retry action + timestamp on 500, search suggestion on 404.

**Effort:** ~2-3 days.

#### Option C — Smart Recovery (Medium effort)
Option B plus: "Did you mean...?" suggestions on 404, auto-retry on 500, error logging, "Report this issue" link.

**Effort:** ~5-7 days.

---

## Tier 3 — Cross-Cutting Infrastructure

These items support everything above and should be built first.

### 3.1 Shared Fragments & Components

| Fragment | Purpose | Guide Section |
|----------|---------|---------------|
| Badge/Status | Semantic status badges (pill-shaped, colored background, text label) | §6.5 |
| Empty State | Title + explanation + optional CTA button | §6.6 |
| Breadcrumb | Auto-generated navigation context | §5.2 |
| Stat Card | Hero metric + label + optional context line + accent | §6.2 |
| Confirmation Dialog | Destructive action confirmation with danger-colored button | §6.7 |
| Toast System | Ephemeral feedback after actions | §6.7 |
| Pagination | Prev/next + page position + page-size selector | §6.3 |
| Filter Panel | Integrated filter controls that belong to a table | §6.3 |
| Tab Bar | Workspace tab navigation with URL hash state | §5, §4 |

**Effort:** ~7-10 days.

### 3.2 Accessibility Baseline (§9)

- Audit and fix all `for`/`id` label associations
- `aria-label` on all icon-only buttons (descriptive: "Edit publication," not "Edit")
- Semantic table structure: `<thead>/<tbody>`, `scope="col"` on headers
- ARIA landmarks: `<nav>`, `<main>`, `<aside>` in the shell
- WCAG AA contrast verification in both themes
- Keyboard navigation flow testing on every page
- Focus ring visibility in both themes
- Screen reader announcement for dynamic content updates

**Effort:** ~5-7 days.

### 3.3 Theme Parity Audit

- Dark mode sweep of every page for broken contrast, missing backgrounds, invisible elements
- All new components built theme-aware from the start
- Chart colors legible in both themes

**Effort:** ~2-3 days.

### 3.4 Responsive Audit

- Sidebar collapse at all breakpoints
- No action buttons hidden via `d-none` on mobile (§4.4)
- Table horizontal scroll on small screens with critical columns pinned left
- Form single-column collapse on mobile
- Stat card grid reflow from multi-column to single-column

**Effort:** ~3-4 days.

---

## Sidebar Navigation Restructure

The workspace consolidation changes what appears in the sidebar. Here's the proposed navigation:

### User Sidebar (Current → Proposed)

**Current (8+ items):**
- Dashboard
- Profile
- Publications
- Activities
- Scopus Tasks
- Indicators
- Reports
- CNFIS Export

**Proposed (3-4 items):**
- **My Workspace** → Tabbed researcher workspace (overview, publications, activities, profile & sync)
- **Evaluation** → Report/indicator workspace (reports, indicator scoring, export)
- **Rankings** → Public ranking views (kept as-is)

### Admin Sidebar (Current → Proposed)

**Current (20+ items across 5 sections):**
Management, Catalog, Rankings, Reporting, Operations

**Proposed (4 sections, tighter grouping):**
- **Operations Center** → Dashboard with integrated operations status
- **People & Access** → Users, Researchers, Groups, Institutions
- **Data Catalog** → Forums, Authors, Affiliations, Publications, Citations, Search
- **Evaluation** → Indicators, Domains, Activities, Reports (Individual + Group), Data Quality (Conflicts, Triage, Source Links)

---

## Implementation Phases

### Phase 0 — Infrastructure (Weeks 1-2)
Build Tier 3: shared fragments, tab bar component, accessibility baseline.
**Deliverable:** Component library ready for all subsequent work.

### Phase 1 — Researcher Workspace (Weeks 3-5)
Build Tier 1.1 (researcher workspace consolidation). This is the highest-impact change.
**Deliverable:** Unified `/user/workspace` replacing 4+ separate pages.

### Phase 2 — Evaluation Workspace (Weeks 5-7)
Build Tier 1.2 (indicator/report consolidation).
**Deliverable:** Unified evaluation surface replacing 6+ separate pages/templates.

### Phase 3 — Admin Foundation (Weeks 7-10)
Build Tier 1.3 (admin operations dashboard) and Tier 1.4 (admin table standardization).
**Deliverable:** Live admin dashboard, consistent admin tables.

### Phase 4 — Remaining Surfaces (Weeks 10-13)
Build Tier 2: publication workflow polish, edit/detail form consistency, public pages, login, error pages.
**Deliverable:** Every page aligned with the design guide.

### Phase 5 — Polish and Audit (Weeks 13-14)
Theme parity audit, responsive audit, accessibility audit, final consistency pass.
**Deliverable:** Production-ready UX across the entire product.

---

## Effort Estimates Summary

| Area | Option A | Option B | Option C |
|------|----------|----------|----------|
| **Tier 1 — Structural** | | | |
| 1.1 Researcher Workspace | ~8-12d | ~14-18d | ~22-28d |
| 1.2 Evaluation Workspace | ~6-8d | ~12-16d | ~20-26d |
| 1.3 Admin Operations Center | ~5-7d | ~10-14d | ~18-24d |
| 1.4 Admin Data Management | ~10-14d | ~18-24d | ~28-36d |
| **Tier 2 — Surface** | | | |
| 2.1 Publication Workflow | ~2-3d | ~5-7d | ~10-14d |
| 2.2 Edit/Detail Pages | ~5-7d | ~8-12d | ~14-18d |
| 2.3 Public Pages | ~5-8d | ~8-12d | ~16-22d |
| 2.4 Login | ~1d | ~2-3d | ~5-7d |
| 2.5 Error Pages | ~1-2d | ~2-3d | ~5-7d |
| **Tier 3 — Infrastructure** | | | |
| 3.1 Shared Fragments | ~7-10d | ~7-10d | ~7-10d |
| 3.2-3.4 Audits | ~10-14d | ~10-14d | ~10-14d |
| | | | |
| **Total (all A)** | **~60-85d** | — | — |
| **Total (all B)** | — | **~96-133d** | — |
| **Total (all C)** | — | — | **~155-206d** |

**Recommended mix:** Option B for Tier 1 (structural changes are where the ROI is), Option A for Tier 2, full Tier 3 → approximately **80-110 days**.

---

## Decision Points

1. **Workspace scope for 1.1** — Should Scopus tasks (sync management) live inside the researcher workspace or remain a separate utility page? The data (Scopus IDs) comes from the profile, but the workflow (trigger sync, view task history) is operational.

2. **Indicator page elimination** — Does the indicator catalog page serve any purpose beyond "pick which indicator to apply"? If not, it can become a selector within the report view (Tier 1.2) and the standalone page can be removed.

3. **Inline vs. page-level editing** — If publications and activities live inside a tabbed workspace, should editing happen inline (panel within the workspace) or as separate pages? Inline is better UX but more complex.

4. **Server-side pagination** — Admin catalog tables (publications, citations, authors) may have thousands of rows. Option B+ for Tier 1.4 needs server-side pagination, which is an architectural decision.

5. **Chart library standardization** — Activities currently uses Chart.js. Evaluation workspace and dashboards will also need charts. Standardize on Chart.js or adopt something else?

6. **Public page priority** — Are public pages (Tier 2.3) part of this cycle or a later dedicated effort?

7. **URL structure** — Workspace consolidation changes URLs. Do we need redirects from old URLs? How long do we maintain them?
