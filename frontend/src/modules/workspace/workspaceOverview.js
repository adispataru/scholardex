/**
 * workspaceOverview.js — H36.4 Adaptive Overview Tab
 *
 * Responsibilities:
 *  - Apply saved card order from window.wsOverviewCardOrder
 *  - Initialize Chart.js mini-charts (publications per year, activity distribution)
 *  - Native HTML5 drag-and-drop to reorder overview panels
 *  - Persist order via POST /user/workspace/preferences
 */

import { postJsonHeaders } from '../shared/fetchUtils';

export function initWorkspaceOverview() {
    const container = document.getElementById('ws-overview-cards');
    if (!container) return; // Not on workspace page, or NEW_USER state (no panels)

    const savedOrder = Array.isArray(window.wsOverviewCardOrder)
        ? window.wsOverviewCardOrder
        : [];

    if (savedOrder.length) {
        _applyCardOrder(container, savedOrder);
    }

    _initCharts();
    _initDragDrop(container);
}

// ── Card order ─────────────────────────────────────────────────────────────

function _applyCardOrder(container, order) {
    const fragment = document.createDocumentFragment();
    const children = Array.from(container.children);

    // Append in saved order first
    for (const id of order) {
        const el = container.querySelector(`[data-card-id="${CSS.escape(id)}"]`);
        if (el) {
            fragment.appendChild(el);
            const idx = children.indexOf(el);
            if (idx !== -1) children.splice(idx, 1);
        }
    }
    // Append any panels not listed in the saved order (new cards added after save)
    for (const el of children) {
        fragment.appendChild(el);
    }
    container.appendChild(fragment);
}

function _persistOrder(container) {
    const order = Array.from(container.querySelectorAll('[data-card-id]'))
        .map(el => el.dataset.cardId);
    fetch('/user/workspace/preferences', {
        method: 'POST',
        headers: postJsonHeaders(),
        body: JSON.stringify({ cardOrder: order })
    }).catch(() => { /* silent — non-critical */ });
}

// ── Charts ─────────────────────────────────────────────────────────────────

function _initCharts() {
    const data = window.wsOverviewChartData;
    if (!data || typeof window.Chart === 'undefined') return;

    const style = getComputedStyle(document.documentElement);
    const colors = {
        primary:  style.getPropertyValue('--app-color-primary').trim()  || '#4e73df',
        success:  style.getPropertyValue('--app-color-success').trim()  || '#1cc88a',
        muted:    style.getPropertyValue('--app-color-text-muted').trim() || '#858796',
        border:   style.getPropertyValue('--app-color-border').trim()   || '#e3e6f0',
        cardBg:   style.getPropertyValue('--app-color-card-bg').trim()  || '#fff',
    };

    _renderBarChart(
        document.getElementById('ws-chart-pubs'),
        data.years,
        data.pubsPerYear,
        'Publications',
        colors
    );

    _renderBarChart(
        document.getElementById('ws-chart-cites'),
        data.years,
        data.citesPerYear,
        'Citations',
        colors
    );
}

function _renderBarChart(canvas, labels, values, label, colors) {
    if (!canvas) return;
    if (!labels || !labels.length) {
        _showChartEmpty(canvas, 'No publication data yet.');
        return;
    }
    new window.Chart(canvas, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label,
                data: values,
                backgroundColor: colors.primary,
                borderWidth: 0,
                borderRadius: 3,
            }]
        },
        options: {
            maintainAspectRatio: false,
            scales: {
                xAxes: [{
                    gridLines: { display: false },
                    ticks: { fontColor: colors.muted, maxTicksLimit: 8 }
                }],
                yAxes: [{
                    ticks: { beginAtZero: true, stepSize: 1, fontColor: colors.muted },
                    gridLines: { color: colors.border, zeroLineColor: colors.border }
                }]
            },
            legend: { display: false },
            tooltips: {
                backgroundColor: colors.cardBg,
                bodyFontColor: colors.muted,
                titleFontColor: colors.muted,
                borderColor: colors.border,
                borderWidth: 1,
                displayColors: false,
                callbacks: {
                    title: (items) => items[0].xLabel,
                    label: (item) => `${item.yLabel} publication${item.yLabel !== 1 ? 's' : ''}`
                }
            }
        }
    });
}

