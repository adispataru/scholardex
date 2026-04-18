function initSingleTabBar(container) {
  const tabList = container.querySelector('[role="tablist"]');
  const tabs = Array.from(container.querySelectorAll('[role="tab"]'));
  const panels = Array.from(container.querySelectorAll('[role="tabpanel"]'));

  if (!tabList || !tabs.length || !panels.length) {
    return;
  }

  // Screen-reader live region — announces active tab on switch
  const liveRegion = document.createElement('div');
  liveRegion.setAttribute('role', 'status');
  liveRegion.setAttribute('aria-live', 'polite');
  liveRegion.setAttribute('aria-atomic', 'true');
  liveRegion.style.cssText = 'position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0';
  container.appendChild(liveRegion);

  const callbacksKey = container.dataset.appTabBarCallbacks;
  // Looked up lazily at activation time — the inline <script> that defines
  // window[callbacksKey] runs AFTER the bundle, so capturing it at init
  // time would always yield an empty object.
  function getCallbackMap() {
    return (callbacksKey && window[callbacksKey]) ? window[callbacksKey] : {};
  }
  const loadedTabs = new Set();

  function getPanel(tab) {
    const panelId = tab.getAttribute('aria-controls');
    return container.querySelector('#' + panelId);
  }

  function getActiveTab() {
    return tabs.find(t => t.getAttribute('aria-selected') === 'true') || null;
  }

  function activateTab(hash, pushHistory) {
    const incomingTab = tabs.find(t => t.dataset.tabId === hash);
    if (!incomingTab) {
      return;
    }

    const outgoingTab = getActiveTab();
    if (incomingTab === outgoingTab) {
      return;
    }

    const outgoingPanel = outgoingTab ? getPanel(outgoingTab) : null;
    const incomingPanel = getPanel(incomingTab);

    if (!incomingPanel) {
      return;
    }

    // Phase 1: fade out outgoing panel (150ms)
    if (outgoingPanel) {
      outgoingPanel.classList.add('app-tab-bar__panel--leaving');
    }

    setTimeout(() => {
      // Finish hiding outgoing
      if (outgoingPanel) {
        outgoingPanel.classList.remove('app-tab-bar__panel--active', 'app-tab-bar__panel--leaving');
        outgoingPanel.hidden = true;
      }
      if (outgoingTab) {
        outgoingTab.setAttribute('aria-selected', 'false');
        outgoingTab.classList.remove('app-tab-bar__tab--active');
        outgoingTab.tabIndex = -1;
      }

      // Phase 2: fade in incoming panel (200ms).
      // Set inline opacity:0 first, force a reflow so the browser commits
      // that frame, add the --active class (CSS: opacity:1 + transition),
      // then clear the inline style so the CSS value wins and the transition
      // from 0→1 fires. Inline style must be removed in the same task —
      // transitionend is NOT used because it would never fire (inline style
      // would win over the CSS class and suppress the transition).
      incomingPanel.hidden = false;
      incomingPanel.style.opacity = '0';
      incomingPanel.getBoundingClientRect(); // commit the opacity-0 frame
      incomingPanel.classList.add('app-tab-bar__panel--active');
      incomingPanel.style.opacity = ''; // let CSS opacity:1 win → transition fires

      incomingTab.setAttribute('aria-selected', 'true');
      incomingTab.classList.add('app-tab-bar__tab--active');
      incomingTab.tabIndex = 0;

      // Announce to screen readers
      const label = incomingTab.querySelector('span')?.textContent.trim()
                 ?? incomingTab.textContent.trim();
      liveRegion.textContent = '';
      requestAnimationFrame(() => { liveRegion.textContent = label + ' tab'; });

      if (pushHistory) {
        history.pushState(null, '', '#' + hash);
      }

      // Fire lazy-load callback. Tabs with data-tab-reload always re-fire;
      // all others fire once and are tracked in loadedTabs.
      const alwaysReload = incomingTab.dataset.tabReload === 'true';
      if (alwaysReload || !loadedTabs.has(hash)) {
        loadedTabs.add(hash);
        if (typeof getCallbackMap()[hash] === 'function') {
          getCallbackMap()[hash](incomingPanel);
        }
      }
    }, outgoingPanel ? 150 : 0);
  }

  // Set initial roving tabindex state
  tabs.forEach((tab, i) => {
    tab.tabIndex = i === 0 ? 0 : -1;
  });

  // Determine and activate initial tab based on URL hash
  function activateInitial() {
    const hash = window.location.hash.slice(1);
    const matchingTab = tabs.find(t => t.dataset.tabId === hash);
    if (matchingTab) {
      // Deactivate all server-rendered defaults before activating the hash tab.
      // Without this, the server-rendered default tab (overview) keeps its
      // active classes alongside the hash-matched tab, causing dual highlight.
      tabs.forEach(t => {
        if (t !== matchingTab) {
          t.setAttribute('aria-selected', 'false');
          t.classList.remove('app-tab-bar__tab--active');
          t.tabIndex = -1;
        }
      });
      panels.forEach(p => {
        if (p !== getPanel(matchingTab)) {
          p.hidden = true;
          p.classList.remove('app-tab-bar__panel--active', 'app-tab-bar__panel--leaving');
        }
      });

      // Activate without transition (first render)
      const panel = getPanel(matchingTab);
      if (panel) {
        panel.hidden = false;
        panel.classList.add('app-tab-bar__panel--active');
      }
      matchingTab.setAttribute('aria-selected', 'true');
      matchingTab.classList.add('app-tab-bar__tab--active');
      matchingTab.tabIndex = 0;

      if (!loadedTabs.has(hash)) {
        loadedTabs.add(hash);
        // Defer the callback so the inline <script> that defines
        // window[callbacksKey] has a chance to execute first.
        // (app.js is a synchronous blocking script loaded before that
        // inline block, so getCallbackMap() would return {} here.)
        setTimeout(() => {
          if (typeof getCallbackMap()[hash] === 'function') {
            getCallbackMap()[hash](panel);
          }
        }, 0);
      }
    } else {
      // Fall back to first tab
      const firstHash = tabs[0].dataset.tabId;
      const firstPanel = getPanel(tabs[0]);
      if (firstPanel) {
        firstPanel.hidden = false;
        firstPanel.classList.add('app-tab-bar__panel--active');
      }
      tabs[0].setAttribute('aria-selected', 'true');
      tabs[0].classList.add('app-tab-bar__tab--active');
      tabs[0].tabIndex = 0;
      history.replaceState(null, '', '#' + firstHash);

      if (!loadedTabs.has(firstHash)) {
        loadedTabs.add(firstHash);
        // Same deferral as above — inline script runs after the bundle.
        setTimeout(() => {
          if (typeof getCallbackMap()[firstHash] === 'function') {
            getCallbackMap()[firstHash](firstPanel);
          }
        }, 0);
      }
    }
  }

  activateInitial();

  // Tab click
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      activateTab(tab.dataset.tabId, true);
    });
  });

  // Arrow-key navigation (roving tabindex, manual activation)
  tabList.addEventListener('keydown', (e) => {
    const current = tabs.indexOf(document.activeElement);
    if (current === -1) {
      return;
    }

    switch (e.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        e.preventDefault();
        tabs[(current + 1) % tabs.length].focus();
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        e.preventDefault();
        tabs[(current - 1 + tabs.length) % tabs.length].focus();
        break;
      case 'Home':
        e.preventDefault();
        tabs[0].focus();
        break;
      case 'End':
        e.preventDefault();
        tabs[tabs.length - 1].focus();
        break;
      case 'Enter':
      case ' ':
        e.preventDefault();
        activateTab(document.activeElement.dataset.tabId, true);
        break;
    }
  });

  // Browser back/forward
  window.addEventListener('popstate', () => {
    const hash = window.location.hash.slice(1);
    if (hash) {
      activateTab(hash, false);
    }
  });

  // Delegate clicks on [data-tab-goto] anywhere inside the container
  container.addEventListener('click', e => {
    const trigger = e.target.closest('[data-tab-goto]');
    if (!trigger) return;
    e.preventDefault();
    activateTab(trigger.dataset.tabGoto, true);
  });

  // Expose public API on the container element for other modules to use
  container._appTabBar = { activateTab };
}

