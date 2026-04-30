// Strict whitelist for fallback hrefs: must be a relative path consisting of
// a leading "/" followed by safe path characters only (letters, digits, -, _,
// ., /). Disallows protocol-relative ("//"), schemes (javascript:, data:),
// query strings, and fragments. Returns "/" for anything that doesn't match.
const SAFE_PATH_PATTERN = /^\/[A-Za-z0-9_\-./]*$/;

function safeFallbackHref(raw) {
  if (typeof raw !== 'string' || raw.length === 0) return '/';
  if (raw.startsWith('//')) return '/';
  if (!SAFE_PATH_PATTERN.test(raw)) return '/';
  return raw;
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
