/**
 * workspacePublications.js — H36.7 Publications tab with master-detail
 *                            H36.9 Inline publication creation wizard
 *
 * Replaces the H36.3 minimal content rendering for the publications tab.
 * Called via window.appWorkspacePublications.init(panel) from workspace.html.
 *
 * Renders:
 *  - Toolbar: Add Publication / Export CNFIS / Scopus Updates
 *  - Stat strip: h-index, total publications, total citations
 *  - Inline wizard (H36.9): Select Forum → Add Authors → Review & Submit
 *  - Paginated table (20/page): Title, Year, Type, Venue, Citations, Actions
 *  - Inline detail expansion on row click: citation list + edit form (subtype)
 *  - Empty state when no publications
 *
 * Edit saves to POST /user/workspace/publications/save/{id}
 * Wizard submits to POST /user/workspace/publications/wizard
 */

import { postJsonHeaders } from '../shared/fetchUtils';

const PAGE_SIZE = 20;

// Subtype options matching the existing publications-edit.html form
const SUBTYPE_OPTIONS = [
    { value: 'ar',        label: 'Article' },
    { value: 'cp',        label: 'Conference Paper' },
    { value: 're',        label: 'Review' },
    { value: 'bk',        label: 'Book' },
    { value: 'ch',        label: 'Book Chapter' },
    { value: 'sh',        label: 'Short Survey' },
    { value: 'le',        label: 'Letter' },
    { value: 'no',        label: 'Note' },
    { value: 'ed',        label: 'Editorial' },
    { value: 'er',        label: 'Erratum' },
    { value: 'dp',        label: 'Data Paper' },
    { value: 'tb',        label: 'Tombstone' },
    { value: 'ab',        label: 'Abstract Report' },
    { value: 'ip',        label: 'Article in Press' },
    { value: 'wp',        label: 'Working Paper' },
    { value: 'undefined', label: 'Undefined' },
];

// Maps subtype codes to badge modifier classes
const SUBTYPE_BADGE_CLASS = {
    ar: 'app-ws-pubs__type-badge--article',
    cp: 'app-ws-pubs__type-badge--conference',
    re: 'app-ws-pubs__type-badge--review',
    bk: 'app-ws-pubs__type-badge--book',
    ch: 'app-ws-pubs__type-badge--book',
};

const AGGREGATION_TYPES = ['Journal', 'Conference Proceeding', 'Book Series', 'Book', 'Trade Journal', 'Report'];

// ── Module state ─────────────────────────────────────────────────────────────

let _panel     = null;
let _mount     = null;
let _allPubs   = [];
let _data      = null;
let _page      = 1;
let _activeId  = null;   // id of the row whose detail is currently open

// Wizard state
let _wizardOpen     = false;
let _wizardStep     = 1;      // 1=forum, 2=authors, 3=metadata
let _wForumId       = null;   // selected existing forum id
let _wNewForum      = null;   // {publicationName, issn, eIssn, aggregationType, publisher}
let _wForumFilter   = '';
let _wAuthorIds     = [];     // staged author ids (ordered)
let _wAuthors       = null;   // Array<ScholardexAuthorView> — null until fetched
let _wAuthorsLoading = false;
let _wTitle         = '';
let _wDate          = '';
let _wSubtype       = 'ar';
let _wSubtypeDesc   = '';
let _wDoi           = '';
let _wVolume        = '';
let _wIssueIdentifier = '';

// ── Public API ───────────────────────────────────────────────────────────────

export function initWorkspacePublications() {
    window.appWorkspacePublications = { init: _init };
}

// ── Init / fetch ─────────────────────────────────────────────────────────────

function _init(panel) {
    _panel = panel;
    _mount = panel.querySelector('[data-workspace-lazy-panel]');
    if (!_mount) return;

    const src = _mount.dataset.src;
    if (!src) return;

    _page      = 1;
    _activeId  = null;
    _wizardOpen = false;
    _showSkeleton();

    fetch(src, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            _data    = data;
            _allPubs = Array.isArray(data.publications) ? data.publications : [];
            _renderAll();
        })
        .catch(() => {
            _mount.innerHTML = _buildError();
        });
}

function _showSkeleton() {
    _mount.setAttribute('aria-busy', 'true');
    _mount.innerHTML = `
        <div class="app-skeleton-table">
          <div class="app-table-section__surface">
            <div class="app-skeleton-table__header">
              <div class="app-skeleton-block app-skeleton-table__header-bar"></div>
            </div>
            ${Array.from({ length: 5 }, () => `
            <div class="app-skeleton-row">
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--title"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--date"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--medium"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--short"></div>
              <div class="app-skeleton-block app-skeleton-cell app-skeleton-cell--narrow"></div>
            </div>`).join('')}
          </div>
        </div>`;
}

// ── Top-level render ─────────────────────────────────────────────────────────

function _renderAll() {
    const container = document.createElement('div');
    container.className = 'app-ws-pubs';

    // Stats
    container.insertAdjacentHTML('beforeend', _buildStats());

    // Toolbar
    container.insertAdjacentHTML('beforeend', _buildToolbar());

    // Wizard placeholder — always present so wizard can open even when list is empty
    const wizPlaceholder = document.createElement('div');
    wizPlaceholder.id = 'ws-pubs-wizard';
    container.appendChild(wizPlaceholder);

    if (_allPubs.length === 0) {
        container.insertAdjacentHTML('beforeend', _buildEmpty());
        _mount.removeAttribute('aria-busy');
        _mount.innerHTML = '';
        _mount.appendChild(container);
        _wireStaticEvents();
        return;
    }

    // Table wrapper (table + pagination)
    const tableWrap = document.createElement('div');
    tableWrap.className = 'app-ws-pubs__table-wrap';
    tableWrap.id = 'ws-pubs-table-wrap';
    container.appendChild(tableWrap);

    _mount.removeAttribute('aria-busy');
    _mount.innerHTML = '';
    _mount.appendChild(container);

    _renderPage();
    _wireStaticEvents();

    // Escape key
    document.addEventListener('keydown', _handleEscape);

    // Retry buttons (own error block)
    _mount.addEventListener('click', e => {
        const btn = e.target.closest('[data-retry-panel]');
        if (btn) _init(_panel);
    });
}

