(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'title',
    direction: 'asc',
    q: ''
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('publications-search'),
    sort: document.getElementById('publications-sort'),
    direction: document.getElementById('publications-direction'),
    size: document.getElementById('publications-size'),
    loading: document.getElementById('publications-loading'),
    error: document.getElementById('publications-error'),
    empty: document.getElementById('publications-empty'),
    tableBody: document.getElementById('publications-table-body'),
    pageInfo: document.getElementById('publications-page-info'),
    totalInfo: document.getElementById('publications-total-info'),
    prev: document.getElementById('publications-prev'),
    next: document.getElementById('publications-next')
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
    els.totalInfo.textContent = totalItems.toLocaleString() + ' results';
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

    els.tableBody.innerHTML = items.map(function (item) {
      const forumHref = item.forumId ? '/forums/' + encodeURIComponent(item.forumId) : null;
      const publicationHref = item.id ? '/publications/' + encodeURIComponent(item.id) : null;

      const titleCell = publicationHref
        ? '<a href="' + publicationHref + '">' + escapeHtml(item.title || '—') + '</a>'
        : escapeHtml(item.title || '—');

      const forumCell = item.forumName
        ? (forumHref
            ? '<a href="' + forumHref + '">' + escapeHtml(item.forumName) + '</a>'
            : escapeHtml(item.forumName))
        : '<span class="app-table__cell--muted">—</span>';

      const authors = Array.isArray(item.authorNames) && item.authorNames.length > 0
        ? escapeHtml(item.authorNames.join(', '))
        : '<span class="app-table__cell--muted">—</span>';

      const citations = typeof item.citedByCount === 'number'
        ? item.citedByCount.toLocaleString()
        : '0';

      const eid = item.eid
        ? '<span class="app-table__cell--identifier">' + escapeHtml(item.eid) + '</span>'
        : '<span class="app-table__cell--muted">—</span>';

      return '<tr>' +
        '<td>' + titleCell + '</td>' +
        '<td class="app-table__cell--identifier">' + escapeHtml(item.year || '—') + '</td>' +
        '<td>' + forumCell + '</td>' +
        '<td class="app-table__cell--secondary">' + authors + '</td>' +
        '<td class="app-table__cell--numeric">' + citations + '</td>' +
        '<td>' + eid + '</td>' +
        '</tr>';
    }).join('');
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    if (state.q) params.set('q', state.q);
    return '/publications/data?' + params.toString();
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
        throw new Error('Unexpected error while loading publications.');
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
      setError(error.message || 'Could not load publications.');
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
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
