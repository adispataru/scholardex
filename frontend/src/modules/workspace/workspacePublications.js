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
import { buildPaginationHtml, wirePaginationClicks } from '../shared/clientPagination';
import { t, tPlural } from '../shared/i18n';

const PAGE_SIZE = 20;

// Subtype options matching the existing publications-edit.html form
const SUBTYPE_OPTIONS = [
    { value: 'ar',        key: 'workspace.pubs.subtype.ar' },
    { value: 'cp',        key: 'workspace.pubs.subtype.cp' },
    { value: 're',        key: 'workspace.pubs.subtype.re' },
    { value: 'bk',        key: 'workspace.pubs.subtype.bk' },
    { value: 'ch',        key: 'workspace.pubs.subtype.ch' },
    { value: 'sh',        key: 'workspace.pubs.subtype.sh' },
    { value: 'le',        key: 'workspace.pubs.subtype.le' },
    { value: 'no',        key: 'workspace.pubs.subtype.no' },
    { value: 'ed',        key: 'workspace.pubs.subtype.ed' },
    { value: 'er',        key: 'workspace.pubs.subtype.er' },
    { value: 'dp',        key: 'workspace.pubs.subtype.dp' },
    { value: 'tb',        key: 'workspace.pubs.subtype.tb' },
    { value: 'ab',        key: 'workspace.pubs.subtype.ab' },
    { value: 'ip',        key: 'workspace.pubs.subtype.ip' },
    { value: 'wp',        key: 'workspace.pubs.subtype.wp' },
    { value: 'undefined', key: 'workspace.pubs.subtype.undefined' },
];

// Maps subtype codes to badge modifier classes
const SUBTYPE_BADGE_CLASS = {
    ar: 'app-ws-pubs__type-badge--article',
    cp: 'app-ws-pubs__type-badge--conference',
    re: 'app-ws-pubs__type-badge--review',
    bk: 'app-ws-pubs__type-badge--book',
    ch: 'app-ws-pubs__type-badge--book',
};

// The VALUE is the canonical aggregationType persisted by the API — only the label is translated, or a
// Romanian UI would start submitting Romanian venue types.
const AGGREGATION_TYPES = [
    { value: 'Journal',               key: 'workspace.pubs.aggregation.journal' },
    { value: 'Conference Proceeding', key: 'workspace.pubs.aggregation.conference' },
    { value: 'Book Series',           key: 'workspace.pubs.aggregation.bookSeries' },
    { value: 'Book',                  key: 'workspace.pubs.aggregation.book' },
    { value: 'Trade Journal',         key: 'workspace.pubs.aggregation.tradeJournal' },
    { value: 'Report',                key: 'workspace.pubs.aggregation.report' },
];

// ── Module state ─────────────────────────────────────────────────────────────

let _panel          = null;
let _mount          = null;
let _allPubs        = [];
let _data           = null;
let _page           = 1;
let _activeId       = null;   // id of the row whose detail is currently open
let _pendingWizard  = false;  // open wizard as soon as the tab finishes loading

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
let _pendingRejectId = null;
let _publicationFilter = 'all';
let _searchQuery = '';
let _selectedPendingIds = new Set();
// H84 S3: duplicate-merge state — { suggestions: [{survivor, duplicate}], mergeStateByPublicationId: {id: 'PENDING'} }.
// Loaded lazily after the list; null until fetched (badges/banner simply don't render).
let _mergeState = null;

// ── Public API ───────────────────────────────────────────────────────────────

export function initWorkspacePublications() {
    window.appWorkspacePublications = {
        init: _init,
        // Called by Quick Actions before the tab has loaded — deferred until _renderAll finishes.
        openWizard() {
            if (_mount && _data) { _openWizard(); }
            else                 { _pendingWizard = true; }
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
    _panel = panel;
    _mount = panel.querySelector('[data-workspace-lazy-panel]');
    if (!_mount) return;

    const src = _mount.dataset.src;
    if (!src) return;

    _page      = 1;
    _activeId  = null;
    _wizardOpen = false;
    _publicationFilter = 'all';
    _selectedPendingIds = new Set();
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
            _fetchMergeState();
        })
        .catch(() => {
            _mount.innerHTML = _buildError();
        });
}

// ── H84 S3: duplicate-merge suggestions + flags ──────────────────────────────

