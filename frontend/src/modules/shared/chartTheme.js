function cssVar(style, name, fallback) {
  const value = style.getPropertyValue(name).trim();
  return value || fallback;
}

function parseColor(color) {
  if (!color) {
    return null;
  }

  const value = color.trim();
  const hex = value.replace('#', '');
  if (/^[0-9a-fA-F]{6}$/.test(hex)) {
    return {
      r: Number.parseInt(hex.slice(0, 2), 16),
      g: Number.parseInt(hex.slice(2, 4), 16),
      b: Number.parseInt(hex.slice(4, 6), 16)
    };
  }

  const rgbMatch = value.match(/^rgba?\(([^)]+)\)$/i);
  if (!rgbMatch) {
    return null;
  }

  const channels = rgbMatch[1]
    .split(',')
    .map((channel) => Number.parseFloat(channel.trim()))
    .filter((channel) => Number.isFinite(channel));

  if (channels.length < 3) {
    return null;
  }

  return { r: channels[0], g: channels[1], b: channels[2] };
}

export function alphaColor(color, alpha) {
  const parsed = parseColor(color);
  if (!parsed) {
    return color;
  }
  return `rgba(${parsed.r}, ${parsed.g}, ${parsed.b}, ${alpha})`;
}

export function getChartTheme() {
  const style = getComputedStyle(document.documentElement);
  const isDark = document.documentElement.getAttribute('data-bs-theme') === 'dark';

  const series = [
    cssVar(style, '--app-chart-series-1', '#5b7cff'),
    cssVar(style, '--app-chart-series-2', '#22c55e'),
    cssVar(style, '--app-chart-series-3', '#06b6d4'),
    cssVar(style, '--app-chart-series-4', '#f59e0b'),
    cssVar(style, '--app-chart-series-5', '#ef4444'),
    cssVar(style, '--app-chart-series-6', '#a855f7'),
    cssVar(style, '--app-chart-series-7', '#94a3b8'),
    cssVar(style, '--app-chart-series-8', '#38bdf8')
  ];

  return {
    isDark,
    text: cssVar(style, '--app-color-text', '#334155'),
    textStrong: cssVar(style, '--app-color-text-strong', '#0f172a'),
    textMuted: cssVar(style, '--app-color-text-muted', '#64748b'),
    border: cssVar(style, '--app-color-border', '#d9e2ec'),
    cardBg: cssVar(style, '--app-color-card-bg', '#ffffff'),
    chartGrid: cssVar(style, '--app-chart-grid', '#dbe4f0'),
    chartTooltipBg: cssVar(style, '--app-chart-tooltip-bg', '#ffffff'),
    primary: cssVar(style, '--app-color-primary', '#4361ee'),
    success: cssVar(style, '--app-color-success', '#059669'),
    warning: cssVar(style, '--app-color-warning', '#d97706'),
    danger: cssVar(style, '--app-color-danger', '#dc2626'),
    series,
    heatmap: {
      q1: cssVar(style, '--app-ranking-q1', '#dc2626'),
      q2: cssVar(style, '--app-ranking-q2', '#f59e0b'),
      q3: cssVar(style, '--app-ranking-q3', '#cbd5e1'),
      q4: cssVar(style, '--app-ranking-q4', '#94a3b8'),
      empty: cssVar(style, '--app-ranking-empty', '#64748b')
    },
    metrics: {
      ais: cssVar(style, '--app-metric-ais', '#8b5cf6'),
      ris: cssVar(style, '--app-metric-ris', '#14b8a6'),
      if: cssVar(style, '--app-metric-if', '#ec4899')
    },
    venueBuckets: {
      Q1: {
        background: cssVar(style, '--app-ranking-q1-fill', 'rgba(220, 38, 38, 0.78)'),
        border: cssVar(style, '--app-ranking-q1-border', '#dc2626')
      },
      Q2: {
        background: cssVar(style, '--app-ranking-q2-fill', 'rgba(245, 158, 11, 0.82)'),
        border: cssVar(style, '--app-ranking-q2-border', '#d97706')
      },
      Q3: {
        background: cssVar(style, '--app-ranking-q3-fill', 'rgba(203, 213, 225, 0.9)'),
        border: cssVar(style, '--app-ranking-q3-border', '#94a3b8')
      },
      Q4: {
        background: cssVar(style, '--app-ranking-q4-fill', 'rgba(148, 163, 184, 0.88)'),
        border: cssVar(style, '--app-ranking-q4-border', '#64748b')
      },
      A_STAR: {
        background: cssVar(style, '--app-ranking-q1-fill', 'rgba(220, 38, 38, 0.78)'),
        border: cssVar(style, '--app-ranking-q1-border', '#dc2626')
      },
      A: {
        background: cssVar(style, '--app-ranking-q1-fill', 'rgba(220, 38, 38, 0.78)'),
        border: cssVar(style, '--app-ranking-q1-border', '#dc2626')
      },
      B: {
        background: cssVar(style, '--app-ranking-q2-fill', 'rgba(245, 158, 11, 0.82)'),
        border: cssVar(style, '--app-ranking-q2-border', '#d97706')
      },
      C: {
        background: cssVar(style, '--app-ranking-q3-fill', 'rgba(203, 213, 225, 0.9)'),
        border: cssVar(style, '--app-ranking-q3-border', '#94a3b8')
      },
      D: {
        background: cssVar(style, '--app-ranking-q4-fill', 'rgba(148, 163, 184, 0.88)'),
        border: cssVar(style, '--app-ranking-q4-border', '#64748b')
      },
      LNCS: {
        background: cssVar(style, '--app-ranking-lncs-fill', 'rgba(56, 189, 248, 0.82)'),
        border: cssVar(style, '--app-ranking-lncs-border', '#0ea5e9')
      },
      BOOK_LNCS: {
        background: cssVar(style, '--app-ranking-book-lncs-fill', 'rgba(37, 99, 235, 0.82)'),
        border: cssVar(style, '--app-ranking-book-lncs-border', '#2563eb')
      },
      SCOPUS: {
        background: cssVar(style, '--app-ranking-scopus-fill', 'rgba(34, 197, 94, 0.8)'),
        border: cssVar(style, '--app-ranking-scopus-border', '#16a34a')
      },
      Unranked: {
        background: cssVar(style, '--app-ranking-unranked-fill', 'rgba(100, 116, 139, 0.88)'),
        border: cssVar(style, '--app-ranking-unranked-border', '#64748b')
      }
    }
  };
}
