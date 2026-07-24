/**
 * workspaceActivities.js — H36.8 Activities tab with master-detail
 *
 * Replaces the H36.3 minimal content rendering for the activities tab.
 * Registered as window.appWorkspaceActivities.init(panel) from workspace.html.
 *
 * Renders:
 *  - Toolbar: Add Activity
 *  - Stats: instance count, activity types count
 *  - Doughnut chart (Chart.js v2) for type distribution
 *  - Paginated table (20/page): Name, Date, Type, Actions
 *  - Inline detail row on click: edit fields + reference fields + delete
 *  - Inline create panel above table
 *  - Empty state
 *
 * Endpoints:
 *   POST /user/workspace/activities/create
 *   POST /user/workspace/activities/update
 *   POST /user/workspace/activities/delete/{id}
 *   GET  /user/activities/activity/{id}/fields   (existing, reused)
 */

import { postJsonHeaders } from '../shared/fetchUtils';
import { buildPaginationHtml, wirePaginationClicks } from '../shared/clientPagination';

const PAGE_SIZE = 20;

const REF_LABELS = {
    FORUM_NAME:      'Forum Name',
    FORUM_ISSN:      'ISSN',
    FORUM_EISSN:     'E-ISSN',
    FORUM_ISBN:      'ISBN',
    FORUM_PUBLISHER: 'Publisher',
    PROJECT_GRANT_ID:'Grant ID',
    UNIVERSITY_NAME: 'University',
    EVENT_NAME:      'Event Name',
};

// ── Module state ─────────────────────────────────────────────────────────────

let _panel      = null;
let _mount      = null;
let _data       = null;
let _instances  = [];
let _page          = 1;
let _activeId      = null;
let _createOpen    = false;
let _pendingCreate = false;  // open create form as soon as the tab finishes loading
let _searchQuery   = '';     // inline text filter

// ── Public API ───────────────────────────────────────────────────────────────

export function initWorkspaceActivities() {
    window.appWorkspaceActivities = {
        init: _init,
        openCreate() {
            if (_mount && _data) { _toggleCreate(); }
            else                 { _pendingCreate = true; }
        },
        // Called by workspaceSearch to pre-populate the filter after tab switch.
        filterBy(q) {
            _searchQuery = (q ?? '').trim().toLowerCase();
            _page = 1;
            _activeId = null;
            if (_mount && _data) _renderPage();
        },
    };
}

// ── Init / fetch ─────────────────────────────────────────────────────────────

