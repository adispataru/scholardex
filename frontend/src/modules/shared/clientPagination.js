/**
 * clientPagination.js — H44.3 Shared client-side pagination utility
 *
 * Extracts the duplicate _buildPagination / click-wiring logic from
 * workspacePublications.js and workspaceActivities.js into a single reusable module.
 *
 * Usage:
 *
 *   // In your _renderPage():
 *   paginationContainer.innerHTML = buildPaginationHtml({ page, total, pageSize, label });
 *   wirePaginationClicks(paginationContainer, (newPage) => {
 *     _page = newPage;
 *     _renderPage();
 *   });
 */

const WINDOW_HALF = 2; // page buttons visible on each side of the current page

/**
 * Returns an HTML string for the pagination bar using shared .app-pagination classes.
 *
 * @param {object} opts
 * @param {number} opts.page      - current page, 1-based
 * @param {number} opts.total     - total item count
 * @param {number} opts.pageSize  - items per page
 * @param {string} [opts.label]   - aria-label for the nav element
 */
export function buildPaginationHtml({ page, total, pageSize, label = 'Pagination' }) {
    const pages = Math.max(1, Math.ceil(total / pageSize));
    const from  = Math.min((page - 1) * pageSize + 1, total);
    const to    = Math.min(page * pageSize, total);
    const info  = `Showing ${from}–${to} of ${total}`;

    const prev = `<button class="app-pagination__btn" type="button"
        data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}
        aria-label="Previous page">
      <i class="fa-solid fa-chevron-left" aria-hidden="true"></i>
    </button>`;

    let lo = Math.max(1, page - WINDOW_HALF);
    let hi = Math.min(pages, lo + 2 * WINDOW_HALF);
    lo = Math.max(1, hi - 2 * WINDOW_HALF);
    const nums = [];
    for (let p = lo; p <= hi; p++) {
        nums.push(`<button class="app-pagination__btn${p === page ? ' app-pagination__btn--active' : ''}"
            type="button" data-page="${p}" aria-label="Page ${p}"
            ${p === page ? 'aria-current="page"' : ''}>${p}</button>`);
    }

    const next = `<button class="app-pagination__btn" type="button"
        data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}
        aria-label="Next page">
      <i class="fa-solid fa-chevron-right" aria-hidden="true"></i>
    </button>`;

    return `
<nav class="app-pagination app-pagination--panel-footer" role="navigation" aria-label="${_esc(label)}">
  <span class="app-pagination__info">${_esc(info)}</span>
  <div class="app-pagination__btns">
    ${prev}${nums.join('')}${next}
  </div>
</nav>`;
}

/**
 * Delegates click events on [data-page] buttons inside `container`,
 * calling `onPageChange(newPage)` only for valid in-range page numbers.
 *
 * @param {HTMLElement} container  - element containing the pagination HTML
 * @param {Function}    onPageChange - (newPage: number) => void
 */
export function wirePaginationClicks(container, onPageChange) {
    container.addEventListener('click', (e) => {
        const btn = e.target.closest('[data-page]');
        if (!btn || btn.disabled || btn.classList.contains('disabled')) return;
        const p = parseInt(btn.dataset.page, 10);
        if (!isNaN(p) && p >= 1) onPageChange(p);
    });
}

// XSS-safe escape for aria labels
function _esc(str) {
    return String(str ?? '')
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;');
}