function _wireStaticEvents() {
    // Add Publication buttons — intercept for wizard (progressive enhancement)
    document.getElementById('ws-pubs-add-btn')?.addEventListener('click', e => {
        e.preventDefault();
        _openWizard();
    });
    document.getElementById('ws-pubs-add-btn-empty')?.addEventListener('click', e => {
        e.preventDefault();
        _openWizard();
    });

    // Scopus Updates button → switch to profile tab
    document.getElementById('ws-pubs-scopus-btn')?.addEventListener('click', () => {
        window.appWorkspaceTabs?.activateTab('profile');
    });
}

function _renderPage() {
    const wrap = document.getElementById('ws-pubs-table-wrap');
    if (!wrap) return;
    wrap.innerHTML = '';

    const start    = (_page - 1) * PAGE_SIZE;
    const pagePubs = _allPubs.slice(start, start + PAGE_SIZE);
    const total    = _allPubs.length;
    const pages    = Math.ceil(total / PAGE_SIZE);

    const table = document.createElement('table');
    table.className = 'app-ws-pubs__table';
    table.setAttribute('role', 'grid');
    table.innerHTML = `
        <colgroup>
          <col class="app-ws-pubs__col-title">
          <col class="app-ws-pubs__col-year">
          <col class="app-ws-pubs__col-type">
          <col class="app-ws-pubs__col-venue">
          <col class="app-ws-pubs__col-cites">
          <col class="app-ws-pubs__col-actions">
        </colgroup>
        <thead>
          <tr>
            <th scope="col">Title</th>
            <th scope="col">Year</th>
            <th scope="col">Type</th>
            <th scope="col">Venue</th>
            <th scope="col" style="text-align:right">Cites</th>
            <th scope="col" style="text-align:right"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody id="ws-pubs-tbody"></tbody>`;

    wrap.appendChild(table);

    const tbody = table.querySelector('#ws-pubs-tbody');
    for (const pub of pagePubs) {
        _appendRow(tbody, pub);
    }

    // Pagination
    if (pages > 1) {
        wrap.insertAdjacentHTML('beforeend', _buildPagination(total, pages));
        wrap.querySelectorAll('.app-ws-pubs__page-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const target = Number(btn.dataset.page);
                if (!isNaN(target)) {
                    _page     = target;
                    _activeId = null;
                    _renderPage();
                }
            });
        });
    }
}

// ── Row rendering ────────────────────────────────────────────────────────────

function _appendRow(tbody, pub) {
    const forum      = _data?.forumMap?.[pub.forumId];
    const venueTitle = forum?.title ?? forum?.publicationName ?? pub.forumId ?? '';
    const subtype    = (pub.subtype ?? '').toLowerCase();
    const badgeCls   = SUBTYPE_BADGE_CLASS[subtype] ?? '';
    const typeLabel  = pub.subtypeDescription ?? pub.subtype ?? '—';
    const year       = pub.coverDate ? pub.coverDate.substring(0, 4) : '—';
    const cites      = pub.citedByCount ?? pub.citedbyCount ?? 0;

    const tr = document.createElement('tr');
    tr.className = 'app-ws-pubs__row';
    tr.dataset.pubId = pub.id;
    if (_activeId === pub.id) tr.classList.add('app-ws-pubs__row--active');

    tr.innerHTML =
        `<td class="app-ws-pubs__col-title">` +
            `<span class="app-ws-pubs__title">${_esc(pub.title ?? '(untitled)')}</span>` +
        `</td>` +
        `<td class="app-ws-pubs__col-year">${_esc(year)}</td>` +
        `<td class="app-ws-pubs__col-type">` +
            `<span class="app-ws-pubs__type-badge ${badgeCls}">${_esc(typeLabel)}</span>` +
        `</td>` +
        `<td class="app-ws-pubs__col-venue">` +
            `<span class="app-ws-pubs__venue" title="${_esc(venueTitle)}">${_esc(venueTitle)}</span>` +
        `</td>` +
        `<td class="app-ws-pubs__col-cites" style="text-align:right">` +
            `<span class="app-ws-pubs__cites ${cites === 0 ? 'app-ws-pubs__cites--zero' : ''}">${cites}</span>` +
        `</td>` +
        `<td class="app-ws-pubs__col-actions" style="text-align:right">` +
            `<button class="app-ws-pubs__action-btn" ` +
                    `type="button" ` +
                    `aria-label="Details: ${_esc(pub.title ?? '')}" ` +
                    `aria-expanded="false" ` +
                    `data-detail-btn="${_esc(pub.id)}">` +
                `<i class="fa-solid fa-chevron-down" aria-hidden="true"></i>` +
            `</button>` +
        `</td>`;

    // Click on row or action button → toggle detail
    tr.addEventListener('click', e => {
        if (e.target.closest('a')) return;
        _toggleDetail(pub, tr);
    });

    tbody.appendChild(tr);

    // Re-open detail if this row was active (e.g. after re-render)
    if (_activeId === pub.id) {
        _insertDetailRow(pub, tr);
    }
}

// ── Detail panel ─────────────────────────────────────────────────────────────

