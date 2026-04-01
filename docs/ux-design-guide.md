# ScholarDex UX Design Guide

Status: active contributor UX guidance for the ScholarDex-owned UI/UX migration.

**Version:** 1.1
**Date:** April 2026
**Purpose:** define the target UX direction for contributors migrating the product toward a ScholarDex-owned design system and interaction model
**Audience:** contributors improving templates, shared fragments, frontend modules, and UI behavior

This guide is the current-source UX reference for ScholarDex. It is grounded in the repo as it exists today, but it defines the intended steady-state direction rather than the legacy runtime baseline. Contributors should use it to migrate the product away from SB Admin 2, Bootstrap 4-era UI conventions, and jQuery-driven interaction patterns toward a ScholarDex-owned UI/UX system with Bootstrap 5-compatible implementation and support for both light and dark themes.

---

## 1. Current Frontend Baseline

Before applying UX changes, work from the repo's actual frontend contract and migration target:

- Authored frontend code lives under `frontend/src/**`.
- Runtime templates consume built assets through `/assets/app.css` and `/assets/app.js`.
- Shared shell work flows through Thymeleaf fragments and the role-aware sidebar composition path.
- SB Admin 2 styling/JS, Bootstrap 4-era markup/classes, jQuery-driven UI behavior, and BS4 DataTables coupling are still present in parts of the runtime today.
- Those dependencies are migration debt, not acceptable steady-state constraints to preserve.
- Shared-shell modernization under `H30` should retire SB Admin 2 assumptions from the authenticated shell rather than preserve or extend them.
- New and touched UI work should converge on Bootstrap 5-compatible markup, repo-owned behavior, and ScholarDex-owned design patterns.
- The target product direction is one ScholarDex UI/UX system that supports both light and dark themes, not continued operation on a Bootstrap 4/SB Admin baseline.

Use this guide together with:

- `docs/frontend-conventions.md` for template and asset rules
- `docs/doc-governance.md` for doc placement and update expectations
- `docs/quality-gates.md` for verification commands tied to frontend and doc changes

---

## 2. Design Principles

These principles should guide UI decisions throughout the migration. Legacy runtime constraints may still exist in untouched areas, but they are not the design target.

### 2.1 Clarity Over Density

Academic data is inherently complex. The UI should reduce cognitive load, not mirror the underlying complexity. Every screen should answer one question clearly: **"What am I looking at, and what can I do here?"**

In practice this means generous spacing between sections, one primary action per view or section, and progressive disclosure for secondary options.

### 2.2 Consistency Builds Trust

Researchers and administrators need to trust the data they see. Visual inconsistency across pagination, card layouts, forms, or actions erodes that trust. Similar pages should use the same patterns unless there is a specific workflow reason to diverge.

### 2.3 Guide, Don't Just Display

Raw tables are useful, but the interface should help users understand what the data means. Favor contextual helper text, explicit statuses, empty states, and summary views that turn raw records into understandable signals.

### 2.4 Respect Both Audiences

Researchers and administrators have different workflows. The same design language should serve both, but each role's surfaces should be optimized for that role's tasks and level of complexity.

### 2.5 Progressive Enhancement

Not every page needs to be rebuilt at once. Improvements should layer onto the existing shell incrementally, with each change producing a visible UX win without requiring the whole application to be modernized first.

---

## 3. Color System

The visual direction should move away from loud default theme coloring and toward restrained, intentional use of color. This is guidance for new and revised UI work; it does not imply that all current pages already follow it.

The product should support both light and dark themes through one cohesive ScholarDex design language. Themes are presentation modes within the same system, not separate product directions.

### 3.1 Core Palette

```text
Brand Primary:       #4361EE
Brand Primary Hover: #3A56D4
Brand Primary Light: #EEF1FD

Neutral 900:         #111827
Neutral 700:         #374151
Neutral 500:         #6B7280
Neutral 300:         #D1D5DB
Neutral 200:         #E5E7EB
Neutral 100:         #F3F4F6
Neutral 50:          #F9FAFB
White:               #FFFFFF
```

### 3.2 Semantic Colors

```text
Success:             #059669
Success Light:       #ECFDF5
Warning:             #D97706
Warning Light:       #FFFBEB
Danger:              #DC2626
Danger Light:        #FEF2F2
Info:                #2563EB
Info Light:          #EFF6FF
```

### 3.3 Usage Rules

