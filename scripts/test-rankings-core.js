const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'rankings-core.js');
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
    'core-search': createElement('core-search'),
    'core-sort': createElement('core-sort', { value: 'name' }),
    'core-direction': createElement('core-direction', { value: 'asc' }),
    'core-size': createElement('core-size', { value: '25' }),
    'core-loading': createElement('core-loading'),
    'core-error': createElement('core-error', { classes: ['d-none'] }),
    'core-empty': createElement('core-empty', { classes: ['d-none'] }),
    'core-table': createElement('core-table', { dataset: { detailBase: '/rankings/core' } }),
    'core-table-body': createElement('core-table-body'),
    'core-page-info': createElement('core-page-info'),
    'core-total-info': createElement('core-total-info'),
    'core-prev': createElement('core-prev'),
    'core-next': createElement('core-next')
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
    sort: 'name',
    direction: 'asc',
    q: null
  });
}

async function testControlChangesTriggerRequestsAndResetPage() {
  const calls = [];
  const queue = [
    { items: [{ id: 'a', name: 'A', acronym: 'AA', category2023: 'A' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'b', name: 'B', acronym: 'BB', category2023: 'B' }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'c', name: 'C', acronym: 'CC', category2023: 'C' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'd', name: 'D', acronym: 'DD', category2023: 'D' }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
  ];

  const els = createHarness(async (url) => {
    calls.push(url);
    const payload = queue.shift() || { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    return { ok: true, status: 200, async json() { return payload; } };
  });

  await wait(10);

  els['core-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['core-sort'].value = 'acronym';
  els['core-sort'].dispatch('change');
  await wait(10);
  const sortQuery = parseQuery(calls[2]);
  assert.strictEqual(sortQuery.page, '0');
  assert.strictEqual(sortQuery.sort, 'acronym');

  els['core-search'].value = 'ml';
  els['core-search'].dispatch('input');
  await wait(350);
  const searchQuery = parseQuery(calls[3]);
  assert.strictEqual(searchQuery.page, '0');
  assert.strictEqual(searchQuery.q, 'ml');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [{ id: 'only', name: 'Only', acronym: 'O', category2023: 'A' }], page: 0, size: 25, totalItems: 1, totalPages: 1 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(els['core-prev'].disabled, true);
  assert.strictEqual(els['core-next'].disabled, true);

  els['core-prev'].dispatch('click');
  els['core-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(calls.length, 1);
}

async function testStatesRendering() {
  const emptyEls = createHarness(async () => ({
    ok: true,
    status: 200,
    async json() {
      return { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    }
  }));

  await wait(10);
  assert.strictEqual(emptyEls['core-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() { return {}; }
  }));

  await wait(10);
  assert.strictEqual(errorEls['core-error'].classList.contains('d-none'), false);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequestsAndResetPage();
  await testPrevNextBoundariesEnforced();
  await testStatesRendering();
  console.log('rankings-core.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