export function initWorkspaceTabs() {
  const containers = document.querySelectorAll('[data-app-tab-bar]');
  if (!containers.length) {
    return;
  }

  containers.forEach(initSingleTabBar);

  // Global public API — routes to the first tab bar on the page
  // (H36 has exactly one per page; extend if multi-bar pages are ever needed)
  window.appWorkspaceTabs = {
    activateTab(hash) {
      const first = containers[0];
      if (first && first._appTabBar) {
        first._appTabBar.activateTab(hash, true);
      }
    }
  };

  // Ctrl+1..Ctrl+4 global shortcuts
  document.addEventListener('keydown', (e) => {
    if (!e.ctrlKey || e.shiftKey || e.altKey || e.metaKey) {
      return;
    }
    const digit = parseInt(e.key, 10);
    if (digit < 1 || digit > 4) {
      return;
    }
    const focused = document.activeElement;
    const isInputFocused = focused && (
      focused.tagName === 'INPUT' ||
      focused.tagName === 'TEXTAREA' ||
      focused.tagName === 'SELECT' ||
      focused.isContentEditable
    );
    if (isInputFocused) {
      return;
    }
    const firstContainer = containers[0];
    if (!firstContainer || !firstContainer._appTabBar) {
      return;
    }
    const tabs = Array.from(firstContainer.querySelectorAll('[role="tab"]'));
    const targetTab = tabs[digit - 1];
    if (targetTab) {
      e.preventDefault();
      firstContainer._appTabBar.activateTab(targetTab.dataset.tabId, true);
      targetTab.focus();
    }
  });
}
