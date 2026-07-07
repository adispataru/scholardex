/* Shared clickable-header sorting for the server-paged ranking tables. Headers opt in with a
 * data-sort-key attribute whose value is the API's sort parameter. Reuses the .app-sortable-th
 * arrow styling from the client-side detail tables (shared-table.css); here a click hands the
 * key + direction to the caller, which drives its server fetch. */
(function () {
  'use strict';

  /**
   * tableEl: the <table>; onSort(key, direction) fires on header click.
   * Returns { update(sort, direction) } to sync the arrows with externally-driven state
   * (e.g. the Sort By dropdown).
   */
  function attach(tableEl, onSort) {
    const headers = Array.prototype.slice.call(
      tableEl.querySelectorAll('thead th[data-sort-key]'));
    let current = { key: null, direction: 'asc' };

    function update(sort, direction) {
      current = { key: sort, direction: direction };
      headers.forEach(function (th) {
        if (th.dataset.sortKey === sort) {
          th.setAttribute('data-sort-dir', direction);
          th.setAttribute('aria-sort', direction === 'asc' ? 'ascending' : 'descending');
        } else {
          th.removeAttribute('data-sort-dir');
          th.removeAttribute('aria-sort');
        }
      });
    }

    headers.forEach(function (th) {
      th.classList.add('app-sortable-th');
      th.setAttribute('tabindex', '0');
      th.setAttribute('role', 'button');
      function activate() {
        const key = th.dataset.sortKey;
        const direction = (current.key === key && current.direction === 'asc') ? 'desc' : 'asc';
        update(key, direction);
        onSort(key, direction);
      }
      th.addEventListener('click', activate);
      th.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          activate();
        }
      });
    });

    return { update: update };
  }

  window.appTableSort = { attach: attach };
})();
