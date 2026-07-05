(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'categoryName',
    direction: 'asc',
    q: '',
    metric: 'AIS'
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
    next: document.getElementById('wos-categories-next'),
    metricToggle: document.getElementById('wos-categories-metric'),
    yearHeader: document.getElementById('wos-categories-year-header'),
    avgHeader: document.getElementById('wos-categories-avg-header'),
    topHeader: document.getElementById('wos-categories-top-header'),
    trendHeader: document.getElementById('wos-categories-trend-header')
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

  function fmtNum(value, digits) {
    if (value == null || isNaN(value)) return '—';
    return Number(value).toFixed(digits == null ? 2 : digits);
  }

  // Inline SVG sparkline of a category's average-AIS trend. Colours by direction (rising = success),
  // via currentColor so it inherits the app theme. Returns a muted dash for <2 points.
  function sparklineSvg(trend) {
    const pts = (trend || []).filter(function (p) { return p && p.avg != null; });
    if (pts.length < 2) {
      return '<span class="app-spark app-spark--empty" aria-hidden="true">—</span>';
    }
    const w = 88, h = 24, pad = 3;
    const ys = pts.map(function (p) { return p.avg; });
    const minX = pts[0].year, maxX = pts[pts.length - 1].year;
    const minY = Math.min.apply(null, ys), maxY = Math.max.apply(null, ys);
    const spanX = (maxX - minX) || 1, spanY = (maxY - minY) || 1;
    const coords = pts.map(function (p) {
      return [
        pad + (p.year - minX) / spanX * (w - 2 * pad),
        h - pad - (p.avg - minY) / spanY * (h - 2 * pad)
      ];
    });
    const d = coords.map(function (c, i) {
      return (i === 0 ? 'M' : 'L') + c[0].toFixed(1) + ' ' + c[1].toFixed(1);
    }).join(' ');
    const last = coords[coords.length - 1];
    const rising = ys[ys.length - 1] >= ys[0];
    const title = 'Avg ' + state.metric + ' ' + minX + '–' + maxX + ': ' + fmtNum(ys[0]) + ' → ' + fmtNum(ys[ys.length - 1]);
    return '<svg class="app-spark ' + (rising ? 'app-spark--up' : 'app-spark--down') + '" width="' + w +
      '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="' + escapeHtml(title) + '">' +
      '<title>' + escapeHtml(title) + '</title>' +
      '<path d="' + d + '" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<circle cx="' + last[0].toFixed(1) + '" cy="' + last[1].toFixed(1) + '" r="2" fill="currentColor"/></svg>';
  }

  function renderRows(items) {
    const detailBase = els.table.dataset.detailBase || '/wos/categories';
    els.tableBody.innerHTML = (items || []).map(function (item) {
      const key = encodeURIComponent(item.key || '');
      // a stale year (behind the dataset-wide latest for the metric) marks a retired category
      const yearCell = item.year == null
        ? '—'
        : (item.stale
            ? '<span class="text-muted" title="Last year with ' + escapeHtml(state.metric) + ' data — this category is no longer published">' + escapeHtml(item.year) + '</span>'
            : escapeHtml(item.year));
      return '<tr>' +
        '<td><a href="' + detailBase + '/' + key + '">' + escapeHtml(item.categoryName) + '</a></td>' +
        '<td class="app-table__cell--identifier">' + escapeHtml(item.edition || '—') + '</td>' +
        '<td class="app-table__cell--numeric">' + escapeHtml(item.journalCount == null ? '—' : item.journalCount) + '</td>' +
        '<td class="app-table__cell--numeric">' + yearCell + '</td>' +
        '<td class="app-table__cell--numeric">' + fmtNum(item.avg) + '</td>' +
        '<td class="app-table__cell--numeric">' + fmtNum(item.top) + '</td>' +
        '<td class="app-spark-cell">' + sparklineSvg(item.trend) + '</td>' +
        '</tr>';
    }).join('');
  }

  function updateMetricHeaders() {
    if (els.avgHeader) els.avgHeader.textContent = 'Avg ' + state.metric;
    if (els.topHeader) els.topHeader.textContent = 'Top ' + state.metric;
    if (els.trendHeader) els.trendHeader.textContent = state.metric + ' trend';
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    params.set('metric', state.metric);
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
    if (els.metricToggle) {
      els.metricToggle.querySelectorAll('[data-metric]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          if (btn.getAttribute('data-metric') === state.metric) return;
          state.metric = btn.getAttribute('data-metric');
          state.page = 0;
          els.metricToggle.querySelectorAll('[data-metric]').forEach(function (b) {
            const active = b === btn;
            b.classList.toggle('btn-primary', active);
            b.classList.toggle('btn-outline-primary', !active);
            b.setAttribute('aria-pressed', String(active));
          });
          updateMetricHeaders();
          fetchPage();
        });
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
