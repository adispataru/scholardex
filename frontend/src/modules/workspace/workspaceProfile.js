/**
 * workspaceProfile.js — H36.10 Profile & Sync tab
 *
 * Registered as window.appWorkspaceProfile.init(panel) from workspace.html.
 *
 * Renders:
 *  - Completeness card: progress bar + 4-item checklist
 *  - Profile identity section: readonly key-value grid + inline edit form
 *  - Scopus sync section: per-ID action buttons + task history tables
 *
 * Endpoints:
 *   GET  /user/workspace/profile
 *   POST /user/workspace/profile/save
 *   POST /user/workspace/profile/sync/publications
 *   POST /user/workspace/profile/sync/citations
 */

// ── Module state ─────────────────────────────────────────────────────────────

let _panel              = null;
let _mount              = null;
let _data               = null;
let _editOpen           = false;
let _delegateController = null;   // AbortController for delegated listeners on _mount

// ── Public API ───────────────────────────────────────────────────────────────

export function initWorkspaceProfile() {
    window.appWorkspaceProfile = { init: _init };
}

// ── Init / fetch ─────────────────────────────────────────────────────────────

function _init(panel) {
    _panel    = panel;
    _mount    = panel.querySelector('[data-workspace-lazy-panel]');
    if (!_mount) return;

    const src = _mount.dataset.src;
    if (!src) return;

    _editOpen = false;

    _showSkeleton();

    fetch(src, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(data => { _data = data; _renderAll(); })
        .catch(() => { _mount.innerHTML = _buildError(); });
}

function _showSkeleton() {
    _mount.innerHTML = `
        <div class="app-ws-prof">
          <div class="app-skeleton-block" style="height:6rem;border-radius:var(--app-radius-card)"></div>
          <div class="app-skeleton-block" style="height:8rem;border-radius:var(--app-radius-card)"></div>
          <div class="app-skeleton-block" style="height:10rem;border-radius:var(--app-radius-card)"></div>
        </div>`;
}

function _buildError() {
    return `<div class="app-ws-prof__empty">
      <i class="fa-solid fa-triangle-exclamation" style="font-size:2rem;color:var(--app-color-text-muted);opacity:.45"></i>
      <p style="margin:0;font-size:.88rem;color:var(--app-color-text-muted)">Failed to load profile data.</p>
      <button class="app-btn app-btn--sm app-btn--secondary" id="ws-prof-retry-btn">Retry</button>
    </div>`;
}

// ── Render ───────────────────────────────────────────────────────────────────

function _renderAll() {
    const researcher = _data?.researcher ?? null;

    const container = document.createElement('div');
    container.className = 'app-ws-prof';

    if (!researcher) {
        container.innerHTML = _buildNoProfile();
    } else {
        container.innerHTML =
            _buildCompletenessCard(researcher, _data.completeness) +
            _buildProfileSection(researcher) +
            _buildSyncSection(_data);
    }

    _mount.innerHTML = '';
    _mount.appendChild(container);
    _wireEvents();

    // For brand-new users (completeness 0) jump straight into edit mode so
    // they don't have to discover the Edit button themselves.
    if (researcher && (_data.completeness ?? 0) === 0) {
        _openEdit();
    }
}

// ── Completeness card ─────────────────────────────────────────────────────────

function _buildCompletenessCard(researcher, completeness) {
    const pct = Math.min(100, Math.max(0, completeness ?? 0));
    const fillClass = pct >= 100 ? 'app-ws-prof__progress-fill app-ws-prof__progress-fill--complete' : 'app-ws-prof__progress-fill';

    const checks = [
        { label: 'First name',     done: !!(researcher?.firstName?.trim()),         anchor: 'ws-prof-edit-firstName' },
        { label: 'Last name',      done: !!(researcher?.lastName?.trim()),           anchor: 'ws-prof-edit-lastName'  },
        { label: 'Scopus ID linked', done: (researcher?.scopusId?.length ?? 0) > 0, anchor: 'ws-prof-edit-scopusId'  },
        { label: 'WoS ID linked',  done: (researcher?.wosId?.length ?? 0) > 0,      anchor: 'ws-prof-edit-wosId'     },
    ];

    const checklistItems = checks.map(c => {
        if (c.done) {
            return `<li class="app-ws-prof__checklist-item">
              <span class="app-ws-prof__checklist-icon--done"><i class="fa-solid fa-check"></i></span>
              <span class="app-ws-prof__checklist-label--done">${_esc(c.label)}</span>
            </li>`;
        }
        return `<li class="app-ws-prof__checklist-item">
          <span class="app-ws-prof__checklist-icon--missing"><i class="fa-solid fa-circle-exclamation"></i></span>
          <span class="app-ws-prof__checklist-label--missing">${_esc(c.label)}</span>
          <a class="app-ws-prof__checklist-link" href="#${c.anchor}" data-prof-checklist-anchor="${c.anchor}">Add &rsaquo;</a>
        </li>`;
    }).join('');

    return `<div class="app-ws-prof__completeness">
      <div class="app-ws-prof__completeness-header">
        <p class="app-ws-prof__completeness-title">Profile completeness</p>
        <span class="app-ws-prof__completeness-score">${pct}%<span class="app-ws-prof__completeness-score-label">complete</span></span>
      </div>
      <div class="app-ws-prof__progress-track">
        <div class="${fillClass}" style="width:${pct}%"></div>
      </div>
      <ul class="app-ws-prof__checklist">${checklistItems}</ul>
    </div>`;
}

// ── Profile identity section ──────────────────────────────────────────────────

function _buildProfileSection(researcher) {
    const scopusPills = _buildIdPills(researcher?.scopusId);
    const wosPills    = _buildIdPills(researcher?.wosId);

    const readonlyGrid = `<div id="ws-prof-readonly-grid" class="app-ws-prof__info-grid">
      <span class="app-ws-prof__info-label">Name</span>
      <span class="app-ws-prof__info-value">${_esc([researcher?.firstName, researcher?.lastName].filter(Boolean).join(' ') || '—')}</span>
      <span class="app-ws-prof__info-label">Google Scholar ID</span>
      <span class="app-ws-prof__info-value">${researcher?.scholarId ? _esc(researcher.scholarId) : '<em class="app-ws-prof__info-value--muted">Not set</em>'}</span>
      <span class="app-ws-prof__info-label">Scopus IDs</span>
      <span class="app-ws-prof__info-value">${scopusPills || '<em class="app-ws-prof__info-value--muted">None</em>'}</span>
      <span class="app-ws-prof__info-label">WoS IDs</span>
      <span class="app-ws-prof__info-value">${wosPills || '<em class="app-ws-prof__info-value--muted">None</em>'}</span>
    </div>`;

    const editForm = _buildEditForm(researcher);

    return `<div class="app-ws-prof__section">
      <div class="app-ws-prof__section-header">
        <i class="fa-solid fa-user" style="color:var(--app-color-text-muted);font-size:.85rem"></i>
        <h2 class="app-ws-prof__section-title">Identity</h2>
        <button class="app-btn app-btn--sm app-btn--secondary" id="ws-prof-edit-btn">
          <i class="fa-solid fa-pen-to-square"></i> Edit
        </button>
      </div>
      <div class="app-ws-prof__section-body">
        ${readonlyGrid}
        <div id="ws-prof-edit-form-container" hidden>${editForm}</div>
      </div>
    </div>`;
}

function _buildIdPills(ids) {
    if (!ids || ids.length === 0) return '';
    return `<ul class="app-ws-prof__id-list">${ids.map(id =>
        `<li class="app-ws-prof__id-pill">${_esc(id)}</li>`
    ).join('')}</ul>`;
}

function _buildEditForm(researcher) {
    const scopusRows = (researcher?.scopusId ?? []).map((id, i) =>
        _buildIdEntryRow('scopusId', id, i)
    ).join('');
    const wosRows = (researcher?.wosId ?? []).map((id, i) =>
        _buildIdEntryRow('wosId', id, i)
    ).join('');

    return `<div class="app-ws-prof__edit-form" id="ws-prof-edit-form">
      <div class="app-ws-prof__form-row">
        <div class="app-ws-prof__field">
          <label class="app-ws-prof__label app-ws-prof__label--required" for="ws-prof-edit-firstName">First name</label>
          <input class="app-ws-prof__input" type="text" id="ws-prof-edit-firstName" name="firstName"
                 value="${_esc(researcher?.firstName ?? '')}" autocomplete="given-name">
        </div>
        <div class="app-ws-prof__field">
          <label class="app-ws-prof__label app-ws-prof__label--required" for="ws-prof-edit-lastName">Last name</label>
          <input class="app-ws-prof__input" type="text" id="ws-prof-edit-lastName" name="lastName"
                 value="${_esc(researcher?.lastName ?? '')}" autocomplete="family-name">
        </div>
      </div>
      <div class="app-ws-prof__field">
        <label class="app-ws-prof__label" for="ws-prof-edit-scholarId">Google Scholar ID</label>
        <input class="app-ws-prof__input" type="text" id="ws-prof-edit-scholarId" name="scholarId"
               value="${_esc(researcher?.scholarId ?? '')}" placeholder="e.g. XXXXXXXXX" style="max-width:20rem">
      </div>
      <div class="app-ws-prof__field" id="ws-prof-edit-scopusId">
        <label class="app-ws-prof__label">Scopus IDs</label>
        <div class="app-ws-prof__id-entries" id="ws-prof-scopus-entries">${scopusRows}</div>
        <button type="button" class="app-btn app-btn--sm app-btn--ghost app-ws-prof__add-id-btn"
                id="ws-prof-add-scopus-btn">
          <i class="fa-solid fa-plus"></i> Add Scopus ID
        </button>
      </div>
      <div class="app-ws-prof__field" id="ws-prof-edit-wosId">
        <label class="app-ws-prof__label">WoS IDs</label>
        <div class="app-ws-prof__id-entries" id="ws-prof-wos-entries">${wosRows}</div>
        <button type="button" class="app-btn app-btn--sm app-btn--ghost app-ws-prof__add-id-btn"
                id="ws-prof-add-wos-btn">
          <i class="fa-solid fa-plus"></i> Add WoS ID
        </button>
      </div>
      <div class="app-ws-prof__form-actions">
        <button type="button" class="app-btn app-btn--sm app-btn--primary" id="ws-prof-save-btn">Save</button>
        <button type="button" class="app-btn app-btn--sm app-btn--secondary" id="ws-prof-cancel-btn">Cancel</button>
        <span class="app-ws-prof__form-feedback" id="ws-prof-form-feedback"></span>
      </div>
    </div>`;
}

function _buildIdEntryRow(field, value, index) {
    return `<div class="app-ws-prof__id-entry-row" data-id-field="${field}">
      <input class="app-ws-prof__input" type="text"
             name="${field}[]" value="${_esc(value)}"
             placeholder="${field === 'scopusId' ? 'Scopus author ID' : 'WoS researcher ID'}">
      <button type="button" class="app-ws-prof__remove-btn" aria-label="Remove" data-remove-id-row>
        <i class="fa-solid fa-xmark"></i>
      </button>
    </div>`;
}

// ── Sync section ──────────────────────────────────────────────────────────────

function _buildSyncSection(data) {
    const researcher = data?.researcher;
    const scopusIds  = researcher?.scopusId ?? [];

    let syncRows;
    if (scopusIds.length === 0) {
        syncRows = `<p class="app-ws-prof__task-empty">Add a Scopus ID to enable sync.</p>`;
    } else {
        syncRows = `<ul class="app-ws-prof__sync-list">${scopusIds.map(id => `
          <li class="app-ws-prof__sync-id-row">
            <span class="app-ws-prof__sync-id-label">${_esc(id)}</span>
            <div class="app-ws-prof__sync-actions">
              <button class="app-btn app-btn--sm app-btn--secondary" data-sync-type="publications" data-sync-id="${_esc(id)}">
                <i class="fa-solid fa-book-open"></i> Update Publications
              </button>
              <button class="app-btn app-btn--sm app-btn--secondary" data-sync-type="citations" data-sync-id="${_esc(id)}">
                <i class="fa-solid fa-quote-right"></i> Update Citations
              </button>
            </div>
          </li>`).join('')}</ul>`;
    }

    const pubTable  = _buildTaskTable('publication-tasks', data?.pubTasks  ?? [], ['Scopus ID', 'Status', 'Initiated', 'Executed', 'Message']);
    const citeTable = _buildTaskTable('citation-tasks',    data?.citeTasks ?? [], ['Scopus ID', 'Status', 'Initiated', 'Executed', 'Message']);

    return `<div class="app-ws-prof__section">
      <div class="app-ws-prof__section-header">
        <i class="fa-solid fa-rotate" style="color:var(--app-color-text-muted);font-size:.85rem"></i>
        <h2 class="app-ws-prof__section-title">Scopus Sync</h2>
      </div>
      <div class="app-ws-prof__section-body" style="padding:0">
        ${syncRows}
        <div class="app-ws-prof__task-subsection">
          <p class="app-ws-prof__task-subsection-title">Publication sync history</p>
          ${pubTable}
        </div>
        <div class="app-ws-prof__task-subsection">
          <p class="app-ws-prof__task-subsection-title">Citation sync history</p>
          ${citeTable}
        </div>
      </div>
    </div>`;
}

function _buildTaskTable(tableId, tasks, cols) {
    const sorted = [...tasks]
        .sort((a, b) => (b.initiatedDate ?? '').localeCompare(a.initiatedDate ?? ''))
        .slice(0, 10);

    if (sorted.length === 0) {
        return `<p class="app-ws-prof__task-empty">No sync history yet.</p>`;
    }

    const rows = sorted.map(t => `<tr>
      <td><span class="app-ws-prof__id-pill">${_esc(t.scopusId ?? '—')}</span></td>
      <td>${_statusBadge(t.status)}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(t.initiatedDate))}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(t.executionDate))}</td>
      <td style="color:var(--app-color-text-muted);font-size:.78rem;max-width:14rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${_esc(t.message ?? '—')}</td>
    </tr>`).join('');

    return `<table class="app-ws-prof__task-table" id="${tableId}">
      <thead><tr>${cols.map(c => `<th>${_esc(c)}</th>`).join('')}</tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
}

function _statusBadge(status) {
    const map = { PENDING: 'pending', COMPLETED: 'completed', FAILED: 'failed' };
    const cls = map[status] ?? 'muted';
    return `<span class="app-ws-prof__badge app-ws-prof__badge--${cls}">${_esc(status ?? '—')}</span>`;
}

// ── No-profile state ──────────────────────────────────────────────────────────

function _buildNoProfile() {
    return `<div class="app-ws-prof__no-profile">
      <span class="app-ws-prof__no-profile-icon"><i class="fa-solid fa-user-slash"></i></span>
      <h3 class="app-ws-prof__no-profile-title">No researcher profile linked</h3>
      <p class="app-ws-prof__no-profile-body">
        Your account is not yet linked to a researcher profile.
        Visit your <a href="/user/profile">profile page</a> to complete setup.
      </p>
    </div>`;
}

// ── Event wiring ──────────────────────────────────────────────────────────────

function _wireEvents() {
    const root = _mount;

    // ── Per-element listeners (elements are replaced on each render, so these
    //    never accumulate — no abort needed) ──────────────────────────────────

    root.querySelector('#ws-prof-retry-btn')?.addEventListener('click', () => _init(_panel));
    root.querySelector('#ws-prof-edit-btn')?.addEventListener('click', _openEdit);
    root.querySelector('#ws-prof-cancel-btn')?.addEventListener('click', _closeEdit);
    root.querySelector('#ws-prof-save-btn')?.addEventListener('click', _saveProfile);

    root.querySelector('#ws-prof-add-scopus-btn')?.addEventListener('click', () => {
        const entries = root.querySelector('#ws-prof-scopus-entries');
        if (entries) {
            entries.insertAdjacentHTML('beforeend', _buildIdEntryRow('scopusId', '', entries.children.length));
            entries.lastElementChild?.querySelector('input')?.focus();
        }
    });

    root.querySelector('#ws-prof-add-wos-btn')?.addEventListener('click', () => {
        const entries = root.querySelector('#ws-prof-wos-entries');
        if (entries) {
            entries.insertAdjacentHTML('beforeend', _buildIdEntryRow('wosId', '', entries.children.length));
            entries.lastElementChild?.querySelector('input')?.focus();
        }
    });

    // ── Delegated listeners are added to the stable _mount node and therefore
    //    accumulate across renders unless explicitly torn down.  Use an
    //    AbortController so re-renders cancel the previous set first. ─────────

    if (_delegateController) _delegateController.abort();
    _delegateController = new AbortController();
    const { signal } = _delegateController;

    root.addEventListener('click', e => {
        // Checklist anchor → open edit form and scroll to field
        const anchor = e.target.closest('[data-prof-checklist-anchor]');
        if (anchor) {
            e.preventDefault();
            const fieldId = anchor.dataset.profChecklistAnchor;
            _openEdit();
            setTimeout(() => {
                const el = root.querySelector(`#${fieldId}`);
                if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); el.focus(); }
            }, 50);
            return;
        }

        // Remove ID row button
        const removeBtn = e.target.closest('[data-remove-id-row]');
        if (removeBtn) {
            removeBtn.closest('.app-ws-prof__id-entry-row')?.remove();
            return;
        }

        // Sync action buttons
        const syncBtn = e.target.closest('[data-sync-type]');
        if (syncBtn) {
            _triggerSync(syncBtn.dataset.syncType, syncBtn.dataset.syncId, syncBtn);
            return;
        }
    }, { signal });
}