- Use neutrals for the majority of text, borders, and surfaces.
- Use primary blue for interactive emphasis, not generic decoration.
- Use semantic colors for status and consequence.
- Avoid stacking multiple colored treatments inside the same card or row.
- Prefer a light neutral page background with white cards for new or refreshed layouts, even if some legacy pages still render on pure white.
- Every color decision should have a corresponding dark-theme treatment with comparable hierarchy, contrast, and restraint.
- Theme support should be implemented through repo-owned tokens and shared styles rather than page-specific overrides.

---

## 4. Typography

Typography guidance should be applied through the existing asset pipeline and shared styles, not through page-by-page ad hoc overrides. Typography hierarchy should remain consistent across both light and dark themes.

### 4.1 Font Direction

Preferred direction:

```css
--font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
--font-mono: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
```

Inter is the preferred UI font direction, but adoption should happen through the existing frontend build and asset contract. Until it is fully adopted, contributors should still follow the type hierarchy below using the fonts available in the current stack.

### 4.2 Type Scale

```text
xs:   0.75rem   (12px)
sm:   0.875rem  (14px)
base: 1rem      (16px)
lg:   1.125rem  (18px)
xl:   1.25rem   (20px)
2xl:  1.5rem    (24px)
3xl:  1.875rem  (30px)
```

### 4.3 Rules

- Page titles: `2xl`, semi-bold, high-contrast text, no forced uppercase.
- Card headers: `lg`, semi-bold, neutral text rather than accent-colored static labels.
- Body text: `base`, regular weight, comfortable line height.
- Data values and hero metrics: `2xl` to `3xl`, bold, visually dominant.
- Small metadata labels: `xs`, medium weight; uppercase is acceptable only for compact descriptive labels.
- Monospace styling is appropriate for IDs, codes, and system identifiers.

---

## 5. Layout And Spacing

### 5.1 Spacing Scale

Use a consistent 4px-derived spacing rhythm:

```text
1:  0.25rem  (4px)
2:  0.5rem   (8px)
3:  0.75rem  (12px)
4:  1rem     (16px)
5:  1.25rem  (20px)
6:  1.5rem   (24px)
8:  2rem     (32px)
10: 2.5rem   (40px)
12: 3rem     (48px)
16: 4rem     (64px)
```

Contributors may map this scale onto existing utility classes or custom styles, but the design goal is consistency rather than reliance on any particular utility framework. As the migration progresses, touched UI should converge on Bootstrap 5-compatible markup and repo-owned spacing primitives instead of extending Bootstrap 4-only utility conventions.

### 5.2 Page Structure

Target structure for authenticated application pages:

- persistent role-aware sidebar
- top navigation row
- page header with title and actions
- bounded content area for readable cards and forms
- footer or bottom spacing that does not crowd content

### 5.3 Key Layout Rules

- Cap general content width for readability on wide screens.
- Use comfortable card padding, with more space than legacy cramped headers/bodies.
- Keep clear separation between major page sections.
- Prefer subtle borders or very light shadows over heavy card shadows.

### 5.4 Responsive Behavior

Responsive expectations should be met within the current templates and fragment structure:

- sidebar behavior may differ by breakpoint, but primary navigation must remain reachable
- stat cards should stack cleanly on smaller screens
- wide tables should remain usable with horizontal overflow where needed
- primary actions must stay visible and discoverable on small screens
- the same page structure must remain legible and navigable in both light and dark themes

---

## 6. Navigation And Shared Shell

This section defines the active shell baseline and the UX direction it now establishes for the rest of the migration while acknowledging current runtime debt outside the touched shell areas.

### 6.1 Sidebar Direction

The long-term direction is a quieter, more structured sidebar with clear grouping, consistent icon sizing, and predictable active states.

For current work:

- preserve the unified sidebar composition path used by shared fragments
- prefer reducing noise over adding more visual treatment
- improve grouping, label clarity, and active-state legibility as part of the migration toward repo-owned shell patterns
- avoid introducing one-off sidebars or bypassing the role-aware fragment path
- do not preserve SB Admin 2 visual patterns as the intended long-term sidebar baseline
- ensure sidebar styling and behavior support both light and dark themes within one consistent navigation model

The authenticated shared shell now uses a repo-owned sidebar implementation through `fragments.html` and `frontend/src/**`; touched shell work should treat that model as the current baseline rather than falling back to SB Admin or per-template sidebar wiring.

### 6.2 Topbar Direction

The topbar should prioritize orientation and context:

- clear page title
- role/context clarity
- room for breadcrumbs on deeper pages
- user/account actions on the right

