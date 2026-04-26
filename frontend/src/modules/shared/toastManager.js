/**
 * toastManager.js — H44.2 Toast notification system
 *
 * Replaces ad-hoc inline feedback spans with a shared accessible toast queue.
 *
 * API (available on every page after app.js runs):
 *
 *   window.appToast.show({
 *     message,      // string  — notification text (required)
 *     tone,         // 'success' | 'error' | 'warning' | 'info'  (default 'info')
 *     duration,     // ms before auto-dismiss; 0 = sticky         (default 4000)
 *     actionLabel,  // string  — optional inline action button label
 *     onAction,     // () => void — called when action button is clicked
 *   })
 */

const TONE_ICONS = {
    success: 'fa-solid fa-circle-check',
    error:   'fa-solid fa-circle-xmark',
    warning: 'fa-solid fa-triangle-exclamation',
    info:    'fa-solid fa-circle-info',
};

const MAX_VISIBLE    = 5;
const DEFAULT_DURATION = 4000;
const EXIT_DURATION  = 180; // ms — must match CSS _toast-out duration

export function initToastManager() {
    if (document.getElementById('app-toast-polite')) return; // guard double-init

    // Two visually-hidden aria-live regions so screen readers announce toasts
    // without the visual container interfering with focus or layout.
    const politeRegion = _makeRegion('app-toast-polite', 'polite', 'status');
    const alertRegion  = _makeRegion('app-toast-alert',  'assertive', 'alert');
    document.body.appendChild(politeRegion);
    document.body.appendChild(alertRegion);

    // Visual container — aria-hidden so screen readers use the live regions above
    const container = document.createElement('div');
    container.className = 'app-toast-container';
    container.setAttribute('aria-hidden', 'true');
    document.body.appendChild(container);

    window.appToast = {
        show({ message, tone = 'info', duration = DEFAULT_DURATION, actionLabel, onAction } = {}) {
            // Announce to screen readers via the appropriate live region
            _announce(message, tone === 'error' ? alertRegion : politeRegion);

            // Evict the oldest visible toast when at the cap
            const existing = container.querySelectorAll('.app-toast:not(.app-toast--leaving)');
            if (existing.length >= MAX_VISIBLE) _dismiss(existing[0]);

            const toast = _createToast({ message, tone, actionLabel, onAction });
            container.appendChild(toast);

            if (duration > 0) {
                setTimeout(() => _dismiss(toast), duration);
            }
        },
    };
}

// ── Internal helpers ─────────────────────────────────────────────────────────

function _announce(message, region) {
    const span = document.createElement('span');
    span.textContent = message;
    region.appendChild(span);
    // Remove after screen reader has had time to announce it
    setTimeout(() => span.remove(), 1500);
}

function _dismiss(toast) {
    if (!toast.isConnected || toast.classList.contains('app-toast--leaving')) return;
    toast.classList.add('app-toast--leaving');
    setTimeout(() => toast.remove(), EXIT_DURATION + 20);
}

function _createToast({ message, tone = 'info', actionLabel, onAction }) {
    const iconClass = TONE_ICONS[tone] ?? TONE_ICONS.info;

    const toast = document.createElement('div');
    toast.className = 'app-toast app-toast--' + tone;
    // role="presentation" — a11y handled by the live regions, not the element itself
    toast.setAttribute('role', 'presentation');
    toast.innerHTML = `
<span class="app-toast__icon" aria-hidden="true"><i class="${iconClass}"></i></span>
<span class="app-toast__body">
  <span class="app-toast__message"></span>
</span>
<button type="button" class="app-toast__dismiss" aria-label="Dismiss notification">
  <i class="fa-solid fa-xmark" aria-hidden="true"></i>
</button>`;

    // Set message via textContent — never innerHTML — to prevent XSS
    toast.querySelector('.app-toast__message').textContent = message;

    if (actionLabel && onAction) {
        const actionBtn = document.createElement('button');
        actionBtn.type = 'button';
        actionBtn.className = 'app-toast__action';
        actionBtn.textContent = actionLabel;
        actionBtn.addEventListener('click', () => {
            _dismiss(toast);
            onAction();
        });
        toast.querySelector('.app-toast__body').appendChild(actionBtn);
    }

    toast.querySelector('.app-toast__dismiss').addEventListener('click', () => _dismiss(toast));

    return toast;
}

function _makeRegion(id, live, role) {
    const el = document.createElement('div');
    el.id = id;
    el.setAttribute('aria-live', live);
    el.setAttribute('aria-atomic', 'false');
    el.setAttribute('role', role);
    // Visually hidden, accessible to screen readers
    el.style.cssText = 'position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0';
    return el;
}