function _toggleDetail(pub, tr) {
    if (_activeId === pub.id) {
        _closeDetail();
        return;
    }
    _closeDetail();
    _activeId = pub.id;
    tr.classList.add('app-ws-pubs__row--active');
    _insertDetailRow(pub, tr);
    const btn = tr.querySelector('[data-detail-btn]');
    if (btn) {
        btn.querySelector('i').className = 'fa-solid fa-chevron-up';
        btn.setAttribute('aria-expanded', 'true');
    }
}

function _closeDetail() {
    if (!_activeId) return;
    const prevTr = document.querySelector(`[data-pub-id="${CSS.escape(_activeId)}"]`);
    let triggerBtn = null;
    if (prevTr) {
        prevTr.classList.remove('app-ws-pubs__row--active');
        triggerBtn = prevTr.querySelector('[data-detail-btn]');
        if (triggerBtn) {
            triggerBtn.querySelector('i').className = 'fa-solid fa-chevron-down';
            triggerBtn.setAttribute('aria-expanded', 'false');
        }
    }
    const prevDetail = document.getElementById('ws-pubs-detail-row');
    const focusWasInDetail = prevDetail?.contains(document.activeElement);
    if (prevDetail) prevDetail.remove();
    _activeId = null;
    // Return focus to the trigger button if focus was inside the detail panel
    if (focusWasInDetail && triggerBtn) triggerBtn.focus();
}

function _insertDetailRow(pub, tr) {
    const colCount = 6;
    const detailTr = document.createElement('tr');
    detailTr.id        = 'ws-pubs-detail-row';
    detailTr.className = 'app-ws-pubs__detail-row';

    const td = document.createElement('td');
    td.setAttribute('colspan', String(colCount));

    td.innerHTML = _buildDetailPanel(pub);
    detailTr.appendChild(td);
    tr.insertAdjacentElement('afterend', detailTr);

    // Close button
    detailTr.querySelector('.app-ws-pubs__detail-close')?.addEventListener('click', () => {
        _closeDetail();
    });

    // Save button
    detailTr.querySelector('[data-save-pub]')?.addEventListener('click', () => {
        _savePub(pub.id, detailTr);
    });
}

function _buildDetailPanel(pub) {
    const cites    = pub.citedByCount ?? pub.citedbyCount ?? 0;
    const citingIds = Array.isArray(pub.citingPublicationIds) ? pub.citingPublicationIds : [];

    let citationsHtml;
    if (cites === 0) {
        citationsHtml = `<p class="app-ws-pubs__citations-count">No citations yet.</p>`;
    } else {
        const previewIds  = citingIds.slice(0, 5);
        const previewItems = previewIds.map(cid => {
            const citp  = _allPubs.find(p => p.id === cid || p.eid === cid);
            const label = citp?.title ?? cid;
            return `<li>${_esc(label)}</li>`;
        }).join('');
        const moreCount = citingIds.length - previewIds.length;
        citationsHtml =
            `<p class="app-ws-pubs__citations-count">` +
                `<strong>${cites}</strong> citation${cites !== 1 ? 's' : ''}` +
            `</p>` +
            (previewItems ? `<ul class="app-ws-pubs__citations-list">${previewItems}</ul>` : '') +
            (moreCount > 0
                ? `<span style="font-size:0.8rem;color:var(--app-color-text-muted)">&hellip; and ${moreCount} more</span>`
                : '') +
            `<a href="/user/publications/citations?id=${_esc(pub.id)}" class="app-ws-pubs__citations-link">` +
                `View all citations →` +
            `</a>`;
    }

    const currentSubtype = pub.subtype ?? '';
    const optionsHtml    = SUBTYPE_OPTIONS.map(o =>
        `<option value="${_esc(o.value)}" ${o.value === currentSubtype ? 'selected' : ''}>${_esc(o.label)}</option>`
    ).join('');

    return `
        <div class="app-ws-pubs__detail-inner">
          <button class="app-ws-pubs__detail-close" type="button" aria-label="Close details">
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
          <div class="app-ws-pubs__detail-panel">

            <div>
              <p class="app-ws-pubs__detail-section-title">Citations</p>
              ${citationsHtml}
            </div>

            <div>
              <p class="app-ws-pubs__detail-section-title">Edit</p>
              <div class="app-ws-pubs__edit-form">
                <div class="app-ws-pubs__edit-field">
                  <label class="app-ws-pubs__edit-label" for="ws-pubs-subtype-${_esc(pub.id)}">Publication type</label>
                  <select class="app-ws-pubs__edit-select"
                          id="ws-pubs-subtype-${_esc(pub.id)}"
                          data-field="subtype">
                    ${optionsHtml}
                  </select>
                </div>
                <div class="app-ws-pubs__edit-field">
                  <label class="app-ws-pubs__edit-label" for="ws-pubs-subtype-desc-${_esc(pub.id)}">Type description</label>
                  <input class="app-ws-pubs__edit-input"
                         id="ws-pubs-subtype-desc-${_esc(pub.id)}"
                         type="text"
                         data-field="subtypeDescription"
                         value="${_esc(pub.subtypeDescription ?? '')}"
                         placeholder="e.g. Journal Article"/>
                </div>
                <div class="app-ws-pubs__edit-actions">
                  <button class="btn btn-sm btn-primary" type="button" data-save-pub="${_esc(pub.id)}">
                    Save
                  </button>
                  <span class="app-ws-pubs__edit-feedback" role="status" aria-live="polite"></span>
                </div>
              </div>
            </div>

          </div>
        </div>`;
}

// ── Save ─────────────────────────────────────────────────────────────────────