function _renderDoughnutChart(canvas, labels, values, colors) {
    if (!canvas) return;
    if (!labels || !labels.length) {
        _showChartEmpty(canvas, 'No citation data yet.');
        return;
    }
    const palette = [
        '#4e73df', '#1cc88a', '#36b9cc', '#f6c23e',
        '#e74a3b', '#858796', '#6610f2', '#fd7e14'
    ];
    new window.Chart(canvas, {
        type: 'doughnut',
        data: {
            labels,
            datasets: [{
                data: values,
                backgroundColor: palette.slice(0, labels.length),
                hoverBackgroundColor: palette.slice(0, labels.length),
                borderWidth: 0,
            }]
        },
        options: {
            maintainAspectRatio: false,
            cutoutPercentage: 70,
            legend: { display: false },
            tooltips: {
                backgroundColor: colors.cardBg,
                bodyFontColor: colors.muted,
                titleFontColor: colors.muted,
                borderColor: colors.border,
                borderWidth: 1,
                displayColors: true,
                caretPadding: 10,
            }
        }
    });
}

function _showChartEmpty(canvas, message) {
    const wrapper = canvas.closest('.app-mini-chart');
    if (wrapper) {
        wrapper.innerHTML = `<p class="app-mini-chart__empty">${message}</p>`;
    }
}

// ── Drag-and-drop ──────────────────────────────────────────────────────────

function _initDragDrop(container) {
    // Disable drag-and-drop on touch/stylus devices — falls back to fixed layout
    if (window.matchMedia('(pointer: coarse)').matches) return;

    let dragSrc = null;
    let dragEnabled = false;

    // Only allow drag when the grab handle is the mousedown target
    container.addEventListener('mousedown', e => {
        dragEnabled = !!e.target.closest('.app-drag-handle');
    });

    container.addEventListener('dragstart', e => {
        if (!dragEnabled) {
            e.preventDefault();
            return;
        }
        const panel = e.target.closest('[data-card-id]');
        if (!panel) return;
        dragSrc = panel;
        // Defer class addition so the drag image is captured before the opacity changes
        requestAnimationFrame(() => panel.classList.add('app-overview-panel--dragging'));
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', panel.dataset.cardId);
    });

    container.addEventListener('dragend', () => {
        if (dragSrc) {
            dragSrc.classList.remove('app-overview-panel--dragging');
        }
        container.querySelectorAll('.app-overview-panel--drag-over')
            .forEach(el => el.classList.remove('app-overview-panel--drag-over'));
        dragSrc = null;
        dragEnabled = false;
    });

    container.addEventListener('dragover', e => {
        e.preventDefault();
        const panel = e.target.closest('[data-card-id]');
        if (!panel || panel === dragSrc) return;
        container.querySelectorAll('.app-overview-panel--drag-over')
            .forEach(el => el.classList.remove('app-overview-panel--drag-over'));
        panel.classList.add('app-overview-panel--drag-over');
        e.dataTransfer.dropEffect = 'move';
    });

    container.addEventListener('dragleave', e => {
        // Only clear if we're leaving the container entirely
        if (!container.contains(e.relatedTarget)) {
            container.querySelectorAll('.app-overview-panel--drag-over')
                .forEach(el => el.classList.remove('app-overview-panel--drag-over'));
        }
    });

    container.addEventListener('drop', e => {
        e.preventDefault();
        const target = e.target.closest('[data-card-id]');
        if (!target || !dragSrc || target === dragSrc) return;

        // Insert before or after target based on vertical midpoint
        const rect = target.getBoundingClientRect();
        const midY = rect.top + rect.height / 2;
        if (e.clientY < midY) {
            container.insertBefore(dragSrc, target);
        } else {
            container.insertBefore(dragSrc, target.nextSibling);
        }

        _persistOrder(container);
    });
}
