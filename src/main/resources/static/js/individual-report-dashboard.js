(function () {
  'use strict';

  // ── Utilities ─────────────────────────────────────────────────────────────

  function toNumber(value) {
    var parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function formatScore(value) {
    return toNumber(value).toFixed(2);
  }

  // ── Threshold icon rail ───────────────────────────────────────────────────

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
      node.classList.remove('is-passed', 'is-failed', 'is-selected-pass', 'is-selected-fail');
      node.classList.add(score >= thresholdValue ? 'is-passed' : 'is-failed');
    });

    button.classList.add(score >= selectedThreshold ? 'is-selected-pass' : 'is-selected-fail');
  }

  function findInitialButton(row, researcherPosition) {
    var buttons = Array.prototype.slice.call(row.querySelectorAll('.threshold-icon-btn'));
    if (buttons.length === 0) return null;
    if (researcherPosition) {
      var matching = buttons.find(function (btn) {
        return btn.getAttribute('data-position') === researcherPosition;
      });
      if (matching) return matching;
    }
    return buttons[0];
  }

  function initThresholdRows(root, researcherPosition) {
    root.querySelectorAll('.criterion-score-row').forEach(function (row) {
      var initial = findInitialButton(row, researcherPosition);
      if (!initial) return;
      applySelection(row, initial);
      row.querySelectorAll('.threshold-icon-btn').forEach(function (button) {
        button.addEventListener('click', function () {
          applySelection(row, button);
        });
      });
    });
  }

  // ── Criterion accordion ───────────────────────────────────────────────────
  // Default: one criterion expanded at a time.

  function openCriterion(root, toggle, target) {
    // Close all others first
    root.querySelectorAll('.indicator-toggle[aria-expanded="true"]').forEach(function (t) {
      if (t !== toggle) {
        var otherId = t.getAttribute('data-target');
        var other = otherId ? root.querySelector('#' + otherId) : null;
        t.setAttribute('aria-expanded', 'false');
        t.classList.add('collapsed');
        if (other) {
          other.classList.add('is-collapsed');
          other.hidden = true;
        }
      }
    });

    toggle.setAttribute('aria-expanded', 'true');
    toggle.classList.remove('collapsed');
    target.classList.remove('is-collapsed');
    target.hidden = false;

    // Update hash to criterion wrapper
    var wrapper = toggle.closest('.criterion-wrapper');
    if (wrapper && wrapper.id) {
      history.replaceState(null, '', '#' + wrapper.id);
    }
  }

  function closeCriterion(toggle, target) {
    toggle.setAttribute('aria-expanded', 'false');
    toggle.classList.add('collapsed');
    target.classList.add('is-collapsed');
    target.hidden = true;
    history.replaceState(null, '', window.location.pathname + window.location.search);
  }

  function initCriterionToggles(root) {
    root.querySelectorAll('.indicator-toggle').forEach(function (toggle) {
      var targetId = toggle.getAttribute('data-target');
      if (!targetId) return;
      var target = root.querySelector('#' + targetId);
      if (!target) return;

      toggle.addEventListener('click', function () {
        var expanded = toggle.getAttribute('aria-expanded') === 'true';
        if (expanded) {
          closeCriterion(toggle, target);
        } else {
          openCriterion(root, toggle, target);
        }
      });
    });
  }

  // ── Indicator inline detail ───────────────────────────────────────────────

  function renderDetailTable(items, outputMode) {
    if (!items || items.length === 0) {
      return '<p class="app-report-card__meta mt-2">No scored items found.</p>';
    }
    var isActivities = outputMode === 'activities';

    var html = '<div class="app-table-scroll"><table class="table table-sm table-bordered app-table mb-0">';
    html += '<thead><tr>';
    if (isActivities) {
      html += '<th scope="col">Name</th>' +
              '<th scope="col">Type</th>' +
              '<th scope="col">Year</th>' +
              '<th scope="col">Source</th>' +
              '<th scope="col">Author Score</th>';
    } else {
      html += '<th scope="col">Title</th>' +
              '<th scope="col">Type</th>' +
              '<th scope="col">Year</th>' +
              '<th scope="col">Quarter</th>' +
              '<th scope="col">Rank</th>' +
              '<th scope="col">Forum Score</th>' +
              '<th scope="col">Source</th>' +
              '<th scope="col">Author Score</th>';
    }
    html += '</tr></thead><tbody>';

    items.forEach(function (item) {
      html += '<tr>';
      if (isActivities) {
        html += '<td>' + esc(item.key) + '</td>';
        html += '<td>' + esc(item.type) + '</td>';
        html += '<td>' + (item.year || '—') + '</td>';
        html += '<td>' + esc(item.scoringSource || '—') + '</td>';
      } else {
        html += '<td>' + esc(item.key) + '</td>';
        html += '<td>' + esc(item.type) + '</td>';
        html += '<td>' + (item.year || '—') + '</td>';
        html += '<td>' + esc(item.quarter || '—') + '</td>';
        html += '<td>' + esc(item.coreRankingEquivalent || '—') + '</td>';
        html += '<td>' + (item.forumScore != null ? toNumber(item.forumScore).toFixed(4) : '—') + '</td>';
        html += '<td>' + esc(item.scoringSource || '—') + '</td>';
      }
      html += '<td>' + toNumber(item.authorScore).toFixed(2) + '</td>';
      html += '</tr>';
    });

    html += '</tbody></table></div>';
    return html;
  }

  function esc(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function loadIndicatorDetail(panel, indicatorId) {
    var skeleton = panel.querySelector('.indicator-detail-skeleton');
    var content = panel.querySelector('.indicator-detail-content');

    // Show skeleton
    if (skeleton) skeleton.hidden = false;
    if (content) content.innerHTML = '';

    fetch('/user/evaluation/indicator/' + encodeURIComponent(indicatorId) + '/detail', {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (data) {
        if (skeleton) skeleton.hidden = true;
        if (content) {
          var headerHtml = '<div class="indicator-detail-header">' +
            '<span class="indicator-detail-total">Total score: <strong>' + toNumber(data.totalScore).toFixed(2) + '</strong></span>' +
            '<span class="indicator-detail-updated">Updated: ' + esc(data.updatedAt ? data.updatedAt.substring(0, 10) : '—') + '</span>' +
            '</div>';
          content.innerHTML = headerHtml + renderDetailTable(data.items, data.outputMode);
        }
        // Update hash to indicator
        history.replaceState(null, '', '#indicator-' + indicatorId);
      })
      .catch(function (err) {
        if (skeleton) skeleton.hidden = true;
        if (content) {
          content.innerHTML = '<p class="app-report-card__meta text-danger mt-2">Failed to load detail. ' + esc(err.message) + '</p>';
        }
      });
  }

  function openIndicatorPanel(panel) {
    panel.hidden = false;
  }

  function closeIndicatorPanel(panel) {
    panel.hidden = true;
    var content = panel.querySelector('.indicator-detail-content');
    if (content) content.innerHTML = '';
    var skeleton = panel.querySelector('.indicator-detail-skeleton');
    if (skeleton) skeleton.hidden = true;
  }

  function initIndicatorExpand(root) {
    root.querySelectorAll('.indicator-expand-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var indicatorId = btn.getAttribute('data-indicator-id');
        var panelId = btn.getAttribute('aria-controls');
        var panel = panelId ? root.querySelector('#' + panelId) : null;
        if (!panel) return;

        var isExpanded = btn.getAttribute('aria-expanded') === 'true';
        if (isExpanded) {
          btn.setAttribute('aria-expanded', 'false');
          closeIndicatorPanel(panel);
          // Revert hash to criterion level
          var wrapper = btn.closest('.criterion-wrapper');
          if (wrapper && wrapper.id) {
            history.replaceState(null, '', '#' + wrapper.id);
          }
        } else {
          // Close other indicators in same criterion first
          var criterion = btn.closest('.indicator-list');
          if (criterion) {
            criterion.querySelectorAll('.indicator-expand-btn[aria-expanded="true"]').forEach(function (other) {
              if (other !== btn) {
                other.setAttribute('aria-expanded', 'false');
                var otherId = other.getAttribute('aria-controls');
                var otherPanel = otherId ? root.querySelector('#' + otherId) : null;
                if (otherPanel) closeIndicatorPanel(otherPanel);
              }
            });
          }
          btn.setAttribute('aria-expanded', 'true');
          openIndicatorPanel(panel);
          loadIndicatorDetail(panel, indicatorId);
        }
      });
    });
  }

  // ── Position selector ────────────────────────────────────────────────────

  var POSITION_LABELS = {
    'ASIST_UNIV': 'Asist. Univ.',
    'LECT_UNIV':  'Lect. Univ.',
    'CONF_UNIV':  'Conf. Univ.',
    'PROF_UNIV':  'Prof. Univ.',
    'CS_I':       'CS I',
    'CS_II':      'CS II',
    'CS_III':     'CS III',
    'ASIST_C':    'Asist. Cercetare',
    'OTHER':      'Other'
  };

  /** Returns {met, total} for criteria applicable to the given position. */
  function computeCriteriaMet(root, position) {
    var met = 0, total = 0;
    root.querySelectorAll('.criterion-score-row').forEach(function (row) {
      var btn = Array.prototype.slice.call(row.querySelectorAll('.threshold-icon-btn'))
        .find(function (b) { return b.getAttribute('data-position') === position; });
      if (!btn) return; // no threshold for this position — criterion not applicable
      total++;
      var score     = toNumber(row.getAttribute('data-criterion-score'));
      var threshold = toNumber(btn.getAttribute('data-threshold-value'));
      if (score >= threshold) met++;
    });
    return { met: met, total: total };
  }

  function updateCriteriaMet(root, position) {
    var valueEl = document.getElementById('eval-criteria-met-value');
    var barEl   = document.getElementById('eval-criteria-met-bar');
    if (!valueEl) return;

    var result = computeCriteriaMet(root, position);
    if (result.total === 0) {
      valueEl.textContent = '—';
      if (barEl) barEl.style.width = '0%';
    } else {
      valueEl.textContent = result.met + ' / ' + result.total;
      if (barEl) barEl.style.width = Math.round(result.met * 100 / result.total) + '%';
    }
  }

  function initPositionSelector(root, researcherPosition) {
    var select = document.querySelector('[data-eval-position-selector]');
    if (!select) return;

    // Collect unique positions that actually appear in the report's thresholds
    var seen = {};
    var orderedPositions = [];
    root.querySelectorAll('.threshold-icon-btn[data-position]').forEach(function (btn) {
      var pos = btn.getAttribute('data-position');
      if (pos && !seen[pos]) {
        seen[pos] = true;
        orderedPositions.push(pos);
      }
    });

    // Sort by the canonical enum order
    var ORDER = ['ASIST_UNIV','LECT_UNIV','CONF_UNIV','PROF_UNIV','CS_I','CS_II','CS_III','ASIST_C','OTHER'];
    orderedPositions.sort(function (a, b) {
      return ORDER.indexOf(a) - ORDER.indexOf(b);
    });

    if (orderedPositions.length === 0) {
      // No thresholds at all — hide the selector cell
      var cell = select.closest('.app-eval-aggregate__cell');
      if (cell) cell.hidden = true;
      return;
    }

    // Populate options
    orderedPositions.forEach(function (pos) {
      var opt = document.createElement('option');
      opt.value = pos;
      opt.textContent = POSITION_LABELS[pos] || pos;
      if (pos === researcherPosition) opt.selected = true;
      select.appendChild(opt);
    });

    // If researcher's position isn't in the list, pick the first available
    if (!seen[researcherPosition] && orderedPositions.length > 0) {
      select.value = orderedPositions[0];
    }

    // Initial criteria-met update (may differ from server-rendered value if
    // the researcher's actual position has no thresholds in this report)
    updateCriteriaMet(root, select.value);

    // Re-apply thresholds and recount on change
    select.addEventListener('change', function () {
      var pos = select.value;
      root.setAttribute('data-researcher-position', pos);
      initThresholdRows(root, pos);
      updateCriteriaMet(root, pos);
    });
  }

  // ── Report switcher ───────────────────────────────────────────────────────

  function initReportSwitcher() {
    var select = document.querySelector('[data-eval-report-switcher]');
    if (!select) return;
    select.addEventListener('change', function () {
      window.location.href = window.location.pathname + '?report=' + encodeURIComponent(select.value);
    });
  }

  // ── Hash restoration ──────────────────────────────────────────────────────

  function applyHashState(root) {
    var hash = window.location.hash;
    if (!hash) return;

    // #indicator-{id} → expand the criterion that contains it, then expand the indicator
    var indMatch = hash.match(/^#indicator-(.+)$/);
    if (indMatch) {
      var indicatorId = indMatch[1];
      var btn = root.querySelector('.indicator-expand-btn[data-indicator-id="' + indicatorId + '"]');
      if (btn) {
        // First open the criterion
        var list = btn.closest('.indicator-list');
        var wrapper = btn.closest('.criterion-wrapper');
        if (list && wrapper) {
          var toggle = wrapper.querySelector('.indicator-toggle');
          if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
            openCriterion(root, toggle, list);
          }
        }
        // Then trigger indicator expand
        btn.click();
        btn.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        return;
      }
    }

    // #criterion-{index} → expand that criterion
    var critMatch = hash.match(/^#criterion-(\d+)$/);
    if (critMatch) {
      var wrapper2 = root.querySelector('#criterion-' + critMatch[1]);
      if (wrapper2) {
        var toggle2 = wrapper2.querySelector('.indicator-toggle');
        var list2 = toggle2 ? root.querySelector('#' + toggle2.getAttribute('data-target')) : null;
        if (toggle2 && list2 && toggle2.getAttribute('aria-expanded') !== 'true') {
          openCriterion(root, toggle2, list2);
          wrapper2.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
      }
    }
  }

  // ── Boot ──────────────────────────────────────────────────────────────────

  function boot() {
    var root = document.querySelector('.individual-report-dashboard');
    if (!root) return;

    var researcherPosition = root.getAttribute('data-researcher-position') || '';

    initPositionSelector(root, researcherPosition);
    initThresholdRows(root, researcherPosition);
    initCriterionToggles(root);
    initIndicatorExpand(root);
    initReportSwitcher();

    // Apply hash state after a tick so the DOM is stable
    setTimeout(function () { applyHashState(root); }, 0);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
