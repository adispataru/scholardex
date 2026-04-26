export function initErrorPages() {
  document.querySelectorAll('[data-app-error-back]').forEach((button) => {
    button.addEventListener('click', () => {
      const fallbackHref = button.getAttribute('data-fallback-href') || '/';
      if (window.history.length > 1) {
        window.history.back();
        return;
      }
      window.location.assign(fallbackHref);
    });
  });

  document.querySelectorAll('[data-app-error-retry]').forEach((button) => {
    button.addEventListener('click', () => {
      window.location.reload();
    });
  });
}
