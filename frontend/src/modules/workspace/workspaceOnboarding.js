/**
 * workspaceOnboarding.js — H70 researcher onboarding wizard (stepped modal).
 *
 * A guided, resume-aware flow that opens at the first incomplete step (driven by the backend
 * ResearcherOnboardingService via the /profile view model's `onboarding` field). Slice 2 implements steps
 * 1–3 (identity ids → ORCID + OpenAlex sync → confirm/deny affiliations); steps 4–5 (author match, bulk
 * publication claim) render an informational placeholder and land in later slices.
 *
 * Per-step save reuses POST /profile/save, which is a FULL overwrite — so every save sends the complete working
 * profile (it preserves wizard-only fields like confirmedScholardexAuthorIds, which the request never carries).
 */

import { postJsonHeaders } from '../shared/fetchUtils';

const PROFILE_URL = '/user/workspace/profile';
const SAVE_URL = '/user/workspace/profile/save';
const OPENALEX_SYNC_URL = '/user/workspace/profile/sync/openalex-authors';
const MODAL_ID = 'ws-onboarding-modal';
const SNOOZE_KEY = 'wsOnboardingSnoozed';

// Flow order must match the backend OnboardingStep enum.
const STEPS = [
    { key: 'IDENTITY_IDS', label: 'Identity' },
    { key: 'ORCID', label: 'ORCID' },
    { key: 'AFFILIATIONS', label: 'Affiliations' },
    { key: 'AUTHOR_MATCH', label: 'Author record' },
    { key: 'PUBLICATION_CLAIM', label: 'Publications' }
];
const INTERACTIVE = new Set(['IDENTITY_IDS', 'ORCID', 'AFFILIATIONS', 'AUTHOR_MATCH']);
const CANDIDATES_URL = '/user/workspace/profile/author-candidates';
const AUTHOR_MATCH_URL = '/user/workspace/profile/author-match';

let _data = null;        // last /profile response
let _profile = null;     // mutable working copy of the editable fields
let _candidates = null;  // author-match candidates (lazy-loaded for step 4; null = not yet fetched)
let _authorMatch = { confirmedAuthorIds: [], primaryAuthorId: null };
let _stepIndex = 0;
let _busy = false;

export function initWorkspaceOnboarding() {
    // The wizard only exists on the researcher workspace page (its modal shell lives there).
    const modal = document.getElementById(MODAL_ID);
    if (!modal) return;
    window.appWorkspaceOnboarding = { open: () => _load(true) };
    // Any close (X, backdrop, escape, "Finish later") snoozes the auto-open for the rest of the session.
    modal.addEventListener('hidden.bs.modal', () => sessionStorage.setItem(SNOOZE_KEY, '1'));
    // Auto-open once per session when onboarding is incomplete and not snoozed.
    if (sessionStorage.getItem(SNOOZE_KEY) === '1') return;
    _load(false);
}

function _load(forceOpen) {
    fetch(PROFILE_URL, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data) => {
            _data = data;
            _seedWorkingProfile(data?.researcher);
            _candidates = null;   // refetched per open
            _authorMatch = { confirmedAuthorIds: [], primaryAuthorId: null };
            const onboarding = data?.onboarding ?? null;
            if (!forceOpen && (onboarding?.complete ?? true)) return;   // nothing to do
            _stepIndex = Math.max(0, _indexOfStep(onboarding?.nextStep) ?? 0);
            _render();
            if (window.appModal) window.appModal.open(MODAL_ID);
        })
        .catch((err) => console.error('onboarding load failed', err));
}

function _seedWorkingProfile(researcher) {
    const r = researcher ?? {};
    _profile = {
        firstName: r.firstName ?? '',
        lastName: r.lastName ?? '',
        scholarId: r.scholarId ?? null,
        scopusId: _asArray(r.scopusId),
        wosId: _asArray(r.wosId),
        orcid: r.orcid ?? '',
        currentAffiliationIds: _asArray(r.currentAffiliationIds),
        pastAffiliationIds: _asArray(r.pastAffiliationIds)
    };
}

// ── rendering ───────────────────────────────────────────────────────────