// ── Edit form open/close ──────────────────────────────────────────────────────

function _openEdit() {
    if (_editOpen) return;
    _editOpen = true;
    const grid      = _mount.querySelector('#ws-prof-readonly-grid');
    const formCont  = _mount.querySelector('#ws-prof-edit-form-container');
    const editBtn   = _mount.querySelector('#ws-prof-edit-btn');
    if (grid)     grid.hidden = true;
    if (formCont) formCont.hidden = false;
    if (editBtn)  editBtn.hidden = true;
    _mount.querySelector('#ws-prof-edit-firstName')?.focus();
}

function _closeEdit() {
    if (!_editOpen) return;
    _editOpen = false;
    const grid      = _mount.querySelector('#ws-prof-readonly-grid');
    const formCont  = _mount.querySelector('#ws-prof-edit-form-container');
    const editBtn   = _mount.querySelector('#ws-prof-edit-btn');
    if (grid)     grid.hidden = false;
    if (formCont) formCont.hidden = true;
    if (editBtn)  editBtn.hidden = false;
}

// ── Save profile ──────────────────────────────────────────────────────────────

function _saveProfile() {
    const feedback  = _mount.querySelector('#ws-prof-form-feedback');
    const saveBtn   = _mount.querySelector('#ws-prof-save-btn');

    const firstName = (_mount.querySelector('#ws-prof-edit-firstName')?.value ?? '').trim();
    const lastName  = (_mount.querySelector('#ws-prof-edit-lastName')?.value ?? '').trim();
    const scholarId = (_mount.querySelector('#ws-prof-edit-scholarId')?.value ?? '').trim() || null;

    const scopusEntries = _mount.querySelectorAll('#ws-prof-scopus-entries .app-ws-prof__id-entry-row input');
    const wosEntries    = _mount.querySelectorAll('#ws-prof-wos-entries .app-ws-prof__id-entry-row input');

    const scopusId = Array.from(scopusEntries).map(i => i.value.trim()).filter(Boolean);
    const wosId    = Array.from(wosEntries).map(i => i.value.trim()).filter(Boolean);

    if (!firstName || !lastName) {
        _setFeedback(feedback, 'error', 'First name and last name are required.');
        return;
    }

    if (saveBtn) saveBtn.disabled = true;
    _setFeedback(feedback, '', '');

    fetch('/user/workspace/profile/save', {
        method:  'POST',
        headers: _postHeaders(),
        body:    JSON.stringify({ firstName, lastName, scholarId, scopusId, wosId })
    })
        .then(r => {
            if (!r.ok) return r.json().then(j => { throw new Error(j.error ?? `HTTP ${r.status}`); });
        })
        .then(() => {
            _init(_panel); // full reload to recalculate completeness
        })
        .catch(err => {
            if (saveBtn) saveBtn.disabled = false;
            _setFeedback(feedback, 'error', err.message ?? 'Save failed. Please try again.');
        });
}

