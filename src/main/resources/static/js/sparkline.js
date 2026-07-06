/* Shared inline-SVG sparkline for ranking tables (WoS category trends, CORE rank trends, ...).
 * Renders via currentColor so the app theme applies; direction colouring is semantic:
 * the line is green when the LAST value is better than the FIRST, where "better" is
 * opts.higherIsBetter (default true — flip for position-style ranks where lower wins). */
(function () {
  'use strict';

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  /**
   * points: [{x, y, label?}] — x is the axis value (year), y the plotted value, label an optional
   *         human value for the tooltip (falls back to y).
   * opts:   { title?, higherIsBetter?, width?, height?, evenSpacing? } — evenSpacing plots points at
   *         equal intervals regardless of x gaps (irregular editions like CORE 2008/2010/2013...).
   */
  function render(points, opts) {
    const o = opts || {};
    const pts = (points || []).filter(function (p) { return p && p.y != null && !isNaN(p.y); });
    if (pts.length < 2) {
      return '<span class="app-spark app-spark--empty" aria-hidden="true">—</span>';
    }
    const w = o.width || 88;
    const h = o.height || 24;
    const pad = 3;
    const ys = pts.map(function (p) { return p.y; });
    const minY = Math.min.apply(null, ys);
    const maxY = Math.max.apply(null, ys);
    const spanY = (maxY - minY) || 1;
    const minX = pts[0].x;
    const maxX = pts[pts.length - 1].x;
    const spanX = (maxX - minX) || 1;
    const coords = pts.map(function (p, i) {
      const fx = o.evenSpacing ? (pts.length === 1 ? 0 : i / (pts.length - 1)) : (p.x - minX) / spanX;
      return [
        pad + fx * (w - 2 * pad),
        h - pad - (p.y - minY) / spanY * (h - 2 * pad)
      ];
    });
    const d = coords.map(function (c, i) {
      return (i === 0 ? 'M' : 'L') + c[0].toFixed(1) + ' ' + c[1].toFixed(1);
    }).join(' ');
    const last = coords[coords.length - 1];
    const higherIsBetter = o.higherIsBetter !== false;
    const improved = higherIsBetter
      ? ys[ys.length - 1] >= ys[0]
      : ys[ys.length - 1] <= ys[0];
    const firstLabel = pts[0].label != null ? pts[0].label : pts[0].y;
    const lastLabel = pts[pts.length - 1].label != null ? pts[pts.length - 1].label : pts[pts.length - 1].y;
    const title = o.title || (minX + '–' + maxX + ': ' + firstLabel + ' → ' + lastLabel);
    return '<svg class="app-spark ' + (improved ? 'app-spark--up' : 'app-spark--down') + '" width="' + w +
      '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="' + escapeHtml(title) + '">' +
      '<title>' + escapeHtml(title) + '</title>' +
      '<path d="' + d + '" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<circle cx="' + last[0].toFixed(1) + '" cy="' + last[1].toFixed(1) + '" r="2" fill="currentColor"/></svg>';
  }

  window.appSparkline = { render: render };
})();
