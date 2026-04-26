/**
 * confirmationDialog.js — H44.1 Shared confirmation dialog
 *
 * Replaces ad-hoc window.confirm() calls with an accessible Bootstrap 4 modal.
 *
 * API (available on every page after app.js runs):
 *
 *   window.appConfirmDialog.open({
 *     title,        // string  — dialog heading (required)
 *     body,         // string  — explanatory sentence (required)
 *     confirmLabel, // string  — confirm button label  (default 'Confirm')
 *     tone,         // 'danger' | 'primary'            (default 'primary')
 *     triggerEl,    // HTMLElement — restored focus target on close (optional)
 *     onConfirm,    // () => void — called when confirm is clicked
 *     onCancel,     // () => void — called on cancel / Escape / backdrop click
 *   })
 */

const DIALOG_ID = 'app-confirm-dialog';

export function initConfirmationDialog() {
    if (document.getElementById(DIALOG_ID)) return; // guard against double-init

    const el = _build();
    document.body.appendChild(el);

    let _onConfirm = null;
    let _onCancel  = null;
    let _triggerEl = null;
    let _confirmed = false; // distinguishes confirm click from backdrop/Escape dismiss

    const titleEl    = el.querySelector('.app-confirm-dialog__title');
    const bodyEl     = el.querySelector('.app-confirm-dialog__body');
    const confirmBtn = el.querySelector('.app-confirm-dialog__confirm');
    const cancelBtn  = el.querySelector('.app-confirm-dialog__cancel');

    confirmBtn.addEventListener('click', () => {
        _confirmed = true;
        _hide();
        if (_onConfirm) _onConfirm();
    });

    // Move focus to cancel button (safe default — don't auto-focus the destructive action)
    el.addEventListener('shown.bs.modal', () => {
        cancelBtn.focus();
    });

    // Restore focus and fire onCancel when hidden without confirmation
    el.addEventListener('hidden.bs.modal', () => {
        if (!_confirmed && _onCancel) _onCancel();
        if (_triggerEl) {
            _triggerEl.focus();
            _triggerEl = null;
        }
        _onConfirm = null;
        _onCancel  = null;
        _confirmed = false;
    });

    function _show() {
        window.$?.('#' + DIALOG_ID).modal?.('show');
    }

    function _hide() {
        window.$?.('#' + DIALOG_ID).modal?.('hide');
    }

    window.appConfirmDialog = {
        open({ title, body, confirmLabel = 'Confirm', tone = 'primary', triggerEl, onConfirm, onCancel } = {}) {
            _triggerEl = triggerEl instanceof HTMLElement ? triggerEl : document.activeElement;
            _onConfirm = onConfirm || null;
            _onCancel  = onCancel  || null;
            _confirmed = false;

            if (titleEl)    titleEl.textContent    = title        || 'Confirm';
            if (bodyEl)     bodyEl.textContent     = body         || 'Are you sure?';
            if (confirmBtn) {
                confirmBtn.textContent = confirmLabel || 'Confirm';
                confirmBtn.className   = 'btn app-confirm-dialog__confirm '
                    + (tone === 'danger' ? 'btn-danger' : 'btn-primary');
            }

            _show();
        },
    };
}

function _build() {
    const el = document.createElement('div');
    el.id = DIALOG_ID;
    el.className = 'modal fade app-confirm-dialog';
    el.setAttribute('role', 'alertdialog');
    el.setAttribute('aria-modal', 'true');
    el.setAttribute('tabindex', '-1');
    el.setAttribute('aria-labelledby', DIALOG_ID + '-title');
    el.setAttribute('aria-describedby', DIALOG_ID + '-body');
    el.innerHTML = `
<div class="modal-dialog modal-dialog-centered app-confirm-dialog__dialog">
  <div class="modal-content app-confirm-dialog__content">
    <div class="modal-header app-confirm-dialog__header">
      <h5 class="modal-title app-confirm-dialog__title" id="${DIALOG_ID}-title">Confirm</h5>
      <button type="button" class="close app-confirm-dialog__close"
              data-dismiss="modal" aria-label="Cancel">
        <span aria-hidden="true">&times;</span>
      </button>
    </div>
    <div class="modal-body app-confirm-dialog__body" id="${DIALOG_ID}-body">Are you sure?</div>
    <div class="modal-footer app-confirm-dialog__footer">
      <button type="button" class="btn btn-secondary app-confirm-dialog__cancel"
              data-dismiss="modal">Cancel</button>
      <button type="button" class="btn btn-primary app-confirm-dialog__confirm">Confirm</button>
    </div>
  </div>
</div>`;
    return el;
}