function _savePub(pubId, detailTr) {
    const subtype            = detailTr.querySelector('[data-field="subtype"]')?.value ?? '';
    const subtypeDescription = detailTr.querySelector('[data-field="subtypeDescription"]')?.value ?? '';
    const feedback = detailTr.querySelector('.app-ws-pubs__edit-feedback');
    const saveBtn  = detailTr.querySelector('[data-save-pub]');

    if (saveBtn) saveBtn.disabled = true;

    fetch(`/user/workspace/publications/save/${encodeURIComponent(pubId)}`, {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({ subtype, subtypeDescription }),
    })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const pub = _allPubs.find(p => p.id === pubId);
            if (pub) {
                pub.subtype            = subtype;
                pub.subtypeDescription = subtypeDescription;
            }
            if (feedback) {
                feedback.textContent = 'Saved.';
                feedback.classList.remove('app-ws-pubs__edit-feedback--error');
                feedback.classList.add('app-ws-pubs__edit-feedback--visible');
                setTimeout(() => feedback.classList.remove('app-ws-pubs__edit-feedback--visible'), 2500);
            }
            const tr = document.querySelector(`[data-pub-id="${CSS.escape(pubId)}"]`);
            if (tr) {
                const badgeEl = tr.querySelector('.app-ws-pubs__type-badge');
                if (badgeEl) {
                    const newCls = SUBTYPE_BADGE_CLASS[(subtype ?? '').toLowerCase()] ?? '';
                    badgeEl.className   = `app-ws-pubs__type-badge ${newCls}`;
                    badgeEl.textContent = subtypeDescription || subtype || '—';
                }
            }
        })
        .catch(() => {
            if (feedback) {
                feedback.textContent = 'Save failed — please try again.';
                feedback.classList.add('app-ws-pubs__edit-feedback--error', 'app-ws-pubs__edit-feedback--visible');
            }
        })
        .finally(() => {
            if (saveBtn) saveBtn.disabled = false;
        });
}

// ── Keyboard ─────────────────────────────────────────────────────────────────

function _handleEscape(e) {
    if (e.key !== 'Escape') return;
    // Wizard takes priority
    if (_wizardOpen) {
        e.stopPropagation();
        _closeWizard(false);
        return;
    }
    if (!_activeId) return;
    const detailRow = document.getElementById('ws-pubs-detail-row');
    if (!detailRow) return;
    e.stopPropagation();
    const savedId = _activeId;
    _closeDetail();
    const tr = document.querySelector(`[data-pub-id="${CSS.escape(savedId ?? '')}"]`);
    tr?.querySelector('[data-detail-btn]')?.focus();
}

// ── Wizard ────────────────────────────────────────────────────────────────────

function _openWizard() {
    if (_wizardOpen) return;
    _wizardOpen      = true;
    _wizardStep      = 1;
    _wForumId        = null;
    _wNewForum       = null;
    _wForumFilter    = '';
    _wAuthorIds      = [];
    _wAuthors        = null;
    _wAuthorsLoading = false;
    _wTitle          = '';
    _wDate           = '';
    _wSubtype        = 'ar';
    _wSubtypeDesc    = '';
    _wDoi            = '';
    _wVolume         = '';
    _wIssueIdentifier = '';
    _renderWizardPanel();
    setTimeout(() => {
        document.getElementById('ws-pubs-wiz-forum-search')?.focus();
    }, 50);
}

function _closeWizard(force) {
    if (!_wizardOpen) return;
    if (!force && _wizardIsDirty()) {
        if (!confirm('Discard unsaved changes?')) return;
    }
    _wizardOpen = false;
    const placeholder = document.getElementById('ws-pubs-wizard');
    if (placeholder) placeholder.innerHTML = '';
    document.getElementById('ws-pubs-add-btn')?.focus();
}

function _wizardIsDirty() {
    return !!(
        _wForumId ||
        (_wNewForum && (_wNewForum.publicationName || _wNewForum.issn)) ||
        _wAuthorIds.length > 0 ||
        _wTitle || _wDate || _wSubtypeDesc || _wDoi
    );
}

function _renderWizardPanel() {
    const placeholder = document.getElementById('ws-pubs-wizard');
    if (!placeholder) return;
    placeholder.innerHTML = _buildWizardShell();
    _wireWizardEvents();
}

function _buildWizardShell() {
    const steps = [
        { label: 'Select Forum' },
        { label: 'Add Authors' },
        { label: 'Details' },
    ];

    const stepsHtml = steps.map((s, i) => {
        const n = i + 1;
        const cls = n < _wizardStep
            ? 'app-ws-pubs__wizard-step--done'
            : n === _wizardStep
                ? 'app-ws-pubs__wizard-step--active'
                : '';
        const dotContent = n < _wizardStep
            ? `<i class="fa-solid fa-check" aria-hidden="true"></i>`
            : String(n);
        return `
            <div class="app-ws-pubs__wizard-step ${cls}" aria-current="${n === _wizardStep ? 'step' : 'false'}">
              <div class="app-ws-pubs__wizard-step-dot">${dotContent}</div>
              <span>${_esc(s.label)}</span>
            </div>`;
    }).join('');

    return `
        <div class="app-ws-pubs__wizard" role="region" aria-label="Add publication wizard">
          <div class="app-ws-pubs__wizard-header">
            <h2 class="app-ws-pubs__wizard-title">Add Publication</h2>
            <button class="app-ws-pubs__wizard-close" type="button" aria-label="Close wizard" id="ws-pubs-wiz-close">
              <i class="fa-solid fa-xmark" aria-hidden="true"></i>
            </button>
          </div>
          <div class="app-ws-pubs__wizard-body">
            <div class="app-ws-pubs__wizard-steps" role="list" aria-label="Wizard steps">
              ${stepsHtml}
            </div>
            <div id="ws-pubs-wiz-step-body">
              ${_renderCurrentStep()}
            </div>
          </div>
        </div>`;
}

