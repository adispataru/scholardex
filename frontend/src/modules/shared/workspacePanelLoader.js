const PANEL_LABELS = {
  publications: 'Publications',
  activities: 'Activities',
  profile: 'Profile',
};

function buildSkeletonRow(cells) {
  const cellHtml = cells
    .map(cls => `<div class="app-skeleton-block app-skeleton-cell ${cls}"></div>`)
    .join('');
  return `<div class="app-skeleton-row">${cellHtml}</div>`;
}

function buildSkeletonTable(rowCount) {
  const rows = Array.from({ length: rowCount }, () =>
    buildSkeletonRow([
      'app-skeleton-cell--title',
      'app-skeleton-cell--date',
      'app-skeleton-cell--medium',
      'app-skeleton-cell--short',
      'app-skeleton-cell--narrow',
    ])
  ).join('');

  return `
    <div class="app-skeleton-table">
      <div class="app-table-section__surface">
        <div class="app-skeleton-table__header">
          <div class="app-skeleton-block app-skeleton-table__header-bar"></div>
        </div>
        ${rows}
      </div>
    </div>`;
}

function buildSkeletonChart() {
  return `
    <div class="app-skeleton-chart">
      <div class="app-skeleton-block"></div>
    </div>`;
}

function buildSkeleton(panelType) {
  switch (panelType) {
    case 'publications':
      return buildSkeletonTable(5);
    case 'activities':
      return buildSkeletonChart() + buildSkeletonTable(3);
    default:
      return '';
  }
}

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function buildContent(panelType, data) {
  // Minimal rendering — H36.7 and H36.8 will replace this with full
  // interactive tables. Purpose here: prove skeleton-to-content transition
  // works without layout shift.
  switch (panelType) {
    case 'publications': {
      const pubs = Array.isArray(data.publications) ? data.publications : [];
      const count = pubs.length;
      const items = pubs.slice(0, 10)
        .map(p => `<li class="app-summary-list__item">
          <span class="app-summary-list__label">${escapeHtml(p.title ?? '(untitled)')}</span>
          <span class="app-summary-list__value">${escapeHtml(p.coverDate ?? '')}</span>
        </li>`)
        .join('');
      return `
        <div class="app-summary-panel">
          <div class="app-summary-panel__header">
            <h2 class="app-summary-panel__title">Publications</h2>
          </div>
          <div class="app-summary-panel__body">
            <p class="app-workflow__intro">${count} publication${count !== 1 ? 's' : ''} in your record.</p>
            <ul class="app-summary-list">${items}</ul>
          </div>
        </div>`;
    }
    case 'activities': {
      const instances = Array.isArray(data.activityInstances) ? data.activityInstances : [];
      const count = instances.length;
      const items = instances.slice(0, 10)
        .map(ai => {
          const name = ai.activity?.name ?? '(unknown activity)';
          return `<li class="app-summary-list__item">
            <span class="app-summary-list__label">${escapeHtml(name)}</span>
            <span class="app-summary-list__value">${escapeHtml(ai.date ?? '')}</span>
          </li>`;
        })
        .join('');
      return `
        <div class="app-summary-panel">
          <div class="app-summary-panel__header">
            <h2 class="app-summary-panel__title">Activities</h2>
          </div>
          <div class="app-summary-panel__body">
            <p class="app-workflow__intro">${count} activity instance${count !== 1 ? 's' : ''} recorded.</p>
            <ul class="app-summary-list">${items}</ul>
          </div>
        </div>`;
    }
    default:
      return '';
  }
}

function buildError(panelType) {
  const label = PANEL_LABELS[panelType] ?? 'content';
  return `
    <div class="app-panel-error app-dashboard-empty">
      <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
      <h3 class="app-dashboard-empty__title">Could not load ${escapeHtml(label)}</h3>
      <p class="app-dashboard-empty__body">Something went wrong while fetching this content.</p>
      <button type="button" class="btn btn-sm btn-outline-primary" data-retry-panel>
        Try again
      </button>
    </div>`;
}

function _loadPanel(panel) {
  const mount = panel.querySelector('[data-workspace-lazy-panel]');
  if (!mount) {
    return;
  }

  const panelType = mount.dataset.workspaceLazyPanel;
  const src = mount.dataset.src;

  if (!src) {
    return;
  }

  mount.innerHTML = buildSkeleton(panelType);

  fetch(src, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
    .then(res => {
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      return res.json();
    })
    .then(data => {
      mount.innerHTML = buildContent(panelType, data);
    })
    .catch(() => {
      mount.innerHTML = buildError(panelType);
    });
}

export function initWorkspacePanelLoader() {
  document.addEventListener('click', e => {
    const btn = e.target.closest('[data-retry-panel]');
    if (!btn) {
      return;
    }
    const panel = btn.closest('[role="tabpanel"]');
    if (panel) {
      _loadPanel(panel);
    }
  });

  window.appWorkspacePanelLoader = { loadPanel: _loadPanel };
}
