const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'indicator-citations-dashboard.js');
const scriptContent = fs.readFileSync(scriptPath, 'utf8');

function createClassList(initial = []) {
  const set = new Set(initial);
  return {
    add(v) { set.add(v); },
    remove(v) { set.delete(v); },
    contains(v) { return set.has(v); },
    toggle(v, force) {
      if (force === undefined) {
        if (set.has(v)) set.delete(v); else set.add(v);
      } else if (force) {
        set.add(v);
      } else {
        set.delete(v);
      }
    }
  };
}

function createElement(tagName, id = null, options = {}) {
  const listeners = {};
  const el = {
    tagName,
    id,
    dataset: options.dataset || {},
    value: options.value || '',
    textContent: options.textContent || '',
    children: [],
    parentNode: null,
    nextElementSibling: null,
    classList: createClassList(options.classes || []),
    attributes: {},
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      syncSiblings(this);
      return child;
    },
    addEventListener(event, handler) {
      listeners[event] = listeners[event] || [];
      listeners[event].push(handler);
    },
    dispatch(eventName, target = this) {
      const handlers = listeners[eventName] || [];
      handlers.forEach((h) => h({ target, preventDefault() {} }));
    },
    setAttribute(name, value) {
      this.attributes[name] = value;
    },
    querySelector(selector) {
      if (selector === 'td') {
        return this.children.find((c) => c.tagName === 'td') || null;
      }
      if (selector === '.js-expand') {
        return findDesc(this, (x) => x.classList && x.classList.contains('js-expand'));
      }
      if (selector === 'button') {
        return findDesc(this, (x) => x.tagName === 'button');
      }
      return null;
    },
    closest(selector) {
      if (selector === '.js-expand' && this.classList.contains('js-expand')) {
        return this;
      }
      return null;
    }
  };
  return el;
}

function syncSiblings(parent) {
  parent.children.forEach((child, idx) => {
    child.nextElementSibling = parent.children[idx + 1] || null;
  });
}

function findDesc(root, predicate) {
  for (const child of root.children || []) {
    if (predicate(child)) return child;
    const nested = findDesc(child, predicate);
    if (nested) return nested;
  }
  return null;
}

function makeRow(key, title, type, score, count, quarter, search) {
  const tr = createElement('tr', null, { classes: ['js-citation-main'], dataset: {
    key,
    search,
    type,
    quarter,
    authorScore: String(score),
    citationCount: String(count)
  }});
  tr.appendChild(createElement('td', null, { textContent: title }));
  const btnCell = createElement('td');
  const btn = createElement('button', null, { classes: ['js-expand'], dataset: { key } });
  btn.textContent = 'Details';
  btnCell.appendChild(btn);
  tr.appendChild(btnCell);

  const detail = createElement('tr', null, { classes: ['js-citation-detail', 'd-none'], dataset: { key } });
  return { tr, detail };
}

function createHarness() {
  const elements = {};
  function reg(el) { if (el.id) elements[el.id] = el; return el; }

  const root = reg(createElement('div', 'citations-dashboard-v2', { dataset: { quarterLabels: 'Q1|Q2', quarterValues: '2|1' } }));
  const tbody = reg(createElement('tbody', 'citations-main-body'));

  const r1 = makeRow('1', 'Alpha', 'Article', 3.2, 4, 'Q1', 'alpha article venuea');
  const r2 = makeRow('2', 'Beta', 'Review', 1.5, 1, 'Q2', 'beta review venueb');
  tbody.appendChild(r1.tr);
  tbody.appendChild(r1.detail);
  tbody.appendChild(r2.tr);
  tbody.appendChild(r2.detail);

  reg(createElement('input', 'citations-search'));
  reg(createElement('input', 'citations-min-score', { value: '0' }));
  reg(createElement('select', 'citations-quarter', { value: '' }));
  reg(createElement('select', 'citations-type', { value: '' }));
  reg(createElement('select', 'citations-sort', { value: 'score_desc' }));
  reg(createElement('button', 'citations-clear'));
  reg(createElement('div', 'citations-empty-state', { classes: ['d-none'] }));
  reg(createElement('span', 'citations-count'));
  reg(createElement('canvas', 'citations-quarter-chart'));

  const document = {
    readyState: 'complete',
    getElementById(id) { return elements[id] || null; },
    createElement(tag) { return createElement(tag); },
    addEventListener() {}
  };

  const context = {
    document,
    window: {
      Chart: function Chart() {}
    },
    Chart: function Chart() {},
    console,
    setTimeout,
    clearTimeout
  };

  vm.createContext(context);
  vm.runInContext(scriptContent, context);

  return { elements, tbody, rows: [r1, r2] };
}

(function run() {
  const h = createHarness();

  assert.strictEqual(h.elements['citations-count'].textContent, '2 / 2 publications');

  h.elements['citations-search'].value = 'beta';
  h.elements['citations-search'].dispatch('input');
  assert.strictEqual(h.rows[0].tr.classList.contains('d-none'), true);
  assert.strictEqual(h.rows[1].tr.classList.contains('d-none'), false);

  h.elements['citations-search'].value = '';
  h.elements['citations-min-score'].value = '2.0';
  h.elements['citations-min-score'].dispatch('input');
  assert.strictEqual(h.rows[0].tr.classList.contains('d-none'), false);
  assert.strictEqual(h.rows[1].tr.classList.contains('d-none'), true);

  const expandBtn = h.rows[0].tr.querySelector('.js-expand');
  h.tbody.dispatch('click', expandBtn);
  assert.strictEqual(h.rows[0].detail.classList.contains('d-none'), false);

  h.elements['citations-clear'].dispatch('click');
  assert.strictEqual(h.elements['citations-min-score'].value, '0');
  assert.strictEqual(h.elements['citations-count'].textContent, '2 / 2 publications');

  console.log('indicator-citations-dashboard.js behavior tests passed.');
})();
