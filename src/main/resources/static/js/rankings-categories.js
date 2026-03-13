(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'categoryName',
    direction: 'asc',
    q: ''
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('wos-categories-search'),
    sort: document.getElementById('wos-categories-sort'),
    direction: document.getElementById('wos-categories-direction'),
    size: document.getElementById('wos-categories-size'),
    loading: document.getElementById('wos-categories-loading'),
    error: document.getElementById('wos-categories-error'),
    empty: document.getElementById('wos-categories-empty'),
    table: document.getElementById('wos-categories-table'),
    tableBody: document.getElementById('wos-categories-table-body'),
    pageInfo: document.getElementById('wos-categories-page-info'),
    totalInfo: document.getElementById('wos-categories-total-info'),
    prev: document.getElementById('wos-categories-prev'),
    next: document.getElementById('wos-categories-next')
  };

  function setLoading(isLoading) {
    els.loading.classList.toggle('d-none', !isLoading);
  }

  function setError(message) {
    els.error.textContent = message || '';
    els.error.classList.toggle('d-none', !message);
  }

  function setEmpty(isEmpty) {
    els.empty.classList.toggle('d-none', !isEmpty);
  }

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
    const detailBase = els.table.dataset.detailBase || '/rankings/categories';
    els.tableBody.innerHTML = (items || []).map(function (item) {
      const key = encodeURIComponent(item.key || '');
      return '<tr>' +
        '<td><a href="' + detailBase + '/' + key + '">' + escapeHtml(item.categoryName) + '</a></td>' +
        '<td>' + escapeHtml(item.edition) + '</td>' +
        '<td>' + escapeHtml(item.journalCount) + '</td>' +
        '<td>' + escapeHtml(item.latestYear == null ? '—' : item.latestYear) + '</td>' +
        '</tr>';
    }).join('');
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    if (state.q) {
      params.set('q', state.q);
    }
    return '/api/rankings/categories?' + params.toString();
  }

  async function fetchPage() {
    setLoading(true);
    setError(null);

    try {
      const response = await fetch(buildUrl(), { headers: { Accept: 'application/json' } });

      if (response.status === 400) {
        let msg = 'Invalid filter parameters.';
        try {
          const body = await response.json();
          if (body && body.message) {
            msg = body.message;
          }
        } catch (ignored) {
        }
        setError(msg);
        setEmpty(false);
        els.tableBody.innerHTML = '';
        totalItems = 0;
        totalPages = 0;
        updatePager();
        return;
      }

      if (!response.ok) {
        throw new Error('Unexpected error while loading categories.');
      }

      const body = await response.json();
      totalItems = Number(body.totalItems || 0);
      totalPages = Number(body.totalPages || 0);
      state.page = Number(body.page || 0);
      state.size = Number(body.size || state.size);

      renderRows(body.items || []);
      setEmpty((body.items || []).length === 0);
      updatePager();
    } catch (error) {
      setError(error.message || 'Could not load categories.');
      setEmpty(false);
      els.tableBody.innerHTML = '';
      totalItems = 0;
      totalPages = 0;
      updatePager();
    } finally {
      setLoading(false);
    }
  }

  function bindEvents() {
    els.search.addEventListener('input', function () {
      const value = els.search.value.trim();
      if (searchDebounce) {
        clearTimeout(searchDebounce);
      }
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
      if (state.page <= 0) {
        return;
      }
      state.page -= 1;
      fetchPage();
    });

    els.next.addEventListener('click', function () {
      if (state.page >= totalPages - 1) {
        return;
      }
      state.page += 1;
      fetchPage();
    });
  }

  function initialize() {
    if (Object.values(els).some(function (value) { return !value; })) {
      return;
    }
    bindEvents();
    updatePager();
    fetchPage();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
