/**
 * workspaceShortcuts.js — H36.11 Keyboard shortcuts and navigation
 *
 * Implements:
 *  - ?          → toggle cheat sheet overlay
 *  - Escape     → close cheat sheet (capture phase, highest priority)
 *  - ↑ ↓        → navigate rows in workspace data tables
 *  - Enter      → open detail panel for focused table row
 *
 * The following shortcuts are already handled by other modules and are
 * listed in the cheat sheet for discoverability only:
 *  - Ctrl+1..4  → switch tab (workspaceTabs.js)
 *  - / Ctrl+K   → focus search (workspaceSearch.js)
 *  - Escape     → close wizard / detail / create (workspacePublications/Activities.js)
 */

// IDs of <tbody> elements that support keyboard row navigation
const NAV_TBODY_IDS = ['ws-pubs-tbody', 'ws-acts-tbody'];

// Set of tbody IDs already wired with a MutationObserver
const _wiredTbodys = new Set();

export function initWorkspaceShortcuts() {
    _buildOverlay();
    // Capture phase: runs before module-level Escape handlers so cheat sheet
    // can close itself first (it sets stopPropagation to prevent double-close).
    document.addEventListener('keydown', _onCaptureKey, { capture: true });
    // Bubble phase: table row navigation (after other handlers have had a chance).
    document.addEventListener('keydown', _onTableNav);
    _watchForTables();
}

// ── Cheat sheet overlay ───────────────────────────────────────────────────────

function _buildOverlay() {
    const el = document.createElement('div');
    el.id = 'ws-shortcuts-overlay';
    el.className = 'app-shortcuts-overlay';
    el.setAttribute('hidden', '');
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-modal', 'true');
    el.setAttribute('aria-labelledby', 'ws-shortcuts-title');

    el.innerHTML =
        `<div class="app-shortcuts-dialog">
          <div class="app-shortcuts-header">
            <h2 id="ws-shortcuts-title" class="app-shortcuts-title">Keyboard Shortcuts</h2>
            <button class="app-shortcuts-close" data-shortcuts-close aria-label="Close">
              <i class="fa-solid fa-xmark" aria-hidden="true"></i>
            </button>
          </div>
          <div class="app-shortcuts-body">
            ${_section('Navigation', [
                [_keys('Ctrl', '1') + ' – ' + _kbd('4'), 'Switch to tab'],
                [_keys('/', 'Ctrl+K'),                    'Focus search'],
            ])}
            ${_section('Tables', [
                [_keys('↑', '↓'),  'Navigate rows'],
                [_kbd('Enter'),     'Open detail panel'],
            ])}
            ${_section('General', [
                [_kbd('?'),    'Toggle shortcuts'],
                [_kbd('Esc'), 'Close overlays &amp; panels'],
            ])}
          </div>
        </div>`;

    document.body.appendChild(el);

    // Close on backdrop click or close button
    el.addEventListener('click', e => {
        if (e.target === el || e.target.closest('[data-shortcuts-close]')) {
            _closeOverlay();
        }
    });
}

function _section(title, rows) {
    const rowsHtml = rows.map(([keys, desc]) =>
        `<div class="app-shortcuts-row">
          <dt class="app-shortcuts-keys">${keys}</dt>
          <dd class="app-shortcuts-desc">${desc}</dd>
        </div>`
    ).join('');
    return `<section>
      <h3 class="app-shortcuts-section-title">${title}</h3>
      <dl class="app-shortcuts-list">${rowsHtml}</dl>
    </section>`;
}

/** Render one or more keyboard glyphs separated by ' or ' */
function _keys(...glyphs) {
    return glyphs.map(_kbd).join(' <span style="color:var(--app-color-text-muted);font-size:.75rem">or</span> ');
}

function _kbd(glyph) {
    // Allow passing "Ctrl+K" as a compound — split on + and render each part
    return glyph.split('+').map(part =>
        `<kbd class="app-shortcuts-kbd">${_esc(part.trim())}</kbd>`
    ).join('<span style="color:var(--app-color-text-muted);font-size:.8rem">+</span>');
}

function _openOverlay() {
    const el = document.getElementById('ws-shortcuts-overlay');
    if (!el) return;
    el.removeAttribute('hidden');
    el.querySelector('.app-shortcuts-close')?.focus();
}

function _closeOverlay() {
    const el = document.getElementById('ws-shortcuts-overlay');
    if (!el) return;
    el.setAttribute('hidden', '');
}

