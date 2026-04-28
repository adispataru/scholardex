export function initPublicShell() {
    const toggle = document.querySelector('[data-app-public-menu-toggle]');
    if (!toggle) return;

    const navId = toggle.getAttribute('aria-controls');
    const nav = navId ? document.getElementById(navId) : null;
    if (!nav) return;

    toggle.addEventListener('click', () => {
        const expanded = toggle.getAttribute('aria-expanded') === 'true';
        toggle.setAttribute('aria-expanded', String(!expanded));
        nav.classList.toggle('is-open', !expanded);
    });

    document.addEventListener('click', (e) => {
        if (!toggle.contains(e.target) && !nav.contains(e.target)) {
            toggle.setAttribute('aria-expanded', 'false');
            nav.classList.remove('is-open');
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && nav.classList.contains('is-open')) {
            toggle.setAttribute('aria-expanded', 'false');
            nav.classList.remove('is-open');
            toggle.focus();
        }
    });
}
