function updateToggleState(body, mobileToggle, desktopToggle) {
  const isMobileOpen = body.classList.contains('app-sidebar-mobile-open');
  const isCollapsed = body.classList.contains('app-sidebar-collapsed');

  if (mobileToggle) {
    mobileToggle.setAttribute('aria-expanded', isMobileOpen ? 'true' : 'false');
  }

  if (desktopToggle) {
    desktopToggle.setAttribute('aria-pressed', isCollapsed ? 'true' : 'false');
  }
}

export function initSharedSidebarShell() {
  const body = document.body;
  const sidebar = document.querySelector('[data-app-sidebar]');
  const mobileToggle = document.querySelector('#sidebarToggleTop');
  const desktopToggle = document.querySelector('[data-app-sidebar-desktop-toggle]');
  const overlayClose = document.querySelector('[data-app-sidebar-overlay-close]');

  if (!body || !sidebar) {
    return;
  }

  const closeMobileSidebar = () => {
    body.classList.remove('app-sidebar-mobile-open');
    updateToggleState(body, mobileToggle, desktopToggle);
  };

  if (mobileToggle) {
    mobileToggle.addEventListener('click', () => {
      body.classList.toggle('app-sidebar-mobile-open');
      updateToggleState(body, mobileToggle, desktopToggle);
    });
  }

  if (desktopToggle) {
    desktopToggle.addEventListener('click', () => {
      body.classList.toggle('app-sidebar-collapsed');
      updateToggleState(body, mobileToggle, desktopToggle);
    });
  }

  if (overlayClose) {
    overlayClose.addEventListener('click', closeMobileSidebar);
  }

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeMobileSidebar();
    }
  });

  window.addEventListener('resize', () => {
    if (window.innerWidth >= 768) {
      closeMobileSidebar();
    }
  });

  updateToggleState(body, mobileToggle, desktopToggle);
}
