/**
 * workspaceProfile.js — Profile & Sync tab
 */

import { postJsonHeaders as _postHeaders } from '../shared/fetchUtils';

let _panel = null;
let _mount = null;
let _data = null;
let _editOpen = false;
let _delegateController = null;

export function initWorkspaceProfile() {
    window.appWorkspaceProfile = { init: _init };
    // Refresh the completeness card after the onboarding wizard saves/closes.
    document.addEventListener('ws-onboarding-updated', () => { if (_panel) _init(_panel); });
}

function _init(panel) {
    _panel = panel;
    _mount = panel.querySelector('[data-workspace-lazy-panel]');
    if (!_mount) return;

    const src = _mount.dataset.src;
    if (!src) return;

    _editOpen = false;
    _showSkeleton();

    fetch(src, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((r) => {
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            return r.json();
        })
        .then((data) => {
            _data = data;
            _renderAll();
        })
        .catch((err) => {
            console.error('workspace profile load failed', err);
            _mount.innerHTML = _buildError(err?.message ?? String(err ?? 'Unknown error'));
        });
}

function _showSkeleton() {
    _mount.innerHTML = `
        <div class="app-ws-prof">
          <div class="app-skeleton-block" style="height:6rem;border-radius:var(--app-radius-card)"></div>
          <div class="app-skeleton-block" style="height:8rem;border-radius:var(--app-radius-card)"></div>
          <div class="app-skeleton-block" style="height:10rem;border-radius:var(--app-radius-card)"></div>
        </div>`;
}

function _buildError(message) {
    return `<div class="app-ws-prof__empty">
      <i class="fa-solid fa-triangle-exclamation" style="font-size:2rem;color:var(--app-color-text-muted);opacity:.45"></i>
      <p style="margin:0;font-size:.88rem;color:var(--app-color-text-muted)">Failed to load profile data.</p>
      ${message ? `<p style="margin:.35rem 0 0 0;font-size:.8rem;color:var(--app-color-danger)">${_esc(message)}</p>` : ''}
      <button class="btn btn-sm btn-outline-secondary" id="ws-prof-retry-btn">Retry</button>
    </div>`;
}

function _renderAll() {
    const researcher = _data?.researcher ?? null;
    const container = document.createElement('div');
    container.className = 'app-ws-prof';

    try {
        if (!researcher) {
            container.innerHTML = _buildNoProfile();
        } else {
            container.innerHTML =
                _buildCompletenessCard(researcher, _data?.completeness) +
                _buildProfileSection(researcher) +
                _buildSyncSection(_data);
        }
    } catch (err) {
        console.error('workspace profile render failed', err, { data: _data });
        container.innerHTML = _buildError(err?.message ?? String(err ?? 'Unknown error'));
    }

    _mount.innerHTML = '';
    _mount.appendChild(container);
    _wireEvents();

    if (researcher && (_data?.completeness ?? 0) === 0) {
        _openEdit();
    }
}

function _buildCompletenessCard(researcher, completeness) {
    // H70: drive the card from the onboarding wizard status (5 steps), not the legacy 4-field metric, so the
    // tab and the wizard agree. Incomplete steps link into the wizard rather than the inline edit form.
    const onboarding = _data?.onboarding ?? null;
    if (onboarding) {
        const pct = Math.min(100, Math.max(0, onboarding.percentComplete ?? 0));
        const done = new Set(onboarding.completedSteps ?? []);
        const steps = [
            { key: 'IDENTITY_IDS', label: 'Author identifiers' },
            { key: 'ORCID', label: 'ORCID linked' },
            { key: 'AFFILIATIONS', label: 'Affiliations confirmed' },
            { key: 'AUTHOR_MATCH', label: 'Author record matched' },
            { key: 'PUBLICATION_CLAIM', label: 'Publications reviewed' }
        ];
        const items = steps.map((s) => {
            if (done.has(s.key)) {
                return `<li class="app-ws-prof__checklist-item">
                  <span class="app-ws-prof__checklist-icon--done"><i class="fa-solid fa-check"></i></span>
                  <span class="app-ws-prof__checklist-label--done">${_esc(s.label)}</span>
                </li>`;
            }
            return `<li class="app-ws-prof__checklist-item">
              <span class="app-ws-prof__checklist-icon--missing"><i class="fa-solid fa-circle-exclamation"></i></span>
              <span class="app-ws-prof__checklist-label--missing">${_esc(s.label)}</span>
              <button type="button" class="app-ws-prof__checklist-link" data-onboarding-open>Continue &rsaquo;</button>
            </li>`;
        }).join('');
        const fillClass = pct >= 100
            ? 'app-ws-prof__progress-fill app-ws-prof__progress-fill--complete'
            : 'app-ws-prof__progress-fill';
        const cta = onboarding.complete ? ''
            : `<button type="button" class="btn btn-sm btn-primary mt-2" data-onboarding-open>Continue setup</button>`;
        return `<div class="app-ws-prof__completeness">
          <div class="app-ws-prof__completeness-header">
            <p class="app-ws-prof__completeness-title">Onboarding</p>
            <span class="app-ws-prof__completeness-score">${pct}%<span class="app-ws-prof__completeness-score-label">complete</span></span>
          </div>
          <div class="app-ws-prof__progress-track">
            <div class="${fillClass}" style="width:${pct}%"></div>
          </div>
          <ul class="app-ws-prof__checklist">${items}</ul>
          ${cta}
        </div>`;
    }

    // Legacy fallback (onboarding status absent).
    const pct = Math.min(100, Math.max(0, completeness ?? 0));
    const fillClass = pct >= 100
        ? 'app-ws-prof__progress-fill app-ws-prof__progress-fill--complete'
        : 'app-ws-prof__progress-fill';
    return `<div class="app-ws-prof__completeness">
      <div class="app-ws-prof__completeness-header">
        <p class="app-ws-prof__completeness-title">Profile completeness</p>
        <span class="app-ws-prof__completeness-score">${pct}%<span class="app-ws-prof__completeness-score-label">complete</span></span>
      </div>
      <div class="app-ws-prof__progress-track">
        <div class="${fillClass}" style="width:${pct}%"></div>
      </div>
    </div>`;
}

function _buildProfileSection(researcher) {
    const scopusPills = _buildIdPills(researcher?.scopusId);
    const wosPills = _buildIdPills(researcher?.wosId);
    const currentAffiliations = _buildAffiliationPills(researcher?.currentAffiliationIds, _data?.observedAffiliations);
    const pastAffiliations = _buildAffiliationPills(researcher?.pastAffiliationIds, _data?.observedAffiliations);
    const readonlyGrid = `<div id="ws-prof-readonly-grid" class="app-ws-prof__info-grid">
      <span class="app-ws-prof__info-label">Name</span>
      <span class="app-ws-prof__info-value">${_esc([researcher?.firstName, researcher?.lastName].filter(Boolean).join(' ') || '—')}</span>
      <span class="app-ws-prof__info-label">ORCID</span>
      <span class="app-ws-prof__info-value">${researcher?.orcid ? _esc(researcher.orcid) : '<em class="app-ws-prof__info-value--muted">Not set</em>'}</span>
      <span class="app-ws-prof__info-label">PhD award year</span>
      <span class="app-ws-prof__info-value">${researcher?.phdAwardYear ? _esc(String(researcher.phdAwardYear)) : '<em class="app-ws-prof__info-value--muted">Not set</em>'}</span>
      <span class="app-ws-prof__info-label">Scopus IDs</span>
      <span class="app-ws-prof__info-value">${scopusPills || '<em class="app-ws-prof__info-value--muted">None</em>'}</span>
      <span class="app-ws-prof__info-label">WoS IDs</span>
      <span class="app-ws-prof__info-value">${wosPills || '<em class="app-ws-prof__info-value--muted">None</em>'}</span>
      <span class="app-ws-prof__info-label">Current affiliations</span>
      <span class="app-ws-prof__info-value">${currentAffiliations || '<em class="app-ws-prof__info-value--muted">None confirmed</em>'}</span>
      <span class="app-ws-prof__info-label">Past affiliations</span>
      <span class="app-ws-prof__info-value">${pastAffiliations || '<em class="app-ws-prof__info-value--muted">None confirmed</em>'}</span>
    </div>`;
    const editForm = _buildEditForm(researcher, _data?.observedAffiliations, _data?.affiliationConfirmationRequired);

    return `<div class="app-ws-prof__section">
      <div class="app-ws-prof__section-header">
        <i class="fa-solid fa-user" style="color:var(--app-color-text-muted);font-size:.85rem"></i>
        <h2 class="app-ws-prof__section-title">Identity</h2>
        <button class="btn btn-sm btn-outline-secondary" id="ws-prof-edit-btn">
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
    const safeIds = _asArray(ids);
    if (safeIds.length === 0) return '';
    return `<ul class="app-ws-prof__id-list">${safeIds.map((id) =>
        `<li class="app-ws-prof__id-pill">${_esc(id)}</li>`
    ).join('')}</ul>`;
}

function _buildAffiliationPills(ids, observedAffiliations) {
    const safeIds = _asArray(ids);
    if (safeIds.length === 0) return '';
    const byId = new Map(_asArray(observedAffiliations).map((item) => [_getAffiliationId(item), item]));
    return `<ul class="app-ws-prof__id-list">${safeIds.map((id) => {
        const entry = byId.get(id);
        const name = entry?.name ?? id;
        const subtitle = [entry?.city, entry?.country].filter(Boolean).join(', ');
        return `<li class="app-ws-prof__id-pill" title="${_esc(subtitle)}">${_esc(name)}</li>`;
    }).join('')}</ul>`;
}

function _buildEditForm(researcher, observedAffiliations, affiliationConfirmationRequired) {
    const scopusRows = _asArray(researcher?.scopusId).map((id, index) =>
        _buildIdEntryRow('scopusId', id, index)
    ).join('');
    const wosRows = _asArray(researcher?.wosId).map((id, index) =>
        _buildIdEntryRow('wosId', id, index)
    ).join('');
    const affiliationScope = _buildAffiliationScopeField(researcher, observedAffiliations, affiliationConfirmationRequired);

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
        <label class="app-ws-prof__label" for="ws-prof-edit-phdYear">PhD award year</label>
        <input class="app-ws-prof__input" type="number" id="ws-prof-edit-phdYear" name="phdAwardYear"
               value="${researcher?.phdAwardYear ?? ''}" min="1900" max="2100" placeholder="optional" style="max-width:10rem">
        <small class="app-ws-prof__hint">Optional — used by evaluation criteria that count only work after your doctorate.</small>
      </div>
      <div class="app-ws-prof__field">
        <label class="app-ws-prof__label" for="ws-prof-edit-orcid">ORCID</label>
        <input class="app-ws-prof__input" type="text" id="ws-prof-edit-orcid" name="orcid"
               value="${_esc(researcher?.orcid ?? '')}" placeholder="0000-0002-1825-0097" style="max-width:20rem">
        <small class="app-ws-prof__hint">Bare or full URL (https://orcid.org/…) — drives the OpenAlex sync below.</small>
      </div>
      <div class="app-ws-prof__field" id="ws-prof-edit-scopusId">
        <label class="app-ws-prof__label">Scopus IDs</label>
        <div class="app-ws-prof__id-entries" id="ws-prof-scopus-entries">${scopusRows}</div>
        <button type="button" class="btn btn-sm btn-link app-ws-prof__add-id-btn" id="ws-prof-add-scopus-btn">
          <i class="fa-solid fa-plus"></i> Add Scopus ID
        </button>
      </div>
      <div class="app-ws-prof__field" id="ws-prof-edit-wosId">
        <label class="app-ws-prof__label">WoS IDs</label>
        <div class="app-ws-prof__id-entries" id="ws-prof-wos-entries">${wosRows}</div>
        <button type="button" class="btn btn-sm btn-link app-ws-prof__add-id-btn" id="ws-prof-add-wos-btn">
          <i class="fa-solid fa-plus"></i> Add WoS ID
        </button>
      </div>
      ${affiliationScope}
      <div class="app-ws-prof__form-actions">
        <button type="button" class="btn btn-sm btn-primary" id="ws-prof-save-btn">Save</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" id="ws-prof-cancel-btn">Cancel</button>
        <span class="app-ws-prof__form-feedback" id="ws-prof-form-feedback"></span>
      </div>
    </div>`;
}

function _buildAffiliationScopeField(researcher, observedAffiliations, affiliationConfirmationRequired) {
    const currentIds = new Set(_asArray(researcher?.currentAffiliationIds));
    const pastIds = new Set(_asArray(researcher?.pastAffiliationIds));
    const candidates = _asArray(observedAffiliations);

    if (!affiliationConfirmationRequired && candidates.length === 0 && !researcher?.affiliationsConfirmedAt) {
        return '';
    }

    const intro = researcher?.affiliationsConfirmedAt
        ? '<p class="app-ws-prof__info-value" style="margin:.35rem 0 .75rem 0">Affiliation scope confirmed. Update it whenever your profile changes.</p>'
        : '<p class="app-ws-prof__info-value" style="margin:.35rem 0 .75rem 0">Select the affiliations that belong to your researcher identity. Publication review stays blocked until you save this section.</p>';

    const rows = candidates.length === 0
        ? '<p class="app-ws-prof__task-empty">No observed affiliations found yet. Save this section to confirm that none currently apply.</p>'
        : `<div style="display:grid;gap:.5rem">${candidates.map((item) => {
            const id = _getAffiliationId(item);
            const currentChecked = currentIds.has(id);
            const pastChecked = !currentChecked && pastIds.has(id);
            const subtitle = [item?.city, item?.country].filter(Boolean).join(', ');
            return `<div class="app-ws-prof__sync-id-row" data-affiliation-scope-row data-affiliation-id="${_esc(id)}" style="display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:.75rem;align-items:center">
      <div>
        <div style="font-weight:600">${_esc(item?.name ?? id)}</div>
        <div class="app-ws-prof__info-value--muted" style="font-size:.78rem">${_esc(subtitle || id)}</div>
      </div>
      <label class="app-ws-prof__sync-mode-opt" style="margin:0"><input type="checkbox" data-affiliation-current ${currentChecked ? 'checked' : ''}> Current</label>
      <label class="app-ws-prof__sync-mode-opt" style="margin:0"><input type="checkbox" data-affiliation-past ${pastChecked ? 'checked' : ''}> Past</label>
    </div>`;
        }).join('')}</div>`;

    return `<div class="app-ws-prof__field" id="ws-prof-affiliation-scope">
      <label class="app-ws-prof__label app-ws-prof__label--required">Affiliation scope</label>
      ${intro}
      ${rows}
    </div>`;
}

function _buildIdEntryRow(field, value) {
    return `<div class="app-ws-prof__id-entry-row" data-id-field="${field}">
      <input class="app-ws-prof__input" type="text"
             name="${field}[]" value="${_esc(value)}"
             placeholder="${field === 'scopusId' ? 'Scopus author ID' : 'WoS researcher ID'}">
      <button type="button" class="app-ws-prof__remove-btn" aria-label="Remove" data-remove-id-row>
        <i class="fa-solid fa-xmark"></i>
      </button>
    </div>`;
}

function _buildSyncSection(data) {
    const scopusIds = _asArray(data?.researcher?.scopusId);
    const currentYear = new Date().getFullYear();

    let syncRows;
    if (scopusIds.length === 0) {
        syncRows = '<p class="app-ws-prof__task-empty">Add a Scopus ID to enable sync.</p>';
    } else {
        syncRows = `<ul class="app-ws-prof__sync-list">${scopusIds.map((id, idx) => `
          <li class="app-ws-prof__sync-id-row" data-sync-row>
            <span class="app-ws-prof__sync-id-label">${_esc(id)}</span>
            <div class="app-ws-prof__sync-mode-group" role="radiogroup" aria-label="Sync mode">
              <label class="app-ws-prof__sync-mode-opt">
                <input type="radio" name="sync-mode-${idx}" value="SINCE_LAST_UPDATE" checked> Since last update
              </label>
              <label class="app-ws-prof__sync-mode-opt">
                <input type="radio" name="sync-mode-${idx}" value="PERIOD"> Period
              </label>
              <label class="app-ws-prof__sync-mode-opt">
                <input type="radio" name="sync-mode-${idx}" value="FULL"> Full update
              </label>
            </div>
            <div class="app-ws-prof__sync-period" hidden>
              <label>From <input type="number" class="app-ws-prof__input app-ws-prof__sync-year"
                                  placeholder="e.g. 2017" min="1990" max="${currentYear}"></label>
              <span>–</span>
              <label>To &nbsp;<input type="number" class="app-ws-prof__input app-ws-prof__sync-year"
                                  placeholder="e.g. ${currentYear}" min="1990" max="${currentYear}"></label>
            </div>
            <div class="app-ws-prof__sync-actions">
              <button class="btn btn-sm btn-outline-primary" data-sync-type="publications" data-sync-id="${_esc(id)}">
                <i class="fa-solid fa-book-open"></i> Update Publications
              </button>
              <button class="btn btn-sm btn-outline-primary" data-sync-type="citations" data-sync-id="${_esc(id)}">
                <i class="fa-solid fa-quote-right"></i> Update Citations
              </button>
            </div>
          </li>`).join('')}</ul>`;
    }

    const pubTable = _buildTaskTable('publication-tasks', data?.pubTasks, ['Scopus ID', 'Mode', 'Status', 'Initiated', 'Executed', 'Message']);
    const citeTable = _buildTaskTable('citation-tasks', data?.citeTasks, ['Scopus ID', 'Mode', 'Status', 'Initiated', 'Executed', 'Message']);

    const orcid = (data?.researcher?.orcid ?? '').trim();
    const openAlexSection = `<div class="app-ws-prof__section">
      <div class="app-ws-prof__section-header">
        <i class="fa-solid fa-arrows-rotate" style="color:var(--app-color-text-muted);font-size:.85rem"></i>
        <h2 class="app-ws-prof__section-title">OpenAlex</h2>
      </div>
      <div class="app-ws-prof__section-body">
        <p class="app-ws-prof__task-subsection-title" style="margin:0 0 .5rem 0">
          Pull your corresponding-author and citation data from OpenAlex by ORCID${orcid ? '' : ' — add your ORCID in the profile above first'}.</p>
        <div class="app-ws-prof__sync-actions">
          <button class="btn btn-sm btn-outline-primary" data-openalex-sync ${orcid ? '' : 'disabled'}>
            <i class="fa-solid fa-arrows-rotate"></i> Update from OpenAlex
          </button>
          <span class="app-ws-prof__openalex-feedback" role="status" aria-live="polite"></span>
        </div>
      </div>
    </div>`;

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
    </div>` + openAlexSection;
}

function _buildTaskTable(tableId, tasks, cols) {
    const sorted = _asArray(tasks)
        .slice()
        .sort((a, b) => (b?.initiatedDate ?? '').localeCompare(a?.initiatedDate ?? ''))
        .slice(0, 10);

    if (sorted.length === 0) {
        return '<p class="app-ws-prof__task-empty">No sync history yet.</p>';
    }

    const rows = sorted.map((task) => `<tr>
      <td><span class="app-ws-prof__id-pill">${_esc(task?.scopusId ?? '—')}</span></td>
      <td style="font-size:.78rem;white-space:nowrap">${_esc(_modeLabel(task?.syncMode))}</td>
      <td>${_statusBadge(task?.status)}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(task?.initiatedDate))}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(task?.executionDate))}</td>
      <td style="color:var(--app-color-text-muted);font-size:.78rem;max-width:14rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${_esc(task?.message ?? '—')}</td>
    </tr>`).join('');

    return `<table class="app-ws-prof__task-table" id="${tableId}">
      <thead><tr>${_asArray(cols).map((col) => `<th>${_esc(col)}</th>`).join('')}</tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
}

