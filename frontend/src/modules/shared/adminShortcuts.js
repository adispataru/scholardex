/**
 * adminShortcuts.js — H40.10 Keyboard shortcuts for admin tables
 *
 * Usage (inline script on each admin page):
 *   window.initAdminShortcuts({
 *     sections: [{title, rows: [{keys, label}]}],   // cheat-sheet content
 *     tables: [{
 *       tbodyEl,           // <tbody> element
 *       isDataRow,         // optional fn(tr)→bool (default: skip .app-admin-empty-row)
 *       keyActions,        // {key: fn(tr)} — fired when key pressed on focused row
 *     }],
 *   });
 *
 * Built-in shortcuts (always active):
 *   j / ArrowDown  → next row
 *   k / ArrowUp    → previous row
 *   ?              → toggle cheat-sheet overlay
 *   Escape         → close overlay
 */
export function initAdminShortcuts({ sections = [], tables = [] } = {}) {
    _buildOverlay(sections);
    document.addEventListener('keydown', _onGlobalKey, { capture: true });
    document.addEventListener('keydown', (e) => _onTableNav(e, tables));
    tables.forEach(t => _wireTable(t));
}

// ── Overlay ────────────────────────────────────────────────────────────────────

function _buildOverlay(sections) {
    if (document.getElementById('admin-shortcuts-overlay')) return;
    const el = document.createElement('div');
    el.id = 'admin-shortcuts-overlay';
    el.className = 'app-shortcuts-overlay';
    el.setAttribute('hidden', '');
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-modal', 'true');
    el.setAttribute('aria-labelledby', 'admin-shortcuts-title');

    const navSection = _section('Navigation', [
        [_keys('j', '↓'), 'Next row'],
        [_keys('k', '↑'), 'Previous row'],
    ]);

    const bodySections = sections.map(s => _section(s.title, s.rows.map(r => [_keysRaw(r.keys), r.label])));

    const generalSection = _section('General', [
        [_kbd('?'),   'Toggle shortcuts'],
        [_kbd('Esc'), 'Close overlay'],
    ]);

    el.innerHTML =
        `<div class="app-shortcuts-dialog">
          <div class="app-shortcuts-header">
            <h2 id="admin-shortcuts-title" class="app-shortcuts-title">Keyboard Shortcuts</h2>
            <button class="app-shortcuts-close" data-shortcuts-close aria-label="Close">
              <i class="fa-solid fa-xmark" aria-hidden="true"></i>
            </button>
          </div>
          <div class="app-shortcuts-body">
            ${navSection}
            ${bodySections.join('')}
            ${generalSection}
          </div>
        </div>`;

    document.body.appendChild(el);

    el.addEventListener('click', e => {
        if (e.target === el || e.target.closest('[data-shortcuts-close]')) _closeOverlay();
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

function _keysRaw(keysArray) {
    return (Array.isArray(keysArray) ? keysArray : [keysArray]).map(_kbd)
        .join(' <span style="color:var(--app-color-text-muted);font-size:.75rem">or</span> ');
}

function _keys(...glyphs) { return _keysRaw(glyphs); }

function _kbd(glyph) {
    return glyph.split('+').map(part =>
        `<kbd class="app-shortcuts-kbd">${_esc(part.trim())}</kbd>`
    ).join('<span style="color:var(--app-color-text-muted);font-size:.8rem">+</span>');
}

function _openOverlay() {
    const el = document.getElementById('admin-shortcuts-overlay');
    if (!el) return;
    el.removeAttribute('hidden');
    el.querySelector('.app-shortcuts-close')?.focus();
}

function _closeOverlay() {
    const el = document.getElementById('admin-shortcuts-overlay');
    if (!el) return;
    el.setAttribute('hidden', '');
}

function _isOverlayVisible() {
    return !document.getElementById('admin-shortcuts-overlay')?.hasAttribute('hidden');
}

// ── Global key handler ─────────────────────────────────────────────────────────

function _onGlobalKey(e) {
    if (_isOverlayVisible() && e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        _closeOverlay();
        return;
    }
    if (e.key === '?' && !_isInField()) {
        e.preventDefault();
        _isOverlayVisible() ? _closeOverlay() : _openOverlay();
    }
}

// ── Row navigation ─────────────────────────────────────────────────────────────

function _onTableNav(e, tables) {
    if (e.defaultPrevented || _isOverlayVisible() || _isInField()) return;

    const tr = document.activeElement?.tagName === 'TR' ? document.activeElement : null;
    if (!tr) return;

    const tableConfig = tables.find(t => t.tbodyEl && t.tbodyEl.contains(tr));
    if (!tableConfig) return;

    switch (e.key) {
        case 'ArrowDown':
        case 'j': {
            if (e.key === 'j' && _isInField()) return;
            e.preventDefault();
            const next = _adjacentRow(tr, tableConfig, 1);
            if (next) _focusRow(tr, next);
            break;
        }
        case 'ArrowUp':
        case 'k': {
            if (e.key === 'k' && _isInField()) return;
            e.preventDefault();
            const prev = _adjacentRow(tr, tableConfig, -1);
            if (prev) _focusRow(tr, prev);
            break;
        }
        case 'Enter':
        case 'e': {
            e.preventDefault();
            tableConfig.keyActions?.['Enter']?.(tr);
            tableConfig.keyActions?.['e']?.(tr);
            break;
        }
        default: {
            const action = tableConfig.keyActions?.[e.key];
            if (action) { e.preventDefault(); action(tr); }
        }
    }
}

// ── Table wiring ───────────────────────────────────────────────────────────────

function _wireTable({ tbodyEl, isDataRow }) {
    if (!tbodyEl) return;
    _initTabindex(tbodyEl, isDataRow);
    // Re-init whenever rows are replaced (pagination / dynamic load)
    new MutationObserver(() => _initTabindex(tbodyEl, isDataRow))
        .observe(tbodyEl, { childList: true });
}

function _initTabindex(tbody, isDataRow) {
    const rows = Array.from(tbody.children).filter(tr => _isData(tr, isDataRow));
    rows.forEach((row, i) => {
        row.tabIndex = i === 0 ? 0 : -1;
        if (!row._adminShortcutsWired) {
            row._adminShortcutsWired = true;
            row.addEventListener('blur',  () => row.classList.remove('app-row--kb-focused'));
            row.addEventListener('focus', () => row.classList.add('app-row--kb-focused'));
        }
    });
}

function _isData(tr, isDataRow) {
    if (typeof isDataRow === 'function') return isDataRow(tr);
    return !tr.classList.contains('app-admin-empty-row');
}

function _adjacentRow(tr, { tbodyEl, isDataRow }, dir) {
    let el = tr;
    while (true) {
        el = dir > 0 ? el.nextElementSibling : el.previousElementSibling;
        if (!el || el.tagName !== 'TR') return null;
        if (_isData(el, isDataRow)) return el;
    }
}

function _focusRow(from, to) {
    from.tabIndex = -1;
    from.classList.remove('app-row--kb-focused');
    to.tabIndex = 0;
    to.classList.add('app-row--kb-focused');
    to.focus();
}

// ── Utilities ──────────────────────────────────────────────────────────────────

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
