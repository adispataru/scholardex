const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'admin-scopus-authors.js');
const scriptContent = fs.readFileSync(scriptPath, 'utf8');

function createClassList(initialClasses = []) {
  const classes = new Set(initialClasses);
  return {
    add(name) { classes.add(name); },
    remove(name) { classes.delete(name); },
    contains(name) { return classes.has(name); }
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
    'admin-authors-afid': createElement('admin-authors-afid', { value: '60000434' }),
    'admin-authors-search': createElement('admin-authors-search'),
    'admin-authors-sort': createElement('admin-authors-sort', { value: 'name' }),
    'admin-authors-direction': createElement('admin-authors-direction', { value: 'asc' }),
    'admin-authors-size': createElement('admin-authors-size', { value: '25' }),
    'admin-authors-loading': createElement('admin-authors-loading'),
    'admin-authors-error': createElement('admin-authors-error', { classes: ['d-none'] }),
    'admin-authors-empty': createElement('admin-authors-empty', { classes: ['d-none'] }),
    'admin-authors-table-body': createElement('admin-authors-table-body'),
    'admin-authors-page-info': createElement('admin-authors-page-info'),
    'admin-authors-total-info': createElement('admin-authors-total-info'),
    'admin-authors-prev': createElement('admin-authors-prev'),
    'admin-authors-next': createElement('admin-authors-next')
  };

  const context = {
    document: {
      readyState: 'complete',
      getElementById(id) { return elements[id] || null; },
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
    afid: query.get('afid'),
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
    return { ok: true, status: 200, async json() { return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 }; } };
  });

  await wait(10);
  assert.strictEqual(calls.length, 1);
  assert.deepStrictEqual(parseQuery(calls[0]), {
    afid: '60000434',
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
    { items: [{ id: 'a', name: 'A', affiliations: ['UVT'] }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'b', name: 'B', affiliations: [] }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'c', name: 'C', affiliations: [] }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'd', name: 'D', affiliations: [] }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
  ];

  const els = createHarness(async (url) => {
    calls.push(url);
    const payload = queue.shift() || { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    return { ok: true, status: 200, async json() { return payload; } };
  });

  await wait(10);
  els['admin-authors-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['admin-authors-sort'].value = 'id';
  els['admin-authors-sort'].dispatch('change');
  await wait(10);
  assert.strictEqual(parseQuery(calls[2]).page, '0');
  assert.strictEqual(parseQuery(calls[2]).sort, 'id');

  els['admin-authors-search'].value = 'alice';
  els['admin-authors-search'].dispatch('input');
  await wait(350);
  assert.strictEqual(parseQuery(calls[3]).page, '0');
  assert.strictEqual(parseQuery(calls[3]).q, 'alice');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return { ok: true, status: 200, async json() { return { items: [{ id: 'only', name: 'Only', affiliations: [] }], page: 0, size: 25, totalItems: 1, totalPages: 1 }; } };
  });

  await wait(10);
  assert.strictEqual(els['admin-authors-prev'].disabled, true);
  assert.strictEqual(els['admin-authors-next'].disabled, true);

  els['admin-authors-prev'].dispatch('click');
  els['admin-authors-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(calls.length, 1);
}

async function testEmptyAndErrorStateRendering() {
  const emptyEls = createHarness(async () => ({ ok: true, status: 200, async json() { return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 }; } }));
  await wait(10);
  assert.strictEqual(emptyEls['admin-authors-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({ ok: false, status: 500, async json() { return {}; } }));
  await wait(10);
  assert.strictEqual(errorEls['admin-authors-error'].classList.contains('d-none'), false);
  assert.ok(errorEls['admin-authors-error'].textContent.length > 0);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequestsAndResetPage();
  await testPrevNextBoundariesEnforced();
  await testEmptyAndErrorStateRendering();
  console.log('admin-scopus-authors.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
