const STORAGE_KEY = 'scholardex-theme';
const THEMES = ['light', 'dark'];

function applyTheme(theme, toggle) {
  const nextTheme = THEMES.includes(theme) ? theme : 'light';
  const root = document.documentElement;
  const isDark = nextTheme === 'dark';

  root.setAttribute('data-bs-theme', nextTheme);
  root.classList.toggle('dark', isDark);

  if (!toggle) {
    return;
  }

  const icon = toggle.querySelector('i');
  const label = toggle.querySelector('.app-shell-theme-toggle__label');

  // The shell renders on every page, including ones with no inline appI18n bundle, so the four strings
  // this function swaps in come from the button's own data-* attributes (filled by messages.properties).
  const d = toggle.dataset;
  toggle.setAttribute('aria-pressed', isDark ? 'true' : 'false');
  toggle.setAttribute('aria-label', isDark
    ? (d.ariaToLight || 'Switch to light theme')
    : (d.ariaToDark || 'Switch to dark theme'));

  if (icon) {
    icon.className = isDark ? 'fa-solid fa-sun' : 'fa-solid fa-moon';
  }

  if (label) {
    label.textContent = isDark ? (d.labelLight || 'Light') : (d.labelDark || 'Dark');
  }

  window.dispatchEvent(new CustomEvent('app:themechange', {
    detail: {
      theme: nextTheme
    }
  }));
}

function resolveInitialTheme() {
  const storedTheme = window.localStorage.getItem(STORAGE_KEY);
  if (THEMES.includes(storedTheme)) {
    return storedTheme;
  }

  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function initThemeShell() {
  const toggle = document.querySelector('[data-app-theme-toggle]');
  const initialTheme = resolveInitialTheme();

  applyTheme(initialTheme, toggle);

  if (!toggle) {
    return;
  }

  toggle.addEventListener('click', () => {
    const currentTheme = document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'light';
    const nextTheme = currentTheme === 'dark' ? 'light' : 'dark';

    window.localStorage.setItem(STORAGE_KEY, nextTheme);
    applyTheme(nextTheme, toggle);
  });
}