function _setFeedback(el, type, msg) {
    if (!el) return;
    el.className = 'app-ws-prof__form-feedback';
    if (type) el.classList.add(`app-ws-prof__form-feedback--${type}`);
    el.textContent = msg;
}

// ── Trigger sync ──────────────────────────────────────────────────────────────

function _triggerSync(type, scopusId, btn) {
    if (btn) btn.disabled = true;

    fetch(`/user/workspace/profile/sync/${type}`, {
        method:  'POST',
        headers: _postHeaders(),
        body:    JSON.stringify({ scopusId })
    })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(task => {
            if (btn) btn.disabled = false;
            _prependTaskRow(type, task, scopusId);
        })
        .catch(err => {
            if (btn) btn.disabled = false;
            _showSyncError(btn, err.message);
        });
}

function _showSyncError(btn, msg) {
    // Show a small inline error next to the button for a few seconds
    if (!btn) return;
    const existing = btn.parentElement?.querySelector('.app-ws-prof__sync-error');
    if (existing) existing.remove();
    const el = document.createElement('span');
    el.className = 'app-ws-prof__sync-error';
    el.style.cssText = 'font-size:.78rem;color:var(--app-color-danger);margin-left:.35rem';
    el.textContent = msg || 'Sync failed';
    btn.insertAdjacentElement('afterend', el);
    setTimeout(() => el.remove(), 4000);
}

