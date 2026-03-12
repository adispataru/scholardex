const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'scholardex-forums.js');
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
    },
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
    'scholardex-forums-search': createElement('scholardex-forums-search'),
    'scholardex-forums-sort': createElement('scholardex-forums-sort', { value: 'publicationName' }),
    'scholardex-forums-direction': createElement('scholardex-forums-direction', { value: 'asc' }),
    'scholardex-forums-wos': createElement('scholardex-forums-wos', { value: 'all' }),
    'scholardex-forums-size': createElement('scholardex-forums-size', { value: '25' }),
    'scholardex-forums-loading': createElement('scholardex-forums-loading'),
    'scholardex-forums-error': createElement('scholardex-forums-error', { classes: ['d-none'] }),
    'scholardex-forums-empty': createElement('scholardex-forums-empty', { classes: ['d-none'] }),
    'scholardex-forums-table-body': createElement('scholardex-forums-table-body'),
    'scholardex-forums-page-info': createElement('scholardex-forums-page-info'),
    'scholardex-forums-total-info': createElement('scholardex-forums-total-info'),
    'scholardex-forums-prev': createElement('scholardex-forums-prev'),
    'scholardex-forums-next': createElement('scholardex-forums-next')
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
  assert.strictEqual(calls.length, 1, 'expected one fetch on initialization');
  assert.deepStrictEqual(parseQuery(calls[0]), {
    page: '0',
    size: '25',
    sort: 'publicationName',
    direction: 'asc',
    q: null,
    wos: 'all'
  });
}

async function testLegacyWosPresetAndControlChangesTriggerRequests() {
  const calls = [];
  const queue = [
    { items: [{ id: 'a', publicationName: 'A', issn: '1', eIssn: '2', aggregationType: 'Journal', wosStatus: 'indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'b', publicationName: 'B', issn: '3', eIssn: '4', aggregationType: 'Book', wosStatus: 'not_applicable' }], page: 1, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'c', publicationName: 'C', issn: '5', eIssn: '6', aggregationType: 'Journal', wosStatus: 'not_indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 },
    { items: [{ id: 'd', publicationName: 'D', issn: '7', eIssn: '8', aggregationType: 'Journal', wosStatus: 'indexed' }], page: 0, size: 25, totalItems: 50, totalPages: 2 }
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
  assert.strictEqual(els['scholardex-forums-wos'].value, 'indexed');
  assert.ok(els['scholardex-forums-table-body'].innerHTML.includes('/scholardex/forums/a'));
  assert.ok(els['scholardex-forums-table-body'].innerHTML.includes('WoS indexed'));

  els['scholardex-forums-next'].dispatch('click');
  await wait(10);
  assert.strictEqual(parseQuery(calls[1]).page, '1');

  els['scholardex-forums-wos'].value = 'not_indexed';
  els['scholardex-forums-wos'].dispatch('change');
  await wait(10);
  const wosQuery = parseQuery(calls[2]);
  assert.strictEqual(wosQuery.page, '0');
  assert.strictEqual(wosQuery.wos, 'not_indexed');

  els['scholardex-forums-search'].value = 'ieee';
  els['scholardex-forums-search'].dispatch('input');
  await wait(350);
  assert.strictEqual(parseQuery(calls[3]).q, 'ieee');
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
  assert.strictEqual(els['scholardex-forums-prev'].disabled, true);
  assert.strictEqual(els['scholardex-forums-next'].disabled, true);

  els['scholardex-forums-prev'].dispatch('click');
  els['scholardex-forums-next'].dispatch('click');
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
  assert.strictEqual(emptyEls['scholardex-forums-empty'].classList.contains('d-none'), false);

  const errorEls = createHarness(async () => ({
    ok: false,
    status: 500,
    async json() {
      return {};
    }
  }));
  await wait(10);
  assert.strictEqual(errorEls['scholardex-forums-error'].classList.contains('d-none'), false);
  assert.ok(errorEls['scholardex-forums-error'].textContent.length > 0);
}

async function run() {
  await testDefaultLoadRequestsExpectedParams();
  await testLegacyWosPresetAndControlChangesTriggerRequests();
  await testPrevNextBoundariesEnforced();
  await testEmptyAndErrorStateRendering();
  console.log('scholardex-forums.js behavior tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
