const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const scriptPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'indicator-publications-dashboard.js');
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
  return {
    tagName,
    id,
    dataset: options.dataset || {},
    value: options.value || '',
    textContent: options.textContent || '',
    children: [],
    classList: createClassList(options.classes || []),
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    addEventListener(event, handler) {
      listeners[event] = listeners[event] || [];
      listeners[event].push(handler);
    },
    dispatch(eventName) {
      const handlers = listeners[eventName] || [];
      handlers.forEach((h) => h({ target: this, preventDefault() {} }));
    },
    querySelector(selector) {
      if (selector === 'td') {
        return this.children.find((c) => c.tagName === 'td') || null;
      }
      return null;
    }
  };
}

function makeRow(title, type, score, year, quarter, search) {
  const tr = createElement('tr', null, {
    classes: ['js-publication-row'],
    dataset: {
      search,
      type,
      quarter,
      authorScore: String(score),
      year: String(year)
    }
  });
  tr.appendChild(createElement('td', null, { textContent: title }));
  return tr;
}

function createHarness() {
  const elements = {};
  function reg(el) { if (el.id) elements[el.id] = el; return el; }

  const root = reg(createElement('div', 'publications-dashboard-v2', { dataset: { quarterLabels: 'Q1|Q2', quarterValues: '3|1' } }));
  const tbody = reg(createElement('tbody', 'publications-main-body'));
  const r1 = makeRow('Alpha', 'Article', 3.2, 2024, 'Q1', 'alpha article venuea');
  const r2 = makeRow('Beta', 'Review', 1.1, 2022, 'Q2', 'beta review venueb');
  tbody.appendChild(r1);
  tbody.appendChild(r2);

  reg(createElement('input', 'publications-search'));
  reg(createElement('input', 'publications-min-score', { value: '0' }));
  reg(createElement('select', 'publications-quarter', { value: '' }));
  reg(createElement('select', 'publications-type', { value: '' }));
  reg(createElement('select', 'publications-sort', { value: 'score_desc' }));
  reg(createElement('button', 'publications-clear'));
  reg(createElement('div', 'publications-empty-state', { classes: ['d-none'] }));
  reg(createElement('span', 'publications-count'));
  reg(createElement('canvas', 'publications-quarter-chart'));

  const document = {
    readyState: 'complete',
    getElementById(id) { return elements[id] || null; },
    createElement(tag) { return createElement(tag); },
    addEventListener() {}
  };

  const context = {
    document,
    window: { Chart: function Chart() {} },
    Chart: function Chart() {},
    console,
    setTimeout,
    clearTimeout
  };

  vm.createContext(context);
  vm.runInContext(scriptContent, context);

  return { elements, rows: [r1, r2] };
}

(function run() {
  const h = createHarness();

  assert.strictEqual(h.elements['publications-count'].textContent, '2 / 2 publications');

  h.elements['publications-search'].value = 'beta';
  h.elements['publications-search'].dispatch('input');
  assert.strictEqual(h.rows[0].classList.contains('d-none'), true);
  assert.strictEqual(h.rows[1].classList.contains('d-none'), false);

  h.elements['publications-search'].value = '';
  h.elements['publications-min-score'].value = '2.0';
  h.elements['publications-min-score'].dispatch('input');
  assert.strictEqual(h.rows[0].classList.contains('d-none'), false);
  assert.strictEqual(h.rows[1].classList.contains('d-none'), true);

  h.elements['publications-clear'].dispatch('click');
  assert.strictEqual(h.elements['publications-min-score'].value, '0');
  assert.strictEqual(h.elements['publications-count'].textContent, '2 / 2 publications');

  console.log('indicator-publications-dashboard.js behavior tests passed.');
})();
