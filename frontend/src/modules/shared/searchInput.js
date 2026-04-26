function emitInput(input) {
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

export function initSearchInputs() {
  document.querySelectorAll('[data-app-search-input]').forEach((root) => {
    const input = root.querySelector('input[type="search"]');
    const clear = root.querySelector('[data-app-search-clear]');
    if (!input || !clear) {
      return;
    }

    clear.addEventListener('click', () => {
      if (!input.value) {
        input.focus();
        return;
      }

      input.value = '';
      emitInput(input);
      input.focus();
    });
  });
}