function _statusBadge(status) {
    const cls = { PENDING: 'pending', COMPLETED: 'completed', FAILED: 'failed' }[status] ?? 'muted';
    return `<span class="app-ws-prof__badge app-ws-prof__badge--${cls}">${_esc(status ?? '—')}</span>`;
}

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

function _wireEvents() {
    const root = _mount;

    root.querySelector('#ws-prof-retry-btn')?.addEventListener('click', () => _init(_panel));
    root.querySelector('#ws-prof-edit-btn')?.addEventListener('click', _openEdit);
    root.querySelector('#ws-prof-cancel-btn')?.addEventListener('click', _closeEdit);
    root.querySelector('#ws-prof-save-btn')?.addEventListener('click', _saveProfile);

    root.querySelector('#ws-prof-add-scopus-btn')?.addEventListener('click', () => {
        const entries = root.querySelector('#ws-prof-scopus-entries');
        if (!entries) return;
        entries.insertAdjacentHTML('beforeend', _buildIdEntryRow('scopusId', ''));
        entries.lastElementChild?.querySelector('input')?.focus();
    });

    root.querySelector('#ws-prof-add-wos-btn')?.addEventListener('click', () => {
        const entries = root.querySelector('#ws-prof-wos-entries');
        if (!entries) return;
        entries.insertAdjacentHTML('beforeend', _buildIdEntryRow('wosId', ''));
        entries.lastElementChild?.querySelector('input')?.focus();
    });

    if (_delegateController) _delegateController.abort();
    _delegateController = new AbortController();
    const { signal } = _delegateController;

    root.addEventListener('click', (e) => {
        const onboardingOpen = e.target.closest('[data-onboarding-open]');
        if (onboardingOpen) {
            e.preventDefault();
            window.appWorkspaceOnboarding?.open();
            return;
        }

        const anchor = e.target.closest('[data-prof-checklist-anchor]');
        if (anchor) {
            e.preventDefault();
            const fieldId = anchor.dataset.profChecklistAnchor;
            _openEdit();
            setTimeout(() => {
                const el = root.querySelector(`#${fieldId}`);
                if (el) {
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    el.focus();
                }
            }, 50);
            return;
        }

        const removeBtn = e.target.closest('[data-remove-id-row]');
        if (removeBtn) {
            removeBtn.closest('.app-ws-prof__id-entry-row')?.remove();
            return;
        }

        const openAlexBtn = e.target.closest('[data-openalex-sync]');
        if (openAlexBtn) {
            _triggerOpenAlexSync(openAlexBtn);
            return;
        }

        const radio = e.target.closest('input[type="radio"][name^="sync-mode-"]');
        if (radio) {
            const row = radio.closest('[data-sync-row]');
            const panel = row?.querySelector('.app-ws-prof__sync-period');
            if (panel) panel.hidden = (radio.value !== 'PERIOD');
            return;
        }

        const syncBtn = e.target.closest('[data-sync-type]');
        if (syncBtn) {
            _triggerSync(syncBtn.dataset.syncType, syncBtn.dataset.syncId, syncBtn);
            return;
        }

        const currentToggle = e.target.closest('[data-affiliation-current]');
        if (currentToggle) {
            const pastToggle = currentToggle.closest('[data-affiliation-scope-row]')?.querySelector('[data-affiliation-past]');
            if (currentToggle.checked && pastToggle) pastToggle.checked = false;
            return;
        }

        const pastToggle = e.target.closest('[data-affiliation-past]');
        if (pastToggle) {
            const currentToggleForRow = pastToggle.closest('[data-affiliation-scope-row]')?.querySelector('[data-affiliation-current]');
            if (pastToggle.checked && currentToggleForRow) currentToggleForRow.checked = false;
        }
    }, { signal });
}

