(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'name',
    direction: 'asc',
    q: '',
    metric: 'rank'
  };
  let lastItems = [];

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('urap-search'),
    sort: document.getElementById('urap-sort'),
    direction: document.getElementById('urap-direction'),
    size: document.getElementById('urap-size'),
    loading: document.getElementById('urap-loading'),
    error: document.getElementById('urap-error'),
    empty: document.getElementById('urap-empty'),
    table: document.getElementById('urap-table'),
    tableBody: document.getElementById('urap-table-body'),
    metricToggle: document.getElementById('urap-metric'),
    trendHeader: document.getElementById('urap-trend-header'),
    pageInfo: document.getElementById('urap-page-info'),
    totalInfo: document.getElementById('urap-total-info'),
    prev: document.getElementById('urap-prev'),
    next: document.getElementById('urap-next')
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

  function formatNumber(value) {
    if (value == null) {
      return '';
    }
    return String(value);
  }

  // Trend of the selected metric over the last URAP editions. Both metrics ship in the same
  // payload, so the Rank | Total Score toggle re-renders without a refetch. Rank improves
  // DOWNWARD, so its direction colouring is inverted (higherIsBetter: false).
  function trendSparkline(trend) {
    const isRank = state.metric === 'rank';
    const pts = (trend || [])
      .filter(function (p) { return p && p[state.metric] != null; })
      .map(function (p) { return { x: p.year, y: p[state.metric], label: formatNumber(p[state.metric]) }; });
    const title = pts.length >= 2
      ? (isRank ? 'Rank ' : 'Total score ') + pts[0].x + '\u2013' + pts[pts.length - 1].x + ': ' +
        pts[0].label + ' \u2192 ' + pts[pts.length - 1].label
      : null;
    return window.appSparkline.render(pts, { title: title, higherIsBetter: !isRank });
  }

  function renderRows(items) {
    lastItems = items || [];
    if (lastItems.length === 0) {
      els.tableBody.innerHTML = '';
      return;
    }

    const detailBase = els.table.dataset.detailBase || '/universities';
    const html = lastItems.map(function (item) {
      const id = encodeURIComponent(item.id || '');
      return '<tr>' +
        '<td class="app-table__cell--numeric">' + escapeHtml(item.rank == null ? '\u2014' : item.rank) + '</td>' +
        '<td><a href="' + detailBase + '/' + id + '">' + escapeHtml(item.name) + '</a></td>' +
        '<td>' + escapeHtml(item.country) + '</td>' +
        '<td class="app-table__cell--numeric">' + escapeHtml(item.year == null ? '\u2014' : item.year) + '</td>' +
        '<td class="app-table__cell--numeric">' + formatNumber(item.total) + '</td>' +
        '<td class="app-spark-cell">' + trendSparkline(item.trend) + '</td>' +
        '</tr>';
    }).join('');

    els.tableBody.innerHTML = html;
  }

  function updateMetricToggle() {
    if (els.trendHeader) {
      els.trendHeader.textContent = state.metric === 'rank' ? 'Rank trend' : 'Score trend';
    }
    if (!els.metricToggle) return;
    els.metricToggle.querySelectorAll('button[data-metric]').forEach(function (btn) {
      const active = btn.dataset.metric === state.metric;
      btn.classList.toggle('btn-primary', active);
      btn.classList.toggle('btn-outline-primary', !active);
      btn.setAttribute('aria-pressed', String(active));
    });
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
    return '/api/rankings/urap?' + params.toString();
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
          // Keep default message
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
        throw new Error('Unexpected error while loading rankings.');
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
      setError(error.message || 'Could not load rankings.');
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
    if (els.metricToggle) {
      els.metricToggle.addEventListener('click', function (event) {
        const btn = event.target.closest('button[data-metric]');
        if (!btn || btn.dataset.metric === state.metric) return;
        state.metric = btn.dataset.metric;
        updateMetricToggle();
        renderRows(lastItems);
      });
    }

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
    if (!els.search || !els.sort || !els.direction || !els.size || !els.loading || !els.error || !els.empty || !els.table || !els.tableBody || !els.pageInfo || !els.totalInfo || !els.prev || !els.next) {
      return;
    }
    bindEvents();
    updateMetricToggle();
    updatePager();
    fetchPage();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
