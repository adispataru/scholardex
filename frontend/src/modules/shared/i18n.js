/**
 * i18n.js — H87 S3a. Translation lookup for the client-side modules.
 *
 * The bundle is INLINED into the page by Thymeleaf (`window.appI18n`) before app.js runs, so lookups are
 * synchronous: no fetch to race with the lazy-loaded workspace panels, and no flash of untranslated text.
 * `messages.properties` stays the single source of truth — the server picks the keys for the active locale.
 *
 * Plural selection mirrors `PluralRules.java`. Romanian has three forms and the third takes a particle
 * ("20 DE publicații"), which the previous `n !== 1 ? 's' : ''` pattern could not express.
 */

const FALLBACK_LOCALE = 'ro';

function bundle() {
    return (typeof window !== 'undefined' && window.appI18n) || {};
}

export function currentLocale() {
    if (typeof window !== 'undefined' && window.appLocale) return window.appLocale;
    if (typeof document !== 'undefined' && document.documentElement?.lang) return document.documentElement.lang;
    return FALLBACK_LOCALE;
}

/**
 * CLDR categories for the two supported languages. Kept in lockstep with PluralRules.java rather than relying
 * on Intl.PluralRules alone, so the two sides cannot drift on the boundary cases (0, 20, 101).
 */
export function pluralCategory(count, locale = currentLocale()) {
    const n = Math.abs(Number(count) || 0);
    if (locale === 'ro') {
        if (n === 1) return 'one';
        const mod100 = n % 100;
        return (n === 0 || (mod100 >= 1 && mod100 <= 19)) ? 'few' : 'other';
    }
    return n === 1 ? 'one' : 'other';
}

/**
 * Translate `key`, substituting {0}, {1}, … positionally.
 * A missing key returns the key itself — visible in the UI and in tests, never a silent blank.
 */
export function t(key, ...args) {
    const raw = bundle()[key];
    if (raw == null) return key;
    return args.reduce((text, arg, i) => text.split(`{${i}}`).join(String(arg)), raw);
}

/**
 * Translate a pluralised message: `base` + '.one' | '.few' | '.other', with the count as {0}.
 *
 * If the selected form is missing, fall back through EVERY other form before giving up: the bundle-parity
 * test keeps shipped families complete, so this only fires on a half-finished key during development — and
 * showing slightly-wrong grammar beats showing a raw message key to a researcher.
 */
export function tPlural(base, count, ...args) {
    const category = pluralCategory(count);
    const candidates = [`${base}.${category}`, `${base}.few`, `${base}.other`, `${base}.one`, base];
    const found = candidates.find(k => bundle()[k] != null);
    return t(found || `${base}.${category}`, count, ...args);
}

/** Locale-aware number formatting for counts shown next to translated nouns. */
export function formatNumber(value, options = {}) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '';
    try {
        return new Intl.NumberFormat(currentLocale(), options).format(n);
    } catch (_) {
        return String(n);
    }
}

/** Expose on window too, so non-module inline scripts and debugging can reach the same lookup. */
if (typeof window !== 'undefined') {
    window.appT = t;
    window.appTPlural = tPlural;
}