Current implementation details such as the `Switch Workspace` dropdown remain shared-shell concerns, not page-owned UX doctrine. Page-level changes should not hard-code new shell patterns outside shared fragments.

Shared-shell work under `H30` is expected to move the authenticated shell away from SB Admin 2 styling/JS assumptions where those assumptions conflict with the intended shell hierarchy and visual direction.
Touched shell behavior should also move away from jQuery-driven Bootstrap 4 conventions and converge on Bootstrap 5-compatible, repo-owned behavior.
The migrated shared header now owns the topbar, page-title presentation, theme toggle, and shared toolbar rhythm for authenticated shared-shell pages. Page templates should treat that header contract as the baseline and avoid reintroducing duplicate title wrappers.

### 6.3 Shared-Shell Rule

If a change affects navigation, topbar, or shared layout:

- implement it through shared fragments and existing composition paths
- preserve the `/assets/app.css` and `/assets/app.js` asset contract
- use `data-bs-theme` on the root element as the theme-state source of truth for migrated shell work
- treat shell-wide dependency removal and Bootstrap 5-compatible cutover as intentional migration work
- do not extend SB Admin 2, Bootstrap 4-era, or jQuery-driven shell usage as part of shared-shell modernization

The shared authenticated shell established by `H30` is the contributor-facing baseline for later work:

- `fragments.html` owns shared sidebar/topbar/page-header composition
- `frontend/src/**` owns shell behavior, shell styling, and theme behavior
- `data-bs-theme` on the root element owns theme state for the migrated shell
- bounded content layout and shared toolbar spacing should be reused rather than rebuilt per page

---

## 7. Component Patterns

### 7.1 Cards

Cards are the primary content container.

Guidance:

- white or near-white background on a light neutral page background
- 1px neutral border or extremely subtle shadow
- moderate corner radius
- optional header with clear separation from body
- no decorative color accents unless they carry meaning
- provide equivalent dark-theme surface treatment with clear elevation and contrast, not a direct inversion of light-theme colors

### 7.2 Stat Cards

The existing stat-card pattern is a good foundation and should be refined rather than discarded.

Preferred treatment:

- small descriptive label
- large metric value
- optional trend or context line
- restrained icon treatment
- a single semantic accent if needed
- equal clarity and hierarchy in both light and dark themes

### 7.3 Data Tables

Tables are a core ScholarDex interaction surface and should be a high-priority consistency target.

Guidance:

- consistent table title and actions area
- standardized filtering and pagination affordances across pages
- horizontal row separation rather than heavy full-grid borders
- clear status treatment using badges or other structured indicators
- monospace presentation for identifier-heavy columns
- restrained row hover states

Table UX work should converge on ScholarDex-owned patterns rather than preserving BS4 DataTables styling as the target presentation. Filtering, sorting, pagination, and row states must remain legible in both light and dark themes.

### 7.4 Buttons

Use a clear action hierarchy:

- primary for the main action in a view or section
- secondary for supporting actions
- ghost or tertiary for low-emphasis actions
- danger only for destructive operations

Icon-only actions must remain legible, accessible, and consistently sized.

### 7.5 Badges And Status Indicators

Prefer structured status indicators over raw text. A status badge should combine:

- semantic color
- text label
- optional dot or icon

Color must not be the only signal.

### 7.6 Forms

Forms should emphasize readability and predictability.

Guidance:

- labels above inputs
- helpful, concise supporting text where needed
- clear required/optional states
- visibly distinct readonly fields
- consistent input sizing across similar contexts
- multi-step flows should expose current step and progress
- touched form behavior should migrate away from Bootstrap 4 modal/tooltip assumptions and remain coherent in both light and dark themes

### 7.7 Empty States

List and table views should not fail into blank space. Empty states should explain:

- what is missing
- why the page is empty
- what action, if any, the user can take next

### 7.8 Feedback And Notifications

Prefer explicit user feedback after significant actions:

- success/error/information messaging for mutations or background operations
- inline validation for form issues
- clear confirmation for destructive actions

The exact implementation may vary while the shared notification pattern is still evolving, but new work should move toward consistent messaging rather than one-off alerts.
Feedback patterns must remain recognizable and accessible in both light and dark themes.

---

## 8. Page-Level Heuristics

These are durable guidance patterns, not a backlog.

### 8.1 Dashboards

Dashboards should surface the most useful summary information for the user role:

- key metrics
- recent activity
- items needing attention
- quick actions

