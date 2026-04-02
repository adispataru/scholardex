import $ from 'jquery';

const DEFAULT_MAX_CLIENT_ROWS = 2000;
const instances = new Map();

function parsePositiveInt(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function shouldSkipClientEnhancement(table) {
  if (table.dataset.datatableServer === 'true') {
    return false;
  }

  const maxRows = parsePositiveInt(table.dataset.datatableMaxRows, DEFAULT_MAX_CLIENT_ROWS);
  const body = table.tBodies && table.tBodies.length > 0 ? table.tBodies[0] : null;
  const rowCount = body ? body.rows.length : 0;
  return rowCount > maxRows;
}

function resolveTable(target) {
  if (!target) {
    return null;
  }

  if (target instanceof HTMLElement && target.tagName === 'TABLE') {
    return target;
  }

  if (typeof target === 'string') {
    return document.querySelector(target);
  }

  return null;
}

function readOptions(table) {
  const options = {};

  if (table.dataset.datatablePaging === 'false') {
    options.paging = false;
  }
  if (table.dataset.datatableSearching === 'false') {
    options.searching = false;
  }
  if (table.dataset.datatableOrdering === 'false') {
    options.ordering = false;
  }
  if (table.dataset.datatableLengthChange === 'false') {
    options.lengthChange = false;
  }
  if (table.dataset.datatableInfo === 'false') {
    options.info = false;
  }

  const pageLength = parsePositiveInt(table.dataset.datatablePageLength, null);
  if (pageLength) {
    options.pageLength = pageLength;
  }

  return options;
}

function getInstance(target) {
  const table = resolveTable(target);
  if (!table || !$.fn.dataTable) {
    return null;
  }

  if ($.fn.dataTable.isDataTable(table)) {
    return $(table).DataTable();
  }

  return instances.get(table) || null;
}

function initTable(table) {
  if (!table || !$.fn.dataTable) {
    return null;
  }

  if ($.fn.dataTable.isDataTable(table)) {
    const instance = $(table).DataTable();
    instances.set(table, instance);
    return instance;
  }

  if (shouldSkipClientEnhancement(table)) {
    return null;
  }

  const instance = $(table).DataTable(readOptions(table));
  instances.set(table, instance);
  return instance;
}

function scheduleInit(table) {
  if (window.requestIdleCallback) {
    window.requestIdleCallback(() => {
      initTable(table);
    }, { timeout: 300 });
    return;
  }

  window.setTimeout(() => {
    initTable(table);
  }, 0);
}

export function initSharedDataTables() {
  window.appDataTables = {
    getInstance
  };

  const tables = document.querySelectorAll('table.js-datatable');
  tables.forEach(scheduleInit);
}
