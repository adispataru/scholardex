(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'publicationName',
    direction: 'asc',
    q: '',
    wos: 'all'
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('admin-forums-search'),
    sort: document.getElementById('admin-forums-sort'),
    direction: document.getElementById('admin-forums-direction'),
    wos: document.getElementById('admin-forums-wos'),
    size: document.getElementById('admin-forums-size'),
    loading: document.getElementById('admin-forums-loading'),
    error: document.getElementById('admin-forums-error'),
    empty: document.getElementById('admin-forums-empty'),
    tableBody: document.getElementById('admin-forums-table-body'),
    pageInfo: document.getElementById('admin-forums-page-info'),
    totalInfo: document.getElementById('admin-forums-total-info'),
    prev: document.getElementById('admin-forums-prev'),
    next: document.getElementById('admin-forums-next')
  };

  function labelWosStatus(status) {
    switch (status) {
      case 'indexed':
        return 'WoS indexed';
      case 'not_indexed':
        return 'Not indexed by WoS';
      case 'not_applicable':
        return 'Not applicable';
      default:
        return status || '';
    }
  }

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
    els.tableBody.innerHTML = (items || []).map(function (item) {
      const id = encodeURIComponent(item.id || '');
      return '<tr>' +
        '<td><a href="/scholardex/forums/' + id + '">' + escapeHtml(item.publicationName) + '</a></td>' +
        '<td>' + escapeHtml(item.issn) + '</td>' +
        '<td>' + escapeHtml(item.eIssn) + '</td>' +
        '<td>' + escapeHtml(item.aggregationType) + '</td>' +
        '<td>' + escapeHtml(labelWosStatus(item.wosStatus)) + '</td>' +
        '<td><a class="btn btn-outline-secondary btn-sm" href="/admin/scholardex/forums/edit/' + id + '">Edit</a></td>' +
        '</tr>';
    }).join('');
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    params.set('wos', state.wos);
    if (state.q) {
      params.set('q', state.q);
    }
    return '/scholardex/forums/data?' + params.toString();
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
      if (!response.ok) {
        throw new Error('Unexpected error while loading forums.');
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
      setError(error.message || 'Could not load forums.');
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
      if (searchDebounce) {
        clearTimeout(searchDebounce);
      }
      searchDebounce = setTimeout(function () {
        state.q = value;
        state.page = 0;
        fetchPage();
      }, 300);
    });

    [els.sort, els.direction, els.wos, els.size].forEach(function (element) {
      element.addEventListener('change', function () {
        state.sort = els.sort.value;
        state.direction = els.direction.value;
        state.wos = els.wos.value;
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
    if (Object.values(els).some(function (value) { return !value; })) {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    if (params.has('wos')) {
      state.wos = params.get('wos');
      els.wos.value = state.wos;
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