function _render() {
    const modal = document.getElementById(MODAL_ID);
    if (!modal) return;
    const body = modal.querySelector('[data-onboarding-body]');
    if (!body) return;
    const step = STEPS[_stepIndex];
    body.innerHTML = _buildStepper() + _buildStepBody(step) + _buildFooter(step);
    _wire(modal, step);
    if (step.key === 'AUTHOR_MATCH' && _candidates === null) _loadCandidates();
}

function _buildStepper() {
    const completed = new Set(_data?.onboarding?.completedSteps ?? []);
    const dots = STEPS.map((s, i) => {
        const state = completed.has(s.key) ? 'done' : (i === _stepIndex ? 'active' : 'todo');
        const icon = state === 'done' ? '<i class="fa-solid fa-check"></i>' : (i + 1);
        return `<li class="app-onb__step app-onb__step--${state}">
          <span class="app-onb__step-dot">${icon}</span>
          <span class="app-onb__step-label">${_esc(s.label)}</span>
        </li>`;
    }).join('');
    const pct = _data?.onboarding?.percentComplete ?? 0;
    return `<ol class="app-onb__steps">${dots}</ol>
      <div class="app-onb__progress"><div class="app-onb__progress-fill" style="width:${pct}%"></div></div>`;
}

function _buildStepBody(step) {
    switch (step.key) {
        case 'IDENTITY_IDS': return _buildIdentityStep();
        case 'ORCID': return _buildOrcidStep();
        case 'AFFILIATIONS': return _buildAffiliationsStep();
        case 'AUTHOR_MATCH': return _buildAuthorMatchStep();
        default: return _buildComingSoon(step);
    }
}

function _buildIdentityStep() {
    return `<div class="app-onb__panel">
      <h6 class="app-onb__panel-title">Your author identifiers</h6>
      <p class="app-onb__panel-help">These link your account to your publications. Scopus ids are often already
        filled in from the staff import — confirm them or add more.</p>
      ${_idList('scopusId', 'Scopus author ID', _profile.scopusId)}
      ${_idList('wosId', 'WoS researcher ID', _profile.wosId)}
    </div>`;
}

function _idList(field, label, ids) {
    const rows = (ids.length ? ids : ['']).map((v, i) => _idRow(field, v, i)).join('');
    return `<div class="app-onb__field">
      <label class="app-onb__label">${_esc(label)}</label>
      <div data-id-list="${field}">${rows}</div>
      <button type="button" class="btn btn-sm btn-link app-onb__add" data-add-id="${field}">
        <i class="fa-solid fa-plus"></i> Add ${_esc(label)}</button>
    </div>`;
}

function _idRow(field, value, index) {
    return `<div class="app-onb__id-row" data-id-row>
      <input type="text" class="form-control form-control-sm" data-id-input="${field}"
             value="${_esc(value)}" placeholder="${_esc(field === 'scopusId' ? 'e.g. 36674707400' : 'e.g. X-1234-2020')}">
      <button type="button" class="btn btn-sm btn-outline-secondary app-onb__id-remove" data-remove-id
              aria-label="Remove" ${index === 0 && value === '' ? 'disabled' : ''}>&times;</button>
    </div>`;
}

function _buildOrcidStep() {
    return `<div class="app-onb__panel">
      <h6 class="app-onb__panel-title">Your ORCID iD</h6>
      <p class="app-onb__panel-help">ORCID lets us pull corresponding-author and citation data from OpenAlex.
        Saving here kicks off a background sync.</p>
      <div class="app-onb__field">
        <label class="app-onb__label" for="onb-orcid">ORCID</label>
        <input type="text" class="form-control form-control-sm" id="onb-orcid"
               value="${_esc(_profile.orcid ?? '')}" placeholder="0000-0002-1825-0097">
        <small class="app-onb__hint">Bare or full URL (https://orcid.org/…) — both work. Leave blank to skip.</small>
      </div>
    </div>`;
}

