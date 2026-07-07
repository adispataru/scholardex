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

  function deltaSign(delta) {
    return delta > 0.005 ? '+' : '';
  }

  function deltaClass(delta) {
    if (delta > 0.005)  return 'eval-delta--positive';
    if (delta < -0.005) return 'eval-delta--negative';
    return 'eval-delta--neutral';
  }

  function deltaText(delta) {
    return deltaSign(delta) + toNumber(delta).toFixed(2);
  }

  function deltaAriaLabel(delta) {
    var abs = toNumber(Math.abs(delta)).toFixed(2);
    if (delta > 0.005)  return 'improved by ' + abs;
    if (delta < -0.005) return 'declined by '  + abs;
    return 'no change';
  }

  var _compareData  = null;
  var _modalTrigger = null; // element to restore focus to when citation modal closes

  // Workbench state — set once in boot(), read everywhere below.
  var _root = null;
  var _reportId = '';
  var _apiBase = '/user/evaluation';
  var _selfMode = true;      // false on the delegated read-only view
  var _position = '';        // currently selected evaluation position
  var _thresholds = [];      // parsed #eval-thresholds-data entries, indexed by criterion index

  // ── Criteria rail + evidence pane ─────────────────────────────────────────

  function readThresholds() {
    _thresholds = [];
    var el = document.getElementById('eval-thresholds-data');
    if (!el) return;
    try {
      var parsed = JSON.parse(el.textContent || '[]');
      (parsed || []).forEach(function (entry) {
        if (entry && typeof entry.index === 'number') _thresholds[entry.index] = entry;
      });
    } catch (e) { /* malformed JSON → rail renders scores without thresholds */ }
  }

  function thresholdByPosition(criterionIndex, position) {
    var entry = _thresholds[criterionIndex];
    if (!entry || !Array.isArray(entry.thresholds) || !position) return null;
    var match = entry.thresholds.find(function (t) { return t.position === position; });
    return match ? toNumber(match.value) : null;
  }

  function criterionScore(criterionIndex) {
    var entry = _thresholds[criterionIndex];
    return entry ? toNumber(entry.score) : 0;
  }

  /** met / near (within 20% below) / below; null when no threshold applies. */
  function chipFor(score, threshold) {
    if (threshold == null || threshold <= 0) return null;
    if (score >= threshold) return { label: 'met', cls: 'app-eval-chip--met' };
    if (score >= 0.8 * threshold) return { label: 'near', cls: 'app-eval-chip--near' };
    return { label: 'below', cls: 'app-eval-chip--below' };
  }

  function applyChip(el, score, threshold) {
    if (!el) return;
    var chip = chipFor(score, threshold);
    if (!chip) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    el.hidden = false;
    el.textContent = chip.label;
    el.className = el.className.split(' ')[0] + ' app-eval-chip ' + chip.cls;
  }

  function ratioText(score, threshold) {
    return threshold != null ? formatScore(score) + ' / ' + formatScore(threshold) : formatScore(score);
  }

  function renderRail(root, position) {
    root.querySelectorAll('.app-eval-rail__tile').forEach(function (tile) {
      var idx = parseInt(tile.getAttribute('data-criterion-index'), 10);
      var score = criterionScore(idx);
      var threshold = thresholdByPosition(idx, position);
      var ratio = tile.querySelector('.app-eval-rail__ratio');
      if (ratio) ratio.textContent = ratioText(score, threshold);
      applyChip(tile.querySelector('.app-eval-rail__chip'), score, threshold);
    });
  }

  function updateEvidenceHeader(root, idx, position) {
    var section = root.querySelector('.app-eval-evidence__criterion[data-criterion-index="' + idx + '"]');
    if (!section) return;
    var score = criterionScore(idx);
    var threshold = thresholdByPosition(idx, position);
    var ratio = section.querySelector('.app-eval-evidence__ratio');
    if (ratio) ratio.textContent = ratioText(score, threshold);
    applyChip(section.querySelector('.app-eval-evidence__chip'), score, threshold);
  }

  function selectCriterion(root, idx, opts) {
    opts = opts || {};
    var found = false;
    root.querySelectorAll('.app-eval-rail__tile').forEach(function (tile) {
      var active = tile.getAttribute('data-criterion-index') === String(idx);
      tile.classList.toggle('is-active', active);
      if (active) { tile.setAttribute('aria-current', 'true'); found = true; }
      else tile.removeAttribute('aria-current');
    });
    root.querySelectorAll('.app-eval-evidence__criterion').forEach(function (section) {
      section.hidden = section.getAttribute('data-criterion-index') !== String(idx);
    });
    if (!found) return;
    updateEvidenceHeader(root, idx, _position);
    if (opts.updateHash !== false) {
      history.replaceState(null, '', '#criterion-' + idx);
    }
    // Eager-load every indicator detail in the selected criterion (once; panels stay rendered).
    var section = root.querySelector('.app-eval-evidence__criterion[data-criterion-index="' + idx + '"]');
    if (section) {
      section.querySelectorAll('.indicator-detail-panel').forEach(function (panel) {
        panel.hidden = false;
        if (panel.getAttribute('data-loaded')) return;
        panel.setAttribute('data-loaded', 'true');
        loadIndicatorDetail(panel, panel.getAttribute('data-indicator-id'), _reportId, _apiBase);
      });
    }
  }

  function initRail(root) {
    root.querySelectorAll('.app-eval-rail__tile').forEach(function (tile) {
      tile.addEventListener('click', function () {
        selectCriterion(root, parseInt(tile.getAttribute('data-criterion-index'), 10));
      });
    });
  }

  // ── Indicator inline detail ───────────────────────────────────────────────

  var RANK_BADGE_CLASS = {
    'A_STAR': 'eval-rank-badge--a-star',
    'A':      'eval-rank-badge--a',
    'Q1':     'eval-rank-badge--a',
    'B':      'eval-rank-badge--b',
    'Q2':     'eval-rank-badge--b',
    'C':      'eval-rank-badge--c',
    'Q3':     'eval-rank-badge--c',
    'D':      'eval-rank-badge--d',
    'Q4':     'eval-rank-badge--d',
    'NON_RANK': 'eval-rank-badge--d'
  };

  function rankBadge(value) {
    if (!value || value === 'NON_RANK') return '';
    var cls = RANK_BADGE_CLASS[value] || 'eval-rank-badge--unknown';
    var label = value === 'A_STAR' ? 'A*' : value.replace('_', ' ');
    return '<span class="eval-rank-badge ' + cls + '">' + esc(label) + '</span>';
  }

  function renderDetailList(items, outputMode, indicatorId) {
    if (!items || items.length === 0) {
      return '<p class="app-eval-muted mt-2">No scored items found.</p>';
    }
    var isActivities = outputMode === 'activities';
    var isCitations  = outputMode === 'citations';

    var html = '<div class="eval-scored-list">';

    items.forEach(function (item) {
      var isClickable = isCitations && indicatorId;
      var clickable = isClickable ? ' eval-scored-item--clickable' : '';
      // A categorized publication whose scoring formula produced 0: shown (it matched the
      // indicator) but visually de-emphasised so it reads as "counted, scored 0".
      var isZeroScored = !isActivities && !isCitations && toNumber(item.authorScore) <= 0;
      var zeroClass = isZeroScored ? ' eval-scored-item--zero' : '';
      var dataAttrs = isClickable
        ? ' role="button" tabindex="0"' +
          ' data-citation-pub="' + esc(item.key) + '"' +
          ' data-indicator-id="' + esc(indicatorId) + '"' +
          ' aria-label="View citations for ' + esc(item.key) + '"'
        : '';
      html += '<div class="eval-scored-item' + clickable + zeroClass + '"' + dataAttrs + '>';
      html += '<div class="eval-scored-item__body">';

      // Line 1: title
      html += '<div class="eval-scored-item__title" title="' + esc(item.key) + '">' + esc(item.key) + '</div>';

      // Line 2: meta badges + text
      html += '<div class="eval-scored-item__meta">';

      if (item.year) {
        html += '<span class="eval-scored-item__meta-text">' + item.year + '</span>';
      }
      if (item.scoringSource) {
        html += '<span class="eval-scored-item__meta-text">· ' + esc(item.scoringSource) + '</span>';
      }
      if (!isActivities) {
        if (item.quarter) html += rankBadge(item.quarter);
        if (item.coreRankingEquivalent && item.coreRankingEquivalent !== item.quarter) {
          html += rankBadge(item.coreRankingEquivalent);
        }
        if (item.forumScore) {
          html += '<span class="eval-scored-item__meta-text">· ' + toNumber(item.forumScore).toFixed(4) + '</span>';
        }
      }
      if (item.type && item.type !== 'publication') {
        html += '<span class="eval-scored-item__meta-text">· ' + esc(item.type) + '</span>';
      }
      if (isCitations) {
        html += '<span class="eval-scored-item__meta-text">· click to see citations</span>';
      }

      html += '</div>'; // meta
      html += '</div>'; // body

      // Score on the right
      if (isZeroScored) {
        html += '<span class="eval-scored-item__zero-flag" title="Categorized for this indicator, but the scoring formula produced 0">below threshold</span>';
      }
      html += '<span class="eval-scored-item__score">' + toNumber(item.authorScore).toFixed(2) + '</span>';
      html += '</div>'; // item
    });

    html += '</div>';
    return html;
  }

  // Human copy for the backend's zeroReason gate markers.
  var ZERO_REASON_COPY = {
    'EXCLUDED_VENUE':       'Venue is on the standards exclusion list — scores 0 by rule.',
    'NON_RESEARCH_SUBTYPE': 'Not an original research contribution (editorial, note, letter, erratum, …).',
    'ROLE_FILTERED':        'Your authorship role on this publication does not match this indicator.',
    'NOT_IN_TOP_N':         'Outside the top-N selection this indicator counts.'
  };

  /**
   * Full-width evidence table for the workbench pane. Titles link to /publications/{id} and
   * venues to /forums/{id} when the payload carries ids; citation-mode rows keep the
   * click-to-drill behavior of the modal. Activities keep the compact list renderer — they
   * have no links or venue columns.
   */
  function renderEvidenceTable(items, outputMode, indicatorId) {
    if (outputMode === 'activities') {
      return renderDetailList(items, outputMode, indicatorId);
    }
    if (!items || items.length === 0) {
      return '<p class="app-eval-muted mt-2">No scored items found.</p>';
    }
    var isCitations = outputMode === 'citations';

    var html = '<table class="app-eval-evidence-table"><thead><tr>' +
      '<th scope="col">' + (isCitations ? 'Publication (click for citations)' : 'Publication') + '</th>' +
      '<th scope="col">Year</th>' +
      '<th scope="col">Rank</th>' +
      '<th scope="col">Venue</th>' +
      '<th scope="col" class="app-eval-evidence-table__num">Points</th>' +
      (_selfMode ? '<th scope="col"><span class="sr-only">Actions</span></th>' : '') +
      '</tr></thead><tbody>';

    items.forEach(function (item) {
      var isZeroScored = !isCitations && toNumber(item.authorScore) <= 0;
      var rowAttrs = '';
      if (isCitations && indicatorId) {
        rowAttrs = ' class="app-eval-evidence-table__row--clickable" role="button" tabindex="0"' +
          ' data-citation-pub="' + esc(item.key) + '"' +
          ' data-indicator-id="' + esc(indicatorId) + '"' +
          ' aria-label="View citations for ' + esc(item.key) + '"';
      } else if (isZeroScored) {
        rowAttrs = ' class="app-eval-evidence-table__row--zero"';
      }
      html += '<tr' + rowAttrs + '>';

      // Publication title — linked when the payload resolved a publication id.
      html += '<td class="app-eval-evidence-table__title">';
      if (item.publicationId && !isCitations) {
        html += '<a href="/publications/' + encodeURIComponent(item.publicationId) + '"' +
          ' title="' + esc(item.key) + '">' + esc(item.key) + '</a>';
      } else {
        html += '<span title="' + esc(item.key) + '">' + esc(item.key) + '</span>';
      }
      html += '</td>';

      html += '<td>' + (item.year ? item.year : '—') + '</td>';

      html += '<td class="app-eval-evidence-table__rank">';
      if (item.quarter) html += rankBadge(item.quarter);
      if (item.coreRankingEquivalent && item.coreRankingEquivalent !== item.quarter) {
        html += rankBadge(item.coreRankingEquivalent);
      }
      if (item.scoringSource) {
        html += '<span class="eval-scored-item__meta-text">' + esc(item.scoringSource) + '</span>';
      }
      html += '</td>';

      html += '<td>';
      if (item.forumId) {
        html += '<a href="/forums/' + encodeURIComponent(item.forumId) + '" title="Open venue page">venue' +
          ' <i class="fa-solid fa-arrow-up-right-from-square fa-xs" aria-hidden="true"></i></a>';
      } else {
        html += '<span class="app-eval-muted">—</span>';
      }
      html += '</td>';

      html += '<td class="app-eval-evidence-table__num">';
      if (item.zeroReason) {
        var reason = ZERO_REASON_COPY[item.zeroReason] || item.zeroReason;
        html += '<span class="eval-scored-item__zero-flag" title="' + esc(reason) + '">why? ' +
          '<i class="fa-solid fa-circle-info fa-xs" aria-hidden="true"></i></span> ';
      } else if (isZeroScored) {
        html += '<span class="eval-scored-item__zero-flag" title="Categorized for this indicator, but the scoring formula produced 0">below threshold</span> ';
      }
      html += '<strong>' + toNumber(item.authorScore).toFixed(2) + '</strong></td>';

      if (_selfMode) {
        html += '<td class="app-eval-evidence-table__actions">';
        if (item.publicationId && !isCitations) {
          html += '<a href="/user/workspace#publications" title="Manage in My Publications">manage</a>';
        }
        html += '</td>';
      }
      html += '</tr>';
    });

    html += '</tbody></table>';
    return html;
  }

  // ── Bootstrap modal helpers (no Bootstrap JS required) ───────────────────

  var _citationBackdrop = null;

  function showBsModal(modal) {
    modal.removeAttribute('aria-hidden');
    modal.setAttribute('aria-modal', 'true');
    modal.style.display = 'block';
    // Force reflow before adding 'show' so the CSS transition plays
    modal.offsetHeight; // eslint-disable-line no-unused-expressions
    modal.classList.add('show');
    document.body.classList.add('modal-open');

    if (!_citationBackdrop) {
      _citationBackdrop = document.createElement('div');
      _citationBackdrop.className = 'modal-backdrop fade';
      document.body.appendChild(_citationBackdrop);
      _citationBackdrop.offsetHeight; // reflow
      _citationBackdrop.classList.add('show');
    }

    // Move focus into the modal (close button is first reachable control)
    var closeBtn = modal.querySelector('[data-dismiss="modal"]');
    if (closeBtn) { setTimeout(function () { closeBtn.focus(); }, 50); }
  }

  function hideBsModal(modal) {
    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');
    modal.removeAttribute('aria-modal');
    modal.style.display = '';
    document.body.classList.remove('modal-open');

    if (_citationBackdrop) {
      _citationBackdrop.remove();
      _citationBackdrop = null;
    }

    // Return focus to whichever element opened the modal
    if (_modalTrigger) {
      _modalTrigger.focus();
      _modalTrigger = null;
    }
  }

  // ── Citation detail modal ─────────────────────────────────────────────────

  function openCitationModal(indicatorId, pubTitle, reportId, apiBase) {
    _modalTrigger = document.activeElement || null;
    var modal      = document.getElementById('citationDetailModal');
    var titleEl    = document.getElementById('citationModalPubTitle');
    var bodyEl     = document.getElementById('citationModalBody');
    var totalEl    = document.getElementById('citationModalTotal');
    if (!modal || !bodyEl) return;

    // Reset
    if (titleEl) titleEl.textContent = pubTitle;
    if (totalEl) totalEl.textContent = '';
    bodyEl.innerHTML =
      '<div class="citation-modal-skeleton">' +
        '<div class="app-skeleton-block" style="height:2.5rem;border-radius:0.35rem;"></div>' +
        '<div class="app-skeleton-block" style="height:2.5rem;border-radius:0.35rem;"></div>' +
        '<div class="app-skeleton-block" style="height:2.5rem;border-radius:0.35rem;"></div>' +
      '</div>';

    // Show modal — no Bootstrap JS required
    showBsModal(modal);

    var citUrl = (apiBase || '/user/evaluation') + '/indicator/' + encodeURIComponent(indicatorId) +
      '/citations?pub=' + encodeURIComponent(pubTitle);
    if (reportId) citUrl += '&report=' + encodeURIComponent(reportId);

    fetch(citUrl, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (data) {
        bodyEl.innerHTML = renderDetailList(data.citations, 'publications', null);
        if (totalEl) {
          totalEl.textContent = data.citations.length + ' citing paper' +
            (data.citations.length !== 1 ? 's' : '') +
            ' · total score ' + toNumber(data.totalScore).toFixed(2);
        }
      })
      .catch(function (err) {
        bodyEl.innerHTML =
          '<p class="app-eval-muted text-danger">Failed to load citations. ' + esc(err.message) + '</p>';
      });
  }

  function initCitationModal(root, reportId, apiBase) {
    // Wire close buttons (data-dismiss="modal") on the citation modal
    var modal = document.getElementById('citationDetailModal');
    if (modal) {
      modal.querySelectorAll('[data-dismiss="modal"]').forEach(function (btn) {
        btn.addEventListener('click', function () { hideBsModal(modal); });
      });
      // Close on backdrop click (clicking outside the dialog)
      modal.addEventListener('click', function (e) {
        if (e.target === modal) hideBsModal(modal);
      });
    }

    // Delegate click on citation rows — content is rendered dynamically
    root.addEventListener('click', function (e) {
      var row = e.target.closest('[data-citation-pub]');
      if (!row) return;
      var pubTitle    = row.getAttribute('data-citation-pub');
      var indicatorId = row.getAttribute('data-indicator-id');
      if (pubTitle && indicatorId) {
        openCitationModal(indicatorId, pubTitle, reportId, apiBase);
      }
    });

    // Keyboard activation for citation rows (Enter / Space)
    root.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      var row = e.target.closest('[data-citation-pub]');
      if (!row) return;
      e.preventDefault();
      var pubTitle    = row.getAttribute('data-citation-pub');
      var indicatorId = row.getAttribute('data-indicator-id');
      if (pubTitle && indicatorId) {
        openCitationModal(indicatorId, pubTitle, reportId, apiBase);
      }
    });
  }

  function esc(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function loadIndicatorDetail(panel, indicatorId, reportId, apiBase) {
    var skeleton = panel.querySelector('.indicator-detail-skeleton');
    var content = panel.querySelector('.indicator-detail-content');

    // Show skeleton
    if (skeleton) skeleton.hidden = false;
    if (content) content.innerHTML = '';

    var url = (apiBase || '/user/evaluation') + '/indicator/' + encodeURIComponent(indicatorId) + '/detail';
    if (reportId) url += '?report=' + encodeURIComponent(reportId);

    fetch(url, {
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
          var indicatorDeltaHtml = '';
          if (_compareData && _compareData.indicatorDeltas) {
            var dp = _compareData.indicatorDeltas[indicatorId];
            if (dp) {
              indicatorDeltaHtml = ' <span class="eval-delta ' + deltaClass(dp.delta) + '"' +
                ' aria-label="' + esc(deltaAriaLabel(dp.delta)) + '">' +
                deltaText(dp.delta) + '</span>';
            }
          }
          var headerHtml = '<div class="indicator-detail-header">' +
            '<span class="indicator-detail-total">Total score: <strong>' + toNumber(data.totalScore).toFixed(2) + '</strong>' + indicatorDeltaHtml + '</span>' +
            '<span class="indicator-detail-updated">Updated: ' + esc(data.updatedAt ? data.updatedAt.substring(0, 10) : '—') + '</span>' +
            '</div>';
          content.innerHTML = headerHtml + renderEvidenceTable(data.items, data.outputMode, data.indicatorId);
        }
      })
      .catch(function (err) {
        if (skeleton) skeleton.hidden = true;
        if (content) {
          content.innerHTML = '<p class="app-eval-muted text-danger mt-2">Failed to load detail. ' + esc(err.message) + '</p>';
        }
      });
  }

  // Indicator sections are always expanded in the workbench; clicking an indicator name just
  // anchors the URL to it (deep-linkable) and scrolls its section into view.
  function initIndicatorHashClicks(root) {
    root.addEventListener('click', function (e) {
      var btn = e.target.closest('.app-eval-indicator__name');
      if (!btn) return;
      var indicatorId = btn.getAttribute('data-indicator-id');
      if (!indicatorId) return;
      history.replaceState(null, '', '#indicator-' + indicatorId);
      var section = btn.closest('.app-eval-indicator');
      if (section) section.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }

  // ── Position selector ────────────────────────────────────────────────────

  var POSITION_LABELS = {
    'ASIST_UNIV': 'Asist. Univ.',
    'LECT_UNIV':  'Lect. Univ.',
    'CONF_UNIV':  'Conf. Univ.',
    'PROF_UNIV':  'Prof. Univ.',
    'HABIL':      'Abilitare',
    'CS_I':       'CS I',
    'CS_II':      'CS II',
    'CS_III':     'CS III',
    'ASIST_C':    'Asist. Cercetare',
    'OTHER':      'Other'
  };

  /** Returns {met, total} for criteria applicable to the given position (from the thresholds JSON). */
  function computeCriteriaMet(position) {
    var met = 0, total = 0;
    _thresholds.forEach(function (entry) {
      if (!entry) return;
      var threshold = thresholdByPosition(entry.index, position);
      if (threshold == null) return; // no threshold for this position — criterion not applicable
      total++;
      if (toNumber(entry.score) >= threshold) met++;
    });
    return { met: met, total: total };
  }

  function updateCriteriaMet(position) {
    var valueEl = document.getElementById('eval-criteria-met-value');
    var barEl   = document.getElementById('eval-criteria-met-bar');
    if (!valueEl) return;

    var result = computeCriteriaMet(position);
    if (result.total === 0) {
      valueEl.textContent = '—';
      if (barEl) barEl.style.width = '0%';
    } else {
      valueEl.textContent = result.met + ' / ' + result.total;
      if (barEl) barEl.style.width = Math.round(result.met * 100 / result.total) + '%';
    }
  }

  function reportPositions() {
    var seen = {};
    var positions = [];
    _thresholds.forEach(function (entry) {
      if (!entry || !Array.isArray(entry.thresholds)) return;
      entry.thresholds.forEach(function (t) {
        if (t.position && !seen[t.position]) {
          seen[t.position] = true;
          positions.push(t.position);
        }
      });
    });
    var ORDER = ['ASIST_UNIV','LECT_UNIV','CONF_UNIV','PROF_UNIV','HABIL','CS_I','CS_II','CS_III','ASIST_C','OTHER'];
    positions.sort(function (a, b) { return ORDER.indexOf(a) - ORDER.indexOf(b); });
    return positions;
  }

  function applyPosition(root, position) {
    _position = position;
    root.setAttribute('data-researcher-position', position);
    renderRail(root, position);
    updateCriteriaMet(position);
    var active = root.querySelector('.app-eval-rail__tile.is-active');
    if (active) {
      updateEvidenceHeader(root, parseInt(active.getAttribute('data-criterion-index'), 10), position);
    }
  }

  function initPositionSelector(root, researcherPosition) {
    _position = researcherPosition || '';
    var select = document.querySelector('[data-eval-position-selector]');
    var positions = reportPositions();

    if (!select) return; // delegated view: fixed researcher position, no selector

    if (positions.length === 0) {
      // No thresholds at all — hide the selector cell
      var cell = select.closest('.app-eval-aggregate__cell');
      if (cell) cell.hidden = true;
      return;
    }

    var seen = {};
    positions.forEach(function (pos) {
      seen[pos] = true;
      var opt = document.createElement('option');
      opt.value = pos;
      opt.textContent = POSITION_LABELS[pos] || pos;
      if (pos === researcherPosition) opt.selected = true;
      select.appendChild(opt);
    });

    // If researcher's position isn't in the list, pick the first available
    if (!seen[researcherPosition]) {
      select.value = positions[0];
    }
    _position = select.value;
    updateCriteriaMet(_position);

    select.addEventListener('change', function () {
      applyPosition(root, select.value);
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

  /** Restores #criterion-N / #indicator-ID deep links; returns true when the hash selected a criterion. */
  function applyHashState(root) {
    var hash = window.location.hash;
    if (!hash) return false;

    // #indicator-{id} → select the owning criterion, then scroll the indicator section into view
    var indMatch = hash.match(/^#indicator-(.+)$/);
    if (indMatch) {
      var panel = root.querySelector('.indicator-detail-panel[data-indicator-id="' + indMatch[1] + '"]');
      var section = panel ? panel.closest('.app-eval-evidence__criterion') : null;
      if (section) {
        selectCriterion(root, parseInt(section.getAttribute('data-criterion-index'), 10), { updateHash: false });
        var indicator = panel.closest('.app-eval-indicator');
        if (indicator) indicator.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        return true;
      }
      return false;
    }

    // #criterion-{index} → select that criterion
    var critMatch = hash.match(/^#criterion-(\d+)$/);
    if (critMatch && root.querySelector('.app-eval-rail__tile[data-criterion-index="' + critMatch[1] + '"]')) {
      selectCriterion(root, parseInt(critMatch[1], 10), { updateHash: false });
      return true;
    }
    return false;
  }

  // ── Overflow menu (compare/snapshot/export/verify live here on the self view) ─

  function initOverflowMenu() {
    var btn = document.getElementById('eval-overflow-btn');
    var menu = document.getElementById('eval-overflow-menu');
    if (!btn || !menu) return;

    function close() {
      menu.hidden = true;
      btn.setAttribute('aria-expanded', 'false');
    }

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = menu.hidden;
      menu.hidden = !open;
      btn.setAttribute('aria-expanded', String(open));
    });
    document.addEventListener('click', function (e) {
      if (!menu.hidden && !menu.contains(e.target) && e.target !== btn) close();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !menu.hidden) {
        close();
        btn.focus();
      }
    });
  }

  // ── Comparison mode ───────────────────────────────────────────────────────

  function applyComparisonDeltas(root, data) {
    _compareData = data;

    // 1. Criterion delta spans (on the rail tiles)
    root.querySelectorAll('.app-eval-rail__tile').forEach(function (tile) {
      var idx = parseInt(tile.getAttribute('data-criterion-index'), 10);
      var dp = data.criterionDeltas ? data.criterionDeltas[idx] : null;
      var span = tile.querySelector('.eval-criterion-delta');
      if (!span || !dp) return;
      span.className = 'eval-criterion-delta eval-delta ' + deltaClass(dp.delta);
      span.textContent = deltaText(dp.delta);
      span.setAttribute('aria-label', deltaAriaLabel(dp.delta));
    });

    // 2. Indicator delta spans
    root.querySelectorAll('.eval-indicator-delta').forEach(function (span) {
      var indicatorId = span.getAttribute('data-indicator-id');
      var dp = data.indicatorDeltas ? data.indicatorDeltas[indicatorId] : null;
      if (!dp) return;
      span.className = 'eval-indicator-delta eval-delta ' + deltaClass(dp.delta);
      span.textContent = deltaText(dp.delta);
      span.setAttribute('aria-label', deltaAriaLabel(dp.delta));
    });

    // 3. Aggregate delta cell
    var aggEl = document.getElementById('eval-aggregate-delta');
    var aggCell = document.getElementById('eval-compare-delta-cell');
    if (aggEl && data.aggregateDelta) {
      var d = data.aggregateDelta;
      aggEl.className = 'app-eval-aggregate__value app-eval-aggregate__value--sm eval-delta ' + deltaClass(d.delta);
      aggEl.textContent = toNumber(d.before).toFixed(2) + ' → ' + toNumber(d.after).toFixed(2) +
        ' (' + deltaText(d.delta) + ')';
      aggEl.setAttribute('aria-label',
        'Score ' + deltaAriaLabel(d.delta) + ': from ' + toNumber(d.before).toFixed(2) +
        ' to ' + toNumber(d.after).toFixed(2));
    }
    if (aggCell) aggCell.hidden = false;

    // 4. Banner
    var banner = document.getElementById('eval-compare-banner');
    var bannerLabel = document.getElementById('eval-compare-banner-label');
    if (bannerLabel && data.runA) {
      var isSnapshot = data.runA.status === 'SNAPSHOT';
      var dateStr = '—';
      try {
        if (data.runA.createdAt) {
          dateStr = new Date(data.runA.createdAt).toLocaleString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
          });
        }
      } catch (e) { dateStr = data.runA.createdAt || '—'; }
      if (isSnapshot && data.runA.name) {
        bannerLabel.textContent = 'snapshot "' + data.runA.name + '" (' + dateStr + ')';
      } else {
        bannerLabel.textContent = 'run from ' + dateStr;
      }
    }
    if (banner) banner.hidden = false;

    // 5. Toggle toolbar controls
    var compareBtn = document.getElementById('eval-compare-btn');
    var clearBtn   = document.getElementById('eval-clear-compare-btn');
    var picker     = document.getElementById('eval-compare-picker');
    if (compareBtn) compareBtn.hidden = true;
    if (clearBtn)   clearBtn.hidden   = false;
    if (picker)     picker.hidden     = true;
  }

  function clearComparisonDeltas(root) {
    _compareData = null;

    root.querySelectorAll('.eval-criterion-delta').forEach(function (s) {
      s.textContent = ''; s.className = 'eval-criterion-delta'; s.removeAttribute('aria-label');
    });
    root.querySelectorAll('.eval-indicator-delta').forEach(function (s) {
      s.textContent = ''; s.className = 'eval-indicator-delta'; s.removeAttribute('aria-label');
    });

    var aggEl   = document.getElementById('eval-aggregate-delta');
    var aggCell = document.getElementById('eval-compare-delta-cell');
    if (aggEl)   { aggEl.textContent = '—'; aggEl.removeAttribute('aria-label'); aggEl.className = 'app-eval-aggregate__value app-eval-aggregate__value--sm'; }
    if (aggCell) aggCell.hidden = true;

    var banner     = document.getElementById('eval-compare-banner');
    var compareBtn = document.getElementById('eval-compare-btn');
    var clearBtn   = document.getElementById('eval-clear-compare-btn');
    var picker     = document.getElementById('eval-compare-picker');
    if (banner)     banner.hidden     = true;
    if (compareBtn) compareBtn.hidden = false;
    if (clearBtn)   clearBtn.hidden   = true;
    if (picker)     picker.hidden     = true;
  }

  function fetchAndApplyComparison(root, compareId, currentRunId, reportId) {
    // compareId is either a plain runId or 'snap:{snapshotId}'
    var url;
    if (compareId && compareId.indexOf('snap:') === 0) {
      var snapshotId = compareId.slice(5);
      url = '/user/evaluation/compare-snapshot?snapshotId=' + encodeURIComponent(snapshotId) +
            '&runId=' + encodeURIComponent(currentRunId);
    } else {
      // runA = past run, runB = current run → delta = current − past (positive = improvement)
      url = '/user/evaluation/compare?runA=' + encodeURIComponent(compareId) +
            '&runB=' + encodeURIComponent(currentRunId);
    }
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (data) {
        applyComparisonDeltas(root, data);
      })
      .catch(function (err) {
        console.warn('[comparison] fetch failed:', err.message);
      });
  }

  function initComparisonControls(root, reportId, currentRunId) {
    if (!currentRunId) return;

    // Enforce correct initial state — clear button hidden until comparison is applied.
    // Do this explicitly in JS so that bfcache restoration or any other mechanism that
    // may have left the button visible on a previous visit cannot carry state forward.
    var clearBtnInit   = document.getElementById('eval-clear-compare-btn');
    var compareBtnInit = document.getElementById('eval-compare-btn');
    if (clearBtnInit)   clearBtnInit.hidden   = true;
    if (compareBtnInit) compareBtnInit.hidden = false;

    // Populate run picker
    var select = document.getElementById('eval-compare-select');
    if (select && Array.isArray(window.evalPriorRuns)) {
      window.evalPriorRuns.forEach(function (run) {
        if (run.runId === currentRunId) return; // exclude current run
        var opt = document.createElement('option');
        opt.value = run.runId;
        var label = '—';
        try {
          label = run.createdAt
            ? new Date(run.createdAt).toLocaleString(undefined, {
                year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
              })
            : run.runId;
        } catch (e) { label = run.runId; }
        opt.textContent = label + (run.status && run.status !== 'READY' ? ' (' + run.status + ')' : '');
        select.appendChild(opt);
      });
      select.addEventListener('change', function () {
        if (!select.value) return;
        fetchAndApplyComparison(root, select.value, currentRunId, reportId);
        var picker = document.getElementById('eval-compare-picker');
        if (picker) picker.hidden = true;
      });
    }

    // "Compare with…" button
    var compareBtn = document.getElementById('eval-compare-btn');
    if (compareBtn) {
      compareBtn.addEventListener('click', function () {
        var picker = document.getElementById('eval-compare-picker');
        if (!picker) return;
        picker.hidden = !picker.hidden;
        if (!picker.hidden) {
          var sel = document.getElementById('eval-compare-select');
          if (sel) sel.focus();
        }
      });
    }

    // "Clear comparison" button
    var clearBtn = document.getElementById('eval-clear-compare-btn');
    if (clearBtn) {
      clearBtn.addEventListener('click', function () {
        clearComparisonDeltas(root);
      });
    }

  }

  // ── Snapshot utilities ────────────────────────────────────────────────────

  function _escHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function _csrfHeaders() {
    var hMeta = document.querySelector('meta[name="_csrf_header"]');
    var tMeta = document.querySelector('meta[name="_csrf"]');
    var h = { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    if (hMeta && tMeta) h[hMeta.content] = tMeta.content;
    return h;
  }

  function _fmtSnapDate(iso) {
    try {
      return iso ? new Date(iso).toLocaleString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
      }) : '—';
    } catch (e) { return iso || '—'; }
  }

  // ── Snapshot panel ────────────────────────────────────────────────────────

  function initSnapshotPanel(root, reportId, currentRunId) {
    // "Save Snapshot" button
    var saveBtn = document.getElementById('eval-save-snapshot-btn');
    if (saveBtn) {
      saveBtn.addEventListener('click', function () {
        _saveSnapshot(root, reportId, currentRunId);
      });
    }

    // "My Snapshots" toggle
    var snapshotsBtn = document.getElementById('eval-snapshots-btn');
    var snapshotsPanel = document.getElementById('eval-snapshots-panel');
    if (snapshotsBtn && snapshotsPanel) {
      snapshotsBtn.addEventListener('click', function () {
        if (snapshotsPanel.hidden) {
          snapshotsPanel.hidden = false;
          snapshotsBtn.setAttribute('aria-expanded', 'true');
          _loadSnapshotList(root, reportId, currentRunId);
          // Move focus to the close button once the panel is visible
          var closeEl = document.getElementById('eval-snapshots-close');
          if (closeEl) { setTimeout(function () { closeEl.focus(); }, 0); }
        } else {
          snapshotsPanel.hidden = true;
          snapshotsBtn.setAttribute('aria-expanded', 'false');
        }
      });
    }

    // Close button inside panel — return focus to the trigger
    var closeBtn = document.getElementById('eval-snapshots-close');
    if (closeBtn && snapshotsPanel) {
      closeBtn.addEventListener('click', function () {
        snapshotsPanel.hidden = true;
        if (snapshotsBtn) {
          snapshotsBtn.setAttribute('aria-expanded', 'false');
          snapshotsBtn.focus();
        }
      });
    }

    // Load snapshots into compare picker on page init (async, non-blocking).
    // Only when the compare picker actually exists — the delegated (admin/supervisor) view ships
    // no snapshot/compare controls, so this must not fire a cross-user snapshot fetch there.
    if (document.getElementById('eval-compare-select')) {
      _loadSnapshotsForPicker(reportId);
    }
  }

  function _saveSnapshot(root, reportId, currentRunId) {
    var defaultName = 'Snapshot ' + new Date().toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });
    var name = window.prompt('Name for this snapshot:', defaultName);
    if (name === null) return; // cancelled
    if (!name.trim()) name = defaultName;

    var saveBtn = document.getElementById('eval-save-snapshot-btn');
    if (saveBtn) saveBtn.disabled = true;

    fetch('/user/evaluation/snapshots', {
      method: 'POST',
      credentials: 'same-origin',
      headers: _csrfHeaders(),
      body: JSON.stringify({ reportId: reportId, name: name.trim() })
    })
    .then(function (res) {
      if (res.status === 422) throw new Error('limit');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    })
    .then(function (snapshot) {
      if (saveBtn) saveBtn.disabled = false;
      _showSnapshotFeedback('Snapshot saved: "' + snapshot.name + '"', false);
      var panel = document.getElementById('eval-snapshots-panel');
      if (panel && !panel.hidden) _loadSnapshotList(root, reportId, currentRunId);
      _loadSnapshotsForPicker(reportId);
    })
    .catch(function (err) {
      if (saveBtn) saveBtn.disabled = false;
      if (err.message === 'limit') {
        _showSnapshotFeedback('Snapshot limit reached (max 50 per report).', true);
      } else {
        _showSnapshotFeedback('Failed to save snapshot.', true);
      }
    });
  }

  function _showSnapshotFeedback(msg, isError) {
    if (window.appToast) {
      window.appToast.show({ message: msg, tone: isError ? 'error' : 'success' });
      return;
    }
    // Fallback for environments where app.js bundle hasn't initialised yet
    var el = document.getElementById('eval-snapshot-feedback');
    if (!el) return;
    el.textContent = msg;
    el.className = 'app-eval-snapshot-feedback' + (isError ? ' app-eval-snapshot-feedback--error' : ' app-eval-snapshot-feedback--ok');
    el.hidden = false;
    setTimeout(function () { el.hidden = true; el.textContent = ''; }, 4000);
  }

  function _loadSnapshotList(root, reportId, currentRunId) {
    var body = document.getElementById('eval-snapshots-body');
    if (!body) return;
    body.innerHTML = '<p class="app-eval-snapshots-panel__loading">Loading\u2026</p>';

    fetch('/user/evaluation/snapshots?report=' + encodeURIComponent(reportId), {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    })
    .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
    .then(function (snapshots) { _renderSnapshotList(body, snapshots, root, reportId, currentRunId); })
    .catch(function () {
      body.innerHTML = '<p class="app-eval-snapshots-panel__empty">Failed to load snapshots.</p>';
    });
  }

  function _renderSnapshotList(body, snapshots, root, reportId, currentRunId) {
    if (!snapshots || snapshots.length === 0) {
      body.innerHTML = '<p class="app-eval-snapshots-panel__empty">No snapshots yet. Click \u201cSave Snapshot\u201d to create one.</p>';
      return;
    }
    var html = '<ul class="app-eval-snapshots-list">';
    snapshots.forEach(function (snap) {
      var scoreStr = snap.totalScore != null ? snap.totalScore.toFixed(2) : null;
      html +=
        '<li class="app-eval-snapshots-item">' +
          '<div class="app-eval-snapshots-item__body">' +
            '<span class="app-eval-snapshots-item__name">' + _escHtml(snap.name) + '</span>' +
            '<span class="app-eval-snapshots-item__meta">' + _escHtml(_fmtSnapDate(snap.createdAt)) +
              (scoreStr ? ' &bull; ' + scoreStr : '') +
            '</span>' +
          '</div>' +
          '<div class="app-eval-snapshots-item__actions">' +
            '<button type="button" class="btn btn-sm btn-outline-primary app-eval-snapshots-item__compare-btn"' +
              ' data-snap-id="' + _escHtml(snap.id) + '" data-snap-name="' + _escHtml(snap.name) + '">' +
              '<i class="fa-solid fa-code-compare fa-xs" aria-hidden="true"></i> Compare' +
            '</button>' +
            '<button type="button" class="btn btn-sm btn-outline-danger app-eval-snapshots-item__delete-btn"' +
              ' data-snap-id="' + _escHtml(snap.id) + '" data-snap-name="' + _escHtml(snap.name) + '"' +
              ' aria-label="Delete snapshot">' +
              '<i class="fa-solid fa-trash fa-xs" aria-hidden="true"></i>' +
            '</button>' +
          '</div>' +
        '</li>';
    });
    html += '</ul>';
    html += '<p class="app-eval-snapshots-panel__cap">' + snapshots.length + ' / 50 snapshots used</p>';
    body.innerHTML = html;

    body.querySelectorAll('.app-eval-snapshots-item__compare-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var snapId = btn.getAttribute('data-snap-id');
        fetchAndApplyComparison(root, 'snap:' + snapId, currentRunId, reportId);
        var panel = document.getElementById('eval-snapshots-panel');
        if (panel) panel.hidden = true;
      });
    });

    body.querySelectorAll('.app-eval-snapshots-item__delete-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var snapId   = btn.getAttribute('data-snap-id');
        var snapName = btn.getAttribute('data-snap-name');
        if (window.appConfirmDialog) {
          window.appConfirmDialog.open({
            title: 'Delete snapshot?',
            body: 'Delete \u201c' + snapName + '\u201d? This cannot be undone.',
            confirmLabel: 'Delete',
            tone: 'danger',
            onConfirm: function () { _deleteSnapshot(body, snapId, root, reportId, currentRunId); },
          });
        } else {
          if (!confirm('Delete snapshot \u201c' + snapName + '\u201d? This cannot be undone.')) return;
          _deleteSnapshot(body, snapId, root, reportId, currentRunId);
        }
      });
    });
  }

  function _deleteSnapshot(body, snapshotId, root, reportId, currentRunId) {
    var hMeta = document.querySelector('meta[name="_csrf_header"]');
    var tMeta = document.querySelector('meta[name="_csrf"]');
    var deleteHeaders = { 'X-Requested-With': 'XMLHttpRequest' };
    if (hMeta && tMeta) deleteHeaders[hMeta.content] = tMeta.content;

    fetch('/user/evaluation/snapshots/' + encodeURIComponent(snapshotId), {
      method: 'DELETE',
      credentials: 'same-origin',
      headers: deleteHeaders
    })
    .then(function (res) {
      if (!res.ok && res.status !== 204) throw new Error('HTTP ' + res.status);
      // If this snapshot was the active comparison, clear it
      if (_compareData && _compareData.runA && _compareData.runA.runId === 'snap:' + snapshotId) {
        clearComparisonDeltas(root);
      }
      _loadSnapshotList(root, reportId, currentRunId);
      _loadSnapshotsForPicker(reportId);
    })
    .catch(function () { _showSnapshotFeedback('Failed to delete snapshot.', true); });
  }

  function _loadSnapshotsForPicker(reportId) {
    fetch('/user/evaluation/snapshots?report=' + encodeURIComponent(reportId), {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    })
    .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
    .then(function (snapshots) { _updateSnapshotsInCompareSelect(snapshots); })
    .catch(function () { /* picker snapshot load failure is non-fatal */ });
  }

  function _updateSnapshotsInCompareSelect(snapshots) {
    var select = document.getElementById('eval-compare-select');
    if (!select) return;
    // Remove existing snapshot group if present
    var existing = select.querySelector('optgroup[data-snapshots]');
    if (existing) existing.remove();
    if (!snapshots || snapshots.length === 0) return;
    var group = document.createElement('optgroup');
    group.label = 'Saved Snapshots';
    group.setAttribute('data-snapshots', '');
    snapshots.forEach(function (snap) {
      var opt = document.createElement('option');
      opt.value = 'snap:' + snap.id;
      opt.textContent = snap.name + ' (' + _fmtSnapDate(snap.createdAt) + ')';
      group.appendChild(opt);
    });
    select.appendChild(group);
  }

  // ── Boot ──────────────────────────────────────────────────────────────────

  function boot() {
    var root = document.querySelector('.individual-report-dashboard');
    if (!root) return;

    _root = root;
    var researcherPosition = root.getAttribute('data-researcher-position') || '';
    _reportId = root.getAttribute('data-report-id') || '';
    // Drilldown fetch base: '/user/evaluation' for the researcher's own page, or
    // '/reports/researcher/{email}' for the delegated admin/supervisor view. Same JS, same
    // endpoints shape — only the prefix differs, so the two surfaces cannot drift.
    _apiBase = root.getAttribute('data-eval-api-base') || '/user/evaluation';
    _selfMode = _apiBase === '/user/evaluation';

    readThresholds();
    initPositionSelector(root, researcherPosition);
    renderRail(root, _position);
    initRail(root);
    initIndicatorHashClicks(root);
    initCitationModal(root, _reportId, _apiBase);
    initReportSwitcher();
    var currentRunId = root.getAttribute('data-run-id') || (window.evalCurrentRunId || null);
    initComparisonControls(root, _reportId, currentRunId);
    initSnapshotPanel(root, _reportId, currentRunId);
    initOverflowMenu();

    // Apply hash state after a tick so the DOM is stable; default to the first criterion.
    setTimeout(function () {
      if (!applyHashState(root)) {
        var first = root.querySelector('.app-eval-rail__tile');
        if (first) selectCriterion(root, parseInt(first.getAttribute('data-criterion-index'), 10), { updateHash: false });
      }
    }, 0);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // When the page is restored from the bfcache (browser Back/Forward), always reset
  // comparison state — comparisons are session-only and should not persist across navigation.
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return; // normal load already handled by boot()
    var root = document.querySelector('.individual-report-dashboard');
    if (root) clearComparisonDeltas(root);
  });
})();
