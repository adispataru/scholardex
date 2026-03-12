const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'admin-scholardex-forums.js');
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

function createHarness(fetchImpl, search = '') {
  const elements = {
    'admin-forums-search': createElement('admin-forums-search'),
    'admin-forums-sort': createElement('admin-forums-sort', { value: 'publicationName' }),
    'admin-forums-direction': createElement('admin-forums-direction', { value: 'asc' }),
    'admin-forums-wos': createElement('admin-forums-wos', { value: 'all' }),
    'admin-forums-size': createElement('admin-forums-size', { value: '25' }),
    'admin-forums-loading': createElement('admin-forums-loading'),
    'admin-forums-error': createElement('admin-forums-error', { classes: ['d-none'] }),
    'admin-forums-empty': createElement('admin-forums-empty', { classes: ['d-none'] }),
    'admin-forums-table-body': createElement('admin-forums-table-body'),
    'admin-forums-page-info': createElement('admin-forums-page-info'),
    'admin-forums-total-info': createElement('admin-forums-total-info'),
    'admin-forums-prev': createElement('admin-forums-prev'),
    'admin-forums-next': createElement('admin-forums-next')
  };

  const context = {
    window: { location: { search } },
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
    q: query.get('q'),
    wos: query.get('wos')
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
    sort: 'publicationName',
    direction: 'asc',
    q: null,
    wos: 'all'
  });
}

async function testLegacyPresetAndControlChangesTriggerRequests() {
  const calls = [];
  const queue = [
    { items: [{ id: 'a', publicationName: 'A', issn: '1', eIssn: '2', aggregationType: 'Journal', wosStatus: 'indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'b', publicationName: 'B', issn: '3', eIssn: '4', aggregationType: 'Book', wosStatus: 'not_applicable' }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'c', publicationName: 'C', issn: '5', eIssn: '6', aggregationType: 'Conference', wosStatus: 'not_indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'd', publicationName: 'D', issn: '7', eIssn: '8', aggregationType: 'Series', wosStatus: 'indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
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
  }, '?wos=indexed');

  await wait(10);
  assert.strictEqual(parseQuery(calls[0]).wos, 'indexed');
  assert.strictEqual(els['admin-forums-wos'].value, 'indexed');
  assert.ok(els['admin-forums-table-body'].innerHTML.includes('/admin/scholardex/forums/edit/a'));

  els['admin-forums-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['admin-forums-search'].value = 'ieee';
  els['admin-forums-search'].dispatch('input');
  await wait(350);
  assert.strictEqual(parseQuery(calls[2]).q, 'ieee');

  els['admin-forums-wos'].value = 'not_indexed';
  els['admin-forums-wos'].dispatch('change');
  await wait(10);
  assert.strictEqual(parseQuery(calls[3]).page, '0');
  assert.strictEqual(parseQuery(calls[3]).wos, 'not_indexed');
}

async function testPrevNextBoundariesEnforced() {
  const calls = [];
  const els = createHarness(async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      async json() {
        return { items: [{ id: 'only', publicationName: 'Only', issn: '', eIssn: '', aggregationType: '', wosStatus: 'not_applicable' }], page: 0, size: 25, totalItems: 1, totalPages: 1 };
      }
    };
  });

  await wait(10);
  assert.strictEqual(els['admin-forums-prev'].disabled, true);
  assert.strictEqual(els['admin-forums-next'].disabled, true);

  els['admin-forums-prev'].dispatch('click');
  els['admin-forums-next'].dispatch('click');
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
  assert.strictEqual(emptyEls['admin-forums-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() {
      return {};
    }
  }));
  await wait(10);
  assert.strictEqual(errorEls['admin-forums-error'].classList.contains('d-none'), false);
  assert.ok(errorEls['admin-forums-error'].textContent.length > 0);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testLegacyPresetAndControlChangesTriggerRequests();
  await testPrevNextBoundariesEnforced();
  await testEmptyAndErrorStateRendering();
  console.log('admin-scholardex-forums.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