function _init(panel) {
    _panel     = panel;
    _mount     = panel.querySelector('[data-workspace-lazy-panel]');
    if (!_mount) return;

    const src = _mount.dataset.src;
    if (!src) return;

    _page       = 1;
    _activeId   = null;
    _createOpen = false;

    _showSkeleton();

    fetch(src, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(data => { _data = data; _instances = Array.isArray(data.activityInstances) ? data.activityInstances : []; _renderAll(); })
        .catch(() => { _mount.innerHTML = _buildError(); });
}

function _showSkeleton() {
    _mount.setAttribute('aria-busy', 'true');
    _mount.innerHTML = `
        <div class="app-skeleton-chart"><div class="app-skeleton-block"></div></div>
        <div class="app-skeleton-table">
          <div class="app-table-section__surface">
            <div class="app-skeleton-table__header">
              <div class="app-skeleton-block app-skeleton-table__header-bar"></div>
            </div>
            ${Array.from({ length: 4 }, () => `
            <div class="app-skeleton-row">
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--title"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--date"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--medium"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--narrow"></div>
            </div>`).join('')}
          </div>
        </div>`;
}

// ── Top-level render ─────────────────────────────────────────────────────────

function _renderAll() {
    const container = document.createElement('div');
    container.className = 'app-ws-acts';

    // Single summary card: hero numbers + distribution bar
    container.insertAdjacentHTML('beforeend', _buildSummaryCard());

    // H78 — "My projects" (canonical projects where the researcher is the director). Read-only, lazy-filled
    // after mount; hidden until/unless the match returns rows.
    const myProjects = document.createElement('div');
    myProjects.id = 'ws-acts-my-projects';
    myProjects.hidden = true;
    container.appendChild(myProjects);

    // Toolbar
    container.insertAdjacentHTML('beforeend', _buildToolbar());

    // Create panel placeholder (hidden initially)
    const createPlaceholder = document.createElement('div');
    createPlaceholder.id = 'ws-acts-create-placeholder';
    container.appendChild(createPlaceholder);

    // Table wrapper
    const tableWrap = document.createElement('div');
    tableWrap.className = 'app-ws-acts__table-wrap';
    tableWrap.id = 'ws-acts-table-wrap';

    if (_instances.length === 0) {
        container.insertAdjacentHTML('beforeend', _buildEmpty());
    } else {
        container.appendChild(tableWrap);
    }

    _mount.removeAttribute('aria-busy');
    _mount.innerHTML = '';
    _mount.appendChild(container);

    // Wire toolbar
    document.getElementById('ws-acts-add-btn')?.addEventListener('click', () => _toggleCreate());
    document.getElementById('ws-acts-participant-btn')?.addEventListener('click', () => _toggleParticipantImport());


    // Render table
    if (_instances.length > 0) {
        _renderPage();
    }

    // H78 — fill the "My projects" card (director-attributed canonical projects)
    _loadMyProjects();

    // Escape key
    document.addEventListener('keydown', _handleEscape);

    // Retry
    _mount.addEventListener('click', e => {
        if (e.target.closest('[data-retry-panel]')) _init(_panel);
    });

    if (_pendingCreate) { _pendingCreate = false; _toggleCreate(); }
}

function _renderPage() {
    const wrap = document.getElementById('ws-acts-table-wrap');
    if (!wrap) return;
    wrap.innerHTML = '';

    const filtered = _searchQuery
        ? _instances.filter(inst => {
              const q    = _searchQuery;
              const name = (inst.name ?? inst.activity?.name ?? '').toLowerCase();
              const type = (inst.activity?.name ?? '').toLowerCase();
              return name.includes(q) || type.includes(q);
          })
        : _instances;

    const start     = (_page - 1) * PAGE_SIZE;
    const pageItems = filtered.slice(start, start + PAGE_SIZE);
    const total     = filtered.length;
    const pages     = Math.ceil(total / PAGE_SIZE);

    const table = document.createElement('table');
    table.className = 'app-ws-acts__table';
    table.setAttribute('role', 'grid');
    table.innerHTML = `
        <colgroup>
          <col class="app-ws-acts__col-name">
          <col class="app-ws-acts__col-date">
          <col class="app-ws-acts__col-type">
          <col class="app-ws-acts__col-actions">
        </colgroup>
        <thead><tr>
          <th scope="col">Name</th>
          <th scope="col">Date</th>
          <th scope="col">Type</th>
          <th scope="col" style="text-align:right"><span class="sr-only">Actions</span></th>
        </tr></thead>
        <tbody id="ws-acts-tbody"></tbody>`;

    wrap.appendChild(table);
    const tbody = table.querySelector('#ws-acts-tbody');
    for (const inst of pageItems) _appendRow(tbody, inst);

    if (pages > 1) {
        const paginationEl = document.createElement('div');
        paginationEl.innerHTML = buildPaginationHtml({ page: _page, total, pageSize: PAGE_SIZE, label: 'Activities pagination' });
        wrap.appendChild(paginationEl.firstElementChild);
        wirePaginationClicks(wrap, (newPage) => {
            _page = newPage;
            _activeId = null;
            _renderPage();
        });
    }
}

// ── Row ───────────────────────────────────────────────────────────────────────

function _appendRow(tbody, inst) {
    const actName = inst.activity?.name ?? '—';
    const date    = inst.date ?? '—';
    const name    = inst.name ?? actName;

    const tr = document.createElement('tr');
    tr.className = 'app-ws-acts__row';
    tr.dataset.instId = inst.id;
    if (_activeId === inst.id) tr.classList.add('app-ws-acts__row--active');

    tr.innerHTML =
        `<td class="app-ws-acts__col-name">` +
            `<span class="app-ws-acts__name">${_esc(name)}</span>` +
        `</td>` +
        `<td class="app-ws-acts__col-date">${_esc(date)}</td>` +
        `<td class="app-ws-acts__col-type">` +
            `<span class="app-ws-acts__type-badge">${_esc(actName)}</span>` +
        `</td>` +
        `<td class="app-ws-acts__col-actions" style="text-align:right">` +
            // H78 — quick "Link" affordance for a project-supporting activity that isn't linked yet.
            ((_supportsProjectLink(inst) && !_isProjectLinked(inst))
                ? `<button class="app-ws-acts__action-btn app-ws-acts__link-btn" type="button" ` +
                        `aria-label="Link to project" data-link-inst="${_esc(inst.id)}">` +
                        `<i class="fa-solid fa-link" aria-hidden="true"></i> Link` +
                    `</button>`
                : '') +
            `<button class="app-ws-acts__action-btn" type="button" ` +
                    `aria-label="Details: ${_esc(name)}" aria-expanded="false" ` +
                    `data-detail-btn="${_esc(inst.id)}">` +
                `<i class="fa-solid fa-chevron-down" aria-hidden="true"></i>` +
            `</button>` +
        `</td>`;

    tr.addEventListener('click', e => {
        if (e.target.closest('a, button.app-ws-acts__action-btn--danger, [data-link-inst]')) return;
        _toggleDetail(inst, tr);
    });
    tr.querySelector('[data-detail-btn]').addEventListener('click', e => {
        e.stopPropagation();
        _toggleDetail(inst, tr);
    });
    tr.querySelector('[data-link-inst]')?.addEventListener('click', e => {
        e.stopPropagation();
        _openLinkRow(inst, tr);
    });

    tbody.appendChild(tr);

    if (_activeId === inst.id) _insertDetailRow(inst, tr);
}

// ── Detail ───────────────────────────────────────────────────────────────────

function _toggleDetail(inst, tr) {
    if (_activeId === inst.id) { _closeDetail(); return; }
    _closeDetail();
    _activeId = inst.id;
    tr.classList.add('app-ws-acts__row--active');
    const btn = tr.querySelector('[data-detail-btn]');
    if (btn) {
        btn.querySelector('i').className = 'fa-solid fa-chevron-up';
        btn.setAttribute('aria-expanded', 'true');
    }
    _insertDetailRow(inst, tr);
}

function _closeDetail() {
    if (!_activeId) return;
    const prevTr = document.querySelector(`[data-inst-id="${CSS.escape(_activeId)}"]`);
    let triggerBtn = null;
    if (prevTr) {
        prevTr.classList.remove('app-ws-acts__row--active');
        triggerBtn = prevTr.querySelector('[data-detail-btn]');
        if (triggerBtn) {
            triggerBtn.querySelector('i').className = 'fa-solid fa-chevron-down';
            triggerBtn.setAttribute('aria-expanded', 'false');
        }
    }
    const prevDetail = document.getElementById('ws-acts-detail-row');
    const focusWasInDetail = prevDetail?.contains(document.activeElement);
    prevDetail?.remove();
    _activeId = null;
    if (focusWasInDetail && triggerBtn) triggerBtn.focus();
}

function _insertDetailRow(inst, tr) {
    const detailTr = document.createElement('tr');
    detailTr.id        = 'ws-acts-detail-row';
    detailTr.className = 'app-ws-acts__detail-row';
    const td = document.createElement('td');
    td.setAttribute('colspan', '4');
    td.innerHTML = _buildDetailPanel(inst);
    detailTr.appendChild(td);
    tr.insertAdjacentElement('afterend', detailTr);

    detailTr.querySelector('.app-ws-acts__detail-close')?.addEventListener('click', () => _closeDetail());
    _wireProjectPickers(detailTr);
    _wireConferencePickers(detailTr);

    detailTr.querySelector('[data-save-inst]')?.addEventListener('click', () => _saveInst(inst.id, detailTr));

    // Delete flow
    detailTr.querySelector('[data-delete-inst]')?.addEventListener('click', e => {
        const btn = e.currentTarget;
        // First click: show confirmation inline
        if (!btn.dataset.confirmed) {
            btn.dataset.confirmed = '1';
            btn.innerHTML =
                `<span class="app-ws-acts__delete-confirm">` +
                    `<i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>` +
                    `Confirm delete?` +
                `</span>`;
            // Reset after 4s if not clicked again
            setTimeout(() => { delete btn.dataset.confirmed; btn.innerHTML = '<i class="fa-solid fa-trash" aria-hidden="true"></i> Delete'; }, 4000);
        } else {
            _deleteInst(inst.id, detailTr);
        }
    });
}

// ── Canonical project picker (H64 slice 2) ────────────────────────────────────
// Renders a PROJECT_GRANT_ID reference as an autocomplete against /api/entities/projects. The visible input is a
// search box; a hidden input carries the chosen canonical id (sproj_…) and keeps the existing data-(create-)ref-field
// attribute so the unchanged save handlers persist it.

function _projectLabel(p) {
    const head = [p.code, p.title].filter(Boolean).join(' — ') || p.id;
    const meta = [];
    if (p.funder) meta.push(p.funder);
    if (p.startYear) meta.push(p.endYear ? `${p.startYear}–${p.endYear}` : `${p.startYear}`);
    return meta.length ? `${head} (${meta.join(', ')})` : head;
}

// ── H78: "My projects" (director-attributed canonical projects) ───────────────
// Read-only surfacing. The candidate list is matched by director name, so it's framed as "may be yours"; the
// import-as-activity action arrives in slice 2.
function _loadMyProjects() {
    const host = document.getElementById('ws-acts-my-projects');
    if (!host) return;
    fetch('/user/workspace/projects/mine', { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(r => (r.ok ? r.json() : []))
        .then(projects => {
            if (!Array.isArray(projects) || projects.length === 0) { host.hidden = true; return; }
            host.hidden = false;
            host.innerHTML = _buildMyProjects(projects);
            host.querySelectorAll('[data-import-project]').forEach(btn =>
                btn.addEventListener('click', () => _importProject(btn.dataset.importProject, btn)));
        })
        .catch(() => { host.hidden = true; });
}

function _buildMyProjects(projects) {
    const items = projects.map(p => {
        const meta = [p.code, p.funder, _projYears(p)].filter(Boolean).join(' · ');
        return `<li class="app-ws-acts__myproj-item">
            <div class="app-ws-acts__myproj-text">
                <span class="app-ws-acts__myproj-title">${_esc(p.title || p.code || p.id)}</span>
                ${meta ? `<span class="app-ws-acts__myproj-meta">${_esc(meta)}</span>` : ''}
            </div>
            <button type="button" class="app-btn app-btn--sm app-ws-acts__myproj-import"
                    data-import-project="${_esc(p.id)}">
                <i class="fa-solid fa-file-import" aria-hidden="true"></i> Import
            </button>
        </li>`;
    }).join('');
    return `<div class="app-card app-ws-acts__myproj">
        <div class="app-ws-acts__myproj-head">
            <i class="fa-solid fa-diagram-project" aria-hidden="true"></i>
            <span class="app-ws-acts__myproj-heading">Projects that may be yours</span>
            <span class="app-ws-acts__myproj-count">${projects.length}</span>
        </div>
        <p class="app-ws-acts__myproj-hint">Matched to you as project director. Import adds it as a Grant Cercetare activity.</p>
        <p class="app-ws-acts__myproj-feedback" id="ws-acts-myproj-feedback" hidden></p>
        <ul class="app-ws-acts__myproj-list">${items}</ul>
    </div>`;
}

function _importProject(projectId, btn) {
    const feedback = document.getElementById('ws-acts-myproj-feedback');
    if (btn) btn.disabled = true;
    fetch(`/user/workspace/activities/import-project/${encodeURIComponent(projectId)}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
    })
        .then(r => r.json().then(body => ({ ok: r.ok, body })))
        .then(({ ok, body }) => {
            if (!ok) throw new Error(body && body.status);
            const title = (body && body.projectTitle) || 'project';
            const msg = body.status === 'EXISTS'
                ? `“${title}” is already in your activities.`
                : `Imported “${title}” as a Grant Cercetare activity.`;
            _showMyProjFeedback(feedback, msg, false);
            // Reload so the new instance appears in the table (and the card re-renders).
            _init(_panel);
        })
        .catch(() => {
            _showMyProjFeedback(feedback, 'Could not import — please try again.', true);
            if (btn) btn.disabled = false;
        });
}

function _showMyProjFeedback(el, msg, isError) {
    if (!el) return;
    el.hidden = false;
    el.textContent = msg;
    el.classList.toggle('app-ws-acts__myproj-feedback--error', !!isError);
}

function _projYears(p) {
    if (p.startYear) return p.endYear ? `${p.startYear}–${p.endYear}` : `${p.startYear}`;
    return p.endYear ? `${p.endYear}` : '';
}

function _projectPickerFieldHtml(label, dataAttr, currentId) {
    return `<div class="app-ws-acts__field js-project-picker">
        <label class="app-ws-acts__label">${_esc(label)}</label>
        <div class="app-ws-acts__picker">
          <input class="app-ws-acts__input js-project-picker-search" type="text" autocomplete="off"
                 placeholder="Search project by title, code, funder…"/>
          <input type="hidden" ${dataAttr}="PROJECT_GRANT_ID" class="js-project-picker-value" value="${_esc(currentId)}"/>
          <ul class="app-ws-acts__picker-results js-project-picker-results" hidden></ul>
        </div>
    </div>`;
}

function _wireProjectPickers(root) {
    root.querySelectorAll('.js-project-picker').forEach(wrap => {
        const search  = wrap.querySelector('.js-project-picker-search');
        const hidden  = wrap.querySelector('.js-project-picker-value');
        const results = wrap.querySelector('.js-project-picker-results');
        if (!search || !hidden || !results) return;

        // Resolve a pre-existing reference (edit form) to a human label; the hidden id is preserved on save even if
        // resolution fails (e.g. before the first projection rebuild).
        if (hidden.value) {
            fetch(`/api/entities/projects/${encodeURIComponent(hidden.value)}`, { credentials: 'same-origin' })
                .then(r => (r.ok ? r.json() : null))
                .then(p => { if (p) search.value = _projectLabel(p); })
                .catch(() => {});
        }

        let timer = null;
        search.addEventListener('input', () => {
            // Typing invalidates a prior pick until a new selection is made.
            hidden.value = '';
            const q = search.value.trim();
            clearTimeout(timer);
            if (q.length < 2) { results.hidden = true; results.innerHTML = ''; return; }
            timer = setTimeout(() => _searchProjects(q, results, search, hidden), 250);
        });
        document.addEventListener('click', e => { if (!wrap.contains(e.target)) results.hidden = true; });
    });
}

function _searchProjects(q, results, search, hidden, onPick) {
    fetch(`/api/entities/projects?size=8&q=${encodeURIComponent(q)}`, { credentials: 'same-origin' })
        .then(r => (r.ok ? r.json() : null))
        .then(page => {
            const items = page?.items ?? [];
            if (items.length === 0) {
                // H78 — admin-deferred not-found guidance (researchers don't mint canonical projects).
                results.innerHTML = `<li class="app-ws-acts__picker-empty">No matching project. Enter it as free `
                    + `text, or ask an admin to import it.</li>`;
                results.hidden = false;
                return;
            }
            results.innerHTML = items.map(p =>
                `<li class="app-ws-acts__picker-item" data-id="${_esc(p.id)}" data-label="${_esc(_projectLabel(p))}">${_esc(_projectLabel(p))}</li>`
            ).join('');
            results.hidden = false;
            results.querySelectorAll('.app-ws-acts__picker-item').forEach(li => {
                li.addEventListener('click', () => {
                    if (hidden) hidden.value = li.dataset.id;
                    if (search) search.value = li.dataset.label;
                    results.hidden = true;
                    if (onPick) onPick(li.dataset.id, li.dataset.label);
                });
            });
        })
        .catch(() => { results.hidden = true; });
}

// ── Conference picker: CORE-backed autocomplete for FORUM_NAME fields ──────────
// Unlike the project picker there is NO hidden id: the picked value is stored as plain
// "ACRONYM — Full Name" text in FORUM_NAME. CORE is the authority the CS conference scorer
// consults, and the acronym+name form both matches by exact acronym AND lets the scorer's
// confidence machinery disambiguate same-acronym CORE collisions by name-token overlap.
// Free text stays valid for conferences not in CORE.
function _conferencePickerFieldHtml(label, dataAttr, currentValue) {
    return `<div class="app-ws-acts__field js-conf-picker">
        <label class="app-ws-acts__label">${_esc(label)}</label>
        <div class="app-ws-acts__picker">
          <input class="app-ws-acts__input js-conf-picker-input" type="text" autocomplete="off"
                 ${dataAttr}="FORUM_NAME" value="${_esc(currentValue)}"
                 placeholder="Search by acronym (e.g. SYNASC) or name — free text also accepted"/>
          <ul class="app-ws-acts__picker-results js-conf-picker-results" hidden></ul>
        </div>
    </div>`;
}

function _wireConferencePickers(root) {
    root.querySelectorAll('.js-conf-picker').forEach(wrap => {
        const input   = wrap.querySelector('.js-conf-picker-input');
        const results = wrap.querySelector('.js-conf-picker-results');
        if (!input || !results) return;

        let timer = null;
        input.addEventListener('input', () => {
            const q = input.value.trim();
            clearTimeout(timer);
            if (q.length < 2) { results.hidden = true; results.innerHTML = ''; return; }
            timer = setTimeout(() => _searchCoreConferences(q, results, input), 250);
        });
        document.addEventListener('click', e => { if (!wrap.contains(e.target)) results.hidden = true; });
    });
}

function _searchCoreConferences(q, results, input) {
    fetch(`/api/entities/core-conferences?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' })
        .then(r => (r.ok ? r.json() : null))
        .then(items => {
            if (!items || items.length === 0) {
                results.innerHTML = `<li class="app-ws-acts__picker-empty">No CORE conference matches — `
                    + `your typed text will be used as-is.</li>`;
                results.hidden = false;
                return;
            }
            results.innerHTML = items.map(c => {
                const value = `${c.acronym} — ${c.name}`;
                const meta = [c.latestRank ? `CORE ${c.latestRank}` : null, c.latestYear ?? null]
                    .filter(Boolean).join(' · ');
                return `<li class="app-ws-acts__picker-item" data-value="${_esc(value)}">`
                    + `${_esc(value)}${meta ? ` <span style="color:var(--app-color-text-muted)">(${_esc(meta)})</span>` : ''}</li>`;
            }).join('');
            results.hidden = false;
            results.querySelectorAll('.app-ws-acts__picker-item').forEach(li => {
                li.addEventListener('click', () => {
                    input.value = li.dataset.value;
                    results.hidden = true;
                });
            });
        })
        .catch(() => { results.hidden = true; });
}

// ── H78 slice 3: link an existing activity to a canonical project ──────────────
// One-click affordance on project-supporting rows that aren't linked yet. Opens a focused picker; selecting a project
// calls the link endpoint (sets PROJECT_GRANT_ID + back-fills blank display fields), then reloads.
function _supportsProjectLink(inst) {
    return (inst.activity?.referenceFields ?? []).includes('PROJECT_GRANT_ID');
}

function _isProjectLinked(inst) {
    return !!(inst.referenceFields && inst.referenceFields.PROJECT_GRANT_ID);
}

function _openLinkRow(inst, tr) {
    // Toggle: a second click on the same row's Link closes it.
    const open = document.getElementById('ws-acts-link-row');
    if (open && open.dataset.forInst === inst.id) { open.remove(); return; }
    _closeDetail();
    open?.remove();

    const linkTr = document.createElement('tr');
    linkTr.id = 'ws-acts-link-row';
    linkTr.dataset.forInst = inst.id;
    linkTr.className = 'app-ws-acts__detail-row';
    const td = document.createElement('td');
    td.setAttribute('colspan', '4');
    td.innerHTML = `<div class="app-ws-acts__link-box">
        <div class="app-ws-acts__link-title">Link “${_esc(inst.name ?? '')}” to a canonical project</div>
        <div class="app-ws-acts__picker">
            <input class="app-ws-acts__input js-link-search" type="text" autocomplete="off"
                   placeholder="Search project by title, code, funder…"/>
            <ul class="app-ws-acts__picker-results js-link-results" hidden></ul>
        </div>
        <p class="app-ws-acts__link-feedback" hidden></p>
    </div>`;
    linkTr.appendChild(td);
    tr.insertAdjacentElement('afterend', linkTr);

    const search  = td.querySelector('.js-link-search');
    const results = td.querySelector('.js-link-results');
    let timer = null;
    search.addEventListener('input', () => {
        const q = search.value.trim();
        clearTimeout(timer);
        if (q.length < 2) { results.hidden = true; results.innerHTML = ''; return; }
        timer = setTimeout(() => _searchProjects(q, results, search, null, id => _linkProject(inst.id, id, td)), 250);
    });
    document.addEventListener('click', e => { if (!td.contains(e.target)) results.hidden = true; });
    search.focus();
}

// ── H78 slice 4: participant search-and-import when adding a Grant Cercetare ───
// Participants have no name in the canonical data, so they self-serve: search a project they took part in and import
// it as a Grant Cercetare with Rol=participant (Membru). Reuses the slice-2 import path (idempotent + pre-fill).
function _toggleParticipantImport() {
    const host = document.getElementById('ws-acts-participant-import');
    if (!host) return;
    if (!host.hidden) { host.hidden = true; host.innerHTML = ''; return; }
    host.hidden = false;
    host.innerHTML = `<div class="app-card app-ws-acts__partimport">
        <div class="app-ws-acts__partimport-title">Search a project you participated in</div>
        <div class="app-ws-acts__picker">
            <input class="app-ws-acts__input js-part-search" type="text" autocomplete="off"
                   placeholder="Search project by title, code, funder…"/>
            <ul class="app-ws-acts__picker-results js-part-results" hidden></ul>
        </div>
        <p class="app-ws-acts__partimport-feedback" hidden></p>
    </div>`;
    const search  = host.querySelector('.js-part-search');
    const results = host.querySelector('.js-part-results');
    let timer = null;
    search.addEventListener('input', () => {
        const q = search.value.trim();
        clearTimeout(timer);
        if (q.length < 2) { results.hidden = true; results.innerHTML = ''; return; }
        timer = setTimeout(() => _searchProjects(q, results, search, null, id => _importParticipantProject(id, host)), 250);
    });
    document.addEventListener('click', e => {
        if (!host.contains(e.target) && e.target.id !== 'ws-acts-participant-btn') results.hidden = true;
    });
    search.focus();
}

function _importParticipantProject(projectId, host) {
    const feedback = host?.querySelector('.app-ws-acts__partimport-feedback');
    fetch(`/user/workspace/activities/import-project/${encodeURIComponent(projectId)}?role=participant`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
    })
        .then(r => r.json().then(body => ({ ok: r.ok, body })))
        .then(({ ok, body }) => {
            if (!ok) throw new Error();
            if (body.status === 'EXISTS' && feedback) {
                feedback.hidden = false;
                feedback.textContent = `“${body.projectTitle}” is already in your activities.`;
                return;
            }
            _init(_panel); // reload so the new participant activity appears
        })
        .catch(() => {
            if (feedback) { feedback.hidden = false; feedback.textContent = 'Could not add — please try again.'; }
        });
}

function _linkProject(instanceId, projectId, container) {
    const feedback = container?.querySelector('.app-ws-acts__link-feedback');
    fetch(`/user/workspace/activities/${encodeURIComponent(instanceId)}/link-project/${encodeURIComponent(projectId)}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
    })
        .then(r => r.json().then(body => ({ ok: r.ok, body })))
        .then(({ ok }) => {
            if (!ok) throw new Error();
            _init(_panel); // reload so the row reflects the new link
        })
        .catch(() => {
            if (feedback) { feedback.hidden = false; feedback.textContent = 'Could not link — please try again.'; }
        });
}

function _buildDetailPanel(inst) {
    // Dynamic fields from the activity definition
    const actFields   = inst.activity?.fields   ?? [];
    const actRefFields = inst.activity?.referenceFields ?? [];
    const fieldValues = inst.fields ?? {};
    const refValues   = inst.referenceFields ?? {};

    let fieldsHtml = '';
    if (actFields.length > 0 || actRefFields.length > 0) {
        // Custom fields
        const customInputs = actFields.map(f => {
            const val = fieldValues[f.name] ?? '';
            if (f.allowedValues?.length > 0) {
                const opts = f.allowedValues.map(v =>
                    `<option value="${_esc(v)}" ${v === val ? 'selected' : ''}>${_esc(v)}</option>`
                ).join('');
                return `<div class="app-ws-acts__field">
                    <label class="app-ws-acts__label">${_esc(f.name)}</label>
                    <select class="app-ws-acts__select" data-field="${_esc(f.name)}">${opts}</select>
                </div>`;
            }
            return `<div class="app-ws-acts__field">
                <label class="app-ws-acts__label">${_esc(f.name)}</label>
                <input class="app-ws-acts__input" type="${f.number ? 'number' : 'text'}"
                       data-field="${_esc(f.name)}" value="${_esc(val)}"/>
            </div>`;
        }).join('');

        // Reference fields
        const refInputs = actRefFields.map(rf => {
            const label = REF_LABELS[rf] ?? rf;
            const val   = refValues[rf] ?? '';
            if (rf === 'PROJECT_GRANT_ID') {
                return _projectPickerFieldHtml(label, 'data-ref-field', val);
            }
            if (rf === 'FORUM_NAME') {
                return _conferencePickerFieldHtml(label, 'data-ref-field', val);
            }
            return `<div class="app-ws-acts__field">
                <label class="app-ws-acts__label">${_esc(label)}</label>
                <input class="app-ws-acts__input" type="text"
                       data-ref-field="${_esc(rf)}" value="${_esc(val)}"/>
            </div>`;
        }).join('');

        fieldsHtml = `
            <div class="app-ws-acts__detail-fields">
                <p class="app-ws-acts__detail-section-title">Fields</p>
                ${customInputs}${refInputs}
            </div>`;
    } else {
        fieldsHtml = `<p style="font-size:0.85rem;color:var(--app-color-text-muted)">This activity has no editable fields.</p>`;
    }

    return `
        <div class="app-ws-acts__detail-inner">
          <button class="app-ws-acts__detail-close" type="button" aria-label="Close details">
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
          <div class="app-ws-acts__detail-grid">
            <!-- Left: info -->
            <div>
              <p class="app-ws-acts__detail-section-title">Activity info</p>
              <p style="margin:0 0 0.25rem;font-size:0.85rem;">
                <strong>Type:</strong> ${_esc(inst.activity?.name ?? '—')}
              </p>
              <p style="margin:0;font-size:0.85rem;">
                <strong>Date:</strong> ${_esc(inst.date ?? '—')}
              </p>
            </div>
            <!-- Right: edit -->
            <div>
              ${fieldsHtml}
              <div class="app-ws-acts__detail-actions">
                <button class="btn btn-sm btn-primary" type="button" data-save-inst="${_esc(inst.id)}">Save</button>
                <button class="btn btn-sm btn-outline-danger" type="button" data-delete-inst="${_esc(inst.id)}">
                  <i class="fa-solid fa-trash" aria-hidden="true"></i> Delete
                </button>
                <span class="app-ws-acts__feedback" role="status" aria-live="polite"></span>
              </div>
            </div>
          </div>
        </div>`;
}

// ── Save / Delete ─────────────────────────────────────────────────────────────

function _saveInst(id, detailTr) {
    const fields    = {};
    const refFields = {};
    detailTr.querySelectorAll('[data-field]').forEach(el => { fields[el.dataset.field] = el.value; });
    detailTr.querySelectorAll('[data-ref-field]').forEach(el => { refFields[el.dataset.refField] = el.value; });

    const feedback = detailTr.querySelector('.app-ws-acts__feedback');
    const saveBtn  = detailTr.querySelector('[data-save-inst]');
    if (saveBtn) saveBtn.disabled = true;

    fetch('/user/workspace/activities/update', {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
        body: JSON.stringify({ id, fields, referenceFields: refFields }),
    })
        .then(r => { if (!r.ok) throw new Error(); })
        .then(() => {
            // Update in-memory
            const inst = _instances.find(i => i.id === id);
            if (inst) { inst.fields = fields; inst.referenceFields = refFields; }
            if (feedback) {
                feedback.textContent = 'Saved.';
                feedback.classList.remove('app-ws-acts__feedback--error');
                feedback.classList.add('app-ws-acts__feedback--visible');
                setTimeout(() => feedback.classList.remove('app-ws-acts__feedback--visible'), 2500);
            }
        })
        .catch(() => {
            if (feedback) {
                feedback.textContent = 'Save failed — please try again.';
                feedback.classList.add('app-ws-acts__feedback--error', 'app-ws-acts__feedback--visible');
            }
        })
        .finally(() => { if (saveBtn) saveBtn.disabled = false; });
}

function _deleteInst(id, detailTr) {
    const deleteBtn = detailTr.querySelector('[data-delete-inst]');
    if (deleteBtn) deleteBtn.disabled = true;

    fetch(`/user/workspace/activities/delete/${encodeURIComponent(id)}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
    })
        .then(r => { if (!r.ok) throw new Error(); })
        .then(() => {
            // Remove from in-memory list
            const idx = _instances.findIndex(i => i.id === id);
            if (idx !== -1) _instances.splice(idx, 1);
            _closeDetail();
            // If page is now empty and not page 1, go back
            const maxPage = Math.max(1, Math.ceil(_instances.length / PAGE_SIZE));
            if (_page > maxPage) _page = maxPage;
            _updateStats();
            if (_instances.length === 0) {
                // Replace table with empty state
                const wrap = document.getElementById('ws-acts-table-wrap');
                if (wrap) wrap.outerHTML = _buildEmpty();
            } else {
                _renderPage();
            }
        })
        .catch(() => {
            if (deleteBtn) deleteBtn.disabled = false;
        });
}

// ── Create panel ──────────────────────────────────────────────────────────────

function _toggleCreate() {
    if (_createOpen) { _closeCreate(); return; }
    _createOpen = true;
    const placeholder = document.getElementById('ws-acts-create-placeholder');
    if (!placeholder) return;
    placeholder.innerHTML = _buildCreateShell();
    const addBtn = document.getElementById('ws-acts-add-btn');
    if (addBtn) {
        addBtn.classList.add('active');
        addBtn.setAttribute('aria-expanded', 'true');
    }
    // Move focus into the create form
    setTimeout(() => placeholder.querySelector('select, input')?.focus(), 0);

    placeholder.querySelector('.app-ws-acts__create-close')?.addEventListener('click', () => _closeCreate());

    const actSelect = placeholder.querySelector('#ws-acts-create-activity');
    actSelect?.addEventListener('change', () => _loadCreateFields(actSelect.value));

    placeholder.querySelector('[data-create-save]')?.addEventListener('click', () => _submitCreate(placeholder));

    // Load fields for initial selection if an activity is pre-selected
    if (actSelect?.value) _loadCreateFields(actSelect.value);
}

function _closeCreate() {
    _createOpen = false;
    const placeholder = document.getElementById('ws-acts-create-placeholder');
    if (placeholder) placeholder.innerHTML = '';
    const addBtn = document.getElementById('ws-acts-add-btn');
    if (addBtn) {
        addBtn.classList.remove('active');
        addBtn.setAttribute('aria-expanded', 'false');
        addBtn.focus();
    }
}

function _buildCreateShell() {
    const activities = _data?.activities ?? [];
    const opts = activities.map(a =>
        `<option value="${_esc(a.id)}">${_esc(a.name)}</option>`
    ).join('');

    return `
        <div class="app-ws-acts__create-panel">
          <div class="app-ws-acts__create-header">
            <h3 class="app-ws-acts__create-title">New Activity Instance</h3>
            <button class="app-ws-acts__create-close" type="button" aria-label="Close create form">
              <i class="fa-solid fa-xmark" aria-hidden="true"></i>
            </button>
          </div>
          <div class="app-ws-acts__create-body">
            <div class="app-ws-acts__create-row">
              <div class="app-ws-acts__field">
                <label class="app-ws-acts__label" for="ws-acts-create-activity">Activity type *</label>
                <select class="app-ws-acts__select" id="ws-acts-create-activity" required>
                  <option value="">— select —</option>
                  ${opts}
                </select>
              </div>
              <div class="app-ws-acts__field">
                <label class="app-ws-acts__label" for="ws-acts-create-name">Name / label</label>
                <input class="app-ws-acts__input" id="ws-acts-create-name" type="text" placeholder="Optional display name"/>
              </div>
              <div class="app-ws-acts__field">
                <label class="app-ws-acts__label" for="ws-acts-create-date">Date *</label>
                <input class="app-ws-acts__input" id="ws-acts-create-date" type="date" required/>
              </div>
            </div>
            <div id="ws-acts-create-dynamic"></div>
            <div class="app-ws-acts__create-actions">
              <button class="btn btn-sm btn-primary" type="button" data-create-save>Save</button>
              <button class="btn btn-sm btn-outline-secondary" type="button"
                      onclick="document.querySelector('.app-ws-acts__create-close')?.click()">Cancel</button>
              <span class="app-ws-acts__feedback" id="ws-acts-create-feedback" role="status" aria-live="polite"></span>
            </div>
          </div>
        </div>`;
}

function _loadCreateFields(activityId) {
    const container = document.getElementById('ws-acts-create-dynamic');
    if (!container) return;
    if (!activityId) { container.innerHTML = ''; return; }

    // Try to find locally first (already loaded in _data.activities)
    const local = (_data?.activities ?? []).find(a => a.id === activityId);
    if (local) { _renderCreateFields(container, local); return; }

    container.innerHTML = `<p style="font-size:0.83rem;color:var(--app-color-text-muted)">Loading fields…</p>`;
    fetch(`/user/activities/activity/${encodeURIComponent(activityId)}/fields`)
        .then(r => r.ok ? r.json() : null)
        .then(activity => { if (activity) _renderCreateFields(container, activity); else container.innerHTML = ''; })
        .catch(() => { container.innerHTML = ''; });
}

function _renderCreateFields(container, activity) {
    const fields    = activity.fields    ?? [];
    const refFields = activity.referenceFields ?? [];
    if (fields.length === 0 && refFields.length === 0) { container.innerHTML = ''; return; }

    const customHtml = fields.map(f => {
        if (f.allowedValues?.length > 0) {
            const opts = f.allowedValues.map(v => `<option value="${_esc(v)}">${_esc(v)}</option>`).join('');
            return `<div class="app-ws-acts__field">
                <label class="app-ws-acts__label">${_esc(f.name)}</label>
                <select class="app-ws-acts__select" data-create-field="${_esc(f.name)}">${opts}</select>
            </div>`;
        }
        return `<div class="app-ws-acts__field">
            <label class="app-ws-acts__label">${_esc(f.name)}</label>
            <input class="app-ws-acts__input" type="${f.number ? 'number' : 'text'}"
                   data-create-field="${_esc(f.name)}" placeholder="${_esc(f.name)}"/>
        </div>`;
    }).join('');

    const refHtml = refFields.map(rf => {
        const label = REF_LABELS[rf] ?? rf;
        if (rf === 'PROJECT_GRANT_ID') {
            return _projectPickerFieldHtml(label, 'data-create-ref-field', '');
        }
        if (rf === 'FORUM_NAME') {
            return _conferencePickerFieldHtml(label, 'data-create-ref-field', '');
        }
        return `<div class="app-ws-acts__field">
            <label class="app-ws-acts__label">${_esc(label)}</label>
            <input class="app-ws-acts__input" type="text"
                   data-create-ref-field="${_esc(rf)}" placeholder="${_esc(label)}"/>
        </div>`;
    }).join('');

    container.innerHTML = `
        <div class="app-ws-acts__create-fields">
          <p class="app-ws-acts__create-fields-title">Fields</p>
          <div class="app-ws-acts__create-row">${customHtml}${refHtml}</div>
        </div>`;
    _wireProjectPickers(container);
    _wireConferencePickers(container);
}

function _submitCreate(placeholder) {
    const activityId = placeholder.querySelector('#ws-acts-create-activity')?.value ?? '';
    const name       = placeholder.querySelector('#ws-acts-create-name')?.value ?? '';
    const date       = placeholder.querySelector('#ws-acts-create-date')?.value ?? '';
    const feedback   = document.getElementById('ws-acts-create-feedback');
    const saveBtn    = placeholder.querySelector('[data-create-save]');

    if (!activityId) { _showFeedback(feedback, 'Please select an activity type.', true); return; }
    if (!date)       { _showFeedback(feedback, 'Please enter a date.', true); return; }

    const fields    = {};
    const refFields = {};
    placeholder.querySelectorAll('[data-create-field]').forEach(el => { fields[el.dataset.createField] = el.value; });
    placeholder.querySelectorAll('[data-create-ref-field]').forEach(el => { refFields[el.dataset.createRefField] = el.value; });

    if (saveBtn) saveBtn.disabled = true;

    fetch('/user/workspace/activities/create', {
        method: 'POST',
        credentials: 'same-origin',
        headers: postJsonHeaders(),
        body: JSON.stringify({ activityId, name, date, fields, referenceFields: refFields }),
    })
        .then(r => { if (!r.ok) throw new Error(); return r.json(); })
        .then(newInst => {
            _instances.unshift(newInst);
            _page = 1;
            _closeCreate();
            _updateStats();
            // Replace empty state if present
            const empty = _mount.querySelector('.app-ws-acts__empty');
            if (empty) {
                const tableWrap = document.createElement('div');
                tableWrap.className = 'app-ws-acts__table-wrap';
                tableWrap.id = 'ws-acts-table-wrap';
                empty.replaceWith(tableWrap);
            }
            _renderPage();
        })
        .catch(() => {
            _showFeedback(feedback, 'Could not save — please try again.', true);
            if (saveBtn) saveBtn.disabled = false;
        });
}

// ── Stats update (after create/delete) ────────────────────────────────────────

function _updateStats() {
    const countEl = document.getElementById('ws-acts-stat-count');
    if (countEl) countEl.textContent = String(_instances.length);
    // Rebuild distribution bar in-place to reflect the new counts
    const card = _mount?.querySelector('.app-ws-acts__summary-card');
    if (card) {
        card.outerHTML = _buildSummaryCard();
    }
}

// ── Keyboard ─────────────────────────────────────────────────────────────────

function _handleEscape(e) {
    if (e.key !== 'Escape') return;
    if (_createOpen) {
        e.stopPropagation();
        _closeCreate();
        document.getElementById('ws-acts-add-btn')?.focus();
        return;
    }
    if (_activeId) {
        const detailRow = document.getElementById('ws-acts-detail-row');
        if (!detailRow) return;
        e.stopPropagation();
        const prevId = _activeId;
        _closeDetail();
        document.querySelector(`[data-inst-id="${CSS.escape(prevId)}"]`)
            ?.querySelector('[data-detail-btn]')?.focus();
    }
}

// ── HTML builders ─────────────────────────────────────────────────────────────

// Palette used for distribution bar segments and legend dots.
// All values are CSS custom properties so they respond to light/dark theme.
const DIST_COLORS = [
    'var(--app-color-primary)',
    'var(--app-color-success)',
    'var(--app-color-warning)',
    'var(--app-color-danger)',
    'var(--app-color-text-muted)',
];

function _buildToolbar() {
    return `
        <div class="app-ws-acts__toolbar">
          <button type="button" class="btn btn-sm btn-primary" id="ws-acts-add-btn" aria-expanded="false">
            <i class="fa-solid fa-plus" aria-hidden="true"></i> Add Activity
          </button>
          <button type="button" class="btn btn-sm btn-outline-primary" id="ws-acts-participant-btn">
            <i class="fa-solid fa-people-group" aria-hidden="true"></i> Add a project you took part in
          </button>
        </div>
        <div id="ws-acts-participant-import" hidden></div>`;
}

function _buildSummaryCard() {
    const total     = _instances.length;
    const labels    = _data?.activityLabels ?? [];
    const counts    = _data?.activityData   ?? [];
    const typeCount = labels.length;

    // Distribution bar segments
    let barHtml    = '';
    let legendHtml = '';
    if (total > 0 && labels.length > 0) {
        barHtml = labels.map((label, i) => {
            const pct   = Math.max(0.5, (counts[i] / total) * 100);
            const color = DIST_COLORS[i % DIST_COLORS.length];
            return `<div class="app-ws-acts__dist-segment"
                         style="width:${pct.toFixed(1)}%;background:${color}"
                         title="${_esc(label)}: ${counts[i]}"></div>`;
        }).join('');

        legendHtml = labels.map((label, i) => {
            const color = DIST_COLORS[i % DIST_COLORS.length];
            return `<span class="app-ws-acts__dist-legend-item">
                <span class="app-ws-acts__dist-dot" style="background:${color}"></span>
                ${_esc(label)} <span class="app-ws-acts__dist-count">${counts[i]}</span>
            </span>`;
        }).join('');
    }

    const distHtml = total > 0
        ? `<div class="app-ws-acts__dist-bar" role="img" aria-label="Activity type distribution">
               ${barHtml}
           </div>
           <div class="app-ws-acts__dist-legend">${legendHtml}</div>`
        : `<p style="margin:0;font-size:0.82rem;color:var(--app-color-text-muted)">No activity data yet.</p>`;

    return `
        <div class="app-ws-acts__summary-card">
          <div class="app-ws-acts__summary-metrics">
            <div class="app-ws-acts__summary-metric">
              <span class="app-ws-acts__summary-value" id="ws-acts-stat-count">${total}</span>
              <span class="app-ws-acts__summary-label">Instances</span>
            </div>
            <div class="app-ws-acts__summary-divider"></div>
            <div class="app-ws-acts__summary-metric">
              <span class="app-ws-acts__summary-value">${typeCount}</span>
              <span class="app-ws-acts__summary-label">Types</span>
            </div>
          </div>
          <div class="app-ws-acts__dist">${distHtml}</div>
        </div>`;
}


function _buildEmpty() {
    return `
        <div class="app-ws-acts__empty">
          <i class="fa-solid fa-pen-fancy app-ws-acts__empty-icon" aria-hidden="true"></i>
          <h2 class="app-ws-acts__empty-title">No activities recorded yet</h2>
          <p class="app-ws-acts__empty-body">Add your first activity to start tracking your professional contributions.</p>
        </div>`;
}

function _buildError() {
    return `
        <div class="app-panel-error app-dashboard-empty">
          <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
          <h3 class="app-dashboard-empty__title">Could not load Activities</h3>
          <p class="app-dashboard-empty__body">Something went wrong while fetching this content.</p>
          <button type="button" class="btn btn-sm btn-outline-primary" data-retry-panel>Try again</button>
        </div>`;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function _showFeedback(el, msg, isError) {
    if (!el) return;
    el.textContent = msg;
    el.classList.toggle('app-ws-acts__feedback--error', isError);
    el.classList.add('app-ws-acts__feedback--visible');
    if (!isError) setTimeout(() => el.classList.remove('app-ws-acts__feedback--visible'), 2500);
}

function _esc(str) {
    return String(str ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