function _buildAffiliationsStep() {
    const observed = _asArray(_data?.observedAffiliations);
    const cur = new Set(_profile.currentAffiliationIds);
    const past = new Set(_profile.pastAffiliationIds);
    if (!observed.length) {
        return `<div class="app-onb__panel">
          <h6 class="app-onb__panel-title">Confirm your affiliations</h6>
          <p class="app-onb__panel-help">No affiliations observed yet — they appear once your author identity
            resolves (after the identity/ORCID steps and a sync). You can confirm later.</p>
        </div>`;
    }
    const rows = observed.map((a) => {
        const id = a.id ?? a.affiliationId;
        const sel = cur.has(id) ? 'current' : (past.has(id) ? 'past' : 'current');
        const loc = [a.city, a.country].filter(Boolean).join(', ');
        return `<div class="app-onb__aff-row" data-aff-row data-aff-id="${_esc(id)}">
          <div class="app-onb__aff-name">${_esc(a.name ?? id)}${loc ? `<span class="app-onb__aff-loc">${_esc(loc)}</span>` : ''}</div>
          <div class="app-onb__aff-choice">
            ${_affRadio(id, 'current', 'Current', sel)}
            ${_affRadio(id, 'past', 'Past', sel)}
            ${_affRadio(id, 'none', 'Not mine', sel)}
          </div>
        </div>`;
    }).join('');
    return `<div class="app-onb__panel">
      <h6 class="app-onb__panel-title">Confirm your affiliations</h6>
      <p class="app-onb__panel-help">We observed these from your publications. Mark each as current, past, or not
        yours. Confirming unblocks your publication review.</p>
      ${rows}
    </div>`;
}

function _affRadio(id, value, label, selected) {
    const name = `aff-${id}`;
    return `<label class="app-onb__aff-opt">
      <input type="radio" name="${_esc(name)}" value="${value}" ${value === selected ? 'checked' : ''}> ${_esc(label)}
    </label>`;
}

function _buildAuthorMatchStep() {
    const header = '<h6 class="app-onb__panel-title">Match your author record</h6>';
    if (_candidates === null) {
        return `<div class="app-onb__panel">${header}
          <div class="app-skeleton-block" style="height:5rem;border-radius:var(--app-radius-card)"></div></div>`;
    }
    if (!_candidates.length) {
        return `<div class="app-onb__panel">${header}
          <p class="app-onb__panel-help">No author records resolved from your identifiers yet. Add a Scopus id or
            ORCID in the earlier steps (and let the sync finish), then return here.</p></div>`;
    }
    const confirmedSet = new Set(_authorMatch.confirmedAuthorIds);
    const rows = _candidates.map((c) => {
        const checked = confirmedSet.has(c.authorId);
        const affs = (c.affiliations || []).map((a) => `<span class="app-onb__cand-aff">${_esc(a)}</span>`).join('');
        return `<div class="app-onb__cand" data-cand-id="${_esc(c.authorId)}">
          <label class="app-onb__cand-pick">
            <input type="checkbox" data-cand-confirm value="${_esc(c.authorId)}" ${checked ? 'checked' : ''}>
          </label>
          <div class="app-onb__cand-body">
            <div class="app-onb__cand-head">
              <span class="app-onb__cand-name">${_esc(c.name || c.authorId)}</span>
              <span class="app-onb__cand-count">${Number(c.publicationCount) || 0} pub${c.publicationCount === 1 ? '' : 's'}</span>
            </div>
            ${affs ? `<div class="app-onb__cand-affs">${affs}</div>` : ''}
            <label class="app-onb__cand-primary${checked ? '' : ' app-onb__cand-primary--off'}">
              <input type="radio" name="onb-primary" data-cand-primary value="${_esc(c.authorId)}"
                     ${_authorMatch.primaryAuthorId === c.authorId ? 'checked' : ''} ${checked ? '' : 'disabled'}> Primary
            </label>
          </div>
        </div>`;
    }).join('');
    return `<div class="app-onb__panel">${header}
      <p class="app-onb__panel-help">These canonical author records resolved from your identifiers. Tick the ones
        that are you — the publication count and affiliations help you tell — and mark your main record as primary.</p>
      ${rows}
    </div>`;
}

