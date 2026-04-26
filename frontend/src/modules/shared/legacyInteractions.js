function querySelectorFromTarget(targetValue) {
  if (!targetValue || !targetValue.startsWith('#')) {
    return null;
  }

  return document.querySelector(targetValue);
}

function setCollapseState(trigger, region, expanded) {
  trigger.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  region.classList.toggle('show', expanded);
  region.hidden = !expanded;
}

function initCollapseTriggers() {
  const triggers = document.querySelectorAll('[data-toggle="collapse"][data-target]');

  triggers.forEach((trigger) => {
    const target = querySelectorFromTarget(trigger.getAttribute('data-target'));
    if (!target) {
      return;
    }

    const initiallyExpanded = trigger.getAttribute('aria-expanded') === 'true' || target.classList.contains('show');
    setCollapseState(trigger, target, initiallyExpanded);

    trigger.addEventListener('click', (event) => {
      event.preventDefault();
      const expanded = trigger.getAttribute('aria-expanded') === 'true';
      setCollapseState(trigger, target, !expanded);
    });
  });
}

function getModalBackdrop() {
  return document.querySelector('[data-app-modal-backdrop]');
}

function ensureModalBackdrop() {
  let backdrop = getModalBackdrop();
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

function setModalState(modal, open) {
  const backdrop = ensureModalBackdrop();
  const closeButtons = modal.querySelectorAll('[data-dismiss="modal"]');

  modal.classList.toggle('show', open);
  modal.hidden = !open;
  modal.setAttribute('aria-hidden', open ? 'false' : 'true');
  backdrop.hidden = !open;
  backdrop.classList.toggle('show', open);
  document.body.classList.toggle('app-modal-open', open);

  closeButtons.forEach((button) => {
    if (!button.hasAttribute('type')) {
      button.setAttribute('type', 'button');
    }
  });

  if (open) {
    const autofocusTarget = modal.querySelector('[autofocus], input, select, textarea, button');
    autofocusTarget?.focus();
  }
}

function initModalTriggers() {
  const triggers = document.querySelectorAll('[data-toggle="modal"][data-target]');
  const backdrop = ensureModalBackdrop();

  const closeAnyOpenModal = () => {
    const openModal = document.querySelector('.modal.show:not([data-app-modal-shell])');
    if (openModal) {
      setModalState(openModal, false);
    }
  };

  triggers.forEach((trigger) => {
    const target = querySelectorFromTarget(trigger.getAttribute('data-target'));
    if (!target) {
      return;
    }
    if (target.hasAttribute('data-app-modal-shell')) {
      return;
    }

    target.hidden = true;
    target.setAttribute('aria-hidden', 'true');

    trigger.addEventListener('click', (event) => {
      event.preventDefault();
      closeAnyOpenModal();
      setModalState(target, true);
    });

    target.querySelectorAll('[data-dismiss="modal"]').forEach((button) => {
      button.addEventListener('click', (event) => {
        event.preventDefault();
        setModalState(target, false);
        trigger.focus();
      });
    });

    target.addEventListener('click', (event) => {
      if (event.target === target) {
        setModalState(target, false);
        trigger.focus();
      }
    });
  });

  backdrop.addEventListener('click', closeAnyOpenModal);

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeAnyOpenModal();
    }
  });
}

function initTooltips() {
  document.querySelectorAll('[data-toggle="tooltip"]').forEach((element) => {
    if (!element.hasAttribute('title') && element.dataset.originalTitle) {
      element.setAttribute('title', element.dataset.originalTitle);
    }
  });
}

function initScrollToTop() {
  const trigger = document.querySelector('.scroll-to-top');
  if (!trigger) {
    return;
  }

  const syncVisibility = () => {
    const shouldShow = window.scrollY > 100;
    trigger.classList.toggle('is-visible', shouldShow);
    trigger.hidden = !shouldShow;
  };

  trigger.hidden = true;
  syncVisibility();

  window.addEventListener('scroll', syncVisibility, { passive: true });
  trigger.addEventListener('click', (event) => {
    event.preventDefault();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
}

export function initLegacyInteractions() {
  initCollapseTriggers();
  initModalTriggers();
  initTooltips();
  initScrollToTop();
}
