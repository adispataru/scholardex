const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'rankings-categories.js');
const scriptContent = fs.readFileSync(scriptPath, 'utf8');

function createClassList(initialClasses = []) {
  const classes = new Set(initialClasses);
  return {
    add(name) { classes.add(name); },
    remove(name) { classes.delete(name); },
    contains(name) { return classes.has(name); },
    toggle(name, force) {
      if (force === undefined) {
        if (classes.has(name)) {
          classes.delete(name);
          return false;
        }
        classes.add(name);
        return true;
      }
      if (force) {
        classes.add(name);
        return true;
      }
      classes.delete(name);
      return false;
    }
  };
}

function createElement(id, options = {}) {
  const listeners = {};
  return {
    id,
    value: options.value || '',
    textContent: options.textContent || '',
    innerHTML: options.innerHTML || '',
    disabled: Boolean(options.disabled),
    dataset: options.dataset || {},
    classList: createClassList(options.classes || []),
    addEventListener(event, handler) {
      listeners[event] = listeners[event] || [];
      listeners[event].push(handler);
    },
    dispatch(event) {
      (listeners[event] || []).forEach((handler) => handler({ target: this }));
    }
  };
}

function createHarness(fetchImpl) {
  const elements = {
    'wos-categories-search': createElement('wos-categories-search'),
    'wos-categories-sort': createElement('wos-categories-sort', { value: 'categoryName' }),
    'wos-categories-direction': createElement('wos-categories-direction', { value: 'asc' }),
    'wos-categories-size': createElement('wos-categories-size', { value: '25' }),
    'wos-categories-loading': createElement('wos-categories-loading'),
    'wos-categories-error': createElement('wos-categories-error', { classes: ['d-none'] }),
    'wos-categories-empty': createElement('wos-categories-empty', { classes: ['d-none'] }),
    'wos-categories-table': createElement('wos-categories-table', { dataset: { detailBase: '/rankings/categories' } }),
    'wos-categories-table-body': createElement('wos-categories-table-body'),
    'wos-categories-page-info': createElement('wos-categories-page-info'),
    'wos-categories-total-info': createElement('wos-categories-total-info'),
    'wos-categories-prev': createElement('wos-categories-prev'),
    'wos-categories-next': createElement('wos-categories-next')
  };

  const context = {
    document: {
      readyState: 'complete',
      getElementById(id) {
        return elements[id] || null;
      },
      addEventListener() {}
    },
    fetch: fetchImpl,
    URLSearchParams,
    encodeURIComponent,
    setTimeout,
    clearTimeout,
    console
  };

  vm.createContext(context);
  vm.runInContext(scriptContent, context);
  return elements;
}

function parseQuery(url) {
  const query = new URL(url, 'http://localhost').searchParams;
  return {
    page: query.get('page'),
    size: query.get('size'),
    sort: query.get('sort'),
    direction: query.get('direction'),
    q: query.get('q')
  };
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function testDefaultLoadRequestsExpectedParams() {
  const calls = [];
  createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(calls.length, 1);
  assert.deepStrictEqual(parseQuery(calls[0]), {
    page: '0',
    size: '25',
    sort: 'categoryName',
    direction: 'asc',
    q: null
  });
}

async function testControlChangesTriggerRequests() {
  const calls = [];
  const queue = [
    { items: [{ key: 'Computer Science - SCIE', categoryName: 'Computer Science', edition: 'SCIE', journalCount: 3, latestYear: 2024 }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ key: 'Economics - SSCI', categoryName: 'Economics', edition: 'SSCI', journalCount: 2, latestYear: 2023 }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ key: 'Design - SCIE', categoryName: 'Design', edition: 'SCIE', journalCount: 4, latestYear: 2022 }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ key: 'Physics - SCIE', categoryName: 'Physics', edition: 'SCIE', journalCount: 10, latestYear: 2025 }], page: 0, size: 50, totalItems: 50, totalPages: 1 }
  ];

  const els = createHarness(async (url) => {
    calls.push(url);
    const payload = queue.shift() || { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    return {
      ok: true,
      status: 200,
      async json() {
        return payload;
      }
    };
  });

  await wait(10);
  assert.ok(els['wos-categories-table-body'].innerHTML.includes('/rankings/categories/Computer%20Science%20-%20SCIE'));

  els['wos-categories-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['wos-categories-search'].value = 'design';
  els['wos-categories-search'].dispatch('input');
  await wait(350);
  assert.strictEqual(parseQuery(calls[2]).q, 'design');

  els['wos-categories-size'].value = '50';
  els['wos-categories-size'].dispatch('change');
  await wait(10);
  assert.strictEqual(parseQuery(calls[3]).size, '50');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [{ key: 'Only - SCIE', categoryName: 'Only', edition: 'SCIE', journalCount: 1, latestYear: 2024 }], page: 0, size: 25, totalItems: 1, totalPages: 1 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(els['wos-categories-prev'].disabled, true);
  assert.strictEqual(els['wos-categories-next'].disabled, true);
  els['wos-categories-prev'].dispatch('click');
  els['wos-categories-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(calls.length, 1);
}

async function testEmptyAndErrorStateRendering() {
  const emptyEls = createHarness(async () => ({
    ok: true,
    status: 200,
    async json() {
      return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    }
  }));
  await wait(10);
  assert.strictEqual(emptyEls['wos-categories-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() {
      return {};
    }
  }));
  await wait(10);
  assert.strictEqual(errorEls['wos-categories-error'].classList.contains('d-none'), false);
  assert.ok(errorEls['wos-categories-error'].textContent.length > 0);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequests();
  await testPrevNextBoundariesEnforced();
  await testEmptyAndErrorStateRendering();
  console.log('rankings-categories.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
