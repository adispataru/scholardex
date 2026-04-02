# ScholarDex UX Design Guide

Status: active contributor UX guidance.

**Version:** 2.0
**Date:** April 2026
**Visual direction:** Clean, modern SaaS — restrained surfaces, intentional color, confident typography
**Audience:** Contributors improving templates, shared fragments, frontend modules, and UI behavior

This guide defines the UX target for ScholarDex. It describes what the product should feel like, how users should experience each surface, and what design qualities contributors should aim for when touching any page. It is a design compass, not an implementation spec — specific pages and page families will make their own implementation choices within these guidelines.

The authenticated shell, table/list, workflow, summary, footer, and shared runtime families already operate on a ScholarDex-owned frontend baseline with light and dark theme support. This guide builds on that foundation and pushes toward a more polished, more intentional product experience.

Use this guide together with `docs/frontend-conventions.md` for template and asset rules, `docs/doc-governance.md` for doc placement, and `docs/quality-gates.md` for verification expectations.

---

## 1. Design Principles

### 1.1 Calm Confidence

ScholarDex handles complex academic data — publications, citations, rankings, institutional hierarchies, scoring criteria. The interface should project calm authority. Generous whitespace, restrained color, and clear hierarchy tell users: "this tool knows what it's doing, and so will you." Avoid visual noise, decoration-for-its-own-sake, and cramped layouts that make complexity feel overwhelming.

### 1.2 One Question Per Screen

Every page should have a clear primary purpose that a user can articulate in one sentence. "I'm looking at my publications." "I'm editing this institution." "I'm reviewing open conflicts." When a page tries to answer too many questions at once, it should be restructured around progressive disclosure — surface the primary answer immediately, and let secondary information unfold on demand.

### 1.3 Meaningful Defaults

Users shouldn't need to configure, filter, or click through to reach useful information. Dashboards should show the most actionable data by default. Tables should open with sensible sorting. Filters should start from the most useful position (usually "all open items" or "my items"), not from blank. The first thing a user sees after landing on a page should already be valuable.

### 1.4 Trust Through Consistency

Every list paginates the same way. Every form validates the same way. Every destructive action confirms the same way. Every empty state explains itself. Users build muscle memory and mental models. Breaking consistency — even for a single page — costs trust. When two pages serve similar functions, they should be visually and structurally indistinguishable until the data differs.

### 1.5 Respect Both Audiences

Researchers managing their own publications and administrators overseeing institutional data have different mental models and priorities. Admin pages can tolerate more density, more batch operations, more technical identifiers. Researcher pages should feel warmer, more personal, and more guided. The same design language serves both, but the emphasis shifts.

### 1.6 Accessibility Is Not Optional

Keyboard navigation, visible focus states, screen reader support, sufficient contrast, and semantic markup are baseline requirements, not stretch goals. Both light and dark themes must meet WCAG AA contrast standards. Every interactive element must be operable without a mouse.

---

## 2. Visual Identity

### 2.1 The Feel

ScholarDex should feel like a well-made professional tool — closer to Linear, Notion, or Stripe Dashboard than to a generic admin panel. The keywords are: **clean, spacious, quietly sophisticated.** Surfaces should breathe. Cards should float gently, not press down with heavy shadows. Color should appear with purpose, not because a component needs decoration.

### 2.2 Light and Dark as First-Class Citizens

Both themes are full product experiences, not afterthoughts. Light mode uses soft warm-neutral backgrounds with white cards. Dark mode uses deep blue-blacks with subtle surface elevation. Neither theme should feel like a simple color inversion of the other — each should feel intentionally designed with its own sense of depth, hierarchy, and warmth.

### 2.3 Color Philosophy

Color carries meaning, not decoration. The palette should be used as follows:

**Primary blue** is reserved for interactive elements: links, primary buttons, active navigation, focus rings. It should never appear as a static label color or background wash without interaction intent.

**Semantic colors** signal consequence: green for success/resolved/passed, amber for pending/attention/threshold, red for errors/open conflicts/danger. These should appear on badges, status dots, alert surfaces, and card accents — always paired with a text label so color is never the only carrier of meaning.

