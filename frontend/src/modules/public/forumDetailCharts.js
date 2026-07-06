import { Chart as FrappeChart } from 'frappe-charts';
import { getChartTheme } from '../shared/chartTheme';
import { escapeHtml } from '../shared/htmlEscape';

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

function collectYearKeys(container, yearSet) {
  if (!container || typeof container !== 'object') {
    return;
  }
  Object.keys(container).forEach((key) => {
    if (/^\d{4}$/.test(key)) {
      yearSet.add(Number(key));
    }
  });
}

function getField(entry, keys) {
  for (let i = 0; i < keys.length; i += 1) {
    const key = keys[i];
    if (entry && entry[key] != null) {
      return entry[key];
    }
  }
  return {};
}

function quarterNumber(rawQuarter) {
  if (rawQuarter == null) {
    return null;
  }
  let normalizedQuarter = rawQuarter;
  if (Array.isArray(normalizedQuarter) && normalizedQuarter.length > 0) {
    normalizedQuarter = normalizedQuarter[0];
  }
  if (typeof normalizedQuarter === 'number' && normalizedQuarter >= 1 && normalizedQuarter <= 4) {
    return normalizedQuarter;
  }
  const normalized = String(normalizedQuarter).trim().toUpperCase();
  const match = normalized.match(/^Q?([1-4])$/);
  return match ? Number(match[1]) : null;
}

function metricCellLabel(quarter, rank) {
  if (quarter == null && rank == null) {
    return '—';
  }
  if (quarter != null && rank != null) {
    return `Q${quarter} #${rank}`;
  }
  if (quarter != null) {
    return `Q${quarter}`;
  }
  return `#${rank}`;
}

function quarterCssClass(quarter) {
  if (quarter === 1) return 'gh-q1';
  if (quarter === 2) return 'gh-q2';
  if (quarter === 3) return 'gh-q3';
  if (quarter === 4) return 'gh-q4';
  return 'gh-empty';
}

function renderCategoryHeatmap(container, entry, years) {
  if (!container) {
    return;
  }

  const metricDefs = [
    {
      label: 'AIS',
      quarterMap: getField(entry, ['qais', 'qAis']),
      rankMap: getField(entry, ['rankAis', 'rankAIS']),
      quartileRankMap: getField(entry, ['quartileRankAis', 'quartileRankAIS'])
    },
    // RIS is intentionally absent: it is AIS scaled by the category median (a monotone within-category
    // transform), so its quartiles equal the AIS quartiles — and the RIS source files carry no category
    // column, so the row could only ever render empty cells.
    {
      label: 'IF',
      quarterMap: getField(entry, ['qif', 'qIF']),
      rankMap: getField(entry, ['rankIf', 'rankIF']),
      quartileRankMap: getField(entry, ['quartileRankIf', 'quartileRankIF'])
    }
  ];

  const columnTemplate = `80px ${years.map(() => '14px').join(' ')}`;
  const headerRow = `<div class="gh-heatmap-row" style="grid-template-columns:${escapeHtml(columnTemplate)};">
    <div class="gh-label"></div>
    ${years.map((year) => `<div class="gh-year" title="${escapeHtml(year)}">${escapeHtml(String(year).slice(-2))}</div>`).join('')}
  </div>`;

  const rowsHtml = metricDefs.map((metric) => {
    const rowCells = years.map((year) => {
      const quarter = quarterNumber(metric.quarterMap[year]);
      const rank = metric.rankMap[year];
      const quartileRank = metric.quartileRankMap[year];
      let title = metricCellLabel(quarter, rank);
      if (quartileRank != null) {
        title += ` (quartile rank: #${quartileRank})`;
      }
      return `<div class="gh-cell ${escapeHtml(quarterCssClass(quarter))}" title="${escapeHtml(title)}"></div>`;
    }).join('');

    return `<div class="gh-heatmap-row" style="grid-template-columns:${escapeHtml(columnTemplate)};">
      <div class="gh-label">${escapeHtml(metric.label)}</div>${rowCells}
    </div>`;
  }).join('');

  container.innerHTML = `<div class="gh-heatmap-wrap"><div class="gh-heatmap">${headerRow}${rowsHtml}</div></div>`;
}

function renderForumDetailCharts() {
  const scoreTarget = document.getElementById('chart-score');
  const categoryData = parseJsonScript('forum-wos-category-data', {}) || {};
  const score = parseJsonScript('forum-wos-score-data', {}) || {};
  score.ais = score.ais || {};
  score.ris = score.ris || {};
  score.if = score.if || score.IF || {};

  if (!scoreTarget && Object.keys(categoryData || {}).length === 0) {
    return;
  }

  const yearSet = new Set();
  collectYearKeys(score.ais, yearSet);
  collectYearKeys(score.ris, yearSet);
  collectYearKeys(score.if, yearSet);

  Object.keys(categoryData || {}).forEach((categoryKey) => {
    const category = categoryData[categoryKey] || {};
    collectYearKeys(category.qAis, yearSet);
    collectYearKeys(category.rankAis, yearSet);
    collectYearKeys(category.qif, yearSet);
    collectYearKeys(category.qIF, yearSet);
    collectYearKeys(category.rankIf, yearSet);
    collectYearKeys(category.rankIF, yearSet);
  });

  const years = Array.from(yearSet).sort((a, b) => a - b);
  const theme = getChartTheme();

  if (scoreTarget && years.length > 0) {
    scoreTarget.innerHTML = '';
    new FrappeChart('#chart-score', {
      title: 'AIS / RIS / IF',
      type: 'line',
      height: 280,
      colors: [theme.metrics.ais, theme.metrics.ris, theme.metrics.if],
      data: {
        labels: years,
        datasets: [{
          name: 'AIS',
          values: years.map((year) => (score.ais && score.ais[year] != null ? score.ais[year] : null))
        }, {
          name: 'RIS',
          values: years.map((year) => (score.ris && score.ris[year] != null ? score.ris[year] : null))
        }, {
          name: 'IF',
          values: years.map((year) => (score.if && score.if[year] != null ? score.if[year] : null))
        }]
      },
      options: {
        lineOptions: { regionFill: 1 },
        axisOptions: { xAxisMode: 'tick', yAxisMode: 'span', xIsSeries: true }
      }
    });
  }

  Object.keys(categoryData || {}).forEach((key) => {
    const container = document.getElementById(`chart-quartile-${key.replace(/ /g, '-')}`);
    renderCategoryHeatmap(container, categoryData[key] || {}, years);
  });
}

function bindThemeListener() {
  if (themeListenerBound) {
    return;
  }
  themeListenerBound = true;
  window.addEventListener('app:themechange', renderForumDetailCharts);
}

export function initForumDetailCharts() {
  bindThemeListener();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', renderForumDetailCharts, { once: true });
    return;
  }
  renderForumDetailCharts();
}