function _renderCurrentStep() {
    if (_wizardStep === 1) return _renderStep1();
    if (_wizardStep === 2) return _renderStep2();
    return _renderStep3();
}

// Step 1: Forum selection
function _renderStep1() {
    const forums = Object.values(_data?.forumMap ?? {});
    const q      = _wForumFilter.toLowerCase();
    const filtered = q.length >= 1
        ? forums.filter(f => f.publicationName?.toLowerCase().includes(q)).slice(0, 20)
        : forums.slice(0, 20);

    const listItems = filtered.map(f => {
        const selected = f.id === _wForumId;
        return `
            <li class="app-ws-pubs__wiz-forum-item ${selected ? 'app-ws-pubs__wiz-forum-item--selected' : ''}"
                data-forum-id="${_esc(f.id)}" role="option" aria-selected="${selected}">
              <div class="app-ws-pubs__wiz-forum-name">${_esc(f.publicationName ?? f.id)}</div>
              <div class="app-ws-pubs__wiz-forum-meta">${_esc(f.aggregationType ?? '')}${f.issn ? ` · ${_esc(f.issn)}` : ''}</div>
            </li>`;
    }).join('');

    const emptyNotice = filtered.length === 0
        ? `<li style="padding:0.5rem;font-size:0.85rem;color:var(--app-color-text-muted)">No forums match — use "Create new forum" below.</li>`
        : '';

    // Prefill new-forum fields if _wNewForum is set
    const nf = _wNewForum ?? {};

    const aggrOptions = AGGREGATION_TYPES.map(t =>
        `<option value="${_esc(t)}" ${(nf.aggregationType ?? '') === t ? 'selected' : ''}>${_esc(t)}</option>`
    ).join('');

    const newForumHtml = `
        <details class="app-ws-pubs__wiz-new-forum" id="ws-pubs-wiz-new-forum-details" ${_wForumId === null && _wNewForum ? 'open' : ''}>
          <summary>+ Create new forum (not listed above)</summary>
          <div class="app-ws-pubs__wiz-new-forum-fields">
            <div class="app-ws-pubs__wiz-field">
              <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-forum-name">Publication name</label>
              <input class="app-ws-pubs__wiz-input" id="ws-wiz-forum-name" type="text"
                     value="${_esc(nf.publicationName ?? '')}" placeholder="e.g. IEEE Transactions on …"/>
            </div>
            <div class="app-ws-pubs__wiz-field">
              <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-forum-type">Forum type</label>
              <select class="app-ws-pubs__wiz-select" id="ws-wiz-forum-type">
                <option value="">— select —</option>
                ${aggrOptions}
              </select>
            </div>
            <div class="app-ws-pubs__wiz-field">
              <label class="app-ws-pubs__wiz-label" for="ws-wiz-forum-issn">ISSN</label>
              <input class="app-ws-pubs__wiz-input" id="ws-wiz-forum-issn" type="text"
                     value="${_esc(nf.issn ?? '')}" placeholder="1234-5678"/>
            </div>
            <div class="app-ws-pubs__wiz-field">
              <label class="app-ws-pubs__wiz-label" for="ws-wiz-forum-eissn">E-ISSN</label>
              <input class="app-ws-pubs__wiz-input" id="ws-wiz-forum-eissn" type="text"
                     value="${_esc(nf.eIssn ?? '')}" placeholder="1234-5678"/>
            </div>
            <div class="app-ws-pubs__wiz-field">
              <label class="app-ws-pubs__wiz-label" for="ws-wiz-forum-publisher">Publisher</label>
              <input class="app-ws-pubs__wiz-input" id="ws-wiz-forum-publisher" type="text"
                     value="${_esc(nf.publisher ?? '')}" placeholder="e.g. Springer"/>
            </div>
          </div>
        </details>`;

    return `
        <input class="app-ws-pubs__wiz-search-input" id="ws-pubs-wiz-forum-search"
               type="search" placeholder="Search forums…" value="${_esc(_wForumFilter)}"
               autocomplete="off" aria-label="Search forums"/>
        <ul class="app-ws-pubs__wiz-forum-list" role="listbox" aria-label="Forum results" id="ws-pubs-wiz-forum-list">
          ${listItems}${emptyNotice}
        </ul>
        ${newForumHtml}
        <div class="app-ws-pubs__wiz-nav">
          <span class="app-ws-pubs__wiz-feedback" id="ws-pubs-wiz-error" role="alert" aria-live="assertive"></span>
          <button class="btn btn-sm btn-outline-secondary" type="button" id="ws-pubs-wiz-cancel">Cancel</button>
          <button class="btn btn-sm btn-primary" type="button" id="ws-pubs-wiz-next">Next →</button>
        </div>`;
}

