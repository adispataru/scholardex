/**
 * Lock-on-submit UX guard.
 *
 * Every real <form> submit in this app is a full-page POST (the AJAX controls use
 * type="button", not form submits), so when the user clicks a synchronous-submit button
 * — "Refresh All", "Run Now", "Nudge", "Verify" — we disable that button and swap its
 * icon for a spinner while the request is in flight. The button stays locked because the
 * server redirects and the page reloads, which resets it naturally.
 *
 * Opt out per form with `data-no-submit-lock`. Forms whose submit is intercepted
 * (event.defaultPrevented — e.g. a future AJAX/confirm handler) are skipped automatically.
 */

const FALLBACK_UNLOCK_MS = 20000;

function spinnerClass(existingIcon) {
  const size = existingIcon && existingIcon.classList.contains('fa-sm') ? ' fa-sm' : '';
  return `fa-solid fa-spinner fa-spin${size}`;
}

function lockButton(button) {
  if (!button || button.dataset.submitLocked === '1') {
    return;
  }
  button.dataset.submitLocked = '1';

  // Preserve enough to restore if the navigation never happens (network failure/hang).
  const originalHtml = button.innerHTML;
  const originalDisabled = button.disabled;

  const icon = button.querySelector('i.fa-solid, i.fa-regular, i.fa-brands, svg.svg-inline--fa');
  if (icon) {
    icon.className = spinnerClass(icon);
  } else {
    button.insertAdjacentHTML('afterbegin', '<i class="fa-solid fa-spinner fa-spin fa-sm" aria-hidden="true"></i> ');
  }
  button.setAttribute('aria-busy', 'true');
  button.disabled = true;

  // Safety net: a full-page POST reloads the page (resetting this), but if the request
  // fails or hangs the user would be stranded with a dead button — restore after a while.
  window.setTimeout(() => {
    if (!document.body.contains(button)) {
      return;
    }
    button.innerHTML = originalHtml;
    button.disabled = originalDisabled;
    button.removeAttribute('aria-busy');
    delete button.dataset.submitLocked;
  }, FALLBACK_UNLOCK_MS);
}

export function initSubmitLock() {
  document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) {
      return;
    }
    // Something already handled this submit (AJAX, confirm dialog, validation) — leave it alone.
    if (event.defaultPrevented || form.hasAttribute('data-no-submit-lock')) {
      return;
    }
    const button = event.submitter
      || form.querySelector('button[type="submit"], input[type="submit"], button:not([type])');
    if (!button) {
      return;
    }
    // Defer so the browser finishes serialising the form (incl. the submitter's value)
    // before we disable the button; the page is still visible while the POST is in flight.
    window.setTimeout(() => lockButton(button), 0);
  });
}
