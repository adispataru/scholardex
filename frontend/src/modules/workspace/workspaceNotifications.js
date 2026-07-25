/**
 * workspaceNotifications.js — H36.6 Notification center
 *
 * Bell button toggles a dropdown panel. On first open, fetches
 * GET /user/workspace/notifications and renders the list.
 *
 * Mark all as read:  POST /user/workspace/notifications/mark-read
 *   → updates lastVisitAt on the server, clears badge, replaces list with empty state.
 *
 * Individual dismiss: POST /user/workspace/notifications/dismiss { id }
 *   → adds ID to dismissedNotificationIds, slides item out, decrements badge.
 *
 * Badge is server-rendered with the initial unread count; JS manages it
 * dynamically thereafter. Initial count is read from window.wsNotifCount.
 */

import { postJsonHeaders } from '../shared/fetchUtils';
import { t, tPlural } from '../shared/i18n';

const ICON_CONFIG = {
    NEW_CITATION:       { iconClass: 'fa-solid fa-quote-right',        modClass: 'app-ws-notif__item-icon--citation' },
    SYNC_COMPLETED:     { iconClass: 'fa-solid fa-rotate-right',       modClass: 'app-ws-notif__item-icon--sync'     },
    REPORT_AVAILABLE:   { iconClass: 'fa-solid fa-file-lines',         modClass: 'app-ws-notif__item-icon--report'   },
    PROFILE_INCOMPLETE: { iconClass: 'fa-solid fa-circle-exclamation', modClass: 'app-ws-notif__item-icon--profile'  },
};

