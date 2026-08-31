(function () {
  'use strict';

  // ── Localisation ──────────────────────────────────────────────────────────

  /**
   * Translate a `report.dash.*` key, substituting {0}, {1}, … positionally.
   *
   * This file is served from static/js and is NOT part of the npm bundle, so it cannot import the shared
   * helper. It calls through to the one app.js exposes (`window.appT`) rather than carrying its own copy:
   * a second implementation of a shared rule is exactly how the Lecture-Notes double count happened.
   * The bundle itself is inlined by individual-report-view.html before this script runs.
   *
   * Falling back to the key (rather than to English) keeps a missing key visible instead of silently
   * shipping the wrong language — the same contract as the shared helper.
   */
  function t(key) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (typeof window.appT === 'function') {
      return window.appT.apply(null, [key].concat(args));
    }
    return key;
  }

  /**
   * Pluralised lookup: `base` + '.one' | '.few' | '.other', with the count as {0}.
   *
   * Counts here MUST go through this rather than through t(): Romanian has three forms and takes a
   * particle from twenty up ("20 DE lucrări"), which no amount of "{0} lucrări" gets right.
   */
  function tp(base, count) {
    var args = Array.prototype.slice.call(arguments, 2);
    if (typeof window.appTPlural === 'function') {
      return window.appTPlural.apply(null, [base, count].concat(args));
    }
    return base;
  }

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

  // H95: perspectives = section-level groupings with a per-position DA/NU verdict over their member
  // criteria. Empty on legacy reports, in which case nothing below changes any behavior.
  var _perspectives = [];       // parsed #eval-perspectives-data entries, in order
  var _bundledCriteria = {};    // criterionIndex → true when grouped under some perspective

  function readPerspectives() {
    _perspectives = [];
    _bundledCriteria = {};
    var el = document.getElementById('eval-perspectives-data');
    if (!el) return;
    try {
      var parsed = JSON.parse(el.textContent || '[]');
      (parsed || []).forEach(function (entry) {
        if (!entry || typeof entry.index !== 'number') return;
        _perspectives.push(entry);
        (entry.members || []).forEach(function (m) { _bundledCriteria[m] = true; });
      });
    } catch (e) { /* malformed JSON → flat rail, as before H95 */ }
  }

  function perspectiveVerdict(entry, position) {
    if (!entry || !position || !entry.verdictByPosition) return null; // null = not applicable
    var v = entry.verdictByPosition[position];
    return typeof v === 'boolean' ? v : null;
  }

  /**
   * Groups the server-rendered rail tiles under perspective headings. Tiles are MOVED, not cloned, so
   * every existing data-criterion-index hook (selection, chips, sparklines) keeps working. Unbundled
   * criteria (supporting caps like the FEAA books-into-P criterion) move BELOW the groups — data
   * order puts them first, but the perspectives are the headline and the default selection should
   * land on their first member. Runs once at boot; verdicts re-render on position change.
   */
  function groupRailByPerspectives(root) {
    if (!_perspectives.length) return;
    var rail = root.querySelector('.app-eval-rail');
    if (!rail) return;
    var grouped = 0;
    _perspectives.forEach(function (entry) {
      var group = document.createElement('section');
      group.className = 'app-eval-rail__group';
      group.setAttribute('data-perspective-index', String(entry.index));
      var header = document.createElement('header');
      header.className = 'app-eval-rail__group-header';
      header.innerHTML = '<span class="app-eval-rail__group-name"></span>' +
        '<span class="app-eval-rail__group-verdict app-eval-chip" hidden></span>';
      header.querySelector('.app-eval-rail__group-name').textContent = entry.name;
      group.appendChild(header);
      appendRouteLegend(group, entry);
      var moved = 0;
      (entry.members || []).forEach(function (idx) {
        var tile = rail.querySelector('.app-eval-rail__tile[data-criterion-index="' + idx + '"]');
        if (tile) { group.appendChild(tile); moved++; }
      });
      if (moved > 0) { rail.appendChild(group); grouped++; }
    });
    if (grouped > 0) {
      Array.from(rail.children).forEach(function (child) {
        if (child.classList && child.classList.contains('app-eval-rail__tile')) rail.appendChild(child);
      });
    }
  }

  /**
   * H95 routes: a legend of composition rows under a perspective's header — the alternative routes
   * of an any-root (FEAA point 4's rutele a–d) or the referenced-perspective components of an
   * all-root (FV Info's "Total — verdict": Perspectiva B/C/D rows explain a NU verdict whose only
   * visible tile, the sum, is DA). Rows may SHARE criteria with tiles anywhere in the rail, so
   * hovering a row highlights its member tiles (in this group or a referenced one) instead of
   * partitioning anything. Verdicts come from the server (same evaluator as the group chip) and
   * re-render on position change.
   */
  function appendRouteLegend(group, entry) {
    var routes = entry.routes;
    if (!Array.isArray(routes) || routes.length < 1) return;
    var box = document.createElement('div');
    box.className = 'app-eval-rail__routes';
    routes.forEach(function (route, ri) {
      var row = document.createElement('div');
      row.className = 'app-eval-rail__route';
      row.setAttribute('data-route-index', String(ri));
      var name = document.createElement('span');
      name.className = 'app-eval-rail__route-name';
      name.textContent = route.label || t('report.dash.routeFallback', ri + 1);
      var chip = document.createElement('span');
      chip.className = 'app-eval-rail__route-verdict app-eval-chip';
      chip.hidden = true;
      row.appendChild(name);
      row.appendChild(chip);
      row.addEventListener('mouseenter', function () { toggleRouteHint(group, route, true); });
      row.addEventListener('mouseleave', function () { toggleRouteHint(group, route, false); });
      box.appendChild(row);
    });
    group.appendChild(box);
  }

  function toggleRouteHint(group, route, on) {
    // Search the whole rail, not just this group: a perspective-ref row (Total — verdict's
    // "Perspectiva B/C/D") points at tiles that live under the REFERENCED perspective's heading.
    var rail = group.closest('.app-eval-rail') || group;
    (route.members || []).forEach(function (idx) {
      var tile = rail.querySelector('.app-eval-rail__tile[data-criterion-index="' + idx + '"]');
      if (tile) tile.classList.toggle('is-route-hint', on);
    });
  }

  function renderPerspectiveVerdicts(root, position) {
    _perspectives.forEach(function (entry) {
      var group = root.querySelector('.app-eval-rail__group[data-perspective-index="' + entry.index + '"]');
      if (!group) return;
      var el = group.querySelector('.app-eval-rail__group-verdict');
      if (el) {
        var verdict = perspectiveVerdict(entry, position);
        if (verdict === null) {
          el.hidden = true;
          el.textContent = '';
          el.className = 'app-eval-rail__group-verdict app-eval-chip';
        } else {
          el.hidden = false;
          el.textContent = verdict ? t('report.dash.verdictYes') : t('report.dash.verdictNo');
          el.className = 'app-eval-rail__group-verdict app-eval-chip ' +
            (verdict ? 'app-eval-chip--met' : 'app-eval-chip--below');
        }
      }
      group.querySelectorAll('.app-eval-rail__route').forEach(function (row) {
        var route = (entry.routes || [])[parseInt(row.getAttribute('data-route-index'), 10)];
        var chip = row.querySelector('.app-eval-rail__route-verdict');
        var v = route && position && route.verdictByPosition
          ? route.verdictByPosition[position] : null;
        var applicable = typeof v === 'boolean';
        row.classList.toggle('is-inapplicable', !applicable);
        if (!applicable) {
          chip.hidden = true;
          chip.textContent = '';
          chip.className = 'app-eval-rail__route-verdict app-eval-chip';
          return;
        }
        chip.hidden = false;
        chip.textContent = v ? t('report.dash.verdictYes') : t('report.dash.verdictNo');
        chip.className = 'app-eval-rail__route-verdict app-eval-chip ' +
          (v ? 'app-eval-chip--met' : 'app-eval-chip--below');
      });
    });
  }

  function thresholdByPosition(criterionIndex, position) {
    var entry = _thresholds[criterionIndex];
    if (!entry || !Array.isArray(entry.thresholds) || !position) return null;
    var match = entry.thresholds.find(function (th) { return th.position === position; });
    return match ? toNumber(match.value) : null;
  }

  /**
   * The criterion's obtained value for threshold comparison. Criteria with threshold-cap additions
   * (FEAA 2026 books-into-P) carry a per-position effective score in `scoreByPosition`; everything
   * else — and any position without an entry — falls back to the canonical `score`.
   */
  function criterionScore(criterionIndex, position) {
    var entry = _thresholds[criterionIndex];
    if (!entry) return 0;
    if (position && entry.scoreByPosition && typeof entry.scoreByPosition[position] === 'number') {
      return toNumber(entry.scoreByPosition[position]);
    }
    return toNumber(entry.score);
  }

  /** Preformatted "indicator: raw → +counted" note lines for the position; empty array when none. */
  function capNotes(criterionIndex, position) {
    var entry = _thresholds[criterionIndex];
    if (!entry || !position || !entry.capNotesByPosition) return [];
    var notes = entry.capNotesByPosition[position];
    return Array.isArray(notes) ? notes : [];
  }

  /** DA / APROAPE (within 20% below) / NU — same verdict vocabulary as the perspective headers;
   *  null when no threshold applies. */
  function chipFor(score, threshold) {
    if (threshold == null || threshold <= 0) return null;
    if (score >= threshold) return { label: t('report.dash.verdictYes'), cls: 'app-eval-chip--met' };
    if (score >= 0.8 * threshold) return { label: t('report.dash.verdictNear'), cls: 'app-eval-chip--near' };
    return { label: t('report.dash.verdictNo'), cls: 'app-eval-chip--below' };
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
      var score = criterionScore(idx, position);
      var threshold = thresholdByPosition(idx, position);
      var ratio = tile.querySelector('.app-eval-rail__ratio');
      if (ratio) ratio.textContent = ratioText(score, threshold);
      applyChip(tile.querySelector('.app-eval-rail__chip'), score, threshold);
    });
  }

  function updateEvidenceHeader(root, idx, position) {
    var section = root.querySelector('.app-eval-evidence__criterion[data-criterion-index="' + idx + '"]');
    if (!section) return;
    var score = criterionScore(idx, position);
    var threshold = thresholdByPosition(idx, position);
    var ratio = section.querySelector('.app-eval-evidence__ratio');
    if (ratio) ratio.textContent = ratioText(score, threshold);
    applyChip(section.querySelector('.app-eval-evidence__chip'), score, threshold);
    renderThresholdCapNotes(section, idx, position);
  }

  /**
   * Position-scoped "counted toward this threshold" lines for threshold-cap additions. Managed here
   * (not server-rendered like the percent-cap notes) because the text changes with the position
   * selector. The mount is created on demand right after the header and reused on re-render.
   */
  function renderThresholdCapNotes(section, idx, position) {
    var mount = section.querySelector('.app-eval-evidence__threshold-cap-notes');
    var notes = capNotes(idx, position);
    if (notes.length === 0) {
      if (mount) mount.remove();
      return;
    }
    if (!mount) {
      mount = document.createElement('div');
      mount.className = 'app-eval-evidence__threshold-cap-notes';
      var header = section.querySelector('.app-eval-evidence__header');
      if (header && header.parentNode) header.parentNode.insertBefore(mount, header.nextSibling);
      else section.insertBefore(mount, section.firstChild);
    }
    var html = '';
    notes.forEach(function (note) {
      html += '<p class="app-eval-evidence__cap-note">' +
        esc(t('report.dash.thresholdCapNote', note)) + '</p>';
    });
    mount.innerHTML = html;
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
    updateSparkline(root, idx, _position);
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

  // ── Next best actions strip ───────────────────────────────────────────────

  /** Top near-miss criteria for the position: within 20% below their threshold, smallest gap first. */
  function renderNearMisses(root, position) {
    var strip = document.getElementById('eval-actions-strip');
    var mount = document.getElementById('eval-near-miss');
    if (!strip || !mount) return;

    var misses = [];
    _thresholds.forEach(function (entry) {
      if (!entry) return;
      var threshold = thresholdByPosition(entry.index, position);
      if (threshold == null || threshold <= 0) return;
      var score = criterionScore(entry.index, position);
      if (score < threshold && score >= 0.8 * threshold) {
        misses.push({ index: entry.index, name: entry.name, gap: threshold - score });
      }
    });
    misses.sort(function (a, b) { return a.gap - b.gap; });

    var html = '';
    misses.slice(0, 3).forEach(function (m) {
      html += '<button type="button" class="app-eval-actions__chip app-eval-actions__chip--near"' +
        ' data-criterion-index="' + m.index + '"' +
        ' title="Select this criterion to see what could close the gap">' +
        '<i class="fa-solid fa-arrows-up-to-line" aria-hidden="true"></i> ' +
        esc(m.name) + ': +' + m.gap.toFixed(2) + ' needed</button>';
    });
    mount.innerHTML = html;

    // The strip shows when it has anything to say (server-rendered pending chip or near-misses).
    var hasPendingChip = !!strip.querySelector('.app-eval-actions__chip--pending');
    strip.hidden = !hasPendingChip && misses.length === 0;

    mount.querySelectorAll('[data-criterion-index]').forEach(function (chip) {
      chip.addEventListener('click', function () {
        selectCriterion(root, parseInt(chip.getAttribute('data-criterion-index'), 10));
        var tile = root.querySelector('.app-eval-rail__tile.is-active');
        if (tile) tile.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
    });
  }

  // ── Criterion trend sparkline (inline SVG, from window.evalPriorRuns) ────

  var SVG_NS = 'http://www.w3.org/2000/svg';

  function sparklineSeries(criterionIndex) {
    if (!Array.isArray(window.evalPriorRuns)) return [];
    return window.evalPriorRuns
      .filter(function (run) { return run.status === 'READY' && run.criteriaScores; })
      .slice(0, 12) // list arrives newest-first; cap before ordering oldest→newest
      .reverse()
      .map(function (run) {
        var v = run.criteriaScores[criterionIndex];
        return v == null ? 0 : toNumber(v);
      });
  }

  function renderSparkline(el, values, threshold) {
    el.innerHTML = '';
    if (!el.ownerDocument || values.length < 2) return;

    var w = 110, h = 28, pad = 3;
    var max = Math.max.apply(null, values.concat(threshold != null ? [threshold] : []));
    var min = Math.min.apply(null, values.concat(threshold != null ? [threshold] : [0]));
    if (max === min) max = min + 1;
    function x(i) { return pad + (w - 2 * pad) * (values.length === 1 ? 0 : i / (values.length - 1)); }
    function y(v) { return h - pad - (h - 2 * pad) * ((v - min) / (max - min)); }

    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
    svg.setAttribute('width', w);
    svg.setAttribute('height', h);
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', 'Score trend across last ' + values.length + ' runs');
    svg.setAttribute('class', 'eval-trend__svg');

    if (threshold != null) {
      var tLine = document.createElementNS(SVG_NS, 'line');
      tLine.setAttribute('x1', pad); tLine.setAttribute('x2', w - pad);
      tLine.setAttribute('y1', y(threshold)); tLine.setAttribute('y2', y(threshold));
      tLine.setAttribute('class', 'eval-trend__threshold');
      svg.appendChild(tLine);
    }

    var line = document.createElementNS(SVG_NS, 'polyline');
    line.setAttribute('points', values.map(function (v, i) { return x(i) + ',' + y(v); }).join(' '));
    line.setAttribute('class', 'eval-trend__line');
    svg.appendChild(line);

    var dot = document.createElementNS(SVG_NS, 'circle');
    dot.setAttribute('cx', x(values.length - 1));
    dot.setAttribute('cy', y(values[values.length - 1]));
    dot.setAttribute('r', 2.5);
    dot.setAttribute('class', 'eval-trend__dot');
    svg.appendChild(dot);

    el.appendChild(svg);
  }

  function updateSparkline(root, idx, position) {
    var mount = root.querySelector('.eval-trend[data-criterion-index="' + idx + '"]');
    if (!mount) return;
    renderSparkline(mount, sparklineSeries(idx), thresholdByPosition(idx, position));
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
      return '<p class="app-eval-muted mt-2">' + esc(t('report.dash.noItems')) + '</p>';
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
        html += feeBadge(item);
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
        if (item.zeroReason) {
          var itemReason = zeroReasonCopy(item.zeroReason);
          html += '<span class="eval-scored-item__zero-flag" title="' + esc(itemReason) + '">' +
            esc(zeroReasonLabel(item)) + '</span>';
        } else {
          html += '<span class="eval-scored-item__zero-flag" title="' + esc(t('report.dash.formulaCutoffHint')) + '">' + esc(t('report.dash.formulaCutoff')) + '</span>';
        }
      }
      html += '<span class="eval-scored-item__score">' + toNumber(item.authorScore).toFixed(2) + '</span>';
      html += '</div>'; // item
    });

    html += '</div>';
    return html;
  }

  // Message-key suffix per backend zeroReason marker. The copy itself lives in messages*.properties
  // (report.dash.zero.* for the tooltip sentence, report.dash.zeroLabel.* for the pill).
  var ZERO_REASON_KEY = {
    'EXCLUDED_VENUE':       'excludedVenue',
    'NON_RESEARCH_SUBTYPE': 'nonResearchSubtype',
    'ROLE_FILTERED':        'roleFiltered',
    'NOT_IN_TOP_N':         'notInTopN',
    'VENUE_TYPE_MISMATCH':  'venueTypeMismatch',
    'FEE_JOURNAL':          'feeJournal',
    'OVER_PER_FORUM_CAP':   'overPerForumCap',
    'SCORED_BY_STRICTER':   'scoredByStricter',
    'NOT_TOP_RANKED':       'notTopRanked',
    'SELF_CITATION':        'selfCitation',
    'SCORE_BELOW_FORMULA_THRESHOLD': 'belowFormulaThreshold',
    'MULTIPLE_GATES':       'multipleGates'
  };

  /** Full explanatory sentence for a zeroReason; falls back to the raw marker for an unknown code. */
  function zeroReasonCopy(reason) {
    var suffix = ZERO_REASON_KEY[reason];
    return suffix ? t('report.dash.zero.' + suffix) : reason;
  }

  // Pill label for a zeroed item; the threshold reason composes the row's own category
  // ("category D — below threshold") so the generic reason code still reads specifically.
  function zeroReasonLabel(item) {
    var suffix = ZERO_REASON_KEY[item.zeroReason];
    // EXCLUDED_VENUE and NOT_IN_TOP_N have no short pill of their own — they fall through to "why?",
    // whose tooltip carries the full sentence.
    var label = (suffix && suffix !== 'excludedVenue' && suffix !== 'notInTopN')
      ? t('report.dash.zeroLabel.' + suffix)
      : t('report.dash.zeroLabel.unknown');
    if (item.zeroReason === 'SCORE_BELOW_FORMULA_THRESHOLD') {
      var cat = item.coreRankingEquivalent || item.quarter;
      if (cat) return t('report.dash.zeroLabel.category', cat === 'A_STAR' ? 'A*' : cat, label);
    }
    // Several gates zeroed the item together: name them ("revistă cu taxă + sub prag") instead of
    // the vague umbrella — the APC cause was invisible behind "mai multe condiții" before this.
    if (item.zeroReason === 'MULTIPLE_GATES' && item.gateCauses) {
      var parts = String(item.gateCauses).split(',').map(function (cause) {
        var key = ZERO_REASON_KEY[cause.trim()];
        return key ? t('report.dash.zeroLabel.' + key) : cause.trim();
      });
      if (parts.length) return parts.join(' + ');
    }
    return label;
  }

  /** APC/fee-journal venue badge — a venue FACT, rendered whether or not any gate fired. */
  function feeBadge(item) {
    if (!item || !item.feeJournal) return '';
    return '<span class="eval-fee-badge" title="' + esc(t('report.dash.apcBadgeHint')) + '">' +
      esc(t('report.dash.apcBadge')) + '</span>';
  }

  // Short label for the forum link: first 10 chars + ellipsis; the full name lives in the tooltip.
  function forumExcerpt(name) {
    if (!name) return t('report.dash.forumFallback');
    var trimmed = String(name).trim();
    return trimmed.length > 11 ? trimmed.slice(0, 10).replace(/[\s,.:;-]+$/, '') + '…' : trimmed;
  }

  /**
   * Full-width evidence table for the workbench pane. Titles link to /publications/{id} and
   * forums to /forums/{id} when the payload carries ids; citation-mode rows keep the
   * click-to-drill behavior of the modal. Activities keep the compact list renderer — they
   * have no links or forum columns.
   */
  function renderEvidenceTable(items, outputMode, indicatorId, totalScore) {
    if (outputMode === 'activities') {
      return renderDetailList(items, outputMode, indicatorId);
    }
    if (!items || items.length === 0) {
      if (toNumber(totalScore) > 0) {
        // Provisional (identity-scored) runs persist correct totals but no per-item breakdown —
        // saying "no items" under a non-zero total reads as a contradiction.
        return '<p class="app-eval-muted mt-2">' + esc(t('report.dash.totalsOnly')) + '</p>';
      }
      return '<p class="app-eval-muted mt-2">' + esc(t('report.dash.noItems')) + '</p>';
    }
    var isCitations = outputMode === 'citations';

    function buildRow(item, mismatch) {
      var isZeroScored = !isCitations && toNumber(item.authorScore) <= 0;
      var rowAttrs = '';
      if (isCitations && indicatorId) {
        rowAttrs = ' class="app-eval-evidence-table__row--clickable" role="button" tabindex="0"' +
          ' data-citation-pub="' + esc(item.key) + '"' +
          ' data-indicator-id="' + esc(indicatorId) + '"' +
          ' aria-label="View citations for ' + esc(item.key) + '"';
      } else if (mismatch) {
        rowAttrs = ' class="app-eval-evidence-table__row--zero app-eval-evidence-table__row--mismatch" hidden';
      } else if (isZeroScored) {
        // Zero-scored rows stay in the payload for transparency but are collapsed behind the
        // "cu punctaj 0" toggle below the table — the counted list is the default reading.
        rowAttrs = ' class="app-eval-evidence-table__row--zero app-eval-evidence-table__row--zeroitem" hidden';
      }
      var row = '<tr' + rowAttrs + '>';

      // Publication title — linked when the payload resolved a publication id.
      row += '<td class="app-eval-evidence-table__title">';
      if (item.publicationId && !isCitations) {
        row += '<a href="/publications/' + encodeURIComponent(item.publicationId) + '"' +
          ' title="' + esc(item.key) + '">' + esc(item.key) + '</a>';
      } else {
        row += '<span title="' + esc(item.key) + '">' + esc(item.key) + '</span>';
      }
      row += '</td>';

      row += '<td>' + (item.year ? item.year : '—') + '</td>';

      row += '<td class="app-eval-evidence-table__rank">';
      if (item.quarter) row += rankBadge(item.quarter);
      if (item.coreRankingEquivalent && item.coreRankingEquivalent !== item.quarter) {
        row += rankBadge(item.coreRankingEquivalent);
      }
      row += feeBadge(item);
      if (item.scoringSource) {
        row += '<span class="eval-scored-item__meta-text">' + esc(item.scoringSource) + '</span>';
      }
      row += '</td>';

      row += '<td class="app-eval-evidence-table__forum">';
      if (item.forumId) {
        var forumLabel = forumExcerpt(item.forumName);
        var forumTitle = item.forumName ? item.forumName : t('report.dash.openForum');
        row += '<a href="/forums/' + encodeURIComponent(item.forumId) + '" title="' + esc(forumTitle) + '">' +
          esc(forumLabel) + '</a>';
      } else {
        row += '<span class="app-eval-muted">—</span>';
      }
      row += '</td>';

      row += '<td class="app-eval-evidence-table__num">';
      if (item.zeroReason) {
        var reason = zeroReasonCopy(item.zeroReason);
        row += '<span class="eval-scored-item__zero-flag" title="' + esc(reason) + '">' + esc(zeroReasonLabel(item)) + ' ' +
          '<i class="fa-solid fa-circle-info fa-xs" aria-hidden="true"></i></span> ';
      } else if (isZeroScored) {
        row += '<span class="eval-scored-item__zero-flag" title="' + esc(t('report.dash.formulaCutoffHint')) + '">' + esc(t('report.dash.formulaCutoff')) + '</span> ';
      }
      row += '<strong>' + toNumber(item.authorScore).toFixed(2) + '</strong></td>';

      if (_selfMode) {
        row += '<td class="app-eval-evidence-table__actions">';
        if (item.publicationId && !isCitations) {
          row += '<a href="/user/workspace#publications" title="' + esc(t('report.dash.manageHint')) + '">'
            + esc(t('report.dash.manage')) + '</a>';
        }
        row += '</td>';
      }
      row += '</tr>';
      return row;
    }

    // Stable partition: counted first, then visible zeros (with their reasons), then
    // wrong-venue-type rows — hidden by default behind the toggle below the table.
    var counted = [], zeros = [], mismatched = [];
    items.forEach(function (item) {
      if (!isCitations && item.zeroReason === 'VENUE_TYPE_MISMATCH') mismatched.push(item);
      else if (!isCitations && toNumber(item.authorScore) <= 0) zeros.push(item);
      else counted.push(item);
    });

    // Default order: category descending (A*, A, B, C, D, unclassified), points descending within a
    // category — the standard's own reading order, and it makes the intermediate thresholds (A*+A+B
    // subtotals) legible at a glance. Clicking a sort header still re-sorts by year/points.
    function categoryOrder(item) {
      switch (item.coreRankingEquivalent) {
        case 'A_STAR': return 0;
        case 'A': return 1;
        case 'B': return 2;
        case 'C': return 3;
        case 'D': return 4;
        default: return 5;
      }
    }
    function byCategoryThenPoints(a, b) {
      return categoryOrder(a) - categoryOrder(b)
          || toNumber(b.authorScore) - toNumber(a.authorScore);
    }
    counted.sort(byCategoryThenPoints);
    zeros.sort(byCategoryThenPoints);

    var html = '';
    if (!isCitations && (zeros.length > 0 || mismatched.length > 0)) {
      html += '<div class="app-eval-evidence-summary">' +
        esc(tp('report.dash.summary.counted', counted.length)) +
        (mismatched.length > 0 ? ' · ' + esc(tp('report.dash.summary.elsewhere', mismatched.length)) : '') +
        (zeros.length > 0 ? ' · ' + esc(tp('report.dash.summary.zero', zeros.length)) : '') +
        '</div>';
    }

    html += '<table class="app-eval-evidence-table"><thead><tr>' +
      '<th scope="col">' + esc(t(isCitations ? 'report.dash.col.publicationCitations' : 'report.dash.col.publication')) + '</th>' +
      '<th scope="col" data-sort-key="year" tabindex="0" role="button" title="' + esc(t('report.dash.sortByYear')) + '">'
        + esc(t('report.dash.col.year')) + '</th>' +
      '<th scope="col">' + esc(t('report.dash.col.rank')) + '</th>' +
      '<th scope="col">' + esc(t('report.dash.col.forum')) + '</th>' +
      '<th scope="col" class="app-eval-evidence-table__num" data-sort-key="points" tabindex="0" role="button" title="'
        + esc(t('report.dash.sortByPoints')) + '">' + esc(t('report.dash.col.points')) + '</th>' +
      (_selfMode ? '<th scope="col"><span class="sr-only">' + esc(t('report.dash.col.actions')) + '</span></th>' : '') +
      '</tr></thead><tbody>';

    counted.forEach(function (item) { html += buildRow(item, false); });
    zeros.forEach(function (item) { html += buildRow(item, false); });
    mismatched.forEach(function (item) { html += buildRow(item, true); });

    html += '</tbody></table>';

    if (!isCitations && zeros.length > 0) {
      // Same pattern as the venue-mismatch toggle: the rows exist for transparency (each carries its
      // zero-reason pill and APC badge), but the default view is the counted list only.
      html += '<button type="button" class="app-eval-zero-toggle" aria-expanded="false">' +
        esc(tp('report.dash.zeroToggle', zeros.length, t('report.dash.mismatch.show'))) +
        '</button>';
    }

    if (mismatched.length > 0) {
      // Name where they went, not just that they differ. A reviewer scanning a conference indicator
      // sees journals at the foot of the list and cannot tell whether they were dropped or moved;
      // "counted under the indicator for their own venue type" answers that without opening a tooltip.
      // The trailing show/hide verb is a separate key so the toggle handler can swap just that word
      // without re-deriving the whole sentence — see initMismatchToggle().
      html += '<button type="button" class="app-eval-mismatch-toggle" aria-expanded="false">' +
        esc(tp('report.dash.mismatch', mismatched.length, t('report.dash.mismatch.show'))) +
        '</button>';
    }
    return html;
  }

  // Client-side sort for the Year/Points columns (delegated — tables are re-rendered on every
  // detail fetch, so the handler lives on the root). First click sorts descending (newest / most
  // points first); clicking again flips the direction. Hidden venue-mismatch rows keep their
  // hidden state wherever they land.
  function initEvidenceSort(root) {
    function sortBy(th) {
      var table = th.closest('table');
      var tbody = table ? table.querySelector('tbody') : null;
      if (!tbody) return;
      var descending = th.getAttribute('aria-sort') !== 'descending';
      table.querySelectorAll('th[data-sort-key]').forEach(function (h) { h.removeAttribute('aria-sort'); });
      th.setAttribute('aria-sort', descending ? 'descending' : 'ascending');
      var cellIndex = th.cellIndex;
      var usePoints = th.getAttribute('data-sort-key') === 'points';
      var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
      function value(row) {
        var cell = row.cells[cellIndex];
        if (!cell) return -Infinity;
        var el = usePoints ? cell.querySelector('strong') : null;
        var n = parseFloat(String((el || cell).textContent).replace(/[^0-9.\-]/g, ''));
        return isNaN(n) ? -Infinity : n;
      }
      rows.sort(function (a, b) { return descending ? value(b) - value(a) : value(a) - value(b); });
      rows.forEach(function (row) { tbody.appendChild(row); });
    }
    root.addEventListener('click', function (e) {
      var th = e.target.closest('.app-eval-evidence-table th[data-sort-key]');
      if (th) sortBy(th);
    });
    root.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      var th = e.target.closest ? e.target.closest('.app-eval-evidence-table th[data-sort-key]') : null;
      if (th) { e.preventDefault(); sortBy(th); }
    });
  }

  // Reveal/hide the collapsed rows of one indicator's evidence table — zero-scored rows and
  // wrong-venue-type rows each have their own toggle (delegated: tables are re-rendered on every
  // detail fetch, so the handler lives on the root). The toggles are SIBLINGS after the table, so
  // the table is found by walking back rather than assuming previousElementSibling.
  function initMismatchToggles(root) {
    function tableBefore(btn) {
      var el = btn.previousElementSibling;
      while (el && !(el.matches && el.matches('table'))) el = el.previousElementSibling;
      return el;
    }
    root.addEventListener('click', function (e) {
      var zeroBtn = e.target.closest('.app-eval-zero-toggle');
      if (zeroBtn) {
        var zeroTable = tableBefore(zeroBtn);
        if (!zeroTable) return;
        var showZeros = zeroBtn.getAttribute('aria-expanded') !== 'true';
        var zeroRows = zeroTable.querySelectorAll('.app-eval-evidence-table__row--zeroitem');
        zeroRows.forEach(function (row) { row.hidden = !showZeros; });
        zeroBtn.setAttribute('aria-expanded', String(showZeros));
        zeroBtn.textContent = tp('report.dash.zeroToggle', zeroRows.length,
            t(showZeros ? 'report.dash.mismatch.hide' : 'report.dash.mismatch.show'));
        return;
      }
      var btn = e.target.closest('.app-eval-mismatch-toggle');
      if (!btn) return;
      var table = tableBefore(btn);
      if (!table) return;
      var show = btn.getAttribute('aria-expanded') !== 'true';
      table.querySelectorAll('.app-eval-evidence-table__row--mismatch').forEach(function (row) {
        row.hidden = !show;
      });
      btn.setAttribute('aria-expanded', String(show));
      // Rebuild the label from the count rather than string-replacing the trailing verb: the old
      // /show$/ swap only worked because the sentence ended in an English word.
      var count = table.querySelectorAll('.app-eval-evidence-table__row--mismatch').length;
      var verb = t(show ? 'report.dash.mismatch.hide' : 'report.dash.mismatch.show');
      btn.textContent = tp('report.dash.mismatch', count, verb);
    });
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
            '<span class="indicator-detail-total">' + esc(t('report.dash.indicatorTotal')) + ' <strong>'
              + toNumber(data.totalScore).toFixed(2) + '</strong>' + indicatorDeltaHtml + '</span>' +
            '<span class="indicator-detail-updated">' + esc(t('report.dash.updated')) + ' ' + esc(data.updatedAt ? data.updatedAt.substring(0, 10) : '—') + '</span>' +
            '</div>';
          content.innerHTML = headerHtml + renderEvidenceTable(data.items, data.outputMode, data.indicatorId, data.totalScore);
        }
      })
      .catch(function (err) {
        if (skeleton) skeleton.hidden = true;
        if (content) {
          content.innerHTML = '<p class="app-eval-muted text-danger mt-2">Failed to load detail. ' + esc(err.message) + '</p>';
        }
      });
  }

  /**
   * Collapse/expand one indicator's evidence list.
   *
   * The collapsed flag lives as a CLASS on the section, not as `hidden` on the panel: `selectCriterion`
   * owns `panel.hidden` (it un-hides every panel in a criterion to trigger the eager load), so using the
   * same flag for both would silently re-expand everything on each criterion switch.
   */
  function setIndicatorCollapsed(section, collapsed) {
    if (!section) return;
    section.classList.toggle('is-collapsed', collapsed);
    var btn = section.querySelector('.app-eval-indicator__name');
    if (btn) btn.setAttribute('aria-expanded', String(!collapsed));
  }

  function expandIndicator(section) {
    setIndicatorCollapsed(section, false);
  }

  // Clicking an indicator name collapses/expands its evidence list, anchors the URL to it
  // (deep-linkable) and — when expanding — scrolls the section into view. A criterion can carry several
  // indicators of sixty-plus rows each, so "always expanded" made the pane a wall of tables.
  function initIndicatorHashClicks(root) {
    root.addEventListener('click', function (e) {
      var btn = e.target.closest('.app-eval-indicator__name');
      if (!btn) return;
      var indicatorId = btn.getAttribute('data-indicator-id');
      if (!indicatorId) return;
      var section = btn.closest('.app-eval-indicator');
      var collapsing = btn.getAttribute('aria-expanded') !== 'false';
      setIndicatorCollapsed(section, collapsing);
      history.replaceState(null, '', '#indicator-' + indicatorId);
      // Scrolling a section the user just collapsed would yank the page for no reason.
      if (!collapsing && section) section.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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
    'ASIST_C':    'Asist. Cercetare'
    // OTHER is resolved through t() in positionLabel() — the rest are academic titles, identical in
    // both languages, so they stay literals rather than becoming twenty pass-through keys.
  };

  /** Display label for a position code; OTHER is the only one that differs between languages. */
  function positionLabel(pos) {
    if (pos === 'OTHER') return t('report.dash.positionOther');
    return POSITION_LABELS[pos] || pos;
  }

  /** Returns {met, total} for criteria applicable to the given position (from the thresholds JSON). */
  function computeCriteriaMet(position) {
    // H95: TOP-LEVEL units — perspectives applicable at the position, plus criteria not bundled into
    // any perspective. Without perspectives this is exactly the pre-H95 count.
    var met = 0, total = 0;
    _thresholds.forEach(function (entry) {
      if (!entry || _bundledCriteria[entry.index]) return;
      var threshold = thresholdByPosition(entry.index, position);
      if (threshold == null) return; // no threshold for this position — criterion not applicable
      total++;
      if (criterionScore(entry.index, position) >= threshold) met++;
    });
    _perspectives.forEach(function (entry) {
      var verdict = perspectiveVerdict(entry, position);
      if (verdict === null) return;
      total++;
      if (verdict) met++;
    });
    return { met: met, total: total };
  }

  /**
   * H99 item 1 (Florin Fortis): the header "PUNCTAJ TOTAL" was server-rendered once with the
   * CANONICAL sum while the Total criterion tile showed the position-effective value (e.g. the
   * Conf/Prof D-gate cut) — two different totals on one page, 1361,92 vs 1355,67. The header now
   * recomputes from the same position-effective criterion scores the tiles use, so the two can
   * never disagree. With no position selected every lookup falls back to the canonical score and
   * the value equals what the server rendered.
   */
  function updateTotalScore(position) {
    var el = document.getElementById('eval-total-score-value');
    if (!el) return; // report has no contributesToTotal criteria — the cell isn't rendered
    var total = 0, any = false;
    _thresholds.forEach(function (entry) {
      if (!entry || !entry.contributesToTotal) return;
      any = true;
      total += criterionScore(entry.index, position);
    });
    if (!any) return;
    // Mirror Thymeleaf's #numbers.formatDecimal(totalScore, 1, 2): two decimals, locale decimal
    // separator, no thousands grouping.
    var locale = (window.appLocale === 'ro') ? 'ro-RO' : 'en-US';
    el.textContent = total.toLocaleString(locale, {
      minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false
    });
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
      entry.thresholds.forEach(function (th) {
        if (th.position && !seen[th.position]) {
          seen[th.position] = true;
          positions.push(th.position);
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
    renderPerspectiveVerdicts(root, position);
    updateCriteriaMet(position);
    updateTotalScore(position);
    var active = root.querySelector('.app-eval-rail__tile.is-active');
    if (active) {
      var activeIdx = parseInt(active.getAttribute('data-criterion-index'), 10);
      updateEvidenceHeader(root, activeIdx, position);
      updateSparkline(root, activeIdx, position);
    }
    renderNearMisses(root, position);
  }

  function initPositionSelector(root, researcherPosition) {
    _position = researcherPosition || '';
    var select = document.querySelector('[data-eval-position-selector]');
    var positions = reportPositions();

    if (!select) return; // no selector on the page (e.g. no thresholds to switch between)

    if (positions.length === 0) {
      // No thresholds at all — hide the selector cell
      var cell = select.closest('.app-eval-aggregate__cell');
      if (cell) cell.hidden = true;
      return;
    }

    // A supervisor arriving from the promotion board carries the TARGET position in the URL — open on
    // that, since "does she clear CONF_UNIV" is the question that was clicked. Falls back to the
    // researcher's own position for a normal visit.
    var initial = root.getAttribute('data-initial-position') || '';
    var preferred = initial || researcherPosition;

    var seen = {};
    positions.forEach(function (pos) {
      seen[pos] = true;
      var opt = document.createElement('option');
      opt.value = pos;
      opt.textContent = positionLabel(pos);
      if (pos === preferred) opt.selected = true;
      select.appendChild(opt);
    });

    // If the preferred position isn't among the report's thresholds, pick the first available
    if (!seen[preferred]) {
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
        // A deep link to an indicator must show it, whatever its collapse state would otherwise be.
        expandIndicator(indicator);
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
      aggEl.setAttribute('aria-label', t('report.dash.scoreDeltaAria',
        deltaAriaLabel(d.delta), toNumber(d.before).toFixed(2), toNumber(d.after).toFixed(2)));
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
    var defaultName = t('report.dash.snapshot.defaultName', new Date().toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    }));
    var name = window.prompt(t('report.dash.snapshot.namePrompt'), defaultName);
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
      _showSnapshotFeedback(t('report.dash.snapshot.saved', snapshot.name), false);
      var panel = document.getElementById('eval-snapshots-panel');
      if (panel && !panel.hidden) _loadSnapshotList(root, reportId, currentRunId);
      _loadSnapshotsForPicker(reportId);
    })
    .catch(function (err) {
      if (saveBtn) saveBtn.disabled = false;
      if (err.message === 'limit') {
        _showSnapshotFeedback(t('report.dash.snapshot.limit'), true);
      } else {
        _showSnapshotFeedback(t('report.dash.snapshot.saveFailed'), true);
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
      body.innerHTML = '<p class="app-eval-snapshots-panel__empty">' + esc(t('report.dash.snapshot.loadFailed')) + '</p>';
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
            title: t('report.dash.snapshot.deleteTitle'),
            body: t('report.dash.snapshot.deleteBody', snapName),
            confirmLabel: t('report.dash.snapshot.deleteConfirm'),
            tone: 'danger',
            onConfirm: function () { _deleteSnapshot(body, snapId, root, reportId, currentRunId); },
          });
        } else {
          if (!confirm(t('report.dash.snapshot.deleteBody', snapName))) return;
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
    .catch(function () { _showSnapshotFeedback(t('report.dash.snapshot.deleteFailed'), true); });
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
    group.label = t('report.dash.snapshot.group');
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
    readPerspectives();
    groupRailByPerspectives(root);
    initPositionSelector(root, researcherPosition);
    renderRail(root, _position);
    renderPerspectiveVerdicts(root, _position);
    updateTotalScore(_position);
    renderNearMisses(root, _position);
    initRail(root);
    initIndicatorHashClicks(root);
    initMismatchToggles(root);
    initEvidenceSort(root);
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