// Step 2: Author selection
function _renderStep2() {
    if (_wAuthorsLoading) {
        return `
            <p class="app-ws-pubs__wiz-loading">
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i> Loading co-authors…
            </p>
            <div class="app-ws-pubs__wiz-nav">
              <span class="app-ws-pubs__wiz-feedback" id="ws-pubs-wiz-error" role="alert" aria-live="assertive"></span>
              <button class="btn btn-sm btn-outline-secondary" type="button" id="ws-pubs-wiz-back">← Back</button>
              <button class="btn btn-sm btn-primary" type="button" id="ws-pubs-wiz-next" disabled>Next →</button>
            </div>`;
    }

    const available = (_wAuthors ?? []).filter(a => !_wAuthorIds.includes(a.id));
    const staged    = _wAuthorIds
        .map(id => (_wAuthors ?? []).find(a => a.id === id))
        .filter(Boolean);

    const availableItems = available.length > 0
        ? available.map(a =>
            `<li class="app-ws-pubs__wiz-author-item" data-add-author="${_esc(a.id)}" title="Add">
               <span>${_esc(a.name ?? a.id)}</span>
               <i class="fa-solid fa-plus" aria-hidden="true" style="color:var(--app-color-primary);font-size:0.75rem"></i>
             </li>`
        ).join('')
        : `<li class="app-ws-pubs__wiz-empty-authors">No co-authors found for this affiliation.</li>`;

    const stagedItems = staged.length > 0
        ? staged.map(a =>
            `<li class="app-ws-pubs__wiz-author-item" data-remove-author="${_esc(a.id)}" title="Remove">
               <span>${_esc(a.name ?? a.id)}</span>
               <i class="fa-solid fa-xmark" aria-hidden="true" style="color:var(--app-color-danger);font-size:0.75rem"></i>
             </li>`
        ).join('')
        : `<li class="app-ws-pubs__wiz-empty-authors">No authors staged yet.</li>`;

    return `
        <div class="app-ws-pubs__wiz-author-cols">
          <div>
            <p class="app-ws-pubs__wiz-author-col-title">Available co-authors</p>
            <ul class="app-ws-pubs__wiz-author-list" id="ws-pubs-wiz-available">${availableItems}</ul>
          </div>
          <div>
            <p class="app-ws-pubs__wiz-author-col-title">Staged authors</p>
            <ul class="app-ws-pubs__wiz-author-list" id="ws-pubs-wiz-staged">${stagedItems}</ul>
          </div>
        </div>
        <div class="app-ws-pubs__wiz-nav">
          <span class="app-ws-pubs__wiz-feedback" id="ws-pubs-wiz-error" role="alert" aria-live="assertive"></span>
          <button class="btn btn-sm btn-outline-secondary" type="button" id="ws-pubs-wiz-back">← Back</button>
          <button class="btn btn-sm btn-primary" type="button" id="ws-pubs-wiz-next">Next →</button>
        </div>`;
}

// Step 3: Metadata fields
function _renderStep3() {
    const subtypeOptions = SUBTYPE_OPTIONS.map(o =>
        `<option value="${_esc(o.value)}" ${o.value === _wSubtype ? 'selected' : ''}>${_esc(o.label)}</option>`
    ).join('');

    return `
        <div class="app-ws-pubs__wiz-fields">
          <div class="app-ws-pubs__wiz-field app-ws-pubs__wiz-field--full">
            <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-title">Title</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-title" type="text"
                   value="${_esc(_wTitle)}" placeholder="Publication title"/>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-date">Cover date</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-date" type="date"
                   value="${_esc(_wDate)}"/>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-subtype-desc">Type description</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-subtype-desc" type="text"
                   value="${_esc(_wSubtypeDesc)}" placeholder="e.g. Journal Article"/>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label" for="ws-wiz-subtype">Subtype code</label>
            <select class="app-ws-pubs__wiz-select" id="ws-wiz-subtype">
              ${subtypeOptions}
            </select>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label" for="ws-wiz-doi">DOI</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-doi" type="text"
                   value="${_esc(_wDoi)}" placeholder="10.1234/…"/>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label" for="ws-wiz-volume">Volume</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-volume" type="text"
                   value="${_esc(_wVolume)}" placeholder="e.g. 42"/>
          </div>
          <div class="app-ws-pubs__wiz-field">
            <label class="app-ws-pubs__wiz-label" for="ws-wiz-issue">Issue</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-issue" type="text"
                   value="${_esc(_wIssueIdentifier)}" placeholder="e.g. 3"/>
          </div>
        </div>
        <div class="app-ws-pubs__wiz-nav">
          <span class="app-ws-pubs__wiz-feedback" id="ws-pubs-wiz-error" role="alert" aria-live="assertive"></span>
          <button class="btn btn-sm btn-outline-secondary" type="button" id="ws-pubs-wiz-back">← Back</button>
          <button class="btn btn-sm btn-primary" type="button" id="ws-pubs-wiz-submit">Submit</button>
        </div>`;
}

// ── Wizard event wiring ───────────────────────────────────────────────────────

function _wireWizardEvents() {
    // Close button
    document.getElementById('ws-pubs-wiz-close')?.addEventListener('click', () => _closeWizard(false));

    // Cancel button (step 1)
    document.getElementById('ws-pubs-wiz-cancel')?.addEventListener('click', () => _closeWizard(false));

    // Back button
    document.getElementById('ws-pubs-wiz-back')?.addEventListener('click', () => _wizardBack());

    // Next / Submit
    document.getElementById('ws-pubs-wiz-next')?.addEventListener('click', () => _wizardNext());
    document.getElementById('ws-pubs-wiz-submit')?.addEventListener('click', () => _submitWizard());

    if (_wizardStep === 1) {
        // Forum search filter
        const searchInput = document.getElementById('ws-pubs-wiz-forum-search');
        searchInput?.addEventListener('input', () => {
            _wForumFilter = searchInput.value;
            _reRenderStepBody();
        });

        // Forum list click
        document.getElementById('ws-pubs-wiz-forum-list')?.addEventListener('click', e => {
            const item = e.target.closest('[data-forum-id]');
            if (!item) return;
            const fid = item.dataset.forumId;
            _wForumId  = _wForumId === fid ? null : fid; // toggle
            _wNewForum = null;
            // Collapse the new-forum details if a forum was selected
            const details = document.getElementById('ws-pubs-wiz-new-forum-details');
            if (details && _wForumId) details.open = false;
            _reRenderStepBody();
        });
    }

    if (_wizardStep === 2) {
        // Add author
        document.getElementById('ws-pubs-wiz-available')?.addEventListener('click', e => {
            const item = e.target.closest('[data-add-author]');
            if (!item) return;
            const aid = item.dataset.addAuthor;
            if (!_wAuthorIds.includes(aid)) _wAuthorIds.push(aid);
            _reRenderStepBody();
        });

        // Remove author
        document.getElementById('ws-pubs-wiz-staged')?.addEventListener('click', e => {
            const item = e.target.closest('[data-remove-author]');
            if (!item) return;
            _wAuthorIds = _wAuthorIds.filter(id => id !== item.dataset.removeAuthor);
            _reRenderStepBody();
        });
    }
}

