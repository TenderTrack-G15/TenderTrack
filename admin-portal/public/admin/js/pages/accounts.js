/*
 * User accounts — the portal's main CRUD module.
 *
 *   Create   New staff account          -> server.js (secret key) + database trigger
 *   Read     Every account               -> admin_list_accounts()
 *   Update   Name, role, department      -> admin_update_account()
 *            Suspend / reinstate         -> server.js (database flag + sign-in block)
 *            Temporary password          -> server.js
 *   Delete   Accounts with no records    -> server.js
 *
 * What the app sees: a new account can sign in to the app straight away with
 * its role; a changed role applies at the next sign-in; a suspended account is
 * refused at sign-in and its data access stops immediately.
 */

import { sb, rpc, api, messageOf } from '../client.js';
import {
  h, clear, icon, pageHead, searchBox, select, badge, note, empty, ago, date, dateTime,
  roleLabel, ROLE_LABELS, toast, modal, confirmDialog, showPassword, field, busy,
} from '../ui.js';

/** Roles an administrator can give. Suppliers register themselves in the app. */
const STAFF_ROLES = ['procurement_officer', 'auditor', 'administrator', 'finance_officer', 'evaluation_committee'];
const NEEDS_DEPARTMENT = new Set(['procurement_officer', 'finance_officer', 'evaluation_committee']);
/** Roles that have screens in the Android app today. */
const HAS_APP_SCREENS = new Set(['procurement_officer', 'auditor', 'supplier', 'public']);
export const ROLE_TONE = {
  administrator: 'warn', auditor: 'info', procurement_officer: 'success',
  finance_officer: 'neutral', evaluation_committee: 'neutral', supplier: 'neutral', public: 'neutral',
};

