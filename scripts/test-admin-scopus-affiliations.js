const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'admin-scopus-affiliations.js');
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
    'admin-affiliations-search': createElement('admin-affiliations-search'),
    'admin-affiliations-sort': createElement('admin-affiliations-sort', { value: 'name' }),
    'admin-affiliations-direction': createElement('admin-affiliations-direction', { value: 'asc' }),
    'admin-affiliations-size': createElement('admin-affiliations-size', { value: '25' }),
    'admin-affiliations-loading': createElement('admin-affiliations-loading'),
    'admin-affiliations-error': createElement('admin-affiliations-error', { classes: ['d-none'] }),
    'admin-affiliations-empty': createElement('admin-affiliations-empty', { classes: ['d-none'] }),
    'admin-affiliations-table-body': createElement('admin-affiliations-table-body'),
    'admin-affiliations-page-info': createElement('admin-affiliations-page-info'),
    'admin-affiliations-total-info': createElement('admin-affiliations-total-info'),
    'admin-affiliations-prev': createElement('admin-affiliations-prev'),
    'admin-affiliations-next': createElement('admin-affiliations-next')
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
    { items: [{ afid: 'a', name: 'A', city: 'C', country: 'R' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ afid: 'b', name: 'B', city: 'C', country: 'R' }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ afid: 'c', name: 'C', city: 'C', country: 'R' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ afid: 'd', name: 'D', city: 'C', country: 'R' }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
  ];

  const els = createHarness(async (url) => {
    calls.push(url);
    const payload = queue.shift() || { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    return { ok: true, status: 200, async json() { return payload; } };
  });

  await wait(10);
  els['admin-affiliations-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['admin-affiliations-sort'].value = 'country';
  els['admin-affiliations-sort'].dispatch('change');
  await wait(10);
  assert.strictEqual(parseQuery(calls[2]).page, '0');
  assert.strictEqual(parseQuery(calls[2]).sort, 'country');

  els['admin-affiliations-search'].value = 'romania';
  els['admin-affiliations-search'].dispatch('input');
  await wait(350);
  assert.strictEqual(parseQuery(calls[3]).page, '0');
  assert.strictEqual(parseQuery(calls[3]).q, 'romania');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return { ok: true, status: 200, async json() { return { items: [{ afid: 'only', name: 'Only', city: '', country: '' }], page: 0, size: 25, totalItems: 1, totalPages: 1 }; } };
  });

  await wait(10);
  assert.strictEqual(els['admin-affiliations-prev'].disabled, true);
  assert.strictEqual(els['admin-affiliations-next'].disabled, true);

  els['admin-affiliations-prev'].dispatch('click');
  els['admin-affiliations-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(calls.length, 1);
}

async function testEmptyAndErrorStateRendering() {
  const emptyEls = createHarness(async () => ({ ok: true, status: 200, async json() { return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 }; } }));
  await wait(10);
  assert.strictEqual(emptyEls['admin-affiliations-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({ ok: false, status: 500, async json() { return {}; } }));
  await wait(10);
  assert.strictEqual(errorEls['admin-affiliations-error'].classList.contains('d-none'), false);
  assert.ok(errorEls['admin-affiliations-error'].textContent.length > 0);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequestsAndResetPage();
  await testPrevNextBoundariesEnforced();
  await testEmptyAndErrorStateRendering();
  console.log('admin-scopus-affiliations.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