**Neutrals** do most of the work. Text, borders, backgrounds, dividers, and structural elements should all live in the neutral range. The visual experience should feel predominantly grayscale with purposeful color punctuation.

**Restraint rule:** No card, row, or section should carry more than one accent color. If a stat card has a colored left border, its interior text should be neutral. If a table row has a colored badge, it shouldn't also have a colored background.

### 2.4 Surface and Depth

The product uses a layered surface model: page background sits behind content cards, which sit behind floating elements (dropdowns, modals, tooltips). Depth should come primarily from subtle background-color differences and borders rather than heavy drop shadows. In dark mode, brighter surfaces mean closer to the user. In light mode, whiter surfaces mean closer to the user.

---

## 3. Typography

### 3.1 Hierarchy Through Weight, Not Ornamentation

Typography does more work than color in ScholarDex. The type system should create clear, scannable hierarchy through weight and size differences alone — without relying on uppercase, letter-spacing, or color to distinguish levels.

**Page titles** should feel authoritative but not shouty: large, semi-bold, high-contrast. No forced uppercase.

**Section and card titles** are one step down: moderately sized, semi-bold, neutral color. They should orient users within the page without competing with the page title.

**Body text and table cells** are the workhorse layer: readable, comfortable line-height, regular weight. This is where users spend most of their reading time, so legibility is paramount.

**Hero metrics** — the big numbers on stat cards and dashboards — are the visual anchor of any summary surface. They should be bold, noticeably larger than surrounding text, and the first thing the eye lands on.

**Metadata labels** above hero metrics, in table headers, and on form sections are small, medium-weight, and muted. They explain; they don't compete.

**System identifiers** (Scopus IDs, ISSN numbers, WoS codes, formulas) should use monospace styling. This is a subtle but important signal that the value is a reference code, not prose.

### 3.2 Content Voice

Labels, helper text, intro paragraphs, and empty states are all part of the UX. They should be:

**Concise.** Don't explain what the user can already see. "Publications" as a card title doesn't need "This is a list of your publications" underneath.

**Helpful where non-obvious.** When a field has constraints, side-effects, or sources the user might not know about, explain briefly: "Find your Scopus Author ID on your Scopus profile page."

**Human but professional.** Avoid jargon when a plain term works. "No publications yet" is better than "Zero records returned." But technical terms that the audience understands (h-Index, ISSN, WoS) should be used without dumbing them down.

**Action-oriented in empty states.** "No publications yet — add your first one to start tracking your research" is better than "No data available."

---

## 4. Layout and Spatial Design

### 4.1 Breathing Room

The most common UX sin in data-heavy apps is cramming. ScholarDex should err on the side of more space, not less. Cards need generous internal padding. Sections need clear separation. Tables need comfortable cell padding. The user's eye should move through a page smoothly, not fight through walls of information.

### 4.2 Content Width

On wide screens, unconstrained content becomes unreadable. The content area should be bounded at a comfortable maximum width and centered. Tables and data-heavy views may expand beyond this when they genuinely need the space, but body text, forms, and card grids should stay within the readable zone.

### 4.3 Page Anatomy

Every authenticated page follows the same structural skeleton:

1. **Sidebar** — persistent, role-aware, collapsible. Always available for navigation.
2. **Header area** — page title, optional breadcrumb, workspace context, theme toggle, and global actions.
3. **Content area** — the page's primary content, bounded for readability, with consistent horizontal and vertical padding.
4. **Footer** — minimal, pushed to the bottom via flex layout.

Within the content area, the rhythm should be:

1. **Page toolbar** (optional) — primary action buttons, export links, right-aligned.
2. **Intro text** (optional) — one line of context. Not every page needs this; use it only when the page purpose is non-obvious or when the context changes (e.g., viewing publications scoped to a specific author).
3. **Summary cards** (optional) — stat cards or key metrics above the main content.
4. **Primary content** — the table, form, report grid, or detail view that is the reason the page exists.

### 4.4 Responsive Behavior

Desktop is the primary context, but tablet and phone experiences should be genuinely usable, not just technically functional.