export async function render(view, ctx) {
  const state = { accounts: [], departments: [], query: '', role: 'all', status: 'all' };
  // The Roles page links here as #/accounts?role=auditor
  const wanted = new URLSearchParams(window.location.hash.split('?')[1] || '').get('role');
  if (wanted && ROLE_LABELS[wanted]) state.role = wanted;

  const [accounts, budgets] = await Promise.all([
    rpc('admin_list_accounts'),
    sb.from('department_budgets').select('department'),
  ]);
  state.accounts = accounts || [];
  state.departments = [...new Set([
    ...((budgets.data || []).map((b) => b.department)),
    ...state.accounts.map((a) => a.department).filter(Boolean),
  ])].sort();

  const countLine = h('p', { class: 'meta count-line' });
  const tableHost = h('div');
  const datalist = h('datalist', { id: 'tt-departments' }, state.departments.map((d) => h('option', { value: d })));

  async function reload() {
    state.accounts = (await rpc('admin_list_accounts')) || [];
    draw();
  }

  // -------------------------------------------------------------------------
  // READ — the table
  // -------------------------------------------------------------------------

  function visibleAccounts() {
    const q = state.query.trim().toLowerCase();
    return state.accounts.filter((a) => {
      if (state.role !== 'all' && a.role !== state.role) return false;
      if (state.status === 'active' && a.suspended) return false;
      if (state.status === 'suspended' && !a.suspended) return false;
      if (!q) return true;
      return `${a.full_name} ${a.email} ${a.department || ''}`.toLowerCase().includes(q);
    });
  }

  function draw() {
    const rows = visibleAccounts();
    countLine.textContent = `${rows.length} of ${state.accounts.length} accounts`;
    clear(tableHost);
    if (!rows.length) {
      tableHost.appendChild(h('div', { class: 'table-wrap' }, empty('No accounts match', 'Change the search or filters.', 'people')));
      return;
    }
    tableHost.appendChild(h('div', { class: 'table-wrap' },
      h('table', {},
        h('thead', {}, h('tr', {},
          h('th', {}, 'Name'), h('th', {}, 'Role'), h('th', {}, 'Department'),
          h('th', {}, 'Status'), h('th', {}, 'Last sign-in'), h('th', {}, 'Actions'))),
        h('tbody', {}, rows.map(accountRow)))));
  }

  function accountRow(a) {
    const isMe = a.id === ctx.me.id;
    const isSupplier = a.role === 'supplier';
    return h('tr', {},
      h('td', {},
        h('div', { class: 'inline' }, h('span', { class: 'primary' }, a.full_name), isMe ? badge('You', 'info', false) : null),
        h('div', { class: 'secondary' }, a.email)),
      h('td', {},
        badge(roleLabel(a.role), ROLE_TONE[a.role] || 'neutral', false),
        !HAS_APP_SCREENS.has(a.role) && a.role !== 'administrator'
          ? h('div', { class: 'secondary' }, 'No app screens yet') : null,
        a.role === 'administrator' ? h('div', { class: 'secondary' }, 'Signs in to this portal') : null),
      h('td', {}, a.department || h('span', { class: 'secondary' }, '—')),
      h('td', {},
        a.suspended ? badge('Suspended', 'danger') : badge('Active', 'success'),
        a.suspended && a.suspended_reason ? h('div', { class: 'secondary' }, a.suspended_reason) : null),
      h('td', { title: a.last_sign_in_at ? dateTime(a.last_sign_in_at) : '' },
        ago(a.last_sign_in_at),
        h('div', { class: 'secondary' }, `Created ${date(a.created_at)}`)),
      h('td', {},
        h('div', { class: 'row-actions' },
          h('button', {
            class: 'btn btn-secondary btn-xs', type: 'button', disabled: isSupplier,
            title: isSupplier ? 'A supplier account belongs to a company registration; its role cannot be changed.' : null,
            onclick: () => openEdit(a),
          }, 'Edit'),
          a.suspended
            ? h('button', { class: 'btn btn-secondary btn-xs', type: 'button', disabled: isMe, onclick: () => reinstate(a) }, 'Reinstate')
            : h('button', { class: 'btn btn-secondary btn-xs', type: 'button', disabled: isMe, onclick: () => openSuspend(a) }, 'Suspend'),
          h('button', { class: 'btn btn-secondary btn-xs', type: 'button', disabled: isMe, onclick: () => resetPassword(a) }, 'Reset password'),
          h('button', { class: 'btn btn-danger btn-xs', type: 'button', disabled: isMe, onclick: () => openDelete(a) }, 'Delete'))));
  }

  // -------------------------------------------------------------------------
  // Shared form parts
  // -------------------------------------------------------------------------

  function roleAndDepartment(initialRole, initialDepartment, lockRole) {
    const role = field({
      label: 'Role',
      input: select(STAFF_ROLES.map((r) => [r, ROLE_LABELS[r]]), initialRole, () => update(), 'Role'),
    });
    role.input.disabled = !!lockRole;
    const department = field({
      label: 'Department',
      input: h('input', { class: 'input', list: 'tt-departments', autocomplete: 'off', maxlength: '120', value: initialDepartment || '' }),
      hint: ' ',
    });
    const hintEl = department.wrap.querySelector('.hint');
    const roleNote = h('div', { class: 'mb-lg' });

    function update() {
      const r = role.input.value;
      hintEl.textContent = NEEDS_DEPARTMENT.has(r)
        ? 'Required. This person works only on this department\'s tenders.'
        : 'Optional for this role.';
      clear(roleNote);
      if (r === 'administrator') {
        roleNote.appendChild(note('warn', 'Administrator', 'Administrators manage every account. They sign in to this portal with two-factor authentication, not to the app.'));
      } else if (!HAS_APP_SCREENS.has(r)) {
        roleNote.appendChild(note('neutral', 'No app screens yet', `${ROLE_LABELS[r]} accounts can be created now, but the app does not have screens for this role yet, so the person cannot sign in to the app until they are added.`));
      }
      roleNote.hidden = !roleNote.firstChild;
    }
    update();
    return { role, department, roleNote, validate() {
      const r = role.input.value;
      if (NEEDS_DEPARTMENT.has(r) && !department.input.value.trim()) {
        department.setError('Choose the department.');
        return false;
      }
      department.setError('');
      return true;
    } };
  }

  function formError() {
    const box = h('div', { class: 'mb-lg', hidden: true });
    return {
      box,
      show(message) { clear(box).appendChild(note('danger', null, message)); box.hidden = false; },
      hide() { clear(box); box.hidden = true; },
    };
  }

  // -------------------------------------------------------------------------
  // CREATE
  // -------------------------------------------------------------------------

  function openCreate() {
    const name = field({ label: 'Full name', input: h('input', { class: 'input', autocomplete: 'off', maxlength: '120', placeholder: 'e.g. Thandi Nkosi' }) });
    const email = field({ label: 'Work email', input: h('input', { class: 'input', type: 'email', autocomplete: 'off', placeholder: 'name@department.gov.za' }), hint: 'They sign in to the app with this email.' });
    const rd = roleAndDepartment('procurement_officer', '');
    const error = formError();

    modal('New account', 'Staff accounts only. Suppliers register themselves in the app.', (close) => {
      const submit = h('button', { class: 'btn btn-primary btn-sm', type: 'submit' }, 'Create account');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          error.hide();
          let ok = true;
          if (name.input.value.trim().length < 2) { name.setError('Enter the person\'s full name.'); ok = false; } else name.setError('');
          if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.input.value.trim())) { email.setError('Enter a valid email address.'); ok = false; } else email.setError('');
          if (!rd.validate()) ok = false;
          if (!ok) return;

          await busy(submit, async () => {
            try {
              const result = await api('POST', '/api/accounts', {
                fullName: name.input.value.trim(),
                email: email.input.value.trim(),
                role: rd.role.input.value,
                department: rd.department.input.value.trim() || null,
              });
              close();
              showPassword('Account created', result.email, result.temporaryPassword);
              toast(`${result.email} can now sign in as ${roleLabel(result.role)}.`);
              await reload();
            } catch (e) {
              error.show(e.message);
            }
          });
        },
      },
      error.box, name.wrap, email.wrap, rd.role.wrap, rd.department.wrap, rd.roleNote,
      h('p', { class: 'hint' }, 'A temporary password is made for them and shown once. Supabase stores only a scrambled copy.'),
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  // -------------------------------------------------------------------------
  // UPDATE — name, role, department
  // -------------------------------------------------------------------------

  function openEdit(account) {
    const isMe = account.id === ctx.me.id;
    const name = field({ label: 'Full name', input: h('input', { class: 'input', autocomplete: 'off', maxlength: '120', value: account.full_name }) });
    const rd = roleAndDepartment(account.role, account.department, isMe);
    const error = formError();

    modal('Edit account', account.email, (close) => {
      const submit = h('button', { class: 'btn btn-primary btn-sm', type: 'submit' }, 'Save changes');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          error.hide();
          if (name.input.value.trim().length < 2) { name.setError('Enter the person\'s full name.'); return; }
          name.setError('');
          if (!rd.validate()) return;
          const newRole = rd.role.input.value;
          if (newRole !== account.role) {
            const yes = await confirmDialog({
              title: 'Change role?',
              message: `${account.full_name} will become ${roleLabel(newRole)} instead of ${roleLabel(account.role)}. The change applies the next time they sign in to the app.`,
              confirmText: 'Change role',
            });
            if (!yes) return;
          }
          await busy(submit, async () => {
            try {
              await rpc('admin_update_account', {
                p_user_id: account.id,
                p_full_name: name.input.value.trim(),
                p_role: newRole,
                p_department: rd.department.input.value.trim() || null,
              });
              close();
              toast('Account updated.');
              await reload();
            } catch (e) {
              error.show(e.message);
            }
          });
        },
      },
      error.box, name.wrap, rd.role.wrap,
      isMe ? h('p', { class: 'hint mb-lg' }, 'You cannot change your own role. Ask another administrator.') : null,
      rd.department.wrap, rd.roleNote,
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  // -------------------------------------------------------------------------
  // UPDATE — suspend and reinstate
  // -------------------------------------------------------------------------

  function openSuspend(account) {
    const reason = field({
      label: 'Reason',
      input: h('textarea', { class: 'textarea', maxlength: '300', placeholder: 'e.g. Left the department on 30 September 2026' }),
      hint: 'Recorded in the audit trail and shown to other administrators.',
    });
    const error = formError();

    modal('Suspend account', account.email, (close) => {
      const submit = h('button', { class: 'btn btn-danger btn-sm', type: 'submit' }, 'Suspend account');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          error.hide();
          if (reason.input.value.trim().length < 5) { reason.setError('Give a reason (at least 5 characters).'); return; }
          reason.setError('');
          await busy(submit, async () => {
            try {
              const result = await api('POST', `/api/accounts/${account.id}/suspend`, { reason: reason.input.value.trim() });
              close();
              if (result.warning) toast(result.warning, 'warn'); else toast(`${account.email} is suspended.`);
              await reload();
            } catch (e) {
              error.show(e.message);
            }
          });
        },
      },
      note('warn', 'Takes effect immediately', `${account.full_name} can no longer sign in, and any open session in the app stops receiving data. Nothing is deleted; you can reinstate the account later.`),
      h('div', { class: 'mt-lg' }),
      error.box, reason.wrap,
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  async function reinstate(account) {
    const yes = await confirmDialog({
      title: 'Reinstate account?',
      message: `${account.full_name} (${account.email}) will be able to sign in again with the same role: ${roleLabel(account.role)}.`,
      confirmText: 'Reinstate',
    });
    if (!yes) return;
    try {
      const result = await api('POST', `/api/accounts/${account.id}/reinstate`, {});
      if (result.warning) toast(result.warning, 'warn'); else toast(`${account.email} is active again.`);
      await reload();
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  // -------------------------------------------------------------------------
  // UPDATE — temporary password
  // -------------------------------------------------------------------------

  async function resetPassword(account) {
    const yes = await confirmDialog({
      title: 'Issue a temporary password?',
      message: `${account.full_name}'s current password stops working. You will see a new temporary password once, to give to them in person.`,
      confirmText: 'Issue password',
    });
    if (!yes) return;
    try {
      const result = await api('POST', `/api/accounts/${account.id}/reset-password`, {});
      showPassword('Temporary password', result.email, result.temporaryPassword);
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  // -------------------------------------------------------------------------
  // DELETE
  // -------------------------------------------------------------------------

  function openDelete(account) {
    const confirmField = field({
      label: `Type ${account.email} to confirm`,
      input: h('input', { class: 'input', autocomplete: 'off', spellcheck: 'false' }),
    });
    const error = formError();

    modal('Delete account', account.email, (close) => {
      const submit = h('button', { class: 'btn btn-danger btn-sm', type: 'submit' }, 'Delete permanently');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          error.hide();
          if (confirmField.input.value.trim().toLowerCase() !== account.email.toLowerCase()) {
            confirmField.setError('The email does not match.');
            return;
          }
          confirmField.setError('');
          await busy(submit, async () => {
            try {
              await api('DELETE', `/api/accounts/${account.id}`);
              close();
              toast(`${account.email} was deleted.`);
              await reload();
            } catch (e) {
              error.show(e.message);
            }
          });
        },
      },
      note('danger', 'This cannot be undone', 'Only accounts that never created records can be deleted. An account that registered tenders, verified deliverables or owns a supplier registration can only be suspended, so the audit trail stays complete (FR3).'),
      h('div', { class: 'mt-lg' }),
      error.box, confirmField.wrap,
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  // -------------------------------------------------------------------------
  // The page
  // -------------------------------------------------------------------------

  const roleOptions = [['all', 'All roles'], ...Object.entries(ROLE_LABELS)];
  const statusOptions = [['all', 'Any status'], ['active', 'Active'], ['suspended', 'Suspended']];

  clear(view).append(
    datalist,
    pageHead('Administration', 'User accounts', 'Create, update, suspend and delete TenderTrack accounts. Every change is recorded in the audit trail.',
      h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: openCreate }, icon('add'), 'New account')),
    h('div', { class: 'mb-lg' }, note('info', 'How changes reach the app',
      'A new account can sign in to the app straight away with its temporary password and lands on the screens for its role. A role change applies at the next sign-in. A suspended account is refused at once.')),
    h('div', { class: 'toolbar' },
      searchBox('Search by name, email or department', (e) => { state.query = e.target.value; draw(); }),
      select(roleOptions, state.role, (e) => { state.role = e.target.value; draw(); }, 'Filter by role'),
      select(statusOptions, state.status, (e) => { state.status = e.target.value; draw(); }, 'Filter by status'),
      h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: (e) => busy(e.currentTarget, () => reload().catch((err) => toast(messageOf(err), 'error'))) }, icon('refresh'), 'Refresh')),
    countLine,
    tableHost,
  );
  draw();
}