function _fetchMergeState() {
    _mergeState = null;
    fetch('/user/workspace/publications/merge-state', { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(res => (res.ok ? res.json() : null))
        .then(state => {
            if (!state) return;
            _mergeState = state;
            _renderMergeSuggestions();
            _renderPage(); // repaint rows so "merge requested" badges appear
        })
        .catch(() => { /* merge hints are progressive enhancement — the list works without them */ });
}

function _mergeRequestState(pubId) {
    return _mergeState?.mergeStateByPublicationId?.[pubId] ?? null;
}

function _buildMergeBadge(pubId) {
    if (_mergeRequestState(pubId) !== 'PENDING') return '';
    return `<span class="app-ws-pubs__review-badge app-ws-pubs__review-badge--pending"
                  title="A duplicate-merge request for this publication is awaiting admin approval.">merge requested</span>`;
}

function _renderMergeSuggestions() {
    const host = document.getElementById('ws-pubs-merge-suggestions');
    if (!host) return;
    const suggestions = _mergeState?.suggestions ?? [];
    if (!suggestions.length) { host.innerHTML = ''; return; }

    const items = suggestions.map((s, i) => {
        const line = side =>
            `<div style="font-size:0.8rem;">` +
                `<strong>${_esc(side.title ?? side.id)}</strong>` +
                `<span class="text-muted"> — ${_esc(side.coverDate ?? 'no date')}` +
                ` · ${side.eid ? 'Scopus' : (side.doi ? 'DOI' : 'no identifier')}` +
                ` · ${side.citedByCount ?? 0} cites</span>` +
            `</div>`;
        return `<div class="app-ws-pubs__merge-suggestion" style="display:flex; align-items:center; gap:0.75rem; padding:0.4rem 0; border-top:1px solid var(--app-color-border, #eee);">
                  <div style="flex:1; min-width:0;">${line(s.survivor)}${line(s.duplicate)}</div>
                  <button type="button" class="btn btn-sm btn-outline-primary"
                          data-merge-flag-a="${_esc(s.survivor.id)}" data-merge-flag-b="${_esc(s.duplicate.id)}"
                          data-merge-flag-index="${i}">
                    Request merge
                  </button>
                </div>`;
    }).join('');

    host.innerHTML = `
        <div class="app-ws-pubs__review-summary" role="region" aria-label="${_esc(t('workspace.pubs.possibleDuplicates'))}" style="margin-bottom:0.75rem;">
          <div style="padding:0.6rem 0.9rem;">
            <div style="display:flex; align-items:center; gap:0.5rem;">
              <i class="fa-solid fa-code-merge" aria-hidden="true"></i>
              <strong>Possible duplicates (${suggestions.length})</strong>
              <span class="text-muted" style="font-size:0.8rem;">same title arriving via two sources — request a merge and an admin will review it</span>
            </div>
            ${items}
          </div>
        </div>`;

    host.querySelectorAll('[data-merge-flag-a]').forEach(btn => {
        btn.addEventListener('click', () => {
            btn.disabled = true;
            _submitMergeFlag(btn.dataset.mergeFlagA, btn.dataset.mergeFlagB, () => { btn.disabled = false; });
        });
    });
}

function _submitMergeFlag(idA, idB, onError) {
    fetch('/user/workspace/publications/merge-requests', {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({ publicationIdA: idA, publicationIdB: idB, note: null }),
    })
        .then(async res => {
            if (!res.ok) {
                let body = null;
                try { body = await res.json(); } catch (_) { /* ignore */ }
                throw new Error(body?.error ?? `HTTP ${res.status}`);
            }
            return res.json();
        })
        .then(() => {
            window.appToast?.show({
                message: t('workspace.pubs.mergeRequested'),
                tone: 'success',
            });
            _fetchMergeState(); // server state is the truth: refresh badges + drop the covered suggestion
        })
        .catch(err => {
            window.appToast?.show({ message: t('workspace.pubs.mergeFailed', err.message), tone: 'error' });
            onError?.();
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
    _selectedPendingIds = new Set([..._selectedPendingIds].filter(pubId => _isPending(pubId) && _allPubs.some(pub => pub.id === pubId)));
    const container = document.createElement('div');
    container.className = 'app-ws-pubs';

    // Stats
    container.insertAdjacentHTML('beforeend', _buildStats());

    // Toolbar
    container.insertAdjacentHTML('beforeend', _buildToolbar());

    // Review summary
    container.insertAdjacentHTML('beforeend', _buildReviewSummary());

    // H84 S3: duplicate-merge suggestions banner (filled after the lazy merge-state fetch)
    container.insertAdjacentHTML('beforeend', '<div id="ws-pubs-merge-suggestions"></div>');

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
        if (_pendingWizard) { _pendingWizard = false; _openWizard(); }
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
    _wireReviewSummaryEvents();
    if (_pendingWizard) { _pendingWizard = false; _openWizard(); }

    // Escape key
    document.addEventListener('keydown', _handleEscape);

    // Retry buttons (own error block)
    _mount.addEventListener('click', e => {
        const btn = e.target.closest('[data-retry-panel]');
        if (btn) _init(_panel);
    });

    // Citations modal trigger (delegated — button is inside the detail panel)
    _mount.addEventListener('click', e => {
        const btn = e.target.closest('[data-citations-modal]');
        if (btn) _openCitationsModal(btn.dataset.citationsModal);
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

    const visiblePubs = _filteredPublications();
    if (_activeId && !visiblePubs.some(pub => pub.id === _activeId)) {
        _activeId = null;
    }

    if (visiblePubs.length === 0) {
        wrap.innerHTML = _publicationFilter === 'pending-review'
            ? _buildQueueEmpty()
            : _buildEmpty();
        return;
    }

    const start    = (_page - 1) * PAGE_SIZE;
    const pagePubs = visiblePubs.slice(start, start + PAGE_SIZE);
    const total    = visiblePubs.length;
    const pages    = Math.ceil(total / PAGE_SIZE);

    const table = document.createElement('table');
    table.className = 'app-ws-pubs__table';
    table.setAttribute('role', 'grid');
    const selectablePagePubs = pagePubs.filter(pub => _isPending(pub.id));
    const allPageSelected = selectablePagePubs.length > 0 && selectablePagePubs.every(pub => _selectedPendingIds.has(pub.id));
    table.innerHTML = `
        <colgroup>
          <col class="app-ws-pubs__col-select">
          <col class="app-ws-pubs__col-title">
          <col class="app-ws-pubs__col-year">
          <col class="app-ws-pubs__col-type">
          <col class="app-ws-pubs__col-venue">
          <col class="app-ws-pubs__col-cites">
          <col class="app-ws-pubs__col-actions">
        </colgroup>
        <thead>
          <tr>
            <th scope="col" class="app-ws-pubs__col-select">
              <input type="checkbox"
                     class="app-ws-pubs__select-all"
                     aria-label="${t('workspace.pubs.selectAllPending')}"
                     data-select-all-pending
                     ${allPageSelected ? 'checked' : ''}
                     ${selectablePagePubs.length === 0 ? 'disabled' : ''}>
            </th>
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

    table.querySelector('[data-select-all-pending]')?.addEventListener('change', e => {
        if (e.target.checked) {
            selectablePagePubs.forEach(pub => _selectedPendingIds.add(pub.id));
        } else {
            selectablePagePubs.forEach(pub => _selectedPendingIds.delete(pub.id));
        }
        _renderPage();
        _refreshReviewSummary();
    });

    // Pagination
    if (pages > 1) {
        const paginationEl = document.createElement('div');
        paginationEl.innerHTML = buildPaginationHtml({ page: _page, total, pageSize: PAGE_SIZE, label: t('workspace.pubs.pagination') });
        wrap.appendChild(paginationEl.firstElementChild);
        wirePaginationClicks(wrap, (newPage) => {
            _page     = newPage;
            _activeId = null;
            _renderPage();
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
    const reviewState = _reviewState(pub.id);
    const suspiciousState = _suspiciousState(pub.id);
    const reviewBadge = _buildReviewBadge(reviewState);
    const reviewSummary = _buildReviewSummaryText(pub, reviewState);
    const suspiciousBadge = _buildSuspiciousBadge(suspiciousState);
    const pending = _isPending(pub.id);
    const selected = pending && _selectedPendingIds.has(pub.id);
    const recommendationBadge = _buildRecommendationBadge(pub.id);

    const tr = document.createElement('tr');
    tr.className = 'app-ws-pubs__row';
    tr.dataset.pubId = pub.id;
    if (_activeId === pub.id) tr.classList.add('app-ws-pubs__row--active');
    if (selected) tr.classList.add('app-ws-pubs__row--selected');

    tr.innerHTML =
        `<td class="app-ws-pubs__col-select">` +
            (pending
                ? `<input type="checkbox"
                          class="app-ws-pubs__row-select"
                          aria-label="Select publication ${_esc(pub.title ?? '')}"
                          data-select-pending="${_esc(pub.id)}"
                          ${selected ? 'checked' : ''}>`
                : `<span class="app-ws-pubs__row-select-placeholder" aria-hidden="true"></span>`) +
        `</td>` +
        `<td class="app-ws-pubs__col-title">` +
            `<span class="app-ws-pubs__title">${_esc(pub.title ?? '(untitled)')}</span>` +
            (reviewSummary
                ? `<span class="app-ws-pubs__title-meta">${_esc(reviewSummary)}</span>`
                : '') +
        `</td>` +
        `<td class="app-ws-pubs__col-year">${_esc(year)}</td>` +
        `<td class="app-ws-pubs__col-type">` +
            `<span class="app-ws-pubs__type-badge ${badgeCls}">${_esc(typeLabel)}</span>` +
            reviewBadge +
            suspiciousBadge +
            recommendationBadge +
            _buildMergeBadge(pub.id) +
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
        if (e.target.closest('a') || e.target.closest('[data-select-pending]')) return;
        _toggleDetail(pub, tr);
    });

    tr.querySelector('[data-select-pending]')?.addEventListener('change', e => {
        if (e.target.checked) {
            _selectedPendingIds.add(pub.id);
        } else {
            _selectedPendingIds.delete(pub.id);
        }
        _renderPage();
        _refreshReviewSummary();
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
    const colCount = 7;
    const detailTr = document.createElement('tr');
    detailTr.id        = 'ws-pubs-detail-row';
    detailTr.className = 'app-ws-pubs__detail-row';

    const td = document.createElement('td');
    td.setAttribute('colspan', String(colCount));

    td.innerHTML = _buildDetailPanel(pub);
    detailTr.appendChild(td);
    tr.insertAdjacentElement('afterend', detailTr);

    // Sync the trigger button's open state HERE — this is the single path every open goes
    // through (fresh click via _toggleDetail AND the re-open-after-rerender path in _appendRow).
    // Doing it only in _toggleDetail left post-confirm/reject re-opens with a downward chevron and
    // aria-expanded="false" while the panel was visibly open.
    const triggerBtn = tr.querySelector('[data-detail-btn]');
    if (triggerBtn) {
        triggerBtn.querySelector('i').className = 'fa-solid fa-chevron-up';
        triggerBtn.setAttribute('aria-expanded', 'true');
    }

    // Close button
    detailTr.querySelector('.app-ws-pubs__detail-close')?.addEventListener('click', () => {
        _closeDetail();
    });

    detailTr.querySelector('[data-confirm-authorship]')?.addEventListener('click', () => {
        _saveAuthorshipDecision(pub.id, 'confirm', detailTr);
    });
    detailTr.querySelector('[data-reject-authorship]')?.addEventListener('click', () => {
        _handleRejectAuthorship(pub.id, detailTr);
    });
    detailTr.querySelector('[data-clear-authorship]')?.addEventListener('click', () => {
        _clearAuthorshipDecision(pub.id, detailTr);
    });

    // H84 S3: manual duplicate flag
    detailTr.querySelector('[data-flag-merge]')?.addEventListener('click', e => {
        const otherId = detailTr.querySelector('[data-merge-other-select]')?.value;
        if (!otherId) {
            window.appToast?.show({ message: t('workspace.pubs.pickDuplicateFirst'), tone: 'info' });
            return;
        }
        e.target.disabled = true;
        _submitMergeFlag(pub.id, otherId, () => { e.target.disabled = false; });
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
                `<strong>${cites}</strong> ${_esc(tPlural('workspace.pubs.citations', cites).replace(String(cites), '').trim())}` +
            `</p>` +
            (previewItems ? `<ul class="app-ws-pubs__citations-list">${previewItems}</ul>` : '') +
            (moreCount > 0
                ? `<span style="font-size:0.8rem;color:var(--app-color-text-muted)">&hellip; and ${moreCount} more</span>`
                : '') +
            `<button type="button" class="app-ws-pubs__citations-link" data-citations-modal="${_esc(pub.id)}">` +
                `${_esc(t('workspace.pubs.viewAllCitations'))}` +
            `</button>`;
    }

    const reviewState = _reviewState(pub.id);
    const reviewBadge = _buildReviewBadge(reviewState);
    const reviewMeta = _buildReviewMeta(pub, reviewState);
    const rejectConfirm = _pendingRejectId === pub.id;
    const suspiciousState = _suspiciousState(pub.id);

    return `
        <div class="app-ws-pubs__detail-inner">
          <button class="app-ws-pubs__detail-close" type="button" aria-label="${t('workspace.pubs.closeDetails')}">
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
          <div class="app-ws-pubs__detail-panel">

            <div>
              <p class="app-ws-pubs__detail-section-title">Citations</p>
              ${citationsHtml}
            </div>

            <div>
              ${_buildSuspiciousDetailSection(suspiciousState)}
            </div>

            <div>
              <p class="app-ws-pubs__detail-section-title">Authorship</p>
              <div class="app-ws-pubs__authorship-panel">
                <div class="app-ws-pubs__authorship-header">
                  ${reviewBadge}
                  ${reviewMeta}
                </div>
                <p class="app-ws-pubs__authorship-body">
                  ${_authorshipBodyText(pub.id, reviewState)}
                </p>
                ${reviewState?.reason
                    ? `<p class="app-ws-pubs__authorship-note">Reason: ${_esc(reviewState.reason)}</p>`
                    : ''}
                ${rejectConfirm
                    ? `<div class="app-ws-pubs__authorship-inline-alert" role="alert">
                         Reject authorship for this publication?
                       </div>`
                    : ''}
                <div class="app-ws-pubs__authorship-actions">
                  <button class="btn btn-sm btn-outline-success" type="button" data-confirm-authorship="${_esc(pub.id)}">
                    Confirm mine
                  </button>
                  <button class="btn btn-sm ${rejectConfirm ? 'btn-danger' : 'btn-outline-danger'}" type="button" data-reject-authorship="${_esc(pub.id)}">
                    ${rejectConfirm ? t('workspace.pubs.confirmRejection') : t('workspace.pubs.rejectAuthorship')}
                  </button>
                  <button class="btn btn-sm btn-link px-0" type="button" data-clear-authorship="${_esc(pub.id)}">
                    Clear decision
                  </button>
                </div>
                <span class="app-ws-pubs__authorship-feedback" role="status" aria-live="polite"></span>
              </div>
              ${_buildMergeFlagSection(pub)}
            </div>

          </div>
        </div>`;
}

/** H84 S3: manual "flag as duplicate of…" — a picker over the researcher's OWN other publications.
 *  The auto-suggest banner covers exact-title pairs; this catches the ones it can't see. */
function _buildMergeFlagSection(pub) {
    if (_mergeRequestState(pub.id) === 'PENDING') {
        return `<p class="app-ws-pubs__detail-section-title" style="margin-top:0.75rem;">Duplicate</p>
                <p style="font-size:0.8rem;" class="text-muted">Merge requested — awaiting admin approval.</p>`;
    }
    const others = _allPubs
        .filter(p => p.id !== pub.id)
        .slice()
        .sort((a, b) => (a.title ?? '').localeCompare(b.title ?? ''));
    if (!others.length) return '';
    const options = others.map(p => {
        const year = p.coverDate ? ` (${p.coverDate.substring(0, 4)})` : '';
        return `<option value="${_esc(p.id)}">${_esc((p.title ?? p.id) + year)}</option>`;
    }).join('');
    return `
        <p class="app-ws-pubs__detail-section-title" style="margin-top:0.75rem;">Duplicate?</p>
        <div style="display:flex; gap:0.4rem; align-items:center; flex-wrap:wrap;">
          <select class="form-control form-control-sm" data-merge-other-select style="max-width:20rem;">
            <option value="">Same paper as…</option>
            ${options}
          </select>
          <button type="button" class="btn btn-sm btn-outline-primary" data-flag-merge="${_esc(pub.id)}">
            Flag as duplicate
          </button>
        </div>
        <p class="text-muted" style="font-size:0.75rem; margin:0.25rem 0 0;">
          An admin reviews the request and combines the two records (citations are merged, nothing is lost).
        </p>`;
}

/**
 * H70: if a decision hits the affiliation-confirmation gate (409 requiresOnboarding), route the researcher
 * into the onboarding wizard instead of showing a generic error. Returns true when it handled the response.
 */
async function _routedToOnboarding(res) {
    if (res.status !== 409) return false;
    let body = null;
    try { body = await res.clone().json(); } catch (_) { body = null; }
    if (body?.requiresOnboarding) {
        window.appToast?.show({ message: t('workspace.pubs.onboardingFirst'), tone: 'info' });
        window.appWorkspaceOnboarding?.open();
        return true;
    }
    return false;
}

function _saveAuthorshipDecision(pubId, action, detailTr) {
    const feedback = detailTr.querySelector('.app-ws-pubs__authorship-feedback');
    const endpoint = action === 'confirm'
        ? `/user/workspace/publications/${encodeURIComponent(pubId)}/authorship/confirm`
        : `/user/workspace/publications/${encodeURIComponent(pubId)}/authorship/reject`;

    fetch(endpoint, {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({ reason: null }),
    })
        .then(async res => {
            if (!res.ok) {
                if (await _routedToOnboarding(res)) return null;
                throw new Error(`HTTP ${res.status}`);
            }
            return res.json();
        })
        .then(state => {
            if (!state) return;   // routed to onboarding
            _pendingRejectId = null;
            _setReviewState(pubId, state);   // recomputes _data.pendingReviewCount first
            _showDecisionToast(action, pubId);
            _refreshAfterAuthorshipDecision(pubId);
        })
        .catch(() => {
            _showAuthorshipFeedback(feedback, t('workspace.pubs.decisionSaveFailed'), true);
        });
}

/**
 * Confirm/reject acknowledgement: closes the feedback loop the silently-updating counters left open.
 * Reports how many publications are still pending so a reviewer clearing the queue feels progress, and
 * offers an Undo (the decision is reversible via the same clear-decision endpoint) — a mis-click during
 * fast queue-clearing is easy because the row leaves the list and the panel auto-advances.
 */
function _showDecisionToast(action, pubId) {
    const remaining = _data?.pendingReviewCount ?? 0;
    const lead = action === 'confirm' ? 'Confirmed' : t('workspace.pubs.removedFromList');
    const tail = remaining > 0
        ? ' ' + tPlural('workspace.pubs.stillPending', remaining)
        : ' ' + t('workspace.pubs.queueClear');
    window.appToast?.show({
        message: lead + tail,
        tone: 'success',
        duration: 7000,   // longer than the 4s default so the Undo is catchable
        actionLabel: 'Undo',
        onAction: () => _undoAuthorshipDecision(pubId),
    });
}

/** Reverts a confirm/reject back to pending (the clear-decision endpoint) from the toast's Undo action. */
function _undoAuthorshipDecision(pubId) {
    fetch(`/user/workspace/publications/${encodeURIComponent(pubId)}/authorship`, {
        method: 'DELETE',
        headers: postJsonHeaders(),
    })
        .then(async res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(state => {
            _setReviewState(pubId, state);
            window.appToast?.show({ message: t('workspace.pubs.decisionUndone'), tone: 'info' });
            _renderAll();
        })
        .catch(() => {
            window.appToast?.show({ message: t('workspace.pubs.undoFailed'), tone: 'error' });
        });
}

function _handleRejectAuthorship(pubId, detailTr) {
    if (_pendingRejectId !== pubId) {
        _pendingRejectId = pubId;
        _rerenderActiveDetail(pubId);
        return;
    }
    _saveAuthorshipDecision(pubId, 'reject', detailTr);
}

function _clearAuthorshipDecision(pubId, detailTr) {
    const feedback = detailTr.querySelector('.app-ws-pubs__authorship-feedback');
    fetch(`/user/workspace/publications/${encodeURIComponent(pubId)}/authorship`, {
        method: 'DELETE',
        headers: postJsonHeaders(),
    })
        .then(async res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(state => {
            _pendingRejectId = null;
            _setReviewState(pubId, state);
            window.appToast?.show({ message: t('workspace.pubs.decisionCleared'), tone: 'success' });
            _refreshAfterAuthorshipDecision(pubId);
        })
        .catch(() => {
            _showAuthorshipFeedback(feedback, t('workspace.pubs.decisionClearFailed'), true);
        });
}

function _showAuthorshipFeedback(feedback, message, isError) {
    if (!feedback) return;
    feedback.textContent = message;
    feedback.classList.toggle('app-ws-pubs__authorship-feedback--error', Boolean(isError));
    feedback.classList.add('app-ws-pubs__authorship-feedback--visible');
    if (!isError) {
        setTimeout(() => feedback.classList.remove('app-ws-pubs__authorship-feedback--visible'), 2500);
    }
}

function _runBulkAuthorshipDecision(action) {
    const publicationIds = [..._selectedPendingIds].filter(pubId => _isPending(pubId));
    if (publicationIds.length === 0) {
        return;
    }

    fetch('/user/workspace/publications/authorship/bulk', {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({
            publicationIds,
            action,
            reason: null,
        }),
    })
        .then(async res => {
            const body = await res.json().catch(() => null);
            if (!res.ok) {
                if (body?.requiresOnboarding) {
                    window.appToast?.show({ message: t('workspace.pubs.onboardingFirst'), tone: 'info' });
                    window.appWorkspaceOnboarding?.open();
                    return null;
                }
                const message = body?.failures?.[0]?.message ?? `HTTP ${res.status}`;
                throw new Error(message);
            }
            return body;
        })
        .then(body => {
            if (!body) return;   // routed to onboarding
            const updatedStates = Array.isArray(body.updatedStates) ? body.updatedStates : [];
            const failures = Array.isArray(body.failures) ? body.failures : [];
            const succeededIds = Array.isArray(body.succeededIds) ? body.succeededIds : [];

            updatedStates.forEach(state => _setReviewState(state.publicationId, state));
            succeededIds.forEach(pubId => _selectedPendingIds.delete(pubId));

            if (failures.length > 0) {
                _selectedPendingIds = new Set(failures.map(failure => failure.publicationId).filter(Boolean));
            }

            const successLabel = action === 'CONFIRM' ? 'confirmed' : 'rejected';
            const bulkMsg = failures.length > 0
                ? `${succeededIds.length} ${successLabel}, ${failures.length} failed`
                : `${succeededIds.length} publication${succeededIds.length === 1 ? '' : 's'} ${successLabel}.`;
            window.appToast?.show({ message: bulkMsg, tone: failures.length > 0 ? 'warning' : 'success' });
            _activeId = null;
            _renderAll();
        })
        .catch(err => {
            window.appToast?.show({ message: err.message ?? t('workspace.pubs.bulkReviewFailed'), tone: 'error' });
            _renderAll();
        });
}

function _reviewState(pubId) {
    return _data?.authorshipReviewStateByPublicationId?.[pubId] ?? { status: 'PENDING', reason: null, updatedAt: null };
}

function _suspiciousState(pubId) {
    return _data?.suspiciousAuthorshipByPublicationId?.[pubId] ?? null;
}

function _setReviewState(pubId, state) {
    if (!_data) return;
    if (!_data.authorshipReviewStateByPublicationId) _data.authorshipReviewStateByPublicationId = {};
    _data.authorshipReviewStateByPublicationId[pubId] = state;
    _data.pendingReviewCount = _allPubs.filter(pub => _isPending(pub.id)).length;
    _data.suspiciousPendingCount = _allPubs.filter(pub => _isPendingSuspicious(pub.id)).length;
    _data.recommendedPendingCount = Math.max(0, _data.pendingReviewCount - _data.suspiciousPendingCount);

    // Keep the overview's "Publications" stat (effective = known minus rejected) in sync without a reload.
    const effectiveCount = _allPubs.filter(pub => _reviewState(pub.id)?.status !== 'REJECTED').length;
    document.dispatchEvent(new CustomEvent('ws-publications-reviewed', { detail: { effectiveCount } }));
}

function _buildReviewBadge(state) {
    const status = state?.status ?? 'PENDING';
    const text = status === 'CONFIRMED' ? 'Confirmed' : status === 'REJECTED' ? 'Rejected' : t('workspace.pubs.pendingReview');
    const cls = status === 'CONFIRMED'
        ? 'app-ws-pubs__review-badge--confirmed'
        : status === 'REJECTED'
            ? 'app-ws-pubs__review-badge--rejected'
            : 'app-ws-pubs__review-badge--pending';
    return `<span class="app-ws-pubs__review-badge ${cls}">${_esc(text)}</span>`;
}

function _buildReviewMeta(pub, state) {
    const summary = _buildReviewSummaryText(pub, state);
    if (!summary) return '';
    return `<span class="app-ws-pubs__authorship-meta">${_esc(summary)}</span>`;
}

function _buildSuspiciousBadge(state) {
    if (!state?.flags?.length) return '';
    return `<span class="app-ws-pubs__suspicious-badge">Needs review</span>`;
}

function _buildRecommendationBadge(pubId) {
    if (!_isRecommendedPending(pubId)) return '';
    return `<span class="app-ws-pubs__recommended-badge">Recommended accept</span>`;
}

function _buildReviewSummaryText(pub, state) {
    const importLineage = _buildImportLineage(pub);
    const updatedLabel = _formatReviewDate(state?.updatedAt);
    if (state?.status === 'CONFIRMED') {
        return updatedLabel
            ? `${importLineage}, locally confirmed on ${updatedLabel}`
            : `${importLineage}, locally confirmed`;
    }
    if (state?.status === 'REJECTED') {
        return updatedLabel
            ? `${importLineage}, locally rejected on ${updatedLabel}`
            : `${importLineage}, locally rejected`;
    }
    return importLineage;
}

function _buildImportLineage(pub) {
    const sources = [];
    if (pub?.eid) sources.push('Scopus');
    if (pub?.wosId) sources.push('WoS');
    if (sources.length === 0) {
        return t('workspace.pubs.importedLinkage');
    }
    if (sources.length === 1) {
        return t('workspace.pubs.importedFrom', sources[0]);
    }
    if (sources.length === 2) {
        return t('workspace.pubs.importedFromTwo', sources[0], sources[1]);
    }
    return `Imported from ${sources.slice(0, -1).join(', ')}, and ${sources[sources.length - 1]}`;
}

function _formatReviewDate(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleDateString();
}

function _authorshipBodyText(pubId, state) {
    if (state?.status === 'CONFIRMED') {
        return t('workspace.pubs.confirmedNote');
    }
    if (state?.status === 'REJECTED') {
        return 'This publication is marked as not yours. It stays visible here for review, but will not count in scoring.';
    }
    if (_isPendingSuspicious(pubId)) {
        return 'This publication is still pending review. Confirm it if it is yours, or reject it if Scopus linked it incorrectly.';
    }
    if (_isRecommendedPending(pubId)) {
        return 'This publication is pending review, but current identity and affiliation checks do not show any mismatch. It is recommended for acceptance if it is yours.';
    }
    return 'This publication is still pending review. Confirm it if it is yours, or reject it if Scopus linked it incorrectly.';
}

function _rerenderActiveDetail(pubId) {
    const pub = _allPubs.find(p => p.id === pubId);
    if (!pub) return;
    // _renderPage rebuilds every row and _appendRow re-opens the active row's detail with the
    // current state (e.g. the two-step reject's "Confirm rejection"). The previous _closeDetail() +
    // _toggleDetail() path was self-defeating: _toggleDetail saw _activeId === pub.id and immediately
    // collapsed the panel, so clicking "Reject authorship" hid the detail instead of advancing the step.
    _activeId = pubId;
    _renderPage();
}

function _refreshAfterAuthorshipDecision(pubId) {
    const nextVisibleId = _nextVisiblePublicationId(pubId);
    _selectedPendingIds.delete(pubId);
    if (_publicationFilter === 'pending-review' && !_isPending(pubId)) {
        _activeId = nextVisibleId;
    } else {
        _activeId = pubId;
    }
    _renderAll();
}

function _wireReviewSummaryEvents() {
    _mount.querySelectorAll('[data-publication-filter]').forEach(btn => {
        btn.addEventListener('click', () => {
            const nextFilter = btn.dataset.publicationFilter === 'pending-review' ? 'pending-review' : 'all';
            if (_publicationFilter === nextFilter) return;
            _publicationFilter = nextFilter;
            _page = 1;
            _activeId = null;
            _renderAll();
        });
    });
    _mount.querySelector('[data-bulk-confirm]')?.addEventListener('click', () => {
        _runBulkAuthorshipDecision('CONFIRM');
    });
    _mount.querySelector('[data-bulk-reject]')?.addEventListener('click', () => {
        _runBulkAuthorshipDecision('REJECT');
    });
    _mount.querySelector('[data-bulk-clear-selection]')?.addEventListener('click', () => {
        _selectedPendingIds.clear();
        _renderAll();
    });
}

/**
 * Re-render just the review-summary/triage bar in place (so the bulk-action bar appears/updates as soon as the
 * selection changes) without a full _renderAll. The filter + bulk buttons use direct listeners, so re-wire them.
 */
function _refreshReviewSummary() {
    const existing = _mount.querySelector('.app-ws-pubs__triage-bar');
    if (!existing) return;
    const tmp = document.createElement('div');
    tmp.innerHTML = _buildReviewSummary();
    const next = tmp.firstElementChild;
    if (!next) return;
    existing.replaceWith(next);
    _wireReviewSummaryEvents();
}

function _buildReviewSummary() {
    const counts = _reviewCounts();
    const pendingCount = _pendingReviewCount();
    const suspiciousCount = _pendingSuspiciousCount();
    const recommendedCount = _recommendedPendingCount();
    const selectedCount = _selectedPendingIds.size;
    const allActive = _publicationFilter === 'all';
    const queueActive = _publicationFilter === 'pending-review';
    const bulkBar = selectedCount > 0
        ? `<div class="app-ws-pubs__bulk-bar" role="region" aria-label="${t('workspace.pubs.bulkActions')}">
            <span class="app-ws-pubs__bulk-count">${selectedCount} selected</span>
            <button type="button" class="btn btn-sm btn-outline-success" data-bulk-confirm>Confirm selected</button>
            <button type="button" class="btn btn-sm btn-outline-danger" data-bulk-reject>Reject selected</button>
            <button type="button" class="btn btn-sm btn-link px-0" data-bulk-clear-selection>Clear selection</button>
          </div>`
        : '';
    return `
        <section class="app-ws-pubs__triage-bar" aria-label="${t('workspace.pubs.reviewQueue')}">
          <div class="app-ws-pubs__review-breakdown" aria-label="${t('workspace.pubs.reviewBreakdown')}">
            <span class="app-ws-pubs__review-stat"><strong>${counts.known}</strong> known</span>
            <span class="app-ws-pubs__review-stat app-ws-pubs__review-stat--confirmed"><strong>${counts.confirmed}</strong> confirmed</span>
            <span class="app-ws-pubs__review-stat app-ws-pubs__review-stat--rejected"><strong>${counts.rejected}</strong> rejected</span>
            <span class="app-ws-pubs__review-stat app-ws-pubs__review-stat--pending"><strong>${counts.pending}</strong> pending</span>
          </div>
          <div class="app-ws-pubs__triage-summary">
            <span class="app-ws-pubs__triage-label">Pending Review</span>
            <strong class="app-ws-pubs__triage-count">${pendingCount}</strong>
            <span class="app-ws-pubs__triage-body">pending publication${pendingCount === 1 ? '' : 's'} need authorship review</span>
            <span class="app-ws-pubs__triage-meta">${suspiciousCount} suspicious, ${recommendedCount} recommended accept</span>
          </div>
          <div class="app-ws-pubs__triage-filters" role="tablist" aria-label="${t('workspace.pubs.reviewFilters')}">
            <button type="button" class="app-ws-pubs__triage-filter ${allActive ? 'app-ws-pubs__triage-filter--active' : ''}" data-publication-filter="all" aria-pressed="${allActive}">
              All
            </button>
            <button type="button" class="app-ws-pubs__triage-filter ${queueActive ? 'app-ws-pubs__triage-filter--active' : ''}" data-publication-filter="pending-review" aria-pressed="${queueActive}">
              Pending Review
            </button>
          </div>
          ${bulkBar}
        </section>`;
}

function _pendingReviewCount() {
    return typeof _data?.pendingReviewCount === 'number'
        ? _data.pendingReviewCount
        : _allPubs.filter(pub => _isPending(pub.id)).length;
}

// Explicit authorship-review breakdown over the known publication set.
function _reviewCounts() {
    let confirmed = 0, rejected = 0, pending = 0;
    for (const pub of _allPubs) {
        const status = _reviewState(pub.id)?.status;
        if (status === 'CONFIRMED') confirmed++;
        else if (status === 'REJECTED') rejected++;
        else pending++;
    }
    return { known: _allPubs.length, confirmed, rejected, pending };
}

function _pendingSuspiciousCount() {
    if (typeof _data?.suspiciousPendingCount === 'number') return _data.suspiciousPendingCount;
    if (!_data?.suspiciousAuthorshipByPublicationId) return 0;
    return Object.keys(_data.suspiciousAuthorshipByPublicationId).filter(_isPendingSuspicious).length;
}

function _recommendedPendingCount() {
    if (typeof _data?.recommendedPendingCount === 'number') return _data.recommendedPendingCount;
    return Math.max(0, _pendingReviewCount() - _pendingSuspiciousCount());
}

function _buildSuspiciousDetailSection(state) {
    if (!state?.flags?.length) {
        return '';
    }
    const reasons = state.flags.map(flag =>
        `<li class="app-ws-pubs__triage-reason-item"><span class="app-ws-pubs__triage-reason-code">${_esc(_formatFlagCode(flag.code))}</span><span>${_esc(flag.message ?? '')}</span></li>`
    ).join('');
    return `
      <p class="app-ws-pubs__detail-section-title">Why this needs review</p>
      <div class="app-ws-pubs__triage-panel">
        <ul class="app-ws-pubs__triage-reason-list">${reasons}</ul>
      </div>`;
}

function _formatFlagCode(code) {
    if (!code) return t('workspace.pubs.needsReview');
    return code.toLowerCase().split('_').map(part => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');
}

function _filteredPublications() {
    let pubs = _allPubs;
    if (_publicationFilter === 'pending-review') {
        pubs = pubs.filter(pub => _isPending(pub.id));
    }
    if (_searchQuery) {
        pubs = pubs.filter(pub => pub.title?.toLowerCase().includes(_searchQuery));
    }
    return pubs;
}

function _isPending(pubId) {
    return _reviewState(pubId).status === 'PENDING';
}

function _isRecommendedPending(pubId) {
    return _isPending(pubId) && !_isPendingSuspicious(pubId);
}

function _isPendingSuspicious(pubId) {
    return _isPending(pubId) && Boolean(_suspiciousState(pubId)?.flags?.length);
}

function _nextVisiblePublicationId(currentId) {
    const visiblePubs = _filteredPublications();
    const currentIndex = visiblePubs.findIndex(pub => pub.id === currentId);
    if (currentIndex === -1) {
        return visiblePubs[0]?.id ?? null;
    }
    return visiblePubs[currentIndex + 1]?.id ?? visiblePubs[currentIndex - 1]?.id ?? null;
}

function _buildQueueEmpty() {
    return `
      <div class="app-ws-pubs__queue-empty">
        <div class="app-ws-pubs__queue-empty-icon"><i class="fa-solid fa-shield-check" aria-hidden="true"></i></div>
        <h3 class="app-ws-pubs__queue-empty-title">No pending publications need review</h3>
        <p class="app-ws-pubs__queue-empty-body">Pending authorship items will appear here until they are confirmed or rejected.</p>
      </div>`;
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
    // Citations modal next
    const citModal = document.getElementById('ws-citations-modal');
    if (citModal && !citModal.hidden) {
        e.stopPropagation();
        _closeCitationsModal();
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

// ── Citations modal ───────────────────────────────────────────────────────────

function _openCitationsModal(pubId) {
    const modal   = document.getElementById('ws-citations-modal');
    const body    = document.getElementById('ws-citations-modal-body');
    const pubEl   = document.getElementById('ws-citations-modal-pub');
    const totalEl = document.getElementById('ws-citations-modal-total');
    if (!modal || !body) return;

    // Show immediately with skeleton, then fetch
    body.innerHTML = `<div style="padding:1.5rem">
        <div class="app-skeleton-block" style="height:2rem;margin-bottom:.75rem;border-radius:.4rem"></div>
        <div class="app-skeleton-block" style="height:2rem;margin-bottom:.75rem;border-radius:.4rem"></div>
        <div class="app-skeleton-block" style="height:2rem;border-radius:.4rem"></div>
    </div>`;
    if (pubEl)   pubEl.textContent   = '';
    if (totalEl) totalEl.textContent = '';

    _setModalState(modal, true);

    fetch(`/user/workspace/publications/${encodeURIComponent(pubId)}/citations`, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(data => _renderCitationsModal(data, pubEl, body, totalEl))
        .catch(err => {
            body.innerHTML = `<p style="padding:1.5rem;color:var(--app-color-danger);font-size:.88rem">
                Failed to load citations: ${_esc(err.message)}</p>`;
        });
}

function _renderCitationsModal(data, pubEl, body, totalEl) {
    const citations = Array.isArray(data.citations) ? data.citations : [];
    const forumMap  = data.forumMap  ?? {};

    if (pubEl)   pubEl.textContent   = data.publication?.title ?? '';
    if (totalEl) totalEl.textContent = `${citations.length} citing publication${citations.length !== 1 ? 's' : ''}`;

    if (citations.length === 0) {
        body.innerHTML = `<p style="padding:1.5rem;font-size:.88rem;color:var(--app-color-text-muted)">No citations found.</p>`;
        return;
    }

    const rows = citations.map(c => {
        const year  = (c.coverDate ?? '').slice(0, 4) || '—';
        const venue = forumMap[c.forum]?.publicationName ?? c.forum ?? '—';
        const cites = c.citedbyCount ?? c.citedByCount ?? 0;
        return `<tr>
          <td style="max-width:22rem">
            <span style="font-size:.85rem;font-weight:600;color:var(--app-color-text-strong)">${_esc(c.title ?? '—')}</span>
          </td>
          <td style="white-space:nowrap;font-size:.82rem;color:var(--app-color-text-muted)">${_esc(year)}</td>
          <td style="font-size:.82rem;color:var(--app-color-text-muted);max-width:14rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${_esc(venue)}</td>
          <td style="white-space:nowrap;font-size:.82rem;text-align:right">${cites > 0 ? cites : '—'}</td>
        </tr>`;
    }).join('');

    body.innerHTML = `<table style="width:100%;border-collapse:collapse;font-size:.83rem">
        <thead>
          <tr style="background:color-mix(in srgb,var(--app-color-card-bg-muted) 80%,transparent)">
            <th style="padding:.5rem .75rem;text-align:left;font-size:.72rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--app-color-text-muted);border-bottom:1px solid var(--app-color-border)">Title</th>
            <th style="padding:.5rem .75rem;text-align:left;font-size:.72rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--app-color-text-muted);border-bottom:1px solid var(--app-color-border);white-space:nowrap">Year</th>
            <th style="padding:.5rem .75rem;text-align:left;font-size:.72rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--app-color-text-muted);border-bottom:1px solid var(--app-color-border)">Venue</th>
            <th style="padding:.5rem .75rem;text-align:right;font-size:.72rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--app-color-text-muted);border-bottom:1px solid var(--app-color-border);white-space:nowrap">Cited&nbsp;by</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
    </table>`;

    // Stripe rows
    body.querySelectorAll('tbody tr').forEach((tr, i) => {
        tr.style.background = i % 2 === 0
            ? 'transparent'
            : 'color-mix(in srgb,var(--app-color-card-bg-muted) 55%,transparent)';
    });
    body.querySelectorAll('tbody td').forEach(td => {
        td.style.padding = '.45rem .75rem';
        td.style.borderBottom = '1px solid var(--app-color-border)';
        td.style.verticalAlign = 'middle';
    });
    body.querySelectorAll('tbody tr:last-child td').forEach(td => {
        td.style.borderBottom = '0';
    });
}

function _closeCitationsModal() {
    const modal = document.getElementById('ws-citations-modal');
    if (modal) _setModalState(modal, false);
}

function _setModalState(modal, open) {
    // Reuse or create the shared backdrop (same pattern as legacyInteractions.js)
    let backdrop = document.querySelector('[data-app-modal-backdrop]');
    if (!backdrop) {
        backdrop = document.createElement('div');
        backdrop.className = 'app-modal-backdrop';
        backdrop.setAttribute('data-app-modal-backdrop', '');
        backdrop.hidden = true;
        document.body.appendChild(backdrop);
    }

    modal.classList.toggle('show', open);
    modal.hidden           = !open;
    modal.setAttribute('aria-hidden', open ? 'false' : 'true');
    backdrop.hidden        = !open;
    backdrop.classList.toggle('show', open);
    document.body.classList.toggle('app-modal-open', open);

    if (open) {
        // Close on backdrop click
        backdrop.onclick = () => _closeCitationsModal();
        // Wire dismiss buttons
        modal.querySelectorAll('[data-dismiss="modal"]').forEach(btn => {
            btn.onclick = () => _closeCitationsModal();
        });
        modal.querySelector('.close')?.focus();
    } else {
        backdrop.onclick = null;
    }
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
        window.appConfirmDialog?.open({
            title: t('workspace.pubs.wizard.discardTitle'),
            body: t('workspace.pubs.wizard.discardBody'),
            confirmLabel: 'Discard',
            tone: 'danger',
            onConfirm: () => _closeWizard(true),
        });
        return;
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
        { label: t('workspace.pubs.wizard.stepForum') },
        { label: t('workspace.pubs.wizard.stepAuthors') },
        { label: t('workspace.pubs.wizard.stepDetails') },
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
        <div class="app-ws-pubs__wizard" role="region" aria-label="${_esc(t('workspace.pubs.wizard.aria'))}">
          <div class="app-ws-pubs__wizard-header">
            <h2 class="app-ws-pubs__wizard-title">${_esc(t('workspace.pubs.wizard.heading'))}</h2>
            <button class="app-ws-pubs__wizard-close" type="button" aria-label="${_esc(t('workspace.pubs.wizard.close'))}" id="ws-pubs-wiz-close">
              <i class="fa-solid fa-xmark" aria-hidden="true"></i>
            </button>
          </div>
          <div class="app-ws-pubs__wizard-body">
            <div class="app-ws-pubs__wizard-steps" role="list" aria-label="${_esc(t('workspace.pubs.wizard.steps'))}">
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
        ? `<li style="padding:0.5rem;font-size:0.85rem;color:var(--app-color-text-muted)">${_esc(t('workspace.pubs.wizard.noForumMatch'))}</li>`
        : '';

    // Prefill new-forum fields if _wNewForum is set
    const nf = _wNewForum ?? {};

    const aggrOptions = AGGREGATION_TYPES.map(opt =>
        `<option value="${_esc(opt.value)}" ${(nf.aggregationType ?? '') === opt.value ? 'selected' : ''}>${_esc(t(opt.key))}</option>`
    ).join('');

    const newForumHtml = `
        <details class="app-ws-pubs__wiz-new-forum" id="ws-pubs-wiz-new-forum-details" ${_wForumId === null && _wNewForum ? 'open' : ''}>
          <summary>${_esc(t('workspace.pubs.wizard.createForumSummary'))}</summary>
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
              <div class="app-ws-pubs__wiz-sense-badge" id="ws-wiz-sense-badge" hidden></div>
            </div>
          </div>
        </details>`;

    return `
        <input class="app-ws-pubs__wiz-search-input" id="ws-pubs-wiz-forum-search"
               type="search" placeholder="${t('workspace.pubs.wizard.searchForums')}" value="${_esc(_wForumFilter)}"
               autocomplete="off" aria-label="${t('workspace.pubs.wizard.searchForumsAria')}"/>
        <ul class="app-ws-pubs__wiz-forum-list" role="listbox" aria-label="${t('workspace.pubs.wizard.forumResults')}" id="ws-pubs-wiz-forum-list">
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
        `<option value="${_esc(o.value)}" ${o.value === _wSubtype ? 'selected' : ''}>${_esc(t(o.key))}</option>`
    ).join('');

    return `
        <div class="app-ws-pubs__wiz-fields">
          <div class="app-ws-pubs__wiz-field app-ws-pubs__wiz-field--full">
            <label class="app-ws-pubs__wiz-label app-ws-pubs__wiz-label--required" for="ws-wiz-title">Title</label>
            <input class="app-ws-pubs__wiz-input" id="ws-wiz-title" type="text"
                   value="${_esc(_wTitle)}" placeholder="${t('workspace.pubs.wizard.title')}"/>
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

function _updateSenseBadge(rawValue) {
    const badge = document.getElementById('ws-wiz-sense-badge');
    if (!badge) return;
    const q = (rawValue || '').trim();
    if (q.length < 2) { badge.hidden = true; badge.innerHTML = ''; return; }
    fetch(`/api/entities/sense-publishers?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' })
        .then(r => (r.ok ? r.json() : null))
        .then(m => {
            if (!m) { badge.hidden = true; return; }
            badge.innerHTML = m.matched
                ? `Detected SENSE publisher category: <strong>${_esc(m.rank)}</strong> (${_esc(m.matchedPublisher)})`
                : t('workspace.pubs.wizard.senseWarning');
            badge.hidden = false;
        })
        .catch(() => { badge.hidden = true; });
}

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

        // Live SENSE publisher badge: same cached resolution the book scorer uses, so the
        // hint always matches what scoring will decide for a book/chapter at this publisher.
        const publisherInput = document.getElementById('ws-wiz-forum-publisher');
        if (publisherInput) {
            let senseTimer = null;
            publisherInput.addEventListener('input', () => {
                clearTimeout(senseTimer);
                senseTimer = setTimeout(() => _updateSenseBadge(publisherInput.value), 250);
            });
            if (publisherInput.value.trim()) _updateSenseBadge(publisherInput.value);
        }

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
            _showWizardError(t('workspace.pubs.wizard.forumRequired'));
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
    if (!_wTitle) return t('workspace.pubs.wizard.titleRequired');
    if (!_wDate)  return t('workspace.pubs.wizard.dateRequired');
    if (!_wSubtypeDesc) return t('workspace.pubs.wizard.typeRequired');
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
        submitBtn.textContent = t('workspace.pubs.wizard.submitting');
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
            _showWizardError(err.message ?? t('workspace.pubs.wizard.submitFailed'));
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
          <a href="#" id="ws-pubs-add-btn" class="btn btn-sm btn-primary">
            <i class="fa-solid fa-plus" aria-hidden="true"></i> ${_esc(t('workspace.pubs.wizard.heading'))}
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


function _buildEmpty() {
    return `
        <div class="app-ws-pubs__empty">
          <i class="fa-solid fa-book-open app-ws-pubs__empty-icon" aria-hidden="true"></i>
          <h2 class="app-ws-pubs__empty-title">No publications yet</h2>
          <p class="app-ws-pubs__empty-body">Add your first publication to start tracking your research output.</p>
          <a href="#" id="ws-pubs-add-btn-empty" class="btn btn-sm btn-primary" style="margin-top:0.5rem">
            <i class="fa-solid fa-plus" aria-hidden="true"></i> ${_esc(t('workspace.pubs.wizard.heading'))}
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
