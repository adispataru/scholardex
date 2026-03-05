// Initialize DataTables only on explicitly marked tables.
(function () {
  var DEFAULT_MAX_CLIENT_ROWS = 2000;

  function parsePositiveInt(value, fallback) {
    var parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
  }

  function shouldSkipClientEnhancement(table) {
    var serverSideEnabled = table.dataset.datatableServer === 'true';
    if (serverSideEnabled) {
      return false;
    }

    var maxRows = parsePositiveInt(table.dataset.datatableMaxRows, DEFAULT_MAX_CLIENT_ROWS);
    var body = table.tBodies && table.tBodies.length > 0 ? table.tBodies[0] : null;
    var rowCount = body ? body.rows.length : 0;
    return rowCount > maxRows;
  }

  function initTable(table) {
    var $table = window.jQuery(table);
    if ($table.hasClass('dataTable') || window.jQuery.fn.dataTable.isDataTable(table)) {
      return;
    }

    if (shouldSkipClientEnhancement(table)) {
      return;
    }

    var options = {};
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

    $table.DataTable(options);
  }

  function scheduleInit(table) {
    if (window.requestIdleCallback) {
      window.requestIdleCallback(function () {
        initTable(table);
      }, { timeout: 300 });
      return;
    }
    window.setTimeout(function () {
      initTable(table);
    }, 0);
  }

  window.jQuery(function () {
    var tables = document.querySelectorAll('table.js-datatable');
    tables.forEach(scheduleInit);
  });
})();