**Sidebar** should collapse to icon-only on medium screens and become an off-canvas drawer on small screens. Navigation must remain reachable at all sizes.

**Stat card grids** should flow from multi-column to single-column gracefully, maintaining card proportions rather than stretching oddly.

**Tables** should scroll horizontally on small screens, but critical columns (name, status, primary action) should be prioritized on the left so they remain visible without scrolling.

**Action buttons** must never disappear on mobile. If a page has primary actions (Add Publication, Export, Refresh), they should be accessible at every breakpoint — relocated into a more compact layout if needed, but never hidden behind `d-none d-sm-inline-block`.

**Forms** should go single-column on small screens. Two-up or three-up field grids should collapse naturally.

---

## 5. Navigation

### 5.1 Sidebar

The sidebar is the user's primary orientation tool. It answers "where am I?" and "where can I go?" at all times. It should feel calm, scannable, and predictable.

**Grouping.** Items should be organized into clearly labeled sections. Section labels are quiet — small, muted, uppercase is acceptable here because these labels are pure structural organizers. Each section should contain 3-6 items; more than that and the section should be split or items should be nested.

**Active state.** The current page's sidebar item should be immediately obvious — a background highlight, a left accent bar, or both. The user should never have to scan the sidebar to figure out which page they're on.

**Label clarity.** Every sidebar label should be understandable to a first-time user of that role. Technical internal names (like "USER_DEFINED Triage") should be translated to user-facing language (like "Data Triage"). If a label requires explanation, it's the wrong label.

**Collapse behavior.** When collapsed to icon-only mode, icons must be distinctive enough to navigate by. Tooltips on hover should reveal the full label. The collapsed state should feel intentional, not broken.

### 5.2 Header and Page Context

The header area serves two functions: global orientation (what workspace am I in? what theme? who am I?) and page-level context (what page is this? what can I do here?).

**Page title** is the single most important orientation element. It should be prominent but not overwhelming — semi-bold, clearly the largest text in the header area.

**Workspace switcher** should make the current role context immediately visible. Users who only have one role should still see their context but shouldn't see a switcher dropdown. Users with multiple roles should be able to switch with one click.

**Breadcrumbs** should appear on pages deeper than one level in a navigation path (e.g., "Publications > Edit Publication" or "Reports > CNFIS 2025"). They provide a return path and contextual orientation. Not every page needs them — top-level section pages don't.

---

## 6. Component Patterns

These patterns describe the intended UX behavior and visual qualities for each component family. They are not implementation specs — specific surfaces will make their own choices about markup and class names within these patterns.

### 6.1 Cards

Cards are the fundamental grouping container. They separate content from the page surface and create scannable visual blocks.

- Background should be the brightest surface in the hierarchy (white in light mode, the nearest elevated dark surface in dark mode).
- Borders should be subtle — a faint neutral line. Heavy borders make cards feel boxy rather than floating.
- Corner radius should be generous enough to feel modern (not sharp-cornered) but not so round that it feels toy-like.
- Internal padding should be comfortable — noticeably more than the minimum needed. Cramped cards feel cheap.
- Card headers, when present, should be separated from the body by a subtle divider. The header title should be the card's "name" — concise, semi-bold, neutral-colored.
- Cards should not carry colored left borders, top borders, or background washes unless the color communicates specific semantic meaning (e.g., a stat card for "open conflicts" might use a danger accent).

### 6.2 Summary and Stat Cards

These are the "hero" elements on dashboards and overview pages. They exist to give the user a fast read on key numbers before diving into detail.

**The metric value is king.** It should be the largest, boldest element in the card — the thing the eye hits first. Everything else (label, icon, context line) is supporting cast.

**Labels** sit above the value, small and muted. They name the metric without competing visually.

**Context lines** (optional) sit below the value. These can show trend ("up 12 from last month"), scope ("for the current report period"), or status ("2 need attention"). They should be small and muted.

**Semantic accents** (a colored left border, a colored dot on the label) should appear only when the metric category has inherent meaning: danger for open problems, success for completed items, warning for pending attention. Neutral metrics (total publications, total researchers) should stay unaccented.