export function initWorkspaceNotifications() {
    const root = document.getElementById('ws-notif-root');
    if (!root) return;

    const btn       = root.querySelector('#ws-notif-btn');
    const panel     = root.querySelector('#ws-notif-panel');
    const list      = root.querySelector('#ws-notif-list');
    const markAllBtn = root.querySelector('#ws-notif-mark-all');
    const live      = root.querySelector('.app-ws-notif__live');
    if (!btn || !panel || !list) return;

    let loaded = false;
    let count  = typeof window.wsNotifCount === 'number' ? window.wsNotifCount : 0;

    // ── Bell toggle ──────────────────────────────────────────────────────
    btn.addEventListener('click', () => {
        if (!panel.hidden) {
            _close();
        } else {
            _open();
            if (!loaded) _fetch();
        }
    });

    // Close on outside click
    document.addEventListener('click', e => {
        if (!root.contains(e.target)) _close();
    });

    // Close on Escape; return focus to bell
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && !panel.hidden) {
            e.stopPropagation();
            _close();
            btn.focus();
        }
    });

    // ── Mark all as read ─────────────────────────────────────────────────
    markAllBtn?.addEventListener('click', () => {
        fetch('/user/workspace/notifications/mark-read', { method: 'POST', headers: postJsonHeaders() })
            .then(() => {
                _updateCount(0);
                list.innerHTML = _emptyHtml();
                loaded = true; // prevent stale refetch
                _announce(t('workspace.notif.allRead'));
                _close();
            })
            .catch(() => {});
    });

    // ── Fetch ────────────────────────────────────────────────────────────
    function _fetch() {
        list.innerHTML = `<li class="app-ws-notif__loading" role="status">
            <i class="fa-solid fa-rotate fa-spin" aria-hidden="true"></i> Loading…
        </li>`;
        fetch('/user/workspace/notifications')
            .then(r => {
                if (!r.ok) throw new Error('Failed');
                return r.json();
            })
            .then(data => {
                loaded = true;
                _updateCount(data.length);
                _renderList(data);
            })
            .catch(() => {
                list.innerHTML = `<li class="app-ws-notif__error">
                    Failed to load notifications — please try again.
                </li>`;
            });
    }

    // ── Render list ──────────────────────────────────────────────────────
    function _renderList(notifications) {
        list.innerHTML = '';
        if (!notifications.length) {
            list.innerHTML = _emptyHtml();
            return;
        }
        const frag = document.createDocumentFragment();
        for (const n of notifications) {
            frag.appendChild(_buildItem(n));
        }
        list.appendChild(frag);
        _announce(`${notifications.length} notification${notifications.length !== 1 ? 's' : ''}.`);
    }

    function _buildItem(n) {
        const cfg = ICON_CONFIG[n.type] ?? { iconClass: 'fa-solid fa-bell', modClass: '' };
        const timeStr = n.occurredAt ? _relativeTime(n.occurredAt) : '';

        const li = document.createElement('li');
        li.className = 'app-ws-notif__item';
        li.dataset.notifId = n.id;

        li.innerHTML =
            `<div class="app-ws-notif__item-icon ${cfg.modClass}" aria-hidden="true">` +
                `<i class="${cfg.iconClass}"></i>` +
            `</div>` +
            `<div class="app-ws-notif__item-body">` +
                `<p class="app-ws-notif__item-title">${_esc(n.title)}</p>` +
                `<p class="app-ws-notif__item-text">${_esc(n.body)}</p>` +
                (timeStr ? `<p class="app-ws-notif__item-time">${timeStr}</p>` : '') +
                (n.actionUrl ? `<a href="${_esc(n.actionUrl)}" class="app-ws-notif__item-action">View →</a>` : '') +
            `</div>` +
            `<button class="app-ws-notif__dismiss" type="button" ` +
                    `aria-label="${_esc(t('workspace.notif.dismiss', n.title))}">` +
                `<i class="fa-solid fa-xmark" aria-hidden="true"></i>` +
            `</button>`;

        li.querySelector('.app-ws-notif__dismiss').addEventListener('click', e => {
            e.stopPropagation();
            _dismiss(n.id, li);
        });

        return li;
    }

    // ── Dismiss ──────────────────────────────────────────────────────────
    function _dismiss(id, li) {
        fetch('/user/workspace/notifications/dismiss', {
            method: 'POST',
            headers: postJsonHeaders(),
            body: JSON.stringify({ id })
        }).then(() => {
            li.classList.add('app-ws-notif__item--dismissed');
            setTimeout(() => {
                li.remove();
                _updateCount(Math.max(0, count - 1));
                if (!list.querySelector('.app-ws-notif__item')) {
                    list.innerHTML = _emptyHtml();
                }
            }, 230);
        }).catch(() => { /* silent — item stays */ });
    }

    // ── Open / close ─────────────────────────────────────────────────────
    function _open() {
        panel.hidden = false;
        btn.setAttribute('aria-expanded', 'true');
    }

    function _close() {
        panel.hidden = true;
        btn.setAttribute('aria-expanded', 'false');
    }

    // ── Badge ─────────────────────────────────────────────────────────────
    function _updateCount(n) {
        count = n;
        btn.setAttribute('aria-label', tPlural('workspace.notif.ariaUnread', n));
        let badge = document.getElementById('ws-notif-badge');
        if (n > 0) {
            if (!badge) {
                badge = document.createElement('span');
                badge.id = 'ws-notif-badge';
                badge.className = 'app-ws-notif__badge';
                badge.setAttribute('aria-hidden', 'true');
                btn.appendChild(badge);
            }
            badge.textContent = n > 99 ? '99+' : String(n);
            badge.hidden = false;
        } else if (badge) {
            badge.hidden = true;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    function _emptyHtml() {
        return `<li class="app-ws-notif__empty">
            <i class="fa-solid fa-circle-check" aria-hidden="true"></i>
            You're all caught up — no new changes since your last visit.
        </li>`;
    }

    function _announce(msg) {
        if (live) {
            live.textContent = '';
            // Force re-render of live region
            requestAnimationFrame(() => { live.textContent = msg; });
        }
    }

    function _relativeTime(dateStr) {
        try {
            const diffMs = Date.now() - new Date(dateStr).getTime();
            if (isNaN(diffMs) || diffMs < 0) return '';
            const mins = Math.floor(diffMs / 60_000);
            if (mins < 1)  return 'just now';
            if (mins < 60) return `${mins} minute${mins !== 1 ? 's' : ''} ago`;
            const hrs = Math.floor(mins / 60);
            if (hrs < 24)  return `${hrs} hour${hrs !== 1 ? 's' : ''} ago`;
            const days = Math.floor(hrs / 24);
            if (days < 30) return `${days} day${days !== 1 ? 's' : ''} ago`;
            return new Date(dateStr).toLocaleDateString();
        } catch (_) {
            return '';
        }
    }

    function _esc(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}
