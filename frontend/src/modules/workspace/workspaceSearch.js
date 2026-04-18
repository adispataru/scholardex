/**
 * workspaceSearch.js — Workspace live filter
 *
 * The header search input filters the currently active workspace tab in real
 * time. No dropdown, no server round-trip.
 *
 * Global shortcut: "/" or Ctrl+K focuses the input.
 * Tab switch: clears the query so each tab starts unfiltered.
 */

export function initWorkspaceSearch() {
    const root = document.getElementById('ws-search-root');
    if (!root) return;

    const input = root.querySelector('#ws-search-input');
    if (!input) return;

    // ── Global shortcut: / or Ctrl+K ─────────────────────────────────────
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

    // ── Live filter ───────────────────────────────────────────────────────
    input.addEventListener('input', () => {
        _applyFilter(input.value.trim());
    });

    // ── Clear on tab switch ───────────────────────────────────────────────
    window.addEventListener('popstate', () => {
        input.value = '';
        _applyFilter('');
    });

    function _applyFilter(q) {
        const tab = window.location.hash.slice(1);
        if (tab === 'publications') {
            window.appWorkspacePublications?.filterBy(q);
        } else if (tab === 'activities') {
            window.appWorkspaceActivities?.filterBy(q);
        }
    }
}