function _openEdit() {
    if (_editOpen) return;
    _editOpen = true;
    const grid = _mount.querySelector('#ws-prof-readonly-grid');
    const formContainer = _mount.querySelector('#ws-prof-edit-form-container');
    const editBtn = _mount.querySelector('#ws-prof-edit-btn');
    if (grid) grid.hidden = true;
    if (formContainer) formContainer.hidden = false;
    if (editBtn) editBtn.hidden = true;
    _mount.querySelector('#ws-prof-edit-firstName')?.focus();
}

function _closeEdit() {
    if (!_editOpen) return;
    _editOpen = false;
    const grid = _mount.querySelector('#ws-prof-readonly-grid');
    const formContainer = _mount.querySelector('#ws-prof-edit-form-container');
    const editBtn = _mount.querySelector('#ws-prof-edit-btn');
    if (grid) grid.hidden = false;
    if (formContainer) formContainer.hidden = true;
    if (editBtn) editBtn.hidden = false;
}

function _saveProfile() {
    const feedback = _mount.querySelector('#ws-prof-form-feedback');
    const saveBtn = _mount.querySelector('#ws-prof-save-btn');

    const firstName = (_mount.querySelector('#ws-prof-edit-firstName')?.value ?? '').trim();
    const lastName = (_mount.querySelector('#ws-prof-edit-lastName')?.value ?? '').trim();
    const orcid = (_mount.querySelector('#ws-prof-edit-orcid')?.value ?? '').trim() || null;
    // PhD award year — optional; blank/invalid → null (no post-PhD anchor).
    const phdRaw = (_mount.querySelector('#ws-prof-edit-phdYear')?.value ?? '').trim();
    const phdAwardYear = phdRaw && Number.isInteger(Number(phdRaw)) ? Number(phdRaw) : null;
    const scopusEntries = _mount.querySelectorAll('#ws-prof-scopus-entries .app-ws-prof__id-entry-row input');
    const wosEntries = _mount.querySelectorAll('#ws-prof-wos-entries .app-ws-prof__id-entry-row input');
    const scopusId = Array.from(scopusEntries).map((input) => input.value.trim()).filter(Boolean);
    const wosId = Array.from(wosEntries).map((input) => input.value.trim()).filter(Boolean);

    const currentAffiliationIds = [];
    const pastAffiliationIds = [];
    _mount.querySelectorAll('[data-affiliation-scope-row]').forEach((row) => {
        const affiliationId = row.getAttribute('data-affiliation-id');
        if (!affiliationId) return;
        if (row.querySelector('[data-affiliation-current]')?.checked) {
            currentAffiliationIds.push(affiliationId);
            return;
        }
        if (row.querySelector('[data-affiliation-past]')?.checked) {
            pastAffiliationIds.push(affiliationId);
        }
    });

    if (!firstName || !lastName) {
        _setFeedback(feedback, 'error', 'First name and last name are required.');
        return;
    }

    if (saveBtn) saveBtn.disabled = true;
    _setFeedback(feedback, '', '');

    fetch('/user/workspace/profile/save', {
        method: 'POST',
        headers: _postHeaders(),
        body: JSON.stringify({
            firstName,
            lastName,
            phdAwardYear,
            orcid,
            scopusId,
            wosId,
            currentAffiliationIds,
            pastAffiliationIds,
            confirmAffiliationScope: true
        })
    })
        .then((r) => {
            if (r.ok) return null;
            return r.json().then((j) => {
                throw new Error(j?.error ?? `HTTP ${r.status}`);
            });
        })
        .then(() => {
            _init(_panel);
        })
        .catch((err) => {
            if (saveBtn) saveBtn.disabled = false;
            _setFeedback(feedback, 'error', err?.message ?? 'Save failed. Please try again.');
        });
}

