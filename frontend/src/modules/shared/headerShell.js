function setWorkspaceMenuState(trigger, menu, expanded) {
  trigger.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  menu.hidden = !expanded;
}

export function initSharedHeaderShell() {
  const switcher = document.querySelector('[data-app-workspace-switcher]');
  if (!switcher) {
    return;
  }

  const trigger = switcher.querySelector('[data-app-workspace-trigger]');
  const menu = switcher.querySelector('[data-app-workspace-menu]');

  if (!trigger || !menu) {
    return;
  }

  const closeMenu = () => setWorkspaceMenuState(trigger, menu, false);
  const openMenu = () => setWorkspaceMenuState(trigger, menu, true);
  const toggleMenu = () => {
    const expanded = trigger.getAttribute('aria-expanded') === 'true';
    if (expanded) {
      closeMenu();
    } else {
      openMenu();
    }
  };

  trigger.addEventListener('click', (event) => {
    event.preventDefault();
    toggleMenu();
  });

  document.addEventListener('click', (event) => {
    if (!switcher.contains(event.target)) {
      closeMenu();
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeMenu();
      trigger.focus();
    }
  });

  menu.addEventListener('click', (event) => {
    const item = event.target.closest('[role="menuitem"]');
    if (item) {
      closeMenu();
    }
  });

  setWorkspaceMenuState(trigger, menu, false);
}
