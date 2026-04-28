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

function asSortedYears(scores) {
  return Object.keys(scores || {})
    .map((year) => Number(year))
    .filter((year) => Number.isInteger(year))
    .sort((a, b) => a - b);
}

function scoreForYear(scores, year) {
  if (!scores || scores[year] == null) {
    return null;
  }
  return scores[year];
}

function fieldValue(score, field) {
  if (!score) {
    return null;
  }

  const aliases = {
    article: ['article'],
    citation: ['citation'],
    totalDocument: ['totalDocument', 'total_document'],
    AIT: ['AIT', 'ait'],
    CIT: ['CIT', 'cit'],
    collaboration: ['collaboration']
  };

  const candidates = aliases[field] || [field];
  for (let i = 0; i < candidates.length; i += 1) {
    const candidate = candidates[i];
    if (score[candidate] != null) {
      return score[candidate];
    }
  }

  return null;
}

function chartOptions(reverseTicks) {
  const theme = getChartTheme();
  return {
    maintainAspectRatio: false,
    legend: {
      display: true,
      position: 'bottom',
      labels: {
        fontColor: theme.textMuted
      }
    },
    scales: {
      xAxes: [{
        ticks: {
          maxTicksLimit: 8,
          fontColor: theme.textMuted
        },
        gridLines: {
          color: theme.chartGrid,
          zeroLineColor: theme.chartGrid
        }
      }],
      yAxes: [{
        ticks: {
          beginAtZero: !reverseTicks,
          reverse: reverseTicks,
          fontColor: theme.textMuted
        },
        gridLines: {
          color: theme.chartGrid,
          zeroLineColor: theme.chartGrid
        }
      }]
    },
    tooltips: {
      backgroundColor: theme.chartTooltipBg,
      bodyFontColor: theme.text,
      titleFontColor: theme.textStrong,
      borderColor: theme.border,
      borderWidth: 1,
      displayColors: false
    }
  };
}

function clearCharts() {
  chartInstances.forEach((chart) => chart.destroy());
  chartInstances = [];
}

function lineChart(canvasId, label, labels, values, color, reverseTicks) {
  const canvas = document.getElementById(canvasId);
  if (!canvas || !window.Chart) {
    return;
  }

  const chart = new window.Chart(canvas.getContext('2d'), {
    type: 'line',
    data: {
      labels,
      datasets: [{
        label,
        data: values,
        backgroundColor: alphaColor(color, 0.12),
        borderColor: color,
        pointRadius: 3,
        pointBackgroundColor: color,
        pointBorderColor: color,
        pointHoverRadius: 4,
        pointHoverBackgroundColor: color,
        pointHoverBorderColor: color,
        pointHitRadius: 12,
        pointBorderWidth: 2,
        fill: false,
        lineTension: 0.2,
        spanGaps: true
      }]
    },
    options: chartOptions(reverseTicks)
  });

  chartInstances.push(chart);
}

function renderUniversityDetailCharts() {
  const scores = parseJsonScript('urap-score-data', {}) || {};
  const fields = parseJsonScript('urap-fields-data', []) || [];
  const years = asSortedYears(scores);

  if (years.length === 0) {
    return;
  }

  const labels = years.map(String);
  const theme = getChartTheme();

  lineChart(
    'urap-total-score-chart',
    'Total Score',
    labels,
    years.map((year) => scoreForYear(scores, year)?.total ?? null),
    theme.series[0],
    false
  );

  lineChart(
    'urap-rank-chart',
    'Rank',
    labels,
    years.map((year) => scoreForYear(scores, year)?.rank ?? null),
    theme.series[3],
    true
  );

  const colors = {
    article: theme.series[1],
    citation: theme.series[6],
    totalDocument: theme.series[2],
    AIT: theme.series[5],
    CIT: theme.series[4],
    collaboration: theme.series[3]
  };

  fields.forEach((field) => {
    const label = field === 'totalDocument' ? 'Total Document' : field;
    lineChart(
      `urap-field-chart-${field}`,
      label,
      labels,
      years.map((year) => fieldValue(scoreForYear(scores, year), field)),
      colors[field] || theme.primary,
      false
    );
  });
}

function bindThemeListener() {
  if (themeListenerBound) {
    return;
  }
  themeListenerBound = true;
  window.addEventListener('app:themechange', () => {
    clearCharts();
    renderUniversityDetailCharts();
  });
}

export function initUniversityDetailCharts() {
  bindThemeListener();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', renderUniversityDetailCharts, { once: true });
    return;
  }
  renderUniversityDetailCharts();
}
