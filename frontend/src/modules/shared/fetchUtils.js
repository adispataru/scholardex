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
    return { 'Content-Type': 'application/json', ...csrfHeaders() };
}

/**
 * The CSRF token header alone, for a POST whose body sets its own Content-Type
 * (a FormData upload carries the multipart boundary the browser chooses).
 */
export function csrfHeaders() {
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    const tokenMeta  = document.querySelector('meta[name="_csrf"]');
    return (headerMeta && tokenMeta)
        ? { [headerMeta.content]: tokenMeta.content }
        : {};
}
