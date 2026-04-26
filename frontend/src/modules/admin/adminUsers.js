/**
 * Admin Users page — edit modal with AJAX save and inline row re-render.
 *
 * Responsibilities:
 *  - Populates #editUserModal from row data-* attributes when the modal opens
 *  - Submits the edit form via fetch() to POST /admin/users/{email}/edit
 *  - On success, re-renders the relevant table row cells in-place (no page reload)
 *  - Shows a native confirm() guard when the user is removing PLATFORM_ADMIN
 *  - Bootstrap 4 handles Escape-to-close and focus trap natively
 */
export function initAdminUsers() {
    const modal = document.getElementById('editUserModal');
    if (!modal) return;

    /** Populated when the modal opens; used by _save() to target the right row. */
    let _currentEmail = null;
    let _triggerBtn = null;

    // ── Populate modal from row data-* on show ─────────────────────────────
    modal.addEventListener('show.bs.modal', function (event) {
        _triggerBtn = event.relatedTarget;
        if (!_triggerBtn) return;

        _currentEmail = _triggerBtn.dataset.email || '';
        const rolesStr = _triggerBtn.dataset.roles || '';
        const activeRoles = rolesStr.split(',').map(r => r.trim()).filter(Boolean);

        // Account section
        const emailDisplay = document.getElementById('edit-user-email-display');
        if (emailDisplay) emailDisplay.value = _currentEmail;

        // Role checkboxes
        modal.querySelectorAll('.edit-user-role-cb').forEach(cb => {
            cb.checked = activeRoles.includes(cb.value);
        });

        // Researcher profile fields
        _setField('edit-first-name', _triggerBtn.dataset.firstName || '');
        _setField('edit-last-name', _triggerBtn.dataset.lastName || '');
        _setField('edit-scholar-id', _triggerBtn.dataset.scholarId || '');
        _setField('edit-scopus-ids', _triggerBtn.dataset.scopusIds || '');
        _setField('edit-wos-ids', _triggerBtn.dataset.wosIds || '');
        const posSelect = document.getElementById('edit-position');
        if (posSelect) posSelect.value = _triggerBtn.dataset.position || '';

        // Clear previous feedback
        _setFeedback('', false);
    });

    // Restore focus to trigger button when modal closes
    modal.addEventListener('hidden.bs.modal', function () {
        if (_triggerBtn) _triggerBtn.focus();
        _triggerBtn = null;
        _currentEmail = null;
    });

    // ── Save button — AJAX submit ───────────────────────────────────────────
    const saveBtn = document.getElementById('edit-user-save-btn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function (e) {
            e.preventDefault();
            _save();
        });
    }

    // ── Helper: build request payload and POST ─────────────────────────────
    function _save() {
        if (!_currentEmail) return;

        const selectedRoles = Array.from(
            modal.querySelectorAll('.edit-user-role-cb:checked')
        ).map(cb => cb.value);

        // Confirmation guard: removing PLATFORM_ADMIN from a user who had it
        const hadAdmin = (_triggerBtn?.dataset.roles || '').includes('PLATFORM_ADMIN');
        const keepingAdmin = selectedRoles.includes('PLATFORM_ADMIN');
        if (hadAdmin && !keepingAdmin) {
            if (!window.confirm(
                'Remove PLATFORM_ADMIN from ' + _currentEmail + '?\n\n' +
                'This will revoke their admin access immediately.'
            )) {
                return;
            }
        }

        const scopusRaw = (_getField('edit-scopus-ids') || '').trim();
        const wosRaw = (_getField('edit-wos-ids') || '').trim();

        const payload = {
            roles: selectedRoles,
            profile: {
                firstName: _getField('edit-first-name'),
                lastName: _getField('edit-last-name'),
                scholarId: _getField('edit-scholar-id'),
                scopusIds: scopusRaw ? scopusRaw.split(',').map(s => s.trim()).filter(Boolean) : [],
                wosIds: wosRaw ? wosRaw.split(',').map(s => s.trim()).filter(Boolean) : [],
                position: _getField('edit-position') || null,
            },
        };

        _setFeedback('Saving…', false);
        _setSaveLoading(true);

        fetch('/admin/users/' + encodeURIComponent(_currentEmail) + '/edit', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        })
            .then(function (res) {
                if (!res.ok) throw new Error('Server returned ' + res.status);
                return res.json();
            })
            .then(function (data) {
                _rerenderRow(data);
                // Close modal after brief success flash
                _setFeedback('Saved.', false);
                setTimeout(function () {
                    var bsModal = typeof bootstrap !== 'undefined'
                        ? bootstrap.Modal.getInstance(modal)
                        : null;
                    if (bsModal) {
                        bsModal.hide();
                    } else if (window.$) {
                        window.$('#editUserModal').modal('hide');
                    }
                }, 600);
            })
            .catch(function (err) {
                _setFeedback('Save failed: ' + err.message, true);
            })
            .finally(function () {
                _setSaveLoading(false);
            });
    }

    // ── Re-render the affected table row ────────────────────────────────────
    function _rerenderRow(data) {
        // Find the row by email text cell
        const rows = document.querySelectorAll('#dataTable tbody tr');
        let targetRow = null;
        for (const tr of rows) {
            const emailCell = tr.querySelector('td:first-child');
            if (emailCell && emailCell.textContent.trim() === data.email) {
                targetRow = tr;
                break;
            }
        }
        if (!targetRow) return;

        const cells = targetRow.querySelectorAll('td');
        // col 0: email (unchanged)
        // col 1: name
        cells[1].textContent = (data.hasProfile && data.name) ? data.name : '';
        if (!data.hasProfile || !data.name) {
            cells[1].innerHTML = '<span class="app-admin-no-profile">—</span>';
        }
        // col 2: scholarId
        cells[2].innerHTML = (data.hasProfile && data.scholarId)
            ? '<span class="app-admin-id-pill">' + _esc(data.scholarId) + '</span>'
            : '<span class="app-admin-no-profile">—</span>';
        // col 3: scopusIds
        cells[3].innerHTML = (data.hasProfile && data.scopusIds && data.scopusIds.length)
            ? data.scopusIds.map(id => '<span class="app-admin-id-pill">' + _esc(id) + '</span>').join('')
            : '<span class="app-admin-no-profile">—</span>';
        // col 4: wosIds
        cells[4].innerHTML = (data.hasProfile && data.wosIds && data.wosIds.length)
            ? data.wosIds.map(id => '<span class="app-admin-id-pill">' + _esc(id) + '</span>').join('')
            : '<span class="app-admin-no-profile">—</span>';
        // col 5: roles
        cells[5].innerHTML = data.roles.length
            ? data.roles.map(r => '<span class="app-admin-role-badge' + _roleMod(r) + '">' + _esc(r) + '</span>').join('')
            : '<span class="app-admin-no-profile">—</span>';
        if (cells[5].querySelector('.app-admin-role-badge')) {
            const wrapper = document.createElement('div');
            wrapper.className = 'app-admin-roles-cell';
            wrapper.innerHTML = cells[5].innerHTML;
            cells[5].innerHTML = '';
            cells[5].appendChild(wrapper);
        }
        // col 6: status (unchanged unless locked state changed — lock/unlock uses separate form POSTs)
        // col 7: actions — update data-* attributes so re-opening the modal is accurate
        const editBtn = targetRow.querySelector('[data-email]');
        if (editBtn) {
            editBtn.dataset.roles = data.roles.join(',');
            editBtn.dataset.firstName = (data.hasProfile && data.name) ? data.name.split(' ')[0] : '';
            editBtn.dataset.lastName = (data.hasProfile && data.name) ? data.name.split(' ').slice(1).join(' ') : '';
            editBtn.dataset.scholarId = data.scholarId || '';
            editBtn.dataset.scopusIds = (data.scopusIds || []).join(',');
            editBtn.dataset.wosIds = (data.wosIds || []).join(',');
            editBtn.dataset.position = data.position || '';
        }
    }

    // ── Small utilities ──────────────────────────────────────────────────────
    function _setField(id, value) {
        const el = document.getElementById(id);
        if (el) el.value = value;
    }

    function _getField(id) {
        const el = document.getElementById(id);
        return el ? el.value : '';
    }

    function _setFeedback(msg, isError) {
        const fb = document.getElementById('edit-user-feedback');
        if (!fb) return;
        fb.textContent = msg;
        fb.className = 'app-form-feedback' + (isError ? ' app-form-feedback--error' : '');
        fb.hidden = !msg;
    }

    function _setSaveLoading(loading) {
        const btn = document.getElementById('edit-user-save-btn');
        if (!btn) return;
        btn.disabled = loading;
        btn.textContent = loading ? 'Saving…' : 'Save Changes';
    }

    function _esc(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function _roleMod(role) {
        if (role.includes('ADMIN')) return ' app-admin-role-badge--admin';
        if (role.includes('SUPERVISOR')) return ' app-admin-role-badge--supervisor';
        return '';
    }
}
