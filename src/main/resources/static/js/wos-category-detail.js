(function () {
  'use strict';

  function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
      .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
  }

  function fmt(v) {
    return v == null || isNaN(v) ? '—' : Number(v).toFixed(2);
  }

  // Parse "year:value,year:value,…" into [{year, value}] (ascending by year).
  function parseTrend(csv) {
    if (!csv) return [];
    return csv.split(',').map(function (pair) {
      const bits = pair.split(':');
      return { year: parseInt(bits[0], 10), value: parseFloat(bits[1]) };
    }).filter(function (p) { return !isNaN(p.year) && !isNaN(p.value); });
  }

  function sparklineSvg(pts) {
    if (pts.length < 2) {
      return '<span class="app-spark app-spark--empty" aria-hidden="true">—</span>';
    }
    const w = 80, h = 22, pad = 3;
    const ys = pts.map(function (p) { return p.value; });
    const minX = pts[0].year, maxX = pts[pts.length - 1].year;
    const minY = Math.min.apply(null, ys), maxY = Math.max.apply(null, ys);
    const spanX = (maxX - minX) || 1, spanY = (maxY - minY) || 1;
    const coords = pts.map(function (p) {
      return [pad + (p.year - minX) / spanX * (w - 2 * pad),
              h - pad - (p.value - minY) / spanY * (h - 2 * pad)];
    });
    const d = coords.map(function (c, i) {
      return (i === 0 ? 'M' : 'L') + c[0].toFixed(1) + ' ' + c[1].toFixed(1);
    }).join(' ');
    const last = coords[coords.length - 1];
    const rising = ys[ys.length - 1] >= ys[0];
    const title = 'AIS ' + minX + '–' + maxX + ': ' + fmt(ys[0]) + ' → ' + fmt(ys[ys.length - 1]);
    return '<svg class="app-spark ' + (rising ? 'app-spark--up' : 'app-spark--down') + '" width="' + w +
      '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="' + escapeHtml(title) + '">' +
      '<title>' + escapeHtml(title) + '</title>' +
      '<path d="' + d + '" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<circle cx="' + last[0].toFixed(1) + '" cy="' + last[1].toFixed(1) + '" r="2" fill="currentColor"/></svg>';
  }

  function renderSparklines() {
    document.querySelectorAll('.app-spark-cell[data-trend]').forEach(function (cell) {
      cell.innerHTML = sparklineSvg(parseTrend(cell.getAttribute('data-trend')));
    });
  }

  // Click a [data-sortable] header to sort rows by that column (numeric via td[data-sort], else text).
  function makeSortable(table) {
    const headers = Array.prototype.slice.call(table.querySelectorAll('thead th'));
    headers.forEach(function (th, colIndex) {
      if (!th.hasAttribute('data-sortable')) return;
      th.classList.add('app-sortable-th');
      th.addEventListener('click', function () {
        const tbody = table.tBodies[0];
        const rows = Array.prototype.slice.call(tbody.rows);
        const asc = th.getAttribute('data-sort-dir') !== 'asc';
        headers.forEach(function (h) { h.removeAttribute('data-sort-dir'); });
        th.setAttribute('data-sort-dir', asc ? 'asc' : 'desc');
        rows.sort(function (a, b) {
          const ca = a.cells[colIndex], cb = b.cells[colIndex];
          const va = ca ? ca.getAttribute('data-sort') : null;
          const vb = cb ? cb.getAttribute('data-sort') : null;
          const na = parseFloat(va), nb = parseFloat(vb);
          let cmp;
          if (!isNaN(na) && !isNaN(nb)) {
            cmp = na - nb;
          } else {
            cmp = String(va == null ? (ca ? ca.textContent : '') : va)
              .localeCompare(String(vb == null ? (cb ? cb.textContent : '') : vb), undefined, { sensitivity: 'base' });
          }
          return asc ? cmp : -cmp;
        });
        rows.forEach(function (r) { tbody.appendChild(r); });
      });
    });
  }

  function init() {
    renderSparklines();
    document.querySelectorAll('table[data-sortable-table]').forEach(makeSortable);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
