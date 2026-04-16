/**
 * workspaceSearch.js — H36.5 Unified cross-entity search
 *
 * ARIA pattern: combobox (role="combobox" on the input, role="listbox" on the
 * results list). Keyboard: ArrowDown/Up to navigate, Enter to select, Escape
 * to close. Global shortcut: "/" or Ctrl+K focuses the input.
 *
 * Results are grouped by entity type. Clicking or pressing Enter on a result
 * switches to the correct workspace tab via window.appWorkspaceTabs.
 */

const DEBOUNCE_MS = 300;
const MIN_QUERY_LEN = 2;

const ENTITY_CONFIG = {
    PUBLICATION: { label: 'Publications', badgeClass: 'app-ws-search__badge--pub', tab: 'publications' },
    ACTIVITY:    { label: 'Activities',   badgeClass: 'app-ws-search__badge--act', tab: 'activities'   },
    CITATION:    { label: 'Citations',    badgeClass: 'app-ws-search__badge--cite', tab: 'publications' },
};

export function initWorkspaceSearch() {
    const root = document.getElementById('ws-search-root');
    if (!root) return;

    const input   = root.querySelector('#ws-search-input');
    const listbox = root.querySelector('#ws-search-results');
    const live    = root.querySelector('.app-ws-search__live');
    if (!input || !listbox || !live) return;

    /** Flat list of result objects matching rendered <li> items (no headers). */
    let currentResults = [];
    /** Index into currentResults for the keyboard-active item. -1 = none. */
    let activeIndex = -1;
    let debounceTimer = null;

    // ── Global shortcut: / or Ctrl+K ────────────────────────────────────
    document.addEventListener('keydown', e => {
        const inField = document.activeElement &&
            document.activeElement.matches('input, textarea, select, [contenteditable]');
        if (inField) return;

        if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey) {
            e.preventDefault();
            input.focus();
            input.select();
        } else if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
            e.preventDefault();
            input.focus();
            input.select();
        }
    });

    // ── Input events ─────────────────────────────────────────────────────
    input.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        const q = input.value.trim();
        if (!q || q.length < MIN_QUERY_LEN) {
            _close();
            return;
        }
        debounceTimer = setTimeout(() => _fetch(q), DEBOUNCE_MS);
    });

    // Re-show results if user re-focuses an input that already has a value
    input.addEventListener('focus', () => {
        if (currentResults.length && listbox.hidden) {
            _open();
        }
    });

    // ── Keyboard navigation inside the input ─────────────────────────────
    input.addEventListener('keydown', e => {
        if (listbox.hidden) return;

        switch (e.key) {
            case 'ArrowDown':
                e.preventDefault();
                _moveActive(1);
                break;
            case 'ArrowUp':
                e.preventDefault();
                _moveActive(-1);
                break;
            case 'Enter':
                e.preventDefault();
                if (activeIndex >= 0 && currentResults[activeIndex]) {
                    _navigate(currentResults[activeIndex]);
                }
                break;
            case 'Escape':
                e.preventDefault();
                _close();
                input.blur();
                break;
        }
    });

    // Close on outside click
    document.addEventListener('click', e => {
        if (!root.contains(e.target)) _close();
    });

    // ── Fetch ─────────────────────────────────────────────────────────────
    function _fetch(q) {
        fetch(`/user/workspace/search?q=${encodeURIComponent(q)}`)
            .then(r => {
                if (!r.ok) throw new Error('Search request failed');
                return r.json();
            })
            .then(data => _render(q, data))
            .catch(() => _renderError());
    }

    // ── Render ────────────────────────────────────────────────────────────
    function _render(q, results) {
        currentResults = results;
        activeIndex = -1;
        listbox.innerHTML = '';

        if (!results.length) {
            const li = document.createElement('li');
            li.className = 'app-ws-search__no-results';
            li.setAttribute('role', 'presentation');
            li.innerHTML = `No results for "<strong>${_esc(q)}</strong>" — try a different term.`;
            listbox.appendChild(li);
            _open();
            live.textContent = 'No results found.';
            return;
        }

        // Group results by entity type preserving insertion order
        const groups = new Map();
        for (const result of results) {
            const type = result.entityType;
            if (!groups.has(type)) groups.set(type, []);
            groups.get(type).push(result);
        }

        let flatIndex = 0;
        for (const [type, items] of groups) {
            const config = ENTITY_CONFIG[type] ?? { label: type, badgeClass: '', tab: 'overview' };

            // Group header
            const header = document.createElement('li');
            header.className = 'app-ws-search__group-header';
            header.setAttribute('role', 'presentation');
            header.textContent = `${config.label} (${items.length})`;
            listbox.appendChild(header);

            for (const item of items) {
                const idx = flatIndex++;
                const li = _buildResultItem(item, config, idx);
                listbox.appendChild(li);
            }
        }

        _open();
        live.textContent = `${results.length} result${results.length !== 1 ? 's' : ''} found.`;
    }

    function _buildResultItem(item, config, idx) {
        const li = document.createElement('li');
        li.className = 'app-ws-search__result';
        li.setAttribute('role', 'option');
        li.setAttribute('aria-selected', 'false');
        li.setAttribute('id', `ws-result-${idx}`);
        li.dataset.index = idx;

        li.innerHTML =
            `<span class="app-ws-search__badge ${config.badgeClass}" aria-hidden="true">` +
                `${_esc(config.label.replace(/s$/, ''))}` +
            `</span>` +
            `<span class="app-ws-search__result-body">` +
                `<span class="app-ws-search__result-title">${_esc(item.title)}</span>` +
                (item.subtitle ? `<span class="app-ws-search__result-date">${_esc(item.subtitle)}</span>` : '') +
            `</span>`;

        li.addEventListener('click', () => _navigate(item));
        li.addEventListener('mouseenter', () => {
            activeIndex = idx;
            _highlightActive();
        });
        return li;
    }

    function _renderError() {
        listbox.innerHTML = '';
        const li = document.createElement('li');
        li.className = 'app-ws-search__no-results';
        li.setAttribute('role', 'presentation');
        li.textContent = 'Search failed — please try again.';
        listbox.appendChild(li);
        _open();
        live.textContent = 'Search failed.';
    }

    // ── Navigation ────────────────────────────────────────────────────────
    function _navigate(result) {
        _close();
        input.value = '';
        currentResults = [];

        const config = ENTITY_CONFIG[result.entityType];
        const tab = config ? config.tab : 'overview';

        // Switch workspace tab if API is available
        window.appWorkspaceTabs?.activateTab(`#${tab}`);

        // Navigate away only for cross-page URLs (citations link to a different page)
        if (result.url && !result.url.startsWith('/user/workspace')) {
            // Small delay lets the tab transition start before navigating
            setTimeout(() => { window.location.href = result.url; }, 80);
        }
    }

    // ── Open / close ──────────────────────────────────────────────────────
    function _open() {
        listbox.hidden = false;
        input.setAttribute('aria-expanded', 'true');
    }

    function _close() {
        listbox.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        activeIndex = -1;
        input.removeAttribute('aria-activedescendant');
    }

    // ── Keyboard highlight ────────────────────────────────────────────────
    function _moveActive(delta) {
        const items = Array.from(listbox.querySelectorAll('.app-ws-search__result'));
        if (!items.length) return;

        const currentDomIdx = items.findIndex(el =>
            el.classList.contains('app-ws-search__result--active'));
        let nextDomIdx;
        if (currentDomIdx === -1) {
            nextDomIdx = delta > 0 ? 0 : items.length - 1;
        } else {
            nextDomIdx = Math.max(0, Math.min(currentDomIdx + delta, items.length - 1));
        }

        activeIndex = parseInt(items[nextDomIdx].dataset.index ?? '-1', 10);
        _highlightActive();
        items[nextDomIdx].scrollIntoView({ block: 'nearest' });
    }

    function _highlightActive() {
        listbox.querySelectorAll('.app-ws-search__result').forEach(el => {
            const isActive = parseInt(el.dataset.index ?? '-1', 10) === activeIndex;
            el.classList.toggle('app-ws-search__result--active', isActive);
            el.setAttribute('aria-selected', isActive ? 'true' : 'false');
        });

        if (activeIndex >= 0) {
            input.setAttribute('aria-activedescendant', `ws-result-${activeIndex}`);
        } else {
            input.removeAttribute('aria-activedescendant');
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    function _esc(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}
