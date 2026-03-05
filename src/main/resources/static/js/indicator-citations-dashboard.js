(function () {
  function numberValue(raw, fallback) {
    var n = Number(raw);
    return Number.isFinite(n) ? n : fallback;
  }

  function compare(a, b, mode) {
    if (mode === 'title_asc') {
      return a.title.localeCompare(b.title);
    }
    if (mode === 'title_desc') {
      return b.title.localeCompare(a.title);
    }
    if (mode === 'count_asc') {
      return a.citationCount - b.citationCount;
    }
    if (mode === 'count_desc') {
      return b.citationCount - a.citationCount;
    }
    if (mode === 'score_asc') {
      return a.authorScore - b.authorScore;
    }
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
      if (!row.classList.contains('js-citation-main')) {
        continue;
      }
      var detail = row.nextElementSibling;
      rows.push({
        key: row.dataset.key,
        main: row,
        detail: detail,
        search: (row.dataset.search || '').toLowerCase(),
        type: normalizeType(row.dataset.type),
        quarter: (row.dataset.quarter || '').trim(),
        authorScore: numberValue(row.dataset.authorScore, 0),
        citationCount: numberValue(row.dataset.citationCount, 0),
        title: row.querySelector('td') ? row.querySelector('td').textContent.trim() : ''
      });
    }
    return rows;
  }

  function buildTypeOptions(rows, select) {
    var seen = new Set();
    rows.forEach(function (r) {
      if (r.type) {
        seen.add(r.type);
      }
    });
    Array.from(seen).sort().forEach(function (type) {
      var option = document.createElement('option');
      option.value = type;
      option.textContent = type;
      select.appendChild(option);
    });
  }

  function setExpanded(item, expanded, expandedMap) {
    expandedMap.set(item.key, expanded);
    if (item.detail) {
      item.detail.classList.toggle('d-none', !expanded);
    }
    var btn = item.main.querySelector('.js-expand');
    if (btn) {
      btn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
      btn.textContent = expanded ? 'Hide' : 'Details';
    }
  }

  function initChart(root) {
    if (!window.Chart) {
      return;
    }
    var labelsRaw = root.dataset.quarterLabels || '';
    var valuesRaw = root.dataset.quarterValues || '';
    var labels = labelsRaw ? labelsRaw.split('|').filter(Boolean) : [];
    var values = valuesRaw ? valuesRaw.split('|').map(function (x) { return numberValue(x, 0); }) : [];
    var canvas = document.getElementById('citations-quarter-chart');
    if (!canvas || labels.length === 0) {
      return;
    }

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
    var root = document.getElementById('citations-dashboard-v2');
    if (!root) {
      return;
    }

    var tbody = document.getElementById('citations-main-body');
    var emptyState = document.getElementById('citations-empty-state');
    var countLabel = document.getElementById('citations-count');

    var searchInput = document.getElementById('citations-search');
    var minScoreInput = document.getElementById('citations-min-score');
    var quarterSelect = document.getElementById('citations-quarter');
    var typeSelect = document.getElementById('citations-type');
    var sortSelect = document.getElementById('citations-sort');
    var clearBtn = document.getElementById('citations-clear');

    var rows = parseRows(tbody);
    var expandedMap = new Map();

    buildTypeOptions(rows, typeSelect);
    initChart(root);

    function render() {
      var q = (searchInput.value || '').trim().toLowerCase();
      var minScore = numberValue(minScoreInput.value, 0);
      var quarter = quarterSelect.value;
      var type = typeSelect.value;
      var sort = sortSelect.value;

      var filtered = rows.filter(function (item) {
        if (q && item.search.indexOf(q) === -1) {
          return false;
        }
        if (item.authorScore < minScore) {
          return false;
        }
        if (quarter && item.quarter !== quarter) {
          return false;
        }
        if (type && item.type !== type) {
          return false;
        }
        return true;
      });

      filtered.sort(function (a, b) { return compare(a, b, sort); });

      rows.forEach(function (item) {
        item.main.classList.add('d-none');
        if (item.detail) {
          item.detail.classList.add('d-none');
        }
      });

      filtered.forEach(function (item) {
        tbody.appendChild(item.main);
        if (item.detail) {
          tbody.appendChild(item.detail);
        }
        item.main.classList.remove('d-none');
        var expanded = expandedMap.get(item.key) === true;
        setExpanded(item, expanded, expandedMap);
      });

      emptyState.classList.toggle('d-none', filtered.length !== 0);
      countLabel.textContent = filtered.length + ' / ' + rows.length + ' publications';
    }

    tbody.addEventListener('click', function (event) {
      var btn = event.target.closest('.js-expand');
      if (!btn) {
        return;
      }
      var key = btn.dataset.key;
      var item = rows.find(function (r) { return r.key === key; });
      if (!item) {
        return;
      }
      var currentlyExpanded = expandedMap.get(item.key) === true;
      setExpanded(item, !currentlyExpanded, expandedMap);
    });

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
