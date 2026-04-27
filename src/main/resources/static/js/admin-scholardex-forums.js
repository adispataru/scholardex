(function () {
  const state = {
    page: 0,
    size: 25,
    sort: 'publicationName',
    direction: 'asc',
    q: '',
    wos: 'all'
  };

  let totalPages = 0;
  let totalItems = 0;
  let searchDebounce = null;

  const els = {
    search: document.getElementById('admin-forums-search'),
    sort: document.getElementById('admin-forums-sort'),
    direction: document.getElementById('admin-forums-direction'),
    wos: document.getElementById('admin-forums-wos'),
    size: document.getElementById('admin-forums-size'),
    loading: document.getElementById('admin-forums-loading'),
    error: document.getElementById('admin-forums-error'),
    empty: document.getElementById('admin-forums-empty'),
    tableBody: document.getElementById('admin-forums-table-body'),
    pageInfo: document.getElementById('admin-forums-page-info'),
    totalInfo: document.getElementById('admin-forums-total-info'),
    prev: document.getElementById('admin-forums-prev'),
    next: document.getElementById('admin-forums-next')
  };

  const editEls = {
    modal: document.getElementById('editForumModal'),
    form: document.getElementById('edit-forum-form'),
    feedback: document.getElementById('edit-forum-feedback'),
    save: document.getElementById('edit-forum-save'),
    id: document.getElementById('edit-forum-id'),
    publicationName: document.getElementById('edit-forum-publication-name'),
    issn: document.getElementById('edit-forum-issn'),
    eIssn: document.getElementById('edit-forum-eissn'),
    isbn: document.getElementById('edit-forum-isbn'),
    aggregationType: document.getElementById('edit-forum-aggregation-type'),
    publisher: document.getElementById('edit-forum-publisher')
  };

  function labelWosStatus(status) {
    switch (status) {
      case 'indexed':
        return 'WoS indexed';
      case 'not_indexed':
        return 'Not indexed by WoS';
      case 'not_applicable':
        return 'Not applicable';
      default:
        return status || '';
    }
  }

  function renderWosBadge(status) {
    const label = escapeHtml(labelWosStatus(status));
    let modifier = 'app-table-badge--warning';
    if (status === 'indexed') {
      modifier = 'app-table-badge--success';
    } else if (status === 'not_applicable') {
      modifier = '';
    }
    const classes = ['app-table-badge'];
    if (modifier) {
      classes.push(modifier);
    }
    return '<span class="' + classes.join(' ') + '">' + label + '</span>';
  }

  function setLoading(isLoading) {
    els.loading.classList.toggle('d-none', !isLoading);
  }

  function setError(message) {
    els.error.textContent = message || '';
    els.error.classList.toggle('d-none', !message);
  }

  function setEmpty(isEmpty) {
    els.empty.classList.toggle('d-none', !isEmpty);
  }

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
      const id = encodeURIComponent(item.id || '');
      const pubsHref = '/admin/scholardex/publications?forumId=' + id;
      return '<tr>' +
        '<td>' + escapeHtml(item.publicationName) + '</td>' +
        '<td class="app-table__cell--identifier">' + escapeHtml(item.issn || '—') + '</td>' +
        '<td class="app-table__cell--identifier">' + escapeHtml(item.eIssn || '—') + '</td>' +
        '<td>' + escapeHtml(item.aggregationType) + '</td>' +
        '<td>' + renderWosBadge(item.wosStatus) + '</td>' +
        '<td><div class="app-admin-actions">' +
          '<a class="btn btn-outline-secondary btn-sm" href="' + pubsHref + '" aria-label="View publications in this forum"><i class="fa-solid fa-file-lines fa-xs"></i> Publications</a>' +
          '<button class="btn btn-outline-secondary btn-sm" type="button" data-edit-forum-id="' + id + '" aria-label="Edit this forum">Edit</button>' +
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
    params.set('wos', state.wos);
    if (state.q) {
      params.set('q', state.q);
    }
    return '/forums/data?' + params.toString();
  }

  function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (!token || !header) {
      return {};
    }
    return { [header.getAttribute('content')]: token.getAttribute('content') };
  }

  function showToast(message, tone) {
    if (window.appToast) {
      window.appToast.show({ message: message, tone: tone || 'info' });
      return;
    }
    if (editEls.feedback) {
      editEls.feedback.textContent = message;
    }
  }

  function setEditBusy(isBusy) {
    if (editEls.save) {
      editEls.save.disabled = isBusy;
      editEls.save.textContent = isBusy ? 'Saving...' : 'Save changes';
    }
  }

  function setField(input, value) {
    if (input) {
      input.value = value == null ? '' : value;
    }
  }

  function populateEditForm(forum) {
    setField(editEls.id, forum.id);
    setField(editEls.publicationName, forum.publicationName);
    setField(editEls.issn, forum.issn);
    setField(editEls.eIssn, forum.eissn || forum.eIssn);
    setField(editEls.isbn, forum.isbn);
    setField(editEls.aggregationType, forum.aggregationType);
    setField(editEls.publisher, forum.publisher);
    if (editEls.form) {
      editEls.form.action = '/admin/scholardex/forums/edit/' + encodeURIComponent(forum.id || '');
    }
    if (editEls.feedback) {
      editEls.feedback.textContent = '';
    }
  }

  async function openEditModal(id, trigger) {
    if (!editEls.modal || !editEls.form) {
      window.location.href = '/admin/scholardex/forums/edit/' + encodeURIComponent(id);
      return;
    }
    try {
      const response = await fetch('/admin/scholardex/forums/' + encodeURIComponent(id) + '/edit-data', {
        headers: { Accept: 'application/json' }
      });
      if (!response.ok) {
        throw new Error('Could not load forum details.');
      }
      const forum = await response.json();
      populateEditForm(forum);
      if (window.appModal) {
        window.appModal.open('editForumModal', { trigger: trigger });
      } else if (window.$ && window.$.fn && window.$.fn.modal) {
        window.$(editEls.modal).modal('show');
      }
    } catch (error) {
      showToast(error.message || 'Could not load forum details.', 'error');
    }
  }

  async function saveEditForm(event) {
    event.preventDefault();
    if (!editEls.form || !editEls.id || !editEls.id.value) {
      return;
    }
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
      if (!response.ok) {
        throw new Error('Forum could not be saved.');
      }
      if (window.appModal) {
        window.appModal.close('editForumModal');
      } else if (window.$ && window.$.fn && window.$.fn.modal) {
        window.$(editEls.modal).modal('hide');
      }
      showToast('Forum updated.', 'success');
      fetchPage();
    } catch (error) {
      showToast(error.message || 'Forum could not be saved.', 'error');
      if (editEls.feedback) {
        editEls.feedback.textContent = error.message || 'Forum could not be saved.';
      }
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
      if (!response.ok) {
        throw new Error('Unexpected error while loading forums.');
      }
      const body = await response.json();
      totalItems = Number(body.totalItems || 0);
      totalPages = Number(body.totalPages || 0);
      state.page = Number(body.page || 0);
      state.size = Number(body.size || state.size);
      renderRows(body.items || []);
      setEmpty((body.items || []).length === 0);
      updatePager();
    } catch (error) {
      setError(error.message || 'Could not load forums.');
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
      if (searchDebounce) {
        clearTimeout(searchDebounce);
      }
      searchDebounce = setTimeout(function () {
        state.q = value;
        state.page = 0;
        fetchPage();
      }, 300);
    });

    [els.sort, els.direction, els.wos, els.size].forEach(function (element) {
      element.addEventListener('change', function () {
        state.sort = els.sort.value;
        state.direction = els.direction.value;
        state.wos = els.wos.value;
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
      const button = event.target.closest('[data-edit-forum-id]');
      if (!button) {
        return;
      }
      event.preventDefault();
      openEditModal(decodeURIComponent(button.getAttribute('data-edit-forum-id') || ''), button);
    });

    if (editEls.form) {
      editEls.form.addEventListener('submit', saveEditForm);
    }
  }

  function initialize() {
    if (Object.values(els).some(function (value) { return !value; })) {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    if (params.has('wos')) {
      state.wos = params.get('wos');
      els.wos.value = state.wos;
    }
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