**Grid behavior.** Stat cards should sit in an auto-flowing grid that adapts from 3-4 columns on desktop to 1 column on mobile. Cards should all be the same height within a row.

### 6.3 Data Tables

Tables are ScholarDex's most-used pattern. They must be excellent.

**Visual treatment.** Tables should feel light and open, not gridded and heavy. Prefer horizontal row dividers only — vertical cell borders add visual noise without aiding readability. Alternating row backgrounds (very subtle) help track the eye across wide tables. Row hover should be a gentle highlight.

**Header row.** Column headers should be small, semi-bold, and muted — they're labels, not content. Sortable columns should have a subtle visual indicator. Sticky headers on long tables help users maintain column context while scrolling.

**Status columns.** Never display raw text like "OPEN" or "RESOLVED" in a table cell. Use structured badges with semantic color and a text label. Status should be immediately scannable without reading.

**Identifier columns.** Scopus IDs, WoS IDs, ISSN numbers, and similar reference codes should render in monospace at a slightly muted color. This visually separates "data for machines" from "data for humans" and improves scannability.

**Action columns.** Row-level actions (edit, view, delete, apply) should be compact icon buttons or small text links. Avoid full-sized buttons in table cells — they create visual clutter. If a row has more than two actions, consider an overflow menu ("...").

**Pagination.** Every paginated table should use the same pagination pattern: a previous/next control, a page position indicator ("Page 2 of 14"), and optionally a page-size selector. Whether pagination is client-side or server-side is an implementation choice, but the visible controls must be identical.

**Filtering.** When a table supports filtering, the filter controls should live in a lightweight panel directly above or within the table card — not in a separate card that creates visual separation between the filter and the data it controls. Filters should feel like they belong to the table, not like a separate UI concern.

**Empty state.** When filters return no results or when the table has no data, display a clear empty state inside the table area — not a blank white space.

### 6.4 Forms

Forms are how users create and modify data. They should feel approachable, not like paperwork.

**Labels above inputs, always.** No floating labels, no inline labels, no labels that only appear on focus. The user should see the field name before they start typing.

**Input sizing.** Inputs should be tall enough to feel comfortable (not cramped single-line fields) and wide enough to show meaningful content. Full-width is the default for single-column forms.

**Readonly fields** should be visually distinct from editable ones — a muted background, no border, or a lock indicator. They should also explain *why* they're readonly when that isn't obvious ("Auto-populated from Scopus" is better than a silently grayed-out field).

**Helper text** belongs below the input, small and muted. Use it to explain sourcing, constraints, or formatting expectations. Don't use it to restate the label.

**Required vs optional.** Mark whichever is the minority. If most fields are required, mark the optional ones with "(optional)." If most are optional, mark the required ones with a red asterisk.

**Multi-step flows** must show the user where they are. A step indicator (numbered steps with labels, showing current/completed/upcoming) gives orientation and progress sense. The user should never wonder "how many more screens until I'm done?"

**Dynamic collections** (add/remove lists of IDs, affiliations, etc.) should use a clean list-plus-add pattern: each item in a row with a subtle remove control, and an "Add another" action below the list. Adding and removing should feel lightweight, not like a major operation.

**Validation** should happen on blur (when the user leaves a field), not on every keystroke. Error messages appear below the offending field with a clear visual treatment (red text, red border). Success validation (green checks) should be used sparingly — only for fields where correctness is actively verified (like ISSN format validation).

### 6.5 Badges and Status Indicators

Structured status indicators replace raw text wherever a piece of data represents a state.

A badge combines a semantic background color (at low opacity), matching text color, and a text label. Optionally a small dot or icon reinforces the color signal. Badges should be compact — pill-shaped, small text, just enough padding to be legible.

Common status mappings:
- **Open, Active, In Progress** — primary or warning treatment depending on context
- **Resolved, Completed, Passed** — success treatment
- **Dismissed, Archived, Inactive** — neutral/muted treatment
- **Failed, Error, Conflict** — danger treatment

### 6.6 Empty States