function _setFeedback(el, type, msg) {
    if (!el) return;
    el.className = 'app-ws-prof__form-feedback';
    if (type) el.classList.add(`app-ws-prof__form-feedback--${type}`);
    el.textContent = msg;
}

function _triggerSync(type, scopusId, btn) {
    const row = btn?.closest('[data-sync-row]');
    const modeRadio = row?.querySelector('input[type="radio"]:checked');
    const syncMode = modeRadio?.value ?? 'SINCE_LAST_UPDATE';
    const yearInputs = row?.querySelectorAll('.app-ws-prof__sync-year');
    const startYear = yearInputs?.[0]?.value ? parseInt(yearInputs[0].value, 10) : null;
    const endYear = yearInputs?.[1]?.value ? parseInt(yearInputs[1].value, 10) : null;

    if (btn) btn.disabled = true;

    fetch(`/user/workspace/profile/sync/${type}`, {
        method: 'POST',
        headers: _postHeaders(),
        body: JSON.stringify({ scopusId, syncMode, startYear, endYear })
    })
        .then((r) => {
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            return r.json();
        })
        .then((task) => {
            if (btn) btn.disabled = false;
            _prependTaskRow(type, task, scopusId, syncMode);
        })
        .catch((err) => {
            if (btn) btn.disabled = false;
            _showSyncError(btn, err?.message);
        });
}