Avoid empty dashboards that provide only navigation chrome.
Dashboard, summary, and quick-action patterns should maintain contrast, emphasis, and readable hierarchy in both themes.

### 8.2 List And Table Pages

Standard direction:

1. Page header with title and primary action.
2. Optional summary context above the main table.
3. Filtering/search affordances near the table.
4. Consistent table structure and pagination.
5. Explicit empty-state behavior.

### 8.3 Edit And Detail Pages

Standard direction:

1. Clear page title and route context.
2. Grouped form or detail content.
3. Save/cancel actions that remain discoverable on long pages.
4. Distinct treatment for readonly or system-managed fields.

### 8.4 Reports And Score Views

Report-like views should emphasize:

- scannable score or criterion blocks
- collapsible supporting detail where dense data is unavoidable
- visual consistency between summary values and supporting explanations

### 8.5 Error Views

Authenticated error pages should preserve orientation and navigation context when practical. Unauthenticated error pages may use a simpler standalone layout.

---

## 9. Accessibility Checklist

These are requirements, not optional enhancements.

- All interactive controls must be keyboard reachable.
- Labels and controls must be programmatically associated.
- Focus states must remain visible.
- Color must not be the only carrier of meaning.
- Icon-only controls must have meaningful `aria-label` text.
- Images must use correct decorative vs meaningful alt treatment.
- Tables must use semantic table structure.
- Landmarks such as navigation and main content should remain explicit.
- Dynamic feedback should be announced appropriately when needed.
- New or refreshed UI work should maintain WCAG AA contrast expectations for text and controls.
- These expectations must be validated in both light and dark themes, not only the default theme.

---

## 10. Motion And Interaction Polish

Motion should be minimal and functional.

- Use short transitions for sidebar collapse, menus, and expandable sections.
- Avoid theatrical page transitions for server-rendered navigation.
- Use loading states that explain waiting without overwhelming the interface.
- Prefer consistency and responsiveness over ornamental animation.

---

## 11. Current Constraints And Migration Notes

These notes are intentionally separate from the durable UX rules above.

- The current shared shell still includes SB Admin 2 styling and JS behavior.
- Many templates still use Bootstrap 4-era classes and patterns, and some page surfaces still rely on BS4 DataTables or jQuery-driven UI behavior.
- Topbar role switching currently appears as a `Switch Workspace` dropdown in shared fragments.
- New and touched UX work should treat SB Admin 2, Bootstrap 4-era conventions, BS4 DataTables coupling, and jQuery-driven UI behavior as migration debt to retire rather than a baseline to preserve.

When performing migration or shell cleanup work:

- preserve the runtime asset contract: `/assets/app.css` and `/assets/app.js`
- evolve styles and behavior through `frontend/src/**` and shared fragments
- replace SB Admin 2-dependent shared-shell behavior with repo-owned shared-shell behavior where the slice touches those areas
- converge touched UI on Bootstrap 5-compatible markup/data APIs and repo-owned behavior
- implement light/dark support through shared tokens and theme-aware styles rather than page-by-page special cases
- remove legacy dependencies only in explicit cleanup slices
- keep route- and role-aware shared composition intact while modernizing the shell

Implementation work touching templates, assets, or shared UI behavior should still follow the repo's frontend and verification guardrails.

---

## 12. Design Tokens Direction

A shared token layer is still the right direction, but it must fit inside the current frontend build pipeline.

Illustrative starter variables:

```css
:root {
  --color-primary: #4361EE;
  --color-primary-hover: #3A56D4;
  --color-primary-light: #EEF1FD;
  --color-success: #059669;
  --color-warning: #D97706;
  --color-danger: #DC2626;
  --color-info: #2563EB;
  --color-neutral-900: #111827;
  --color-neutral-700: #374151;
  --color-neutral-500: #6B7280;
  --color-neutral-300: #D1D5DB;
  --color-neutral-200: #E5E7EB;
  --color-neutral-100: #F3F4F6;
  --color-neutral-50: #F9FAFB;
  --color-white: #FFFFFF;
}
```

These should be introduced and evolved through the existing frontend source and emitted asset bundle rather than by creating a separate runtime styling contract.

---

## 13. Frontend Organization Notes

The current contract remains:

- authored source in `frontend/src/**`
- emitted runtime assets in `src/main/resources/static/assets/**`
- template usage through `/assets/app.css` and `/assets/app.js`

If contributors choose to further organize shared styles or UI modules, that organization should remain internal to the existing frontend build pipeline. It should not create a second parallel frontend structure or bypass the current asset contract.