function _loadCandidates() {
    fetch(CANDIDATES_URL, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((list) => {
            _candidates = Array.isArray(list) ? list : [];
            // Seed the selection: keep already-confirmed; otherwise pre-tick the most-published record (top of the
            // list) as a sensible, reviewable default. Primary defaults to the first selected.
            const pre = _candidates.filter((c) => c.alreadyConfirmed).map((c) => c.authorId);
            const seed = pre.length ? pre : _candidates.slice(0, 1).map((c) => c.authorId);
            _authorMatch = { confirmedAuthorIds: seed, primaryAuthorId: seed[0] ?? null };
        })
        .catch((err) => { console.error('author candidates load failed', err); _candidates = []; })
        .finally(() => { if (STEPS[_stepIndex]?.key === 'AUTHOR_MATCH') _render(); });
}

function _captureAuthorMatch(modal) {
    const confirmed = [...modal.querySelectorAll('[data-cand-confirm]:checked')].map((i) => i.value);
    let primary = modal.querySelector('[data-cand-primary]:checked')?.value ?? null;
    if (primary && !confirmed.includes(primary)) primary = null;
    if (!primary && confirmed.length) primary = confirmed[0];
    _authorMatch = { confirmedAuthorIds: confirmed, primaryAuthorId: primary };
}

function _saveAuthorMatch() {
    return fetch(AUTHOR_MATCH_URL, { method: 'POST', headers: postJsonHeaders(), body: JSON.stringify(_authorMatch) })
        .then((r) => (r.ok ? r : Promise.reject(new Error(`HTTP ${r.status}`))));
}

function _buildComingSoon(step) {
    return `<div class="app-onb__panel app-onb__panel--soon">
      <i class="fa-regular fa-clock app-onb__soon-icon"></i>
      <h6 class="app-onb__panel-title">${_esc(step.label)} — coming next</h6>
      <p class="app-onb__panel-help">You've completed the setup steps. Matching your canonical author record and
        the recommended publication claim are landing in an upcoming release.</p>
    </div>`;
}

function _buildFooter(step) {
    const isFirst = _stepIndex === 0;
    const interactive = INTERACTIVE.has(step.key);
    const nextLabel = step.key === 'AUTHOR_MATCH' ? 'Confirm &amp; continue'
        : interactive ? 'Save &amp; continue' : 'Done';
    return `<div class="app-onb__footer">
      <button type="button" class="btn btn-link btn-sm app-onb__dismiss" data-onb-dismiss>Finish later</button>
      <div class="app-onb__footer-nav">
        ${isFirst ? '' : '<button type="button" class="btn btn-outline-secondary btn-sm" data-onb-back>Back</button>'}
        <button type="button" class="btn btn-primary btn-sm" data-onb-next>${nextLabel}</button>
      </div>
    </div>`;
}

// ── interaction ─────────────────────────────────────────────────────────

function _wire(modal, step) {
    modal.querySelectorAll('[data-add-id]').forEach((btn) =>
        btn.addEventListener('click', () => {
            const field = btn.dataset.addId;
            _collectIds(modal);                 // keep current edits
            _profile[field] = [..._profile[field], ''];
            _render();
        }));
    modal.querySelectorAll('[data-remove-id]').forEach((btn) =>
        btn.addEventListener('click', () => {
            _collectIds(modal);
            const row = btn.closest('[data-id-row]');
            const field = row?.querySelector('[data-id-input]')?.dataset.idInput;
            const idx = [...row.parentElement.children].indexOf(row);
            if (field && idx >= 0) { _profile[field].splice(idx, 1); _render(); }
        }));
    modal.querySelectorAll('[data-cand-confirm]').forEach((cb) =>
        cb.addEventListener('change', () => { _captureAuthorMatch(modal); _render(); }));
    modal.querySelectorAll('[data-cand-primary]').forEach((rb) =>
        rb.addEventListener('change', () => _captureAuthorMatch(modal)));
    modal.querySelector('[data-onb-back]')?.addEventListener('click', () => { _stepIndex = Math.max(0, _stepIndex - 1); _render(); });
    modal.querySelector('[data-onb-next]')?.addEventListener('click', () => _next(modal, step));
    modal.querySelectorAll('[data-onb-dismiss]').forEach((b) => b.addEventListener('click', _dismiss));
}

function _next(modal, step) {
    if (_busy) return;
    if (!INTERACTIVE.has(step.key)) { _advance(); return; }
    _busy = true;
    _captureStep(modal, step);
    const savePromise = step.key === 'AUTHOR_MATCH' ? _saveAuthorMatch() : _save(step.key === 'AFFILIATIONS');
    savePromise
        .then(() => (step.key === 'ORCID' && _profile.orcid ? _triggerOpenAlex() : Promise.resolve()))
        .then(() => _refreshStatus())
        .then(() => { _busy = false; _advance(); })
        .catch((err) => { _busy = false; console.error('onboarding step save failed', err); _toast('Could not save — please retry.'); });
}

function _captureStep(modal, step) {
    if (step.key === 'IDENTITY_IDS') {
        _collectIds(modal);
    } else if (step.key === 'ORCID') {
        _profile.orcid = (modal.querySelector('#onb-orcid')?.value ?? '').trim();
    } else if (step.key === 'AFFILIATIONS') {
        const current = [], past = [];
        modal.querySelectorAll('[data-aff-row]').forEach((row) => {
            const id = row.dataset.affId;
            const choice = row.querySelector('input[type=radio]:checked')?.value ?? 'current';
            if (choice === 'current') current.push(id);
            else if (choice === 'past') past.push(id);
        });
        _profile.currentAffiliationIds = current;
        _profile.pastAffiliationIds = past;
    } else if (step.key === 'AUTHOR_MATCH') {
        _captureAuthorMatch(modal);
    }
}

function _collectIds(modal) {
    ['scopusId', 'wosId'].forEach((field) => {
        const list = modal.querySelector(`[data-id-list="${field}"]`);
        if (!list) return;
        _profile[field] = [...list.querySelectorAll(`[data-id-input="${field}"]`)]
            .map((i) => i.value.trim()).filter((v) => v.length > 0);
    });
}

function _advance() {
    if (_stepIndex < STEPS.length - 1) { _stepIndex += 1; _render(); }
    else _dismiss();
}

function _save(confirmAffiliationScope) {
    return fetch(SAVE_URL, {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({
            firstName: _profile.firstName,
            lastName: _profile.lastName,
            scholarId: _profile.scholarId,
            scopusId: _profile.scopusId,
            wosId: _profile.wosId,
            orcid: _profile.orcid || null,
            currentAffiliationIds: _profile.currentAffiliationIds,
            pastAffiliationIds: _profile.pastAffiliationIds,
            confirmAffiliationScope: !!confirmAffiliationScope
        })
    }).then((r) => (r.ok ? r : Promise.reject(new Error(`HTTP ${r.status}`))));
}

function _triggerOpenAlex() {
    // Best-effort — a missing/invalid ORCID returns 422; don't block the wizard on it.
    return fetch(OPENALEX_SYNC_URL, { method: 'POST', headers: postJsonHeaders() }).catch(() => {});
}

function _refreshStatus() {
    return fetch(PROFILE_URL, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((r) => (r.ok ? r.json() : null))
        .then((data) => { if (data) { _data = data; } })
        .catch(() => {});
}

function _dismiss() {
    sessionStorage.setItem(SNOOZE_KEY, '1');
    if (window.appModal) window.appModal.close(MODAL_ID);
    // Let the profile panel refresh its completeness card if it's mounted.
    if (window.appWorkspaceProfile?.init && document.querySelector('[data-workspace-lazy-panel]')) {
        document.dispatchEvent(new CustomEvent('ws-onboarding-updated'));
    }
}

function _toast(message) {
    const modal = document.getElementById(MODAL_ID);
    let el = modal?.querySelector('[data-onb-toast]');
    if (el) { el.textContent = message; el.hidden = false; }
}

// ── helpers ─────────────────────────────────────────────────────────────

function _indexOfStep(key) {
    if (!key) return null;
    const i = STEPS.findIndex((s) => s.key === key);
    return i >= 0 ? i : null;
}

function _asArray(v) {
    return Array.isArray(v) ? v.filter((x) => x != null) : [];
}

function _esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
