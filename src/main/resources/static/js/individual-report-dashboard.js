(function () {
  function toNumber(value) {
    var parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function formatScore(value) {
    return toNumber(value).toFixed(2);
  }

  function applySelection(row, button) {
    var score = toNumber(row.getAttribute('data-criterion-score'));
    var selectedPosition = button.getAttribute('data-position') || 'Position';
    var selectedThreshold = toNumber(button.getAttribute('data-threshold-value'));

    var label = row.querySelector('.criterion-selected-position');
    var ratio = row.querySelector('.criterion-score-ratio');
    if (label) {
      label.textContent = selectedPosition;
    }
    if (ratio) {
      ratio.textContent = formatScore(score) + ' / ' + formatScore(selectedThreshold);
    }

    var buttons = row.querySelectorAll('.threshold-icon-btn');
    buttons.forEach(function (node) {
      var thresholdValue = toNumber(node.getAttribute('data-threshold-value'));
      node.classList.remove('is-passed');
      node.classList.remove('is-failed');
      node.classList.remove('is-selected-pass');
      node.classList.remove('is-selected-fail');
      node.classList.add(score >= thresholdValue ? 'is-passed' : 'is-failed');
    });

    button.classList.add(score >= selectedThreshold ? 'is-selected-pass' : 'is-selected-fail');
  }

  function findInitialButton(row, researcherPosition) {
    var buttons = Array.prototype.slice.call(row.querySelectorAll('.threshold-icon-btn'));
    if (buttons.length === 0) {
      return null;
    }
    if (researcherPosition) {
      var matching = buttons.find(function (button) {
        return button.getAttribute('data-position') === researcherPosition;
      });
      if (matching) {
        return matching;
      }
    }
    return buttons[0];
  }

  function boot() {
    var root = document.querySelector('.individual-report-dashboard');
    if (!root) {
      return;
    }

    var researcherPosition = root.getAttribute('data-researcher-position') || '';
    var rows = root.querySelectorAll('.criterion-score-row');
    rows.forEach(function (row) {
      var initial = findInitialButton(row, researcherPosition);
      if (!initial) {
        return;
      }
      applySelection(row, initial);

      row.querySelectorAll('.threshold-icon-btn').forEach(function (button) {
        button.addEventListener('click', function () {
          applySelection(row, button);
        });
      });
    });

    var toggles = root.querySelectorAll('.indicator-toggle');
    toggles.forEach(function (toggle) {
      var targetId = toggle.getAttribute('data-target');
      if (!targetId) {
        return;
      }
      var target = root.querySelector('#' + targetId);
      if (!target) {
        return;
      }
      toggle.addEventListener('click', function () {
        var expanded = toggle.getAttribute('aria-expanded') === 'true';
        toggle.setAttribute('aria-expanded', expanded ? 'false' : 'true');
        target.classList.toggle('is-collapsed', expanded);
        target.hidden = expanded;
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