Every view that can be empty must handle that state explicitly. An empty state has three parts:

1. **What's missing** — a clear title: "No publications yet," "No conflicts match your filters."
2. **Why it matters or what happened** — a brief explanation: "Add your first publication to start tracking your research output" or "Try broadening your filter criteria."
3. **What to do** (when applicable) — a primary action button: "Add Publication," "Clear Filters."

Empty states for filtered-to-zero should be visually lighter than empty states for genuinely-no-data, since the former is temporary and the latter may need onboarding guidance.

### 6.7 Feedback and Notifications

After a user takes an action, the interface should confirm what happened.

**Toasts** for ephemeral confirmations: "Publication saved," "Report refreshed," "Conflict resolved." These appear briefly, then disappear. They should be small, positioned consistently (e.g., top-right below the header), and carry a semantic color accent matching the action outcome.

**Inline alerts** for persistent page-level messages: errors from a failed save, success from a completed batch operation, warnings about data integrity. These live within the content flow and persist until the user navigates away or the condition clears.

**Confirmation dialogs** for destructive or irreversible actions: deleting records, clearing all conflicts, bulk status changes. The dialog should name what will happen, state consequences if any, and offer a clear cancel path. The destructive action button should be visually distinct (danger-colored) and should never be the default/pre-focused button.

### 6.8 Modals and Overlays