function _triggerOpenAlexSync(btn) {
    const feedback = btn?.parentElement?.querySelector('.app-ws-prof__openalex-feedback');
    const setFeedback = (msg, color) => { if (feedback) { feedback.textContent = msg; feedback.style.cssText = `font-size:.78rem;margin-left:.5rem;color:${color}`; } };
    if (btn) btn.disabled = true;
    setFeedback('Starting…', 'var(--app-color-text-muted)');
    fetch('/user/workspace/profile/sync/openalex-authors', { method: 'POST', headers: _postHeaders() })
        .then((r) => {
            if (r.status === 422) throw new Error('Add a valid ORCID to your profile first.');
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            return r.json().catch(() => ({}));
        })
        .then(() => {
            if (btn) btn.disabled = false;
            setFeedback('Update started — your works will appear after it runs.', 'var(--app-color-success)');
        })
        .catch((err) => {
            if (btn) btn.disabled = false;
            setFeedback(err?.message || 'Could not start the update.', 'var(--app-color-danger)');
        });
}

function _showSyncError(btn, msg) {
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

function _prependTaskRow(type, task, scopusId, syncMode) {
    const tableId = type === 'publications' ? 'publication-tasks' : 'citation-tasks';
    const table = _mount.querySelector(`#${tableId}`);

    if (!table) {
        _init(_panel);
        return;
    }

    const tbody = table.querySelector('tbody');
    if (!tbody) return;

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><span class="app-ws-prof__id-pill">${_esc(task?.scopusId ?? scopusId ?? '—')}</span></td>
      <td style="font-size:.78rem;white-space:nowrap">${_esc(_modeLabel(task?.syncMode ?? syncMode))}</td>
      <td>${_statusBadge(task?.status)}</td>
      <td style="white-space:nowrap;font-size:.78rem">${_esc(_fmtDate(task?.initiatedDate))}</td>
      <td style="white-space:nowrap;font-size:.78rem">—</td>
      <td style="color:var(--app-color-text-muted);font-size:.78rem">—</td>`;
    tbody.insertBefore(tr, tbody.firstChild);

    while (tbody.rows.length > 10) tbody.deleteRow(tbody.rows.length - 1);
}

function _asArray(value) {
    return Array.isArray(value) ? value : [];
}

function _getAffiliationId(item) {
    if (!item || typeof item !== 'object') return '';
    return String(item.id ?? item.afid ?? '');
}

function _esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function _modeLabel(mode) {
    if (mode === 'FULL') return 'Full';
    if (mode === 'PERIOD') return 'Period';
    return 'Since last';
}

function _fmtDate(iso) {
    if (!iso) return '—';
    try {
        return new Date(iso).toLocaleString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    } catch {
        return iso;
    }
}
