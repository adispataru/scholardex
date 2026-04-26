/**
 * Shared column-visibility toggle for admin tables.
 *
 * Usage:
 *   initAdminColumnToggle({
 *     tableId,           // unique key for localStorage, e.g. 'publications'
 *     tableEl,           // <table> DOM element
 *     toolbarActionsEl,  // element to inject the "Columns" button into
 *     columns,           // [{key, label, required}]
 *   });
 *
 * Each <th> and <td> in the target table must carry a data-col="key" attribute.
 * Hidden columns get the class .app-col--hidden (display:none via CSS).
 * Preferences persist in localStorage under adminColVis:<tableId>.
 */
export function initAdminColumnToggle({ tableId, tableEl, toolbarActionsEl, columns }) {
  const STORAGE_KEY = 'adminColVis:' + tableId;

  function _load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? new Set(JSON.parse(raw)) : new Set();
    } catch (_) { return new Set(); }
  }

  function _save(hidden) {
    try {
      if (hidden.size === 0) {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, JSON.stringify([...hidden]));
      }
    } catch (_) {}
  }

  function _applyVisibility(hidden) {
    columns.forEach(({ key }) => {
      const isHidden = hidden.has(key);
      tableEl.querySelectorAll('[data-col="' + key + '"]').forEach(el => {
        el.classList.toggle('app-col--hidden', isHidden);
      });
    });
  }

  function _buildDropdown(hidden) {
    const wrap = document.createElement('div');
    wrap.className = 'app-col-toggle';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-outline-secondary btn-sm app-col-toggle__btn';
    btn.setAttribute('aria-haspopup', 'true');
    btn.setAttribute('aria-expanded', 'false');
    btn.setAttribute('aria-controls', 'col-toggle-menu-' + tableId);
    btn.innerHTML = '<i class="fa-solid fa-table-columns fa-xs" aria-hidden="true"></i> Columns';

    const menu = document.createElement('div');
    menu.className = 'app-col-toggle__menu';
    menu.id = 'col-toggle-menu-' + tableId;
    menu.setAttribute('role', 'group');
    menu.setAttribute('aria-label', 'Column visibility');
    menu.hidden = true;

    columns.forEach(({ key, label, required }) => {
      const item = document.createElement('label');
      item.className = 'app-col-toggle__item' + (required ? ' app-col-toggle__item--required' : '');

      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = !hidden.has(key);
      cb.disabled = !!required;
      cb.setAttribute('data-col-key', key);

      const span = document.createElement('span');
      span.textContent = label;
      if (required) {
        const lock = document.createElement('i');
        lock.className = 'fa-solid fa-lock fa-xs app-col-toggle__lock';
        lock.setAttribute('aria-hidden', 'true');
        span.appendChild(document.createTextNode('\u00a0'));
        span.appendChild(lock);
      }

      item.appendChild(cb);
      item.appendChild(span);
      menu.appendChild(item);

      cb.addEventListener('change', () => {
        const currentHidden = _load();
        if (cb.checked) currentHidden.delete(key); else currentHidden.add(key);
        _save(currentHidden);
        _applyVisibility(currentHidden);
      });
    });

    wrap.appendChild(btn);
    wrap.appendChild(menu);

    // Toggle open/close; move focus to first checkbox when opening via keyboard
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = !menu.hidden;
      menu.hidden = open;
      btn.setAttribute('aria-expanded', String(!open));
      if (!open) {
        const firstCb = menu.querySelector('input[type="checkbox"]:not(:disabled)');
        if (firstCb) firstCb.focus();
      }
    });

    // Close on outside click
    document.addEventListener('click', (e) => {
      if (!wrap.contains(e.target)) {
        menu.hidden = true;
        btn.setAttribute('aria-expanded', 'false');
      }
    });

    // Close on Escape
    menu.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        menu.hidden = true;
        btn.setAttribute('aria-expanded', 'false');
        btn.focus();
      }
    });

    return wrap;
  }

  const hidden = _load();
  _applyVisibility(hidden);

  const dropdownEl = _buildDropdown(hidden);
  toolbarActionsEl.appendChild(dropdownEl);

  return {
    reinit() { _applyVisibility(_load()); },
  };
}
