const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

let activeModal = null;
let returnFocusTo = null;

function getBackdrop() {
  return document.querySelector('[data-app-modal-backdrop]');
}

function ensureBackdrop() {
  let backdrop = getBackdrop();
  if (backdrop) {
    return backdrop;
  }

  backdrop = document.createElement('div');
  backdrop.className = 'app-modal-backdrop';
  backdrop.setAttribute('data-app-modal-backdrop', '');
  backdrop.hidden = true;
  document.body.appendChild(backdrop);
  return backdrop;
}

function resolveModal(target) {
  if (!target) {
    return null;
  }

  if (target instanceof HTMLElement) {
    return target;
  }

  const id = String(target).startsWith('#') ? String(target).slice(1) : String(target);
  return document.getElementById(id);
}

function emitModalEvent(modal, name, relatedTarget = null) {
  const event = new CustomEvent(name, {
    bubbles: true,
    cancelable: name === 'show.bs.modal' || name === 'hide.bs.modal',
    detail: { relatedTarget },
  });
  Object.defineProperty(event, 'relatedTarget', { value: relatedTarget });
  return modal.dispatchEvent(event);
}

function getFocusable(modal) {
  return Array.from(modal.querySelectorAll(FOCUSABLE_SELECTOR))
    .filter((el) => el.offsetParent !== null || el === document.activeElement);
}

function focusInitial(modal) {
  const target = modal.querySelector('[autofocus]') || getFocusable(modal)[0] || modal;
  if (!target.hasAttribute('tabindex') && target === modal) {
    target.setAttribute('tabindex', '-1');
  }
  target.focus();
}

function syncState(modal, open) {
  const backdrop = ensureBackdrop();
  modal.hidden = !open;
  modal.classList.toggle('show', open);
  modal.setAttribute('aria-hidden', open ? 'false' : 'true');
  backdrop.hidden = !open;
  backdrop.classList.toggle('show', open);
  document.body.classList.toggle('app-modal-open', open);
}

function trapFocus(event) {
  if (event.key !== 'Tab' || !activeModal) {
    return;
  }

  const focusable = getFocusable(activeModal);
  if (focusable.length === 0) {
    event.preventDefault();
    activeModal.focus();
    return;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function closeActiveOnEscape(event) {
  if (event.key === 'Escape' && activeModal) {
    event.preventDefault();
    close(activeModal);
  }
}

function open(target, options = {}) {
  const modal = resolveModal(target);
  if (!modal) {
    return null;
  }

  if (activeModal && activeModal !== modal) {
    close(activeModal, { restoreFocus: false });
  }

  const trigger = options.trigger || document.activeElement;
  if (!emitModalEvent(modal, 'show.bs.modal', trigger)) {
    return modal;
  }

  returnFocusTo = trigger instanceof HTMLElement ? trigger : null;
  activeModal = modal;
  syncState(modal, true);
  focusInitial(modal);
  emitModalEvent(modal, 'shown.bs.modal', trigger);
  return modal;
}

function close(target, options = {}) {
  const modal = resolveModal(target);
  if (!modal || !modal.classList.contains('show')) {
    return null;
  }

  const trigger = returnFocusTo;
  if (!emitModalEvent(modal, 'hide.bs.modal', trigger)) {
    return modal;
  }

  syncState(modal, false);
  emitModalEvent(modal, 'hidden.bs.modal', trigger);
  activeModal = null;

  if (options.restoreFocus !== false && trigger && document.contains(trigger)) {
    trigger.focus();
  }
  returnFocusTo = null;
  return modal;
}

function wireModal(modal) {
  modal.hidden = true;
  modal.setAttribute('aria-hidden', 'true');

  modal.querySelectorAll('[data-dismiss="modal"]').forEach((button) => {
    if (!button.hasAttribute('type')) {
      button.setAttribute('type', 'button');
    }
    button.addEventListener('click', (event) => {
      event.preventDefault();
      close(modal);
    });
  });

  modal.addEventListener('click', (event) => {
    if (event.target === modal) {
      close(modal);
    }
  });
}

function wireTriggers() {
  document.querySelectorAll('[data-toggle="modal"][data-target]').forEach((trigger) => {
    const target = resolveModal(trigger.getAttribute('data-target'));
    if (!target || !target.hasAttribute('data-app-modal-shell')) {
      return;
    }

    trigger.addEventListener('click', (event) => {
      event.preventDefault();
      open(target, { trigger });
    });
  });
}

export function initModalShell() {
  window.appModal = { open, close };

  document.querySelectorAll('[data-app-modal-shell]').forEach(wireModal);
  wireTriggers();

  const backdrop = ensureBackdrop();
  backdrop.addEventListener('click', () => {
    if (activeModal) {
      close(activeModal);
    }
  });

  document.addEventListener('keydown', trapFocus);
  document.addEventListener('keydown', closeActiveOnEscape);
}
