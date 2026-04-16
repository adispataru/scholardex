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

// ── Public API ───────────────────────────────────────────────────────────────

export function initWorkspaceActivities() {
    window.appWorkspaceActivities = {
        init: _init,
        openCreate() {
            if (_mount && _data) { _toggleCreate(); }
            else                 { _pendingCreate = true; }
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

    _page      = 1;
    _activeId  = null;
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

    // Render table
    if (_instances.length > 0) {
        _renderPage();
    }

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

    const start     = (_page - 1) * PAGE_SIZE;
    const pageItems = _instances.slice(start, start + PAGE_SIZE);
    const total     = _instances.length;
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
        wrap.insertAdjacentHTML('beforeend', _buildPagination(total, pages));
        wrap.querySelectorAll('.app-ws-acts__page-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const p = Number(btn.dataset.page);
                if (!isNaN(p)) { _page = p; _activeId = null; _renderPage(); }
            });
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
            `<button class="app-ws-acts__action-btn" type="button" ` +
                    `aria-label="Details: ${_esc(name)}" aria-expanded="false" ` +
                    `data-detail-btn="${_esc(inst.id)}">` +
                `<i class="fa-solid fa-chevron-down" aria-hidden="true"></i>` +
            `</button>` +
        `</td>`;

    tr.addEventListener('click', e => {
        if (e.target.closest('a, button.app-ws-acts__action-btn--danger')) return;
        _toggleDetail(inst, tr);
    });
    tr.querySelector('[data-detail-btn]').addEventListener('click', e => {
        e.stopPropagation();
        _toggleDetail(inst, tr);
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

    fetch(`/user/workspace/activities/delete/${encodeURIComponent(id)}`, { method: 'POST', headers: postJsonHeaders() })
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
        </div>`;
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

function _buildPagination(total, pages) {
    const info = `Showing ${Math.min((_page - 1) * PAGE_SIZE + 1, total)}–${Math.min(_page * PAGE_SIZE, total)} of ${total}`;
    const prev = `<button class="app-ws-acts__page-btn" type="button" data-page="${_page - 1}" ${_page <= 1 ? 'disabled' : ''} aria-label="Previous page"><i class="fa-solid fa-chevron-left" aria-hidden="true"></i></button>`;
    const next = `<button class="app-ws-acts__page-btn" type="button" data-page="${_page + 1}" ${_page >= pages ? 'disabled' : ''} aria-label="Next page"><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button>`;

    let lo = Math.max(1, _page - 2), hi = Math.min(pages, lo + 4);
    lo = Math.max(1, hi - 4);
    const nums = [];
    for (let p = lo; p <= hi; p++) {
        nums.push(`<button class="app-ws-acts__page-btn ${p === _page ? 'app-ws-acts__page-btn--active' : ''}" type="button" data-page="${p}" aria-label="Page ${p}" ${p === _page ? 'aria-current="page"' : ''}>${p}</button>`);
    }
    return `
        <div class="app-ws-acts__pagination" role="navigation" aria-label="Activities pagination">
          <span>${_esc(info)}</span>
          <div class="app-ws-acts__pagination-btns">${prev}${nums.join('')}${next}</div>
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
