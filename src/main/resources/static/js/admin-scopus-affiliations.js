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
    search: document.getElementById('admin-affiliations-search'),
    sort: document.getElementById('admin-affiliations-sort'),
    direction: document.getElementById('admin-affiliations-direction'),
    size: document.getElementById('admin-affiliations-size'),
    loading: document.getElementById('admin-affiliations-loading'),
    error: document.getElementById('admin-affiliations-error'),
    empty: document.getElementById('admin-affiliations-empty'),
    tableBody: document.getElementById('admin-affiliations-table-body'),
    pageInfo: document.getElementById('admin-affiliations-page-info'),
    totalInfo: document.getElementById('admin-affiliations-total-info'),
    prev: document.getElementById('admin-affiliations-prev'),
    next: document.getElementById('admin-affiliations-next')
  };

  function setLoading(isLoading) {
    if (isLoading) {
      els.loading.classList.remove('d-none');
    } else {
      els.loading.classList.add('d-none');
    }
  }

  function setError(message) {
    if (!message) {
      els.error.classList.add('d-none');
      els.error.textContent = '';
      return;
    }
    els.error.textContent = message;
    els.error.classList.remove('d-none');
  }

  function setEmpty(isEmpty) {
    if (isEmpty) {
      els.empty.classList.remove('d-none');
    } else {
      els.empty.classList.add('d-none');
    }
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
    if (!items || items.length === 0) {
      els.tableBody.innerHTML = '';
      return;
    }

    const html = items.map(function (item) {
      const id = encodeURIComponent(item.afid || '');
      return '<tr>' +
        '<td><a href="/admin/scopus/affiliations/edit/' + id + '">' + escapeHtml(item.name) + '</a></td>' +
        '<td>' + escapeHtml(item.afid) + '</td>' +
        '<td>' + escapeHtml(item.city) + '</td>' +
        '<td>' + escapeHtml(item.country) + '</td>' +
        '</tr>';
    }).join('');

    els.tableBody.innerHTML = html;
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
    return '/api/scopus/affiliations?' + params.toString();
  }

  async function fetchPage() {
    setLoading(true);
    setError(null);

    try {
      const response = await fetch(buildUrl(), {
        headers: { Accept: 'application/json' }
      });

      if (response.status === 401) {
        setError('Your session has expired. Please log in again.');
        setEmpty(false);
        els.tableBody.innerHTML = '';
        totalItems = 0;
        totalPages = 0;
        updatePager();
        return;
      }

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
        throw new Error('Unexpected error while loading affiliations.');
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
      setError(error.message || 'Could not load affiliations.');
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

    els.sort.addEventListener('change', function () {
      state.sort = els.sort.value;
      state.page = 0;
      fetchPage();
    });

    els.direction.addEventListener('change', function () {
      state.direction = els.direction.value;
      state.page = 0;
      fetchPage();
    });

    els.size.addEventListener('change', function () {
      state.size = Number(els.size.value);
      state.page = 0;
      fetchPage();
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
    if (!els.search || !els.sort || !els.direction || !els.size || !els.loading || !els.error || !els.empty || !els.tableBody || !els.pageInfo || !els.totalInfo || !els.prev || !els.next) {
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
