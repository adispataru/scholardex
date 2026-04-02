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
  initSelectedOptionDescriptionSync();
}
