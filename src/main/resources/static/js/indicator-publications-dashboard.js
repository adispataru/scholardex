(function () {
  function numberValue(raw, fallback) {
    var n = Number(raw);
    return Number.isFinite(n) ? n : fallback;
  }

  function compare(a, b, mode) {
    if (mode === 'title_asc') return a.title.localeCompare(b.title);
    if (mode === 'title_desc') return b.title.localeCompare(a.title);
    if (mode === 'year_desc') return b.year - a.year;
    if (mode === 'year_asc') return a.year - b.year;
    if (mode === 'score_asc') return a.authorScore - b.authorScore;
    return b.authorScore - a.authorScore;
  }

  function normalizeType(value) {
    return (value || '').trim();
  }

  function parseRows(tbody) {
    var rows = [];
    var children = Array.prototype.slice.call(tbody.children);
    for (var i = 0; i < children.length; i++) {
      var row = children[i];
      if (!row.classList.contains('js-publication-row')) continue;

      rows.push({
        row: row,
        search: (row.dataset.search || '').toLowerCase(),
        type: normalizeType(row.dataset.type),
        quarter: (row.dataset.quarter || '').trim(),
        authorScore: numberValue(row.dataset.authorScore, 0),
        year: numberValue(row.dataset.year, 0),
        title: row.querySelector('td') ? row.querySelector('td').textContent.trim() : ''
      });
    }
    return rows;
  }

  function buildTypeOptions(rows, select) {
    var seen = new Set();
    rows.forEach(function (r) {
      if (r.type) seen.add(r.type);
    });
    Array.from(seen).sort().forEach(function (type) {
      var option = document.createElement('option');
      option.value = type;
      option.textContent = type;
      select.appendChild(option);
    });
  }

  function initChart(root, canvasId) {
    if (!window.Chart) return;

    var labelsRaw = root.dataset.quarterLabels || '';
    var valuesRaw = root.dataset.quarterValues || '';
    var labels = labelsRaw ? labelsRaw.split('|').filter(Boolean) : [];
    var values = valuesRaw ? valuesRaw.split('|').map(function (x) { return numberValue(x, 0); }) : [];
    var canvas = document.getElementById(canvasId);
    if (!canvas || labels.length === 0) return;

    new window.Chart(canvas, {
      type: 'doughnut',
      data: {
        labels: labels,
        datasets: [{
          data: values,
          backgroundColor: ['#1d4ed8', '#10b981', '#06b6d4', '#f59e0b', '#ef4444', '#8b5cf6', '#64748b', '#0ea5e9'],
          hoverBorderColor: '#ffffff'
        }]
      },
      options: {
        maintainAspectRatio: false,
        legend: { display: false },
        cutoutPercentage: 70
      }
    });
  }

  function boot() {
    var root = document.getElementById('publications-dashboard-v2');
    if (!root) return;

    var tbody = document.getElementById('publications-main-body');
    var emptyState = document.getElementById('publications-empty-state');
    var countLabel = document.getElementById('publications-count');

    var searchInput = document.getElementById('publications-search');
    var minScoreInput = document.getElementById('publications-min-score');
    var quarterSelect = document.getElementById('publications-quarter');
    var typeSelect = document.getElementById('publications-type');
    var sortSelect = document.getElementById('publications-sort');
    var clearBtn = document.getElementById('publications-clear');

    var rows = parseRows(tbody);
    buildTypeOptions(rows, typeSelect);
    initChart(root, 'publications-quarter-chart');

    function render() {
      var q = (searchInput.value || '').trim().toLowerCase();
      var minScore = numberValue(minScoreInput.value, 0);
      var quarter = quarterSelect.value;
      var type = typeSelect.value;
      var sort = sortSelect.value;

      var filtered = rows.filter(function (item) {
        if (q && item.search.indexOf(q) === -1) return false;
        if (item.authorScore < minScore) return false;
        if (quarter && item.quarter !== quarter) return false;
        if (type && item.type !== type) return false;
        return true;
      });

      filtered.sort(function (a, b) { return compare(a, b, sort); });

      rows.forEach(function (item) {
        item.row.classList.add('d-none');
      });

      filtered.forEach(function (item) {
        tbody.appendChild(item.row);
        item.row.classList.remove('d-none');
      });

      emptyState.classList.toggle('d-none', filtered.length !== 0);
      countLabel.textContent = filtered.length + ' / ' + rows.length + ' publications';
    }

    [searchInput, minScoreInput, quarterSelect, typeSelect, sortSelect].forEach(function (el) {
      el.addEventListener('input', render);
      el.addEventListener('change', render);
    });

    clearBtn.addEventListener('click', function () {
      searchInput.value = '';
      minScoreInput.value = '0';
      quarterSelect.value = '';
      typeSelect.value = '';
      sortSelect.value = 'score_desc';
      render();
    });

    render();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