function _reRenderStepBody() {
    const body = document.getElementById('ws-pubs-wiz-step-body');
    if (!body) return;
    body.innerHTML = _renderCurrentStep();
    _wireWizardEvents();
}

// ── Wizard navigation ─────────────────────────────────────────────────────────

function _wizardNext() {
    if (_wizardStep === 1) {
        _captureStep1();
        const forumSelected = !!_wForumId;
        const newForumValid  = _wNewForum &&
            _wNewForum.publicationName &&
            _wNewForum.aggregationType;
        if (!forumSelected && !newForumValid) {
            _showWizardError('Please select a forum from the list, or fill in the publication name and type in "Create new forum".');
            return;
        }
        _clearWizardError();
        _wizardStep = 2;
        _renderWizardPanel();
        _fetchAuthorsIfNeeded();
    } else if (_wizardStep === 2) {
        _wizardStep = 3;
        _renderWizardPanel();
        setTimeout(() => document.getElementById('ws-wiz-title')?.focus(), 50);
    }
}

function _wizardBack() {
    if (_wizardStep === 2) {
        _wizardStep = 1;
        _renderWizardPanel();
    } else if (_wizardStep === 3) {
        _captureStep3();
        _wizardStep = 2;
        _renderWizardPanel();
    }
}

function _captureStep1() {
    const details = document.getElementById('ws-pubs-wiz-new-forum-details');
    if (details?.open) {
        _wForumId  = null;
        _wNewForum = {
            publicationName: document.getElementById('ws-wiz-forum-name')?.value?.trim() ?? '',
            issn:            document.getElementById('ws-wiz-forum-issn')?.value?.trim() ?? '',
            eIssn:           document.getElementById('ws-wiz-forum-eissn')?.value?.trim() ?? '',
            aggregationType: document.getElementById('ws-wiz-forum-type')?.value ?? '',
            publisher:       document.getElementById('ws-wiz-forum-publisher')?.value?.trim() ?? '',
        };
    }
}

function _captureStep3() {
    _wTitle           = document.getElementById('ws-wiz-title')?.value?.trim() ?? '';
    _wDate            = document.getElementById('ws-wiz-date')?.value?.trim() ?? '';
    _wSubtypeDesc     = document.getElementById('ws-wiz-subtype-desc')?.value?.trim() ?? '';
    _wSubtype         = document.getElementById('ws-wiz-subtype')?.value ?? 'ar';
    _wDoi             = document.getElementById('ws-wiz-doi')?.value?.trim() ?? '';
    _wVolume          = document.getElementById('ws-wiz-volume')?.value?.trim() ?? '';
    _wIssueIdentifier = document.getElementById('ws-wiz-issue')?.value?.trim() ?? '';
}

function _validateStep3() {
    if (!_wTitle) return 'Title is required.';
    if (!_wDate)  return 'Cover date is required.';
    if (!_wSubtypeDesc) return 'Type description is required.';
    return null;
}

function _showWizardError(msg) {
    const el = document.getElementById('ws-pubs-wiz-error');
    if (el) el.textContent = msg;
}

function _clearWizardError() {
    const el = document.getElementById('ws-pubs-wiz-error');
    if (el) el.textContent = '';
}

// ── Wizard: fetch authors ─────────────────────────────────────────────────────

function _fetchAuthorsIfNeeded() {
    if (_wAuthors !== null) {
        // already loaded
        _reRenderStepBody();
        return;
    }
    const afid = Array.isArray(_data?.affiliations) && _data.affiliations.length > 0
        ? _data.affiliations[0].afid
        : null;

    if (!afid) {
        _wAuthors        = [];
        _wAuthorsLoading = false;
        _reRenderStepBody();
        return;
    }

    _wAuthorsLoading = true;
    _reRenderStepBody();

    fetch(`/user/workspace/publications/wizard-authors?afid=${encodeURIComponent(afid)}`, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
    })
        .then(res => res.ok ? res.json() : [])
        .then(authors => {
            _wAuthors        = Array.isArray(authors) ? authors : [];
            _wAuthorsLoading = false;
            if (_wizardStep === 2) _reRenderStepBody();
        })
        .catch(() => {
            _wAuthors        = [];
            _wAuthorsLoading = false;
            if (_wizardStep === 2) _reRenderStepBody();
        });
}

// ── Wizard: submit ────────────────────────────────────────────────────────────