Modals should be used sparingly — only when an action genuinely needs to interrupt the current context (confirmations, quick create/edit that doesn't warrant a full page, selecting from a complex list). Never use a modal for something that should be a page.

When used, modals should: have a clear title, be dismissible via Escape and clicking outside, trap focus while open, and restore focus on close.

### 6.9 Charts and Data Visualization

When charts appear (indicator dashboards, report views), they should follow the same visual restraint as the rest of the product. Avoid gratuitous 3D effects, excessive gridlines, or rainbow color schemes. Charts should use the product's semantic color palette, have clear axis labels, and include a legend when the meaning isn't self-evident.

Charts must be legible in both themes. Avoid colors that lose contrast against dark backgrounds.

---

## 7. Page Family Guidelines

These describe the UX intent for each major page type. They are durable direction, not a task backlog.

### 7.1 Dashboards

A dashboard is the user's home base. It should answer: "What should I pay attention to right now?"

**User Dashboard** should surface: publication and citation summary metrics, recent activity or changes, any pending actions (reports to review, profiles to complete), and quick links to the most common tasks.

**Admin Dashboard** should surface: operational health indicators (open conflicts, data integrity status), recent system activity (latest updates, sync status), items needing admin attention, and quick links to the most common admin operations.

Dashboards should never be empty shells. If live data isn't available for a card or section, show a meaningful placeholder state that explains what will appear there, not a blank card with a static label.

### 7.2 List and Table Pages

These are the most common page type. The pattern should be:

1. Page header with title and primary action(s).
2. Optional summary context (stat cards, author profile card) above the table when the data has meaningful aggregate metrics.
3. Filter controls integrated with the table surface.
4. The data table itself, with consistent column styling, status badges, and row actions.
5. Pagination controls below the table.
6. Empty state when no data matches.

Lists that represent a researcher's personal data (publications, activities, reports) should feel warm and ownership-oriented: "Your Publications," personal stat cards, profile context.

Lists in the admin context can be more operational and neutral: "Researchers List," "Identity Conflict Queue."

### 7.3 Edit and Detail Pages

The pattern should be:

1. Page header with title and breadcrumb back to the parent list.
2. Form content in a clean card surface, with field groups separated by subtle section dividers or headings.
3. Readonly fields styled distinctly with an explanation of why they can't be edited.
4. Save/Cancel actions at the bottom of the form (or sticky at the bottom for long forms).

Long forms should group related fields under section headings. A form with 15 ungrouped fields is harder to scan than three groups of five.

### 7.4 Multi-Step Workflows

Any process that spans more than one page (like adding a publication) should have:

1. A step indicator showing current position, total steps, and step labels.
2. Clear forward/back navigation with a labeled "Next" button (not just a generic "Submit").
3. Preservation of entered data when navigating between steps.
4. A review or summary step at the end when the operation is significant.

Steps should have action-oriented labels that describe what the user does at each step ("Select Forum," "Add Authors," "Review & Submit") rather than generic labels ("Step 1," "Step 2").

### 7.5 Report and Score Views

The individual report view (with criterion cards, threshold icons, and collapsible indicator lists) is the most design-advanced page in the current product. Its patterns should be the reference for other report-like surfaces:

- **Card-per-criterion** layout with a scannable grid.
- **Score values** as the visual anchor of each card.
- **Threshold indicators** that show progress through a career-level scale using semantic icons and color states (passed, failed, selected).
- **Collapsible detail** for secondary information (individual indicator scores) that the user can expand on demand.
- **Refresh actions** clearly positioned and scoped (refresh one indicator vs. refresh all).

### 7.6 Error Pages

Authenticated users who hit an error should stay inside the application shell — sidebar and navigation remain available so they can self-recover. The error content should be centered, friendly, and provide clear escape routes ("Go back," "Go to dashboard").

Unauthenticated errors (like a 404 on a public URL) can use a standalone centered layout.

Error pages should work in both themes.

---

## 8. Interaction Quality

### 8.1 Motion

Motion should be functional, not decorative. It communicates state changes and spatial relationships.

- **Sidebar collapse/expand:** smooth width transition, fast enough to feel responsive (~200ms).
- **Dropdown menus and workspace switcher:** subtle fade-in with slight vertical shift. Opens fast, closes immediately.
- **Collapsible sections** (filter panels, indicator lists): height transition with ease-in-out.
- **Page navigation:** No transition. Server-rendered pages should load fast; adding animated transitions would feel sluggish.
- **Hover states:** Instant color change, no transition delay. Interactive elements should feel immediately responsive.
- **Loading states:** For data that takes time to load, prefer skeleton shimmer placeholders (gray boxes that pulse) over spinners. Spinners should only appear on explicit user-triggered actions (button clicks that trigger server operations).

### 8.2 Progressive Disclosure

Not everything needs to be visible at once. Dense pages should layer information:

- **Level 1:** Summary metrics and status — visible immediately.
- **Level 2:** Detailed data tables and lists — visible immediately but scrollable.
- **Level 3:** Secondary detail (individual indicator scores, author affiliations, conflict candidate lists) — collapsed by default, expandable on demand.

The toggle between levels should be obvious (a clear "show more" or chevron affordance) and maintain the user's scroll position.

### 8.3 Keyboard Navigation

All interactive elements must be reachable and operable via keyboard. Tab order should follow the visual layout. Focus rings must be visible in both themes. Modal focus must be trapped. Escape should close overlays and menus. Enter should activate focused buttons and links.

### 8.4 Error Recovery

When something goes wrong (a form save fails, a network request errors, a batch operation partially succeeds), the interface should:

1. Tell the user what happened in plain language.
2. Preserve their work (don't clear a form because the save failed).
3. Suggest what to do next ("Try again" or "Check your connection").
4. Make the retry path obvious (a button, not just a message).

---

## 9. Accessibility Requirements

These are requirements for all new and touched UI work.

- All interactive controls must be keyboard reachable and operable.
- Focus states must be visible in both light and dark themes.
- Labels and inputs must be programmatically associated (via `for`/`id` or `aria-labelledby`).
- Color must never be the only carrier of meaning — always pair with text, icons, or patterns.
- Icon-only buttons must have descriptive `aria-label` text (e.g., "Edit publication," not "Edit").
- Images use meaningful `alt` text for content images and `alt=""` with `role="presentation"` for decorative images.
- Tables use semantic structure: `<thead>`, `<tbody>`, `scope="col"` on header cells.
- ARIA landmarks (`<nav>`, `<main>`, `<aside>`) should be present in the page structure.
- Dynamic content updates (toasts, filtered table results, async load completions) should be announced to screen readers when appropriate.
- All text and interactive controls must meet WCAG AA contrast ratios (4.5:1 for normal text, 3:1 for large text) in both themes.