function _prependTaskRow(type, task, scopusId) {
    const tableId = type === 'publications' ? 'publication-tasks' : 'citation-tasks';
    const table   = _mount.querySelector(`#${tableId}`);

    if (!table) {
        // Table may not exist yet (no prior tasks — only a <p> was rendered).
        // Simplest path: reload the whole tab.
        _init(_panel);
        return;
    }

    const tbody = table.querySelector('tbody');
    if (!tbody) return;

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><span class="app-ws-prof__id-pill">${_esc(task.scopusId ?? scopusId ?? '—')}</span></td>
      <td>${_statusBadge(task.status)}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(task.initiatedDate))}</td>
      <td style="white-space:nowrap;font-size:.78rem">—</td>
      <td style="color:var(--app-color-text-muted);font-size:.78rem">—</td>`;
    tbody.insertBefore(tr, tbody.firstChild);

    // Keep at most 10 rows
    while (tbody.rows.length > 10) tbody.deleteRow(tbody.rows.length - 1);
}

import { postJsonHeaders as _postHeaders } from '../shared/fetchUtils';

// ── Utilities ─────────────────────────────────────────────────────────────────

function _esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function _fmtDate(iso) {
    if (!iso) return '—';
    try {
        return new Date(iso).toLocaleString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric',
            hour: '2-digit', minute: '2-digit'
        });
    } catch {
        return iso;
    }
}