function _submitWizard() {
    _captureStep3();
    const err = _validateStep3();
    if (err) {
        _showWizardError(err);
        return;
    }
    _clearWizardError();

    const submitBtn = document.getElementById('ws-pubs-wiz-submit');
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.textContent = 'Submitting…';
    }

    const command = {
        title:            _wTitle,
        doi:              _wDoi,
        subtypeDescription: _wSubtypeDesc,
        subtype:          _wSubtype,
        coverDate:        _wDate,
        volume:           _wVolume,
        issueIdentifier:  _wIssueIdentifier,
        authorIdsCsv:     _wAuthorIds.join(','),
        forum:            _wForumId ?? '',
    };

    // Attach new-forum fields when creating a new forum
    if (!_wForumId && _wNewForum) {
        command.wizardForumPublicationName  = _wNewForum.publicationName;
        command.wizardForumIssn             = _wNewForum.issn;
        command.wizardForumEIssn            = _wNewForum.eIssn;
        command.wizardForumAggregationType  = _wNewForum.aggregationType;
        command.wizardForumPublisher        = _wNewForum.publisher;
    }

    fetch('/user/workspace/publications/wizard', {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify(command),
    })
        .then(async res => {
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(body.error ?? `HTTP ${res.status}`);
            }
            return body;
        })
        .then(() => {
            _wizardOpen = false;
            // Full reload to show the new publication
            _init(_panel);
        })
        .catch(err => {
            _showWizardError(err.message ?? 'Submission failed — please try again.');
            if (submitBtn) {
                submitBtn.disabled    = false;
                submitBtn.textContent = 'Submit';
            }
        });
}

// ── HTML builders ─────────────────────────────────────────────────────────────

function _buildToolbar() {
    return `
        <div class="app-ws-pubs__toolbar">
          <a href="/user/publications/add" id="ws-pubs-add-btn" class="btn btn-sm btn-primary">
            <i class="fa-solid fa-plus" aria-hidden="true"></i> Add Publication
          </a>
          <a href="/user/publications" class="btn btn-sm btn-outline-secondary">
            <i class="fa-solid fa-file-export" aria-hidden="true"></i> Export CNFIS
          </a>
          <button type="button" class="btn btn-sm btn-outline-secondary" id="ws-pubs-scopus-btn">
            <i class="fa-solid fa-rotate" aria-hidden="true"></i> Scopus Updates
          </button>
          <span class="app-ws-pubs__toolbar-spacer"></span>
        </div>`;
}

function _buildStats() {
    const count  = _data?.publications?.length ?? 0;
    const hIndex = _data?.hIndex ?? 0;
    const cites  = _data?.numCitations ?? 0;
    return `
        <div class="app-ws-pubs__stats">
          <div class="app-ws-pubs__stat">
            <p class="app-ws-pubs__stat-label">Publications</p>
            <p class="app-ws-pubs__stat-value">${count}</p>
          </div>
          <div class="app-ws-pubs__stat app-ws-pubs__stat--success">
            <p class="app-ws-pubs__stat-label">Citations</p>
            <p class="app-ws-pubs__stat-value">${cites}</p>
          </div>
          <div class="app-ws-pubs__stat app-ws-pubs__stat--warning">
            <p class="app-ws-pubs__stat-label">H-Index</p>
            <p class="app-ws-pubs__stat-value">${hIndex}</p>
          </div>
        </div>`;
}

function _buildPagination(total, pages) {
    const info = `Showing ${Math.min((_page - 1) * PAGE_SIZE + 1, total)}–${Math.min(_page * PAGE_SIZE, total)} of ${total}`;

    const prevBtn = `<button class="app-ws-pubs__page-btn" type="button" data-page="${_page - 1}" ${_page <= 1 ? 'disabled' : ''} aria-label="Previous page">
        <i class="fa-solid fa-chevron-left" aria-hidden="true"></i>
    </button>`;

    const range = [];
    const half  = 2;
    let lo = Math.max(1, _page - half);
    let hi = Math.min(pages, lo + 2 * half);
    lo = Math.max(1, hi - 2 * half);
    for (let p = lo; p <= hi; p++) range.push(p);

    const pageBtns = range.map(p =>
        `<button class="app-ws-pubs__page-btn ${p === _page ? 'app-ws-pubs__page-btn--active' : ''}" type="button" data-page="${p}" aria-label="Page ${p}" ${p === _page ? 'aria-current="page"' : ''}>${p}</button>`
    ).join('');

    const nextBtn = `<button class="app-ws-pubs__page-btn" type="button" data-page="${_page + 1}" ${_page >= pages ? 'disabled' : ''} aria-label="Next page">
        <i class="fa-solid fa-chevron-right" aria-hidden="true"></i>
    </button>`;

    return `
        <div class="app-ws-pubs__pagination" role="navigation" aria-label="Publications pagination">
          <span>${_esc(info)}</span>
          <div class="app-ws-pubs__pagination-btns">
            ${prevBtn}${pageBtns}${nextBtn}
          </div>
        </div>`;
}

function _buildEmpty() {
    return `
        <div class="app-ws-pubs__empty">
          <i class="fa-solid fa-book-open app-ws-pubs__empty-icon" aria-hidden="true"></i>
          <h2 class="app-ws-pubs__empty-title">No publications yet</h2>
          <p class="app-ws-pubs__empty-body">Add your first publication to start tracking your research output.</p>
          <a href="/user/publications/add" id="ws-pubs-add-btn-empty" class="btn btn-sm btn-primary" style="margin-top:0.5rem">
            <i class="fa-solid fa-plus" aria-hidden="true"></i> Add Publication
          </a>
        </div>`;
}

function _buildError() {
    return `
        <div class="app-panel-error app-dashboard-empty">
          <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
          <h3 class="app-dashboard-empty__title">Could not load Publications</h3>
          <p class="app-dashboard-empty__body">Something went wrong while fetching this content.</p>
          <button type="button" class="btn btn-sm btn-outline-primary" data-retry-panel>
            Try again
          </button>
        </div>`;
}

// ── Utility ───────────────────────────────────────────────────────────────────

function _esc(str) {
    return String(str ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
