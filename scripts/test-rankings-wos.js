const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'rankings-wos.js');
const scriptContent = fs.readFileSync(scriptPath, 'utf8');

function createClassList(initialClasses = []) {
  const classes = new Set(initialClasses);
  return {
    add(name) {
      classes.add(name);
    },
    remove(name) {
      classes.delete(name);
    },
    contains(name) {
      return classes.has(name);
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
    'wos-search': createElement('wos-search'),
    'wos-sort': createElement('wos-sort', { value: 'name' }),
    'wos-direction': createElement('wos-direction', { value: 'asc' }),
    'wos-size': createElement('wos-size', { value: '25' }),
    'wos-loading': createElement('wos-loading'),
    'wos-error': createElement('wos-error', { classes: ['d-none'] }),
    'wos-empty': createElement('wos-empty', { classes: ['d-none'] }),
    'wos-table-body': createElement('wos-table-body'),
    'wos-page-info': createElement('wos-page-info'),
    'wos-total-info': createElement('wos-total-info'),
    'wos-prev': createElement('wos-prev'),
    'wos-next': createElement('wos-next')
  };

  const documentListeners = {};
  const context = {
    document: {
      readyState: 'complete',
      getElementById(id) {
        return elements[id] || null;
      },
      addEventListener(event, handler) {
        documentListeners[event] = handler;
      }
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
  assert.strictEqual(calls.length, 1, 'expected one fetch on initialization');
  assert.deepStrictEqual(parseQuery(calls[0]), {
    page: '0',
    size: '25',
    sort: 'name',
    direction: 'asc',
    q: null
  });
}

async function testControlChangesTriggerRequestsAndResetPage() {
  const calls = [];
  const queue = [
    { items: [{ id: 'a', name: 'A', issn: '1', eIssn: '2', alternativeIssns: [] }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'b', name: 'B', issn: '3', eIssn: '4', alternativeIssns: [] }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'c', name: 'C', issn: '5', eIssn: '6', alternativeIssns: [] }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'd', name: 'D', issn: '7', eIssn: '8', alternativeIssns: [] }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
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

  els['wos-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1', 'next should request next page');

  els['wos-sort'].value = 'issn';
  els['wos-sort'].dispatch('change');
  await wait(10);
  const sortQuery = parseQuery(calls[2]);
  assert.strictEqual(sortQuery.page, '0', 'sort change should reset page to 0');
  assert.strictEqual(sortQuery.sort, 'issn', 'sort value should be propagated');

  els['wos-search'].value = 'quantum';
  els['wos-search'].dispatch('input');
  await wait(350);
  const searchQuery = parseQuery(calls[3]);
  assert.strictEqual(searchQuery.page, '0', 'search should reset page to 0');
  assert.strictEqual(searchQuery.q, 'quantum', 'search term should be propagated');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [{ id: 'only', name: 'Only', issn: '', eIssn: '', alternativeIssns: [] }], page: 0, size: 25, totalItems: 1, totalPages: 1 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(els['wos-prev'].disabled, true, 'prev should be disabled on single-page result');
  assert.strictEqual(els['wos-next'].disabled, true, 'next should be disabled on single-page result');

  els['wos-prev'].dispatch('click');
  els['wos-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(calls.length, 1, 'boundary clicks should not trigger additional requests');
}

async function testEmptyStateRendering() {
  const els = createHarness(async () => ({
    ok: true,
    status: 200,
    async json() {
      return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    }
  }));

  await wait(10);
  assert.strictEqual(els['wos-empty'].classList.contains('d-none'), false, 'empty state should be visible');
  assert.strictEqual(els['wos-table-body'].innerHTML, '', 'table body should remain empty for empty results');
}

async function testErrorStateRendering() {
  const els = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() {
      return {};
    }
  }));

  await wait(10);
  assert.strictEqual(els['wos-error'].classList.contains('d-none'), false, 'error state should be visible');
  assert.ok(els['wos-error'].textContent.length > 0, 'error state should contain a message');
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequestsAndResetPage();
  await testPrevNextBoundariesEnforced();
  await testEmptyStateRendering();
  await testErrorStateRendering();
  console.log('rankings-wos.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
