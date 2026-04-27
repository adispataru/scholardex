(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'name',
    direction: 'asc',
    q: ''
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('admin-affiliations-search'),
    sort: document.getElementById('admin-affiliations-sort'),
    direction: document.getElementById('admin-affiliations-direction'),
    size: document.getElementById('admin-affiliations-size'),
    loading: document.getElementById('admin-affiliations-loading'),
    error: document.getElementById('admin-affiliations-error'),
    empty: document.getElementById('admin-affiliations-empty'),
    tableBody: document.getElementById('admin-affiliations-table-body'),
    pageInfo: document.getElementById('admin-affiliations-page-info'),
    totalInfo: document.getElementById('admin-affiliations-total-info'),
    prev: document.getElementById('admin-affiliations-prev'),
    next: document.getElementById('admin-affiliations-next')
  };

  const editEls = {
    modal: document.getElementById('editAffiliationModal'),
    form: document.getElementById('edit-affiliation-form'),
    feedback: document.getElementById('edit-affiliation-feedback'),
    save: document.getElementById('edit-affiliation-save'),
    afid: document.getElementById('edit-affiliation-afid'),
    name: document.getElementById('edit-affiliation-name'),
    city: document.getElementById('edit-affiliation-city'),
    country: document.getElementById('edit-affiliation-country')
  };

  function setLoading(isLoading) { els.loading.classList.toggle('d-none', !isLoading); }
  function setError(message) {
    els.error.textContent = message || '';
    els.error.classList.toggle('d-none', !message);
  }
  function setEmpty(isEmpty) { els.empty.classList.toggle('d-none', !isEmpty); }

  function updatePager() {
    if (totalItems === 0) {
      els.pageInfo.textContent = 'Page 0 of 0';
      els.totalInfo.textContent = '0 results';
      els.prev.disabled = true;
      els.next.disabled = true;
      return;
    }
    els.pageInfo.textContent = 'Page ' + (state.page + 1) + ' of ' + totalPages;
    els.totalInfo.textContent = totalItems + ' results';
    els.prev.disabled = state.page <= 0;
    els.next.disabled = state.page >= totalPages - 1;
  }

  function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function renderRows(items) {
    els.tableBody.innerHTML = (items || []).map(function (item) {
      const id = encodeURIComponent(item.afid || '');
      const pubsHref = '/admin/scholardex/publications?affiliationId=' + id;
      return '<tr>' +
        '<td>' + escapeHtml(item.name) + '</td>' +
        '<td class="app-table__cell--identifier">' + escapeHtml(item.afid || '—') + '</td>' +
        '<td>' + escapeHtml(item.city || '—') + '</td>' +
        '<td>' + escapeHtml(item.country || '—') + '</td>' +
        '<td><div class="app-admin-actions">' +
          '<a class="btn btn-outline-secondary btn-sm" href="' + pubsHref + '" aria-label="View publications for this affiliation"><i class="fa-solid fa-file-lines fa-xs"></i> Publications</a>' +
          '<button class="btn btn-outline-secondary btn-sm" type="button" data-edit-affiliation-id="' + id + '" aria-label="Edit this affiliation">Edit</button>' +
        '</div></td>' +
        '</tr>';
    }).join('');
  }

  function buildUrl() {
    const params = new URLSearchParams();
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    params.set('sort', state.sort);
    params.set('direction', state.direction);
    if (state.q) params.set('q', state.q);
    return '/api/entities/affiliations?' + params.toString();
  }

  function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (!token || !header) return {};
    return { [header.getAttribute('content')]: token.getAttribute('content') };
  }

  function showToast(message, tone) {
    if (window.appToast) {
      window.appToast.show({ message: message, tone: tone || 'info' });
      return;
    }
    if (editEls.feedback) editEls.feedback.textContent = message;
  }

  function setEditBusy(isBusy) {
    if (editEls.save) {
      editEls.save.disabled = isBusy;
      editEls.save.textContent = isBusy ? 'Saving...' : 'Save changes';
    }
  }

  function setField(input, value) {
    if (input) input.value = value == null ? '' : value;
  }

  function populateEditForm(affiliation) {
    const id = affiliation.afid || affiliation.id || '';
    setField(editEls.afid, id);
    setField(editEls.name, affiliation.name);
    setField(editEls.city, affiliation.city);
    setField(editEls.country, affiliation.country);
    if (editEls.form) {
      editEls.form.action = '/admin/scholardex/affiliations/edit/' + encodeURIComponent(id);
    }
    if (editEls.feedback) editEls.feedback.textContent = '';
  }

  async function openEditModal(id, trigger) {
    if (!editEls.modal || !editEls.form) {
      window.location.href = '/admin/scholardex/affiliations/edit/' + encodeURIComponent(id);
      return;
    }
    try {
      const response = await fetch('/admin/scholardex/affiliations/' + encodeURIComponent(id) + '/edit-data', {
        headers: { Accept: 'application/json' }
      });
      if (!response.ok) throw new Error('Could not load affiliation details.');
      populateEditForm(await response.json());
      if (window.appModal) {
        window.appModal.open('editAffiliationModal', { trigger: trigger });
      } else if (window.$ && window.$.fn && window.$.fn.modal) {
        window.$(editEls.modal).modal('show');
      }
    } catch (error) {
      showToast(error.message || 'Could not load affiliation details.', 'error');
    }
  }

  async function saveEditForm(event) {
    event.preventDefault();
    if (!editEls.form || !editEls.afid || !editEls.afid.value) return;
    setEditBusy(true);
    try {
      const response = await fetch(editEls.form.action, {
        method: 'POST',
        headers: {
          Accept: 'text/html',
          'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
          ...csrfHeaders()
        },
        body: new URLSearchParams(new FormData(editEls.form)).toString()
      });
      if (!response.ok) throw new Error('Affiliation could not be saved.');
      if (window.appModal) {
        window.appModal.close('editAffiliationModal');
      } else if (window.$ && window.$.fn && window.$.fn.modal) {
        window.$(editEls.modal).modal('hide');
      }
      showToast('Affiliation updated.', 'success');
      fetchPage();
    } catch (error) {
      showToast(error.message || 'Affiliation could not be saved.', 'error');
      if (editEls.feedback) editEls.feedback.textContent = error.message || 'Affiliation could not be saved.';
    } finally {
      setEditBusy(false);
    }
  }

  async function fetchPage() {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(buildUrl(), { headers: { Accept: 'application/json' } });
      if (response.status === 400) {
        const body = await response.json().catch(function () { return {}; });
        throw new Error(body.message || 'Invalid filter parameters.');
      }
      if (!response.ok) throw new Error('Unexpected error while loading affiliations.');
      const body = await response.json();
      totalItems = Number(body.totalItems || 0);
      totalPages = Number(body.totalPages || 0);
      state.page = Number(body.page || 0);
      state.size = Number(body.size || state.size);
      renderRows(body.items || []);
      setEmpty((body.items || []).length === 0);
      updatePager();
    } catch (error) {
      setError(error.message || 'Could not load affiliations.');
      els.tableBody.innerHTML = '';
      totalItems = 0;
      totalPages = 0;
      setEmpty(false);
      updatePager();
    } finally {
      setLoading(false);
    }
  }

  function bindEvents() {
    els.search.addEventListener('input', function () {
      const value = els.search.value.trim();
      if (searchDebounce) clearTimeout(searchDebounce);
      searchDebounce = setTimeout(function () {
        state.q = value;
        state.page = 0;
        fetchPage();
      }, 300);
    });
    [els.sort, els.direction, els.size].forEach(function (element) {
      element.addEventListener('change', function () {
        state.sort = els.sort.value;
        state.direction = els.direction.value;
        state.size = Number(els.size.value);
        state.page = 0;
        fetchPage();
      });
    });
    els.prev.addEventListener('click', function () {
      if (state.page <= 0) return;
      state.page -= 1;
      fetchPage();
    });
    els.next.addEventListener('click', function () {
      if (state.page >= totalPages - 1) return;
      state.page += 1;
      fetchPage();
    });
    els.tableBody.addEventListener('click', function (event) {
      const button = event.target.closest('[data-edit-affiliation-id]');
      if (!button) return;
      event.preventDefault();
      openEditModal(decodeURIComponent(button.getAttribute('data-edit-affiliation-id') || ''), button);
    });
    if (editEls.form) {
      editEls.form.addEventListener('submit', saveEditForm);
    }
  }

  function initialize() {
    if (Object.values(els).some(function (value) { return !value; })) return;
    bindEvents();
    updatePager();
    fetchPage();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
