import { alphaColor, getChartTheme } from '../shared/chartTheme';

let chartInstances = [];
let themeListenerBound = false;

function parseJsonScript(id, fallback) {
  const element = document.getElementById(id);
  if (!element) {
    return fallback;
  }
  try {
    return JSON.parse(element.textContent || '');
  } catch (_error) {
    return fallback;
  }
}

function clearCharts() {
  chartInstances.forEach((chart) => chart.destroy());
  chartInstances = [];
}

function barOptions(percentScale) {
  const theme = getChartTheme();
  return {
    maintainAspectRatio: false,
    legend: {
      display: percentScale,
      position: 'bottom',
      labels: { fontColor: theme.textMuted }
    },
    scales: {
      xAxes: [{
        ticks: {
          beginAtZero: true,
          fontColor: theme.textMuted,
          max: percentScale ? 100 : undefined,
          precision: 0
        },
        gridLines: { color: theme.chartGrid, zeroLineColor: theme.chartGrid }
      }],
      yAxes: [{
        ticks: {
          beginAtZero: true,
          fontColor: theme.textMuted,
          max: percentScale ? 100 : undefined,
          precision: 0
        },
        gridLines: { color: theme.chartGrid, zeroLineColor: theme.chartGrid }
      }]
    },
    tooltips: {
      backgroundColor: theme.chartTooltipBg,
      bodyFontColor: theme.text,
      titleFontColor: theme.textStrong,
      borderColor: theme.border,
      borderWidth: 1
    }
  };
}

function positionCountChart(data) {
  const canvas = document.getElementById('orgunit-position-count-chart');
  if (!canvas || !window.Chart || !data.positions.length) {
    return;
  }
  const theme = getChartTheme();
  const chart = new window.Chart(canvas.getContext('2d'), {
    type: 'horizontalBar',
    data: {
      labels: data.positions.map((p) => p.label),
      datasets: [{
        // Localized server-side: this page has no inline appI18n bundle, so the label rides in on the canvas.
        label: canvas.dataset.seriesLabel || 'Researchers',
        data: data.positions.map((p) => p.count),
        backgroundColor: alphaColor(theme.series[0], 0.55),
        borderColor: theme.series[0],
        borderWidth: 1
      }]
    },
    options: barOptions(false)
  });
  chartInstances.push(chart);
}

function metPercentChart(data) {
  const canvas = document.getElementById('orgunit-met-percent-chart');
  if (!canvas || !window.Chart || !data.criteria.length) {
    return;
  }
  const theme = getChartTheme();
  // Positions with no threshold anywhere (e.g. Unclassified) would chart as all-gaps — drop them.
  const positions = data.positions.filter((p) => {
    const met = data.metPercentByPosition[p.key] || {};
    return Object.keys(met).length > 0;
  });
  if (!positions.length) {
    return;
  }
  const datasets = data.criteria.map((criterion, i) => {
    const color = theme.series[i % theme.series.length];
    return {
      label: criterion.name,
      data: positions.map((p) => {
        const met = data.metPercentByPosition[p.key] || {};
        return met[criterion.index] != null ? Math.round(met[criterion.index]) : null;
      }),
      backgroundColor: alphaColor(color, 0.55),
      borderColor: color,
      borderWidth: 1
    };
  });
  const chart = new window.Chart(canvas.getContext('2d'), {
    type: 'bar',
    data: { labels: positions.map((p) => p.label), datasets },
    options: barOptions(true)
  });
  chartInstances.push(chart);
}

function bindPositionFilter() {
  const select = document.getElementById('orgunit-position-filter');
  if (!select) {
    return;
  }
  select.addEventListener('change', () => {
    const api = window.appDataTables && window.appDataTables.getInstance('#orgunit-report-table');
    if (!api) {
      return;
    }
    const value = select.value;
    api.column(1).search(value ? `^${value}$` : '', true, false).draw();
  });
}

function csvEscape(value) {
  const text = value == null ? '' : String(value);
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function bindCsvExport(data) {
  const button = document.getElementById('orgunit-export-csv');
  if (!button) {
    return;
  }
  button.addEventListener('click', () => {
    const header = (button.dataset.csvHeader || 'Researcher|Position|Department').split('|')
      .concat(data.criteria.map((c) => c.name));
    const lines = [header.map(csvEscape).join(',')];
    data.rows.forEach((row) => {
      const cells = [row.name, row.position, row.department]
        .concat(row.scores.map((score) => (score == null ? '' : score.toFixed(2))));
      lines.push(cells.map(csvEscape).join(','));
    });
    const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'orgunit-report.csv';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);
  });
}

function renderCharts() {
  const data = parseJsonScript('orgunit-dashboard-data', null);
  if (!data || !data.positions) {
    return;
  }
  positionCountChart(data);
  metPercentChart(data);
}

function bindThemeListener() {
  if (themeListenerBound) {
    return;
  }
  themeListenerBound = true;
  window.addEventListener('app:themechange', () => {
    clearCharts();
    renderCharts();
  });
}

function boot() {
  const data = parseJsonScript('orgunit-dashboard-data', null);
  if (!data || !data.positions) {
    return;
  }
  bindThemeListener();
  renderCharts();
  bindPositionFilter();
  bindCsvExport(data);
}

export function initOrgUnitReportDashboard() {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
    return;
  }
  boot();
}
