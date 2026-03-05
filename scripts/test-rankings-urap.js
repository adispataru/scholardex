const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'rankings-urap.js');
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
    'urap-search': createElement('urap-search'),
    'urap-sort': createElement('urap-sort', { value: 'name' }),
    'urap-direction': createElement('urap-direction', { value: 'asc' }),
    'urap-size': createElement('urap-size', { value: '25' }),
    'urap-loading': createElement('urap-loading'),
    'urap-error': createElement('urap-error', { classes: ['d-none'] }),
    'urap-empty': createElement('urap-empty', { classes: ['d-none'] }),
    'urap-table': createElement('urap-table', { dataset: { detailBase: '/rankings/urap' } }),
    'urap-table-body': createElement('urap-table-body'),
    'urap-page-info': createElement('urap-page-info'),
    'urap-total-info': createElement('urap-total-info'),
    'urap-prev': createElement('urap-prev'),
    'urap-next': createElement('urap-next')
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
    { items: [{ id: 'u1', name: 'Uni1', country: 'RO', year: 2025, rank: 10 }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'u2', name: 'Uni2', country: 'US', year: 2025, rank: 11 }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'u3', name: 'Uni3', country: 'DE', year: 2025, rank: 12 }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'u4', name: 'Uni4', country: 'UK', year: 2025, rank: 13 }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
  ];

  const els = createHarness(async (url) => {
    calls.push(url);
    const payload = queue.shift() || { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    return { ok: true, status: 200, async json() { return payload; } };
  });

  await wait(10);

  els['urap-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['urap-sort'].value = 'country';
  els['urap-sort'].dispatch('change');
  await wait(10);
  const sortQuery = parseQuery(calls[2]);
  assert.strictEqual(sortQuery.page, '0');
  assert.strictEqual(sortQuery.sort, 'country');

  els['urap-search'].value = 'romania';
  els['urap-search'].dispatch('input');
  await wait(350);
  const searchQuery = parseQuery(calls[3]);
  assert.strictEqual(searchQuery.page, '0');
  assert.strictEqual(searchQuery.q, 'romania');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [{ id: 'only', name: 'Only', country: 'RO', year: 2025, rank: 1 }], page: 0, size: 25, totalItems: 1, totalPages: 1 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(els['urap-prev'].disabled, true);
  assert.strictEqual(els['urap-next'].disabled, true);

  els['urap-prev'].dispatch('click');
  els['urap-next'].dispatch('click');
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
  assert.strictEqual(emptyEls['urap-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() { return {}; }
  }));

  await wait(10);
  assert.strictEqual(errorEls['urap-error'].classList.contains('d-none'), false);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testControlChangesTriggerRequestsAndResetPage();
  await testPrevNextBoundariesEnforced();
  await testStatesRendering();
  console.log('rankings-urap.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
