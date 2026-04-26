/**
 * Shared bulk-select infrastructure for server-rendered admin tables.
 *
 * Usage:
 *   const bulk = initAdminBulkSelect({ tableKey, fingerprint, cbSelector,
 *     selectAllSelector, barSelector, countSelector, bulkFormId, inputName });
 *   // bulk.getIds()      → Set of currently selected IDs
 *   // bulk.clearAll()    → clear selection everywhere
 *   // bulk.reinit()      → call after DataTables redraws rows
 *
 * sessionStorage key: adminBulk:<tableKey>:<fingerprint>
 * When fingerprint changes (filter set changes), stored selection is discarded.
 */
export function initAdminBulkSelect({
  tableKey,           // unique id for this table, e.g. 'publications'
  fingerprint,        // string representing current filters; change → clear selection
  cbSelector,         // CSS selector for per-row checkboxes, e.g. '.js-bulk-cb'
  selectAllSelector,  // CSS selector for the select-all header checkbox
  barSelector,        // CSS selector for the selection status bar element
  countSelector,      // CSS selector for the "N selected" text element
  bulkFormId,         // id of the bulk-action <form> element
  inputName,          // name attribute for the injected hidden inputs
}) {
  const STORAGE_KEY = 'adminBulk:' + tableKey + ':' + fingerprint;
  let ids = _load();

  function _load() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? new Set(JSON.parse(raw)) : new Set();
    } catch (_) { return new Set(); }
  }

  function _save() {
    try {
      if (ids.size === 0) {
        sessionStorage.removeItem(STORAGE_KEY);
      } else {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]));
      }
    } catch (_) {}
  }

  function _updateBar() {
    const bar = document.querySelector(barSelector);
    const countEl = countSelector ? document.querySelector(countSelector) : null;
    if (bar) {
      bar.classList.toggle('d-none', ids.size === 0);
      if (!bar.getAttribute('aria-live')) {
        bar.setAttribute('aria-live', 'polite');
        bar.setAttribute('role', 'status');
      }
    }
    if (countEl) countEl.textContent = ids.size + (ids.size === 1 ? ' item' : ' items') + ' selected';
  }

  function _syncSelectAll() {
    const sa = document.querySelector(selectAllSelector);
    if (!sa) return;
    const pageCbs = Array.from(document.querySelectorAll(cbSelector));
    if (pageCbs.length === 0) { sa.checked = false; sa.indeterminate = false; return; }
    const checkedCount = pageCbs.filter(cb => ids.has(cb.value)).length;
    sa.checked = checkedCount === pageCbs.length;
    sa.indeterminate = checkedCount > 0 && checkedCount < pageCbs.length;
  }

  function _syncCheckboxes() {
    document.querySelectorAll(cbSelector).forEach(cb => {
      cb.checked = ids.has(cb.value);
    });
    _syncSelectAll();
  }

  function _bindCheckboxes() {
    document.querySelectorAll(cbSelector).forEach(cb => {
      cb.addEventListener('change', () => {
        if (cb.checked) ids.add(cb.value); else ids.delete(cb.value);
        _save();
        _updateBar();
        _syncSelectAll();
      });
    });

    const sa = document.querySelector(selectAllSelector);
    if (sa) {
      sa.addEventListener('change', () => {
        document.querySelectorAll(cbSelector).forEach(cb => {
          cb.checked = sa.checked;
          if (sa.checked) ids.add(cb.value); else ids.delete(cb.value);
        });
        _save();
        _updateBar();
      });
    }
  }

  function _bindForm() {
    const form = bulkFormId ? document.getElementById(bulkFormId) : null;
    if (!form) return;
    form.addEventListener('submit', () => {
      form.querySelectorAll('[data-bulk-inject]').forEach(el => el.remove());
      ids.forEach(id => {
        const inp = document.createElement('input');
        inp.type = 'hidden';
        inp.name = inputName;
        inp.value = id;
        inp.dataset.bulkInject = '1';
        form.appendChild(inp);
      });
    });
  }

  function reinit() {
    _syncCheckboxes();
    _bindCheckboxes();
    _updateBar();
  }

  _bindCheckboxes();
  _syncCheckboxes();
  _bindForm();
  _updateBar();

  return {
    getIds() { return new Set(ids); },
    clearAll() {
      ids.clear();
      _save();
      document.querySelectorAll(cbSelector).forEach(cb => { cb.checked = false; });
      const sa = document.querySelector(selectAllSelector);
      if (sa) { sa.checked = false; sa.indeterminate = false; }
      _updateBar();
    },
    reinit,
  };
}
