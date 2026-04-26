(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'name',
    direction: 'asc',
    q: ''
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('admin-authors-search'),
    sort: document.getElementById('admin-authors-sort'),
    direction: document.getElementById('admin-authors-direction'),
    size: document.getElementById('admin-authors-size'),
    loading: document.getElementById('admin-authors-loading'),
    error: document.getElementById('admin-authors-error'),
    empty: document.getElementById('admin-authors-empty'),
    tableBody: document.getElementById('admin-authors-table-body'),
    pageInfo: document.getElementById('admin-authors-page-info'),
    totalInfo: document.getElementById('admin-authors-total-info'),
    prev: document.getElementById('admin-authors-prev'),
    next: document.getElementById('admin-authors-next')
  };

  function setLoading(isLoading) { els.loading.classList.toggle('d-none', !isLoading); }
  function setError(message) {
    els.error.textContent = message || '';
    els.error.classList.toggle('d-none', !message);
  }
  function setEmpty(isEmpty) { els.empty.classList.toggle('d-none', !isEmpty); }

  function updatePager() {
    if (totalItems === 0) {
      els.pageInfo.textContent = 'Page 0 of 0';
      els.totalInfo.textContent = '0 results';
      els.prev.disabled = true;
      els.next.disabled = true;
      return;
    }
    els.pageInfo.textContent = 'Page ' + (state.page + 1) + ' of ' + totalPages;
    els.totalInfo.textContent = totalItems + ' results';
    els.prev.disabled = state.page <= 0;
    els.next.disabled = state.page >= totalPages - 1;
  }

  function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function renderRows(items) {
    els.tableBody.innerHTML = (items || []).map(function (item) {
      const affiliations = (item.affiliations || []).map(escapeHtml).join(', ');
      const id = encodeURIComponent(item.id || '');
      const profileHref = '/user/authors/view/' + id;
      const pubsHref = '/admin/scholardex/publications?authorId=' + id;
      return '<tr>' +
        '<td data-col="name"><a href="' + profileHref + '">' + escapeHtml(item.name) + '</a></td>' +
        '<td data-col="affiliations">' + (affiliations || '<span class="text-muted">No affiliations</span>') + '</td>' +
        '<td data-col="actions"><a class="btn btn-outline-secondary btn-sm" href="' + pubsHref + '" aria-label="View publications for this author"><i class="fa-solid fa-file-lines fa-xs"></i> Publications</a></td>' +
        '</tr>';
    }).join('');
    if (window._authorsColToggle) window._authorsColToggle.reinit();
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    if (state.q) params.set('q', state.q);
    return '/api/entities/authors?' + params.toString();
  }

  async function fetchPage() {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(buildUrl(), { headers: { Accept: 'application/json' } });
      if (response.status === 400) {
        const body = await response.json().catch(function () { return {}; });
        throw new Error(body.message || 'Invalid filter parameters.');
      }
      if (!response.ok) throw new Error('Unexpected error while loading authors.');
      const body = await response.json();
      totalItems = Number(body.totalItems || 0);
      totalPages = Number(body.totalPages || 0);
      state.page = Number(body.page || 0);
      state.size = Number(body.size || state.size);
      renderRows(body.items || []);
      setEmpty((body.items || []).length === 0);
      updatePager();
    } catch (error) {
      setError(error.message || 'Could not load authors.');
      els.tableBody.innerHTML = '';
      totalItems = 0;
      totalPages = 0;
      setEmpty(false);
      updatePager();
    } finally {
      setLoading(false);
    }
  }

  function bindEvents() {
    els.search.addEventListener('input', function () {
      const value = els.search.value.trim();
      if (searchDebounce) clearTimeout(searchDebounce);
      searchDebounce = setTimeout(function () {
        state.q = value;
        state.page = 0;
        fetchPage();
      }, 300);
    });
    [els.sort, els.direction, els.size].forEach(function (element) {
      element.addEventListener('change', function () {
        state.sort = els.sort.value;
        state.direction = els.direction.value;
        state.size = Number(els.size.value);
        state.page = 0;
        fetchPage();
      });
    });
    els.prev.addEventListener('click', function () {
      if (state.page <= 0) return;
      state.page -= 1;
      fetchPage();
    });
    els.next.addEventListener('click', function () {
      if (state.page >= totalPages - 1) return;
      state.page += 1;
      fetchPage();
    });
  }

  function initialize() {
    if (Object.values(els).some(function (value) { return !value; })) return;
    bindEvents();
    updatePager();
    fetchPage();
    var toolbarActionsEl = document.getElementById('authors-toolbar-actions');
    var tableEl = document.getElementById('admin-authors-table');
    if (window.initAdminColumnToggle && toolbarActionsEl && tableEl) {
      window._authorsColToggle = window.initAdminColumnToggle({
        tableId: 'authors',
        tableEl: tableEl,
        toolbarActionsEl: toolbarActionsEl,
        columns: [
          { key: 'name',         label: 'Name',         required: true },
          { key: 'affiliations', label: 'Affiliations', required: false },
          { key: 'actions',      label: 'Actions',      required: true },
        ],
      });
    }
    var tbody = tableEl ? tableEl.querySelector('tbody') : null;
    if (window.initAdminShortcuts && tbody) {
      window.initAdminShortcuts({
        sections: [{
          title: 'Row Actions',
          rows: [{ keys: ['e', 'Enter'], label: 'View publications for focused author' }],
        }],
        tables: [{
          tbodyEl: tbody,
          keyActions: {
            'e':     function(tr) { var a = tr.querySelector('[data-col="actions"] a'); if (a) window.location.href = a.href; },
            'Enter': function(tr) { var a = tr.querySelector('[data-col="actions"] a'); if (a) window.location.href = a.href; },
          },
        }],
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
