function safeFallbackHref(raw) {
  // Reconstruct a same-origin path via URL parsing; URL.pathname/search/hash
  // produce fresh strings, so javascript:, data:, and cross-origin URLs are
  // dropped. Returns '/' for any input that isn't a same-origin URL.
  if (typeof raw !== 'string' || raw.length === 0) return '/';
  try {
    const url = new URL(raw, window.location.origin);
    if (url.origin !== window.location.origin) return '/';
    return url.pathname + url.search + url.hash;
  } catch (_e) {
    return '/';
  }
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
