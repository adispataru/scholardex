/**
 * fetchUtils.js — shared fetch/CSRF helpers for workspace modules.
 *
 * Spring Security requires the CSRF token on all state-changing requests
 * to non-/api/** endpoints. Thymeleaf injects the token into two <meta>
 * tags in the page head (via the core-styles fragment).
 */

/**
 * Returns headers object with Content-Type: application/json and the
 * CSRF token header (e.g. X-CSRF-TOKEN: <token>) if present in the page.
 */
export function postJsonHeaders() {
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    const tokenMeta  = document.querySelector('meta[name="_csrf"]');
    const csrf = (headerMeta && tokenMeta)
        ? { [headerMeta.content]: tokenMeta.content }
        : {};
    return { 'Content-Type': 'application/json', ...csrf };
}