function _isOverlayVisible() {
    return !document.getElementById('ws-shortcuts-overlay')?.hasAttribute('hidden');
}

// ── Global key handlers ───────────────────────────────────────────────────────

/** Capture-phase handler: highest priority. Handles cheat sheet open/close. */
function _onCaptureKey(e) {
    if (_isOverlayVisible() && e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation(); // prevent other Escape handlers from firing
        _closeOverlay();
        return;
    }

    if (e.key === '?') {
        const inField = _isInField();
        if (!inField) {
            e.preventDefault();
            _isOverlayVisible() ? _closeOverlay() : _openOverlay();
        }
    }
}

/** Bubble-phase handler: table row arrow key and Enter navigation. */
function _onTableNav(e) {
    if (e.defaultPrevented) return;
    if (_isOverlayVisible()) return; // modal blocks table nav

    const tr = document.activeElement?.tagName === 'TR'
        ? document.activeElement
        : null;
    if (!tr) return;

    const tbody = tr.parentElement;
    if (!tbody || !NAV_TBODY_IDS.includes(tbody.id)) return;

    switch (e.key) {
        case 'ArrowDown': {
            e.preventDefault();
            const next = _nextDataRow(tr, 1);
            if (next) _focusRow(tr, next);
            break;
        }
        case 'ArrowUp': {
            e.preventDefault();
            const prev = _nextDataRow(tr, -1);
            if (prev) _focusRow(tr, prev);
            break;
        }
        case 'Enter': {
            e.preventDefault();
            tr.click();
            break;
        }
        case 'Escape': {
            // Let other handlers (activities/publications) deal with open details.
            // Just blur the row so focus leaves the table.
            tr.blur();
            break;
        }
    }
}

// ── Table row focus management ────────────────────────────────────────────────

/**
 * Walk sibling <tr>s in the given direction (1=down, -1=up), skipping
 * detail/expansion rows (those without a data-pub-id or data-inst-id attribute).
 */
function _nextDataRow(tr, dir) {
    let el = tr;
    while (true) {
        el = dir > 0 ? el.nextElementSibling : el.previousElementSibling;
        if (!el || el.tagName !== 'TR') return null;
        if (_isDataRow(el)) return el;
    }
}

function _isDataRow(tr) {
    return tr.hasAttribute('data-pub-id') || tr.hasAttribute('data-inst-id');
}

function _focusRow(from, to) {
    from.tabIndex = -1;
    from.classList.remove('app-row--kb-focused');
    to.tabIndex = 0;
    to.classList.add('app-row--kb-focused');
    to.focus();
}

/**
 * Set tabindex on all data rows in a tbody.
 * First data row gets tabindex=0 (Tab entry point); rest get -1.
 */
function _initTbodyTabindex(tbody) {
    const rows = Array.from(tbody.children).filter(_isDataRow);
    rows.forEach((row, i) => {
        row.tabIndex = i === 0 ? 0 : -1;

        // Remove kb-focus class when row loses focus
        if (!row._shortcutsWired) {
            row._shortcutsWired = true;
            row.addEventListener('blur', () => row.classList.remove('app-row--kb-focused'));
            row.addEventListener('focus', () => row.classList.add('app-row--kb-focused'));
        }
    });
}

// ── MutationObserver: wire new tables as they appear ─────────────────────────

function _watchForTables() {
    // Check once immediately (for tabs that are active at load time)
    _tryWireTbodys();

    // Watch the workspace root for new descendants (lazy-loaded tabs)
    const root = document.querySelector('[data-app-tab-bar]');
    if (!root) return;

    new MutationObserver(_tryWireTbodys)
        .observe(root, { childList: true, subtree: true });
}

function _tryWireTbodys() {
    NAV_TBODY_IDS.forEach(id => {
        if (_wiredTbodys.has(id)) return;
        const tbody = document.getElementById(id);
        if (!tbody) return;

        _wiredTbodys.add(id);
        _initTbodyTabindex(tbody);

        // Re-init tabindex whenever rows are replaced (pagination / reload)
        new MutationObserver(() => _initTbodyTabindex(tbody))
            .observe(tbody, { childList: true });
    });
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function _isInField() {
    const el = document.activeElement;
    return el && (
        el.tagName === 'INPUT' ||
        el.tagName === 'TEXTAREA' ||
        el.tagName === 'SELECT' ||
        el.isContentEditable
    );
}

function _esc(str) {
    return String(str ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
