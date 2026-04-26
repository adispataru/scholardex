(function () {
  'use strict';

  /* ── Relative-time formatting ─────────────────────────────────────────── */

  function relativeTime(isoString) {
    if (!isoString) return null;
    var diff = Math.round((Date.now() - new Date(isoString).getTime()) / 1000);
    if (diff < 5) return 'just now';
    if (diff < 3600) return Math.floor(diff / 60) + ' minutes ago';
    if (diff < 86400) return Math.floor(diff / 3600) + ' hours ago';
    return Math.floor(diff / 86400) + ' days ago';
  }

  document.querySelectorAll('[data-admin-ts]').forEach(function (el) {
    var rel = relativeTime(el.getAttribute('data-admin-ts'));
    if (rel) el.textContent = rel;
  });

  /* ── WoS enrichment "Run Now" confirmation + AJAX ─────────────────────── */

  var runBtn      = document.getElementById('wos-run-btn');
  var dialog      = document.getElementById('admin-confirm-dialog');
  var cancelBtn   = document.getElementById('admin-confirm-cancel');
  var okBtn       = document.getElementById('admin-confirm-ok');
  var feedback    = document.getElementById('wos-run-feedback');
  var badgeEl     = document.getElementById('wos-status-badge');
  var metaEl      = document.getElementById('wos-run-meta');

  if (runBtn && dialog) {
    runBtn.addEventListener('click', function () {
      dialog.hidden = false;
      cancelBtn.focus();
    });

    cancelBtn.addEventListener('click', function () {
      dialog.hidden = true;
      runBtn.focus();
    });

    dialog.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        dialog.hidden = true;
        runBtn.focus();
        return;
      }
      if (e.key === 'Tab') {
        var focusable = [cancelBtn, okBtn];
        var first = focusable[0];
        var last  = focusable[focusable.length - 1];
        if (e.shiftKey) {
          if (document.activeElement === first) { e.preventDefault(); last.focus(); }
        } else {
          if (document.activeElement === last)  { e.preventDefault(); first.focus(); }
        }
      }
    });

    okBtn.addEventListener('click', function () {
      dialog.hidden = true;
      setRunning();

      var csrfToken  = document.querySelector('meta[name="_csrf"]');
      var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
      var headers    = { 'Content-Type': 'application/json' };
      if (csrfToken && csrfHeader) {
        headers[csrfHeader.getAttribute('content')] = csrfToken.getAttribute('content');
      }

      fetch('/admin/initialization/wos/enrichment/run', {
        method: 'POST',
        headers: headers,
        credentials: 'same-origin'
      })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          return res.json();
        })
        .then(function (data) {
          var outcome = data.failed > 0 ? 'PARTIAL' : 'SUCCESS';
          updateBadge(outcome);
          var ts = data.completedAt;
          if (metaEl && ts) {
            metaEl.setAttribute('data-admin-ts', ts);
            var rel = relativeTime(ts);
            if (rel) metaEl.textContent = rel;
          }
          showFeedback(
            'success',
            'Enrichment complete — processed: ' + data.processed +
            ', computed: ' + data.computed +
            ', failed: ' + data.failed
          );
          runBtn.disabled = false;
        })
        .catch(function (err) {
          updateBadge('FAILED');
          showFeedback('danger', 'Enrichment failed: ' + err.message);
          runBtn.disabled = false;
        });
    });
  }

  function setRunning() {
    if (runBtn) runBtn.disabled = true;
    updateBadge('IN_PROGRESS');
    if (feedback) feedback.hidden = true;
  }

  function updateBadge(outcome) {
    if (!badgeEl) return;
    badgeEl.className = 'app-admin-status-badge ' + outcomeClass(outcome);
    badgeEl.textContent = outcomeLabel(outcome);
  }

  function showFeedback(type, msg) {
    if (!feedback) return;
    feedback.className = 'app-admin-ops-card__feedback app-admin-ops-card__feedback--' + type;
    feedback.textContent = msg;
    feedback.hidden = false;
  }

  function outcomeClass(outcome) {
    if (outcome === 'SUCCESS')     return 'app-admin-status-badge--success';
    if (outcome === 'FAILED')      return 'app-admin-status-badge--danger';
    if (outcome === 'PARTIAL' ||
        outcome === 'IN_PROGRESS') return 'app-admin-status-badge--warning';
    return 'app-admin-status-badge--muted';
  }

  function outcomeLabel(outcome) {
    if (outcome === 'SUCCESS')     return 'Success';
    if (outcome === 'FAILED')      return 'Failed';
    if (outcome === 'PARTIAL')     return 'Partial';
    if (outcome === 'IN_PROGRESS') return 'Running…';
    return 'Never run';
  }
})();
