function initTooltips() {
  if (typeof window.$ !== 'function' || !window.$.fn || typeof window.$.fn.tooltip !== 'function') {
    return;
  }

  window.$('[data-toggle="tooltip"]').tooltip();
}

function initSelectedOptionDescriptionSync() {
  const configurableSelects = document.querySelectorAll('[data-sync-selected-description-target]');

  configurableSelects.forEach((selectElement) => {
    const targetSelector = selectElement.dataset.syncSelectedDescriptionTarget;
    if (!targetSelector) {
      return;
    }

    const targetElement = document.querySelector(targetSelector);
    if (!targetElement) {
      return;
    }

    const syncDescription = () => {
      const selectedOption = selectElement.options[selectElement.selectedIndex];
      targetElement.textContent = selectedOption?.dataset?.description || '';
    };

    selectElement.addEventListener('change', syncDescription);
    syncDescription();
  });
}

export function initSharedDomBehaviors() {
  initTooltips();
  initSelectedOptionDescriptionSync();
}
