function safeFallbackHref(raw) {
  // Only allow same-origin relative paths starting with "/" (and not "//"),
  // to block javascript:, data:, and protocol-relative URLs.
  if (typeof raw !== 'string' || raw.length === 0) return '/';
  if (raw.startsWith('/') && !raw.startsWith('//')) return raw;
  return '/';
}

export function initErrorPages() {
  document.querySelectorAll('[data-app-error-back]').forEach((button) => {
    button.addEventListener('click', () => {
      const fallbackHref = safeFallbackHref(button.getAttribute('data-fallback-href'));
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
