/*
 * Roles and permissions (Deliverable 3, section 5.7; FR16).
 * Assigns a role and department scope to an account, and shows what each of
 * the seven roles is allowed and not allowed to do. The rules themselves are
 * enforced by the database (row level security), not by this page.
 */

import { sb, rpc, messageOf } from '../client.js';
import { h, clear, pageHead, badge, select, field, busy, toast, confirmDialog, roleLabel, ROLE_LABELS } from '../ui.js';
import { ROLE_TONE } from './accounts.js';

const ASSIGNABLE = ['procurement_officer', 'auditor', 'administrator', 'finance_officer', 'evaluation_committee'];
const NEEDS_DEPARTMENT = new Set(['procurement_officer', 'finance_officer', 'evaluation_committee']);

/** What each role may and may not do. Mirrors the security rules in Supabase. */
export const ROLES = [
  {
    role: 'public',
    signIn: 'No sign-in. Welcome screen → Continue as Member of Public',
    app: true, department: false,
    grants: ['View published tenders, awards and payment totals', 'Follow deliverables and spending by department', 'Report a concern about a tender'],
    denies: ['See tenders that are not yet published', 'See supplier documents or banking details', 'Change anything'],
  },
  {
    role: 'supplier',
    signIn: 'App → Supplier Login (registers in the app)',
    app: true, department: false,
    grants: ['Register the company (CSD and CIPC numbers)', 'Upload compliance documents and banking details', 'Read open tenders, tender packs and updates', 'Follow its own registration status'],
    denies: ['See other suppliers\' documents or banking details', 'Change a tender or its status', 'Read the audit trail'],
  },
  {
    role: 'procurement_officer',
    signIn: 'App → Government Official Login',
    app: true, department: true,
    grants: ['Register and publish tenders (FR1)', 'Move tenders through the lifecycle (FR2)', 'Verify supplier registrations', 'Record payments and verify deliverables', 'Handle compliance flags'],
    denies: ['Create accounts or change roles', 'Change their own role'],
  },
  {
    role: 'evaluation_committee',
    signIn: 'App → Government Official Login (screens not built yet)',
    app: false, department: true,
    grants: ['Score bids against the published criteria', 'Recommend a supplier'],
    denies: ['Award contracts', 'Record payments', 'Manage accounts'],
  },
  {
    role: 'finance_officer',
    signIn: 'App → Government Official Login (screens not built yet)',
    app: false, department: true,
    grants: ['Record payments against awarded tenders (FR12)', 'Update department budgets (FR11)'],
    denies: ['Register or award tenders', 'Manage accounts'],
  },
  {
    role: 'auditor',
    signIn: 'App → Government Official Login',
    app: true, department: false,
    grants: ['Read every tender record, bid, evaluation and award decision', 'Read the full audit trail and tender history', 'Review compliance reports'],
    denies: ['Create or edit tenders', 'Submit bids or award contracts', 'Delete records or approve payments'],
  },
  {
    role: 'administrator',
    signIn: 'This portal only, with two-factor authentication',
    app: false, department: false,
    grants: ['Create, update, suspend and delete accounts', 'Assign roles and department scope', 'Monitor activity and irregular patterns', 'Search and export audit logs and reports', 'Publish announcements and flag tenders for review'],
    denies: ['Register, edit or award tenders', 'Record payments', 'See supplier banking details', 'Sign in to the app'],
  },
];

export async function render(view, ctx) {
  const [accounts, budgets] = await Promise.all([
    rpc('admin_list_accounts'),
    sb.from('department_budgets').select('department'),
  ]);
  const counts = {};
  for (const a of accounts) if (!a.suspended) counts[a.role] = (counts[a.role] || 0) + 1;
  const departments = [...new Set([...(budgets.data || []).map((b) => b.department), ...accounts.map((a) => a.department).filter(Boolean)])].sort();

  clear(view).append(
    pageHead('Administration', 'Roles & permissions', 'Assign a role and department scope, and see what each of the seven roles can and cannot do.'),
    assignCard(accounts, departments, ctx),
    h('h2', { class: 'h2 section-title' }, 'The seven roles'),
    h('p', { class: 'meta mb-lg' }, 'These rules are enforced by the database for every request, from the app and from this portal alike.'),
    h('div', { class: 'cards-2' }, ROLES.map((r) => roleCard(r, counts[r.role] || 0, ctx))),
  );
}

function roleCard(r, count, ctx) {
  return h('div', { class: 'card role-card' },
    h('div', { class: 'card-head' },
      h('div', {}, badge(roleLabel(r.role), ROLE_TONE[r.role] || 'neutral', false)),
      r.role === 'public'
        ? h('span', { class: 'meta' }, 'No accounts')
        : h('button', { class: 'link-btn', type: 'button', onclick: () => ctx.go(`accounts?role=${r.role}`) },
          `${count} active ${count === 1 ? 'account' : 'accounts'}`)),
    h('p', { class: 'meta' }, r.signIn),
    h('div', { class: 'inline mt-lg' },
      r.app ? badge('App screens', 'success') : badge(r.role === 'administrator' ? 'Portal only' : 'No app screens yet', 'neutral'),
      r.department ? badge('Department scope', 'info') : null),
    h('ul', { class: 'perm-list' },
      r.grants.map((g) => h('li', {}, h('span', { class: 'yes', 'aria-label': 'Allowed' }, '✓'), g)),
      r.denies.map((d) => h('li', { class: 'denied' }, h('span', { class: 'no', 'aria-label': 'Not allowed' }, '✕'), d))));
}

/** Assign a role and department scope to an existing staff account. */
function assignCard(accounts, departments, ctx) {
  const staff = accounts.filter((a) => a.role !== 'supplier' && a.role !== 'public' && a.id !== ctx.me.id);
  if (!staff.length) {
    return h('div', { class: 'card' }, h('p', { class: 'card-title' }, 'Assign a role'),
      h('p', { class: 'meta' }, 'There are no other staff accounts yet. Create one on the User accounts page.'));
  }

  const account = field({ label: 'Account', input: select(staff.map((a) => [a.id, `${a.full_name} · ${a.email}`]), staff[0].id, () => fill(), 'Account') });
  const role = field({ label: 'Role', input: select(ASSIGNABLE.map((r) => [r, ROLE_LABELS[r]]), staff[0].role, () => hint(), 'Role') });
  const department = field({ label: 'Department scope', input: h('input', { class: 'input', list: 'tt-role-departments', autocomplete: 'off' }), hint: ' ' });
  const hintEl = department.wrap.querySelector('.hint');
  const current = h('p', { class: 'meta mb-lg' });
  const submit = h('button', { class: 'btn btn-primary btn-sm', type: 'submit' }, 'Save role');

  function selected() { return staff.find((a) => a.id === account.input.value); }
  function fill() {
    const a = selected();
    role.input.value = a.role;
    department.input.value = a.department || '';
    current.textContent = `Currently ${roleLabel(a.role)}${a.department ? ` · ${a.department}` : ''}${a.suspended ? ' · suspended' : ''}.`;
    hint();
  }
  function hint() {
    hintEl.textContent = NEEDS_DEPARTMENT.has(role.input.value)
      ? 'Required: this person works only on this department\'s tenders.'
      : 'Not used for this role.';
  }
  fill();

  const form = h('form', {
    class: 'card', novalidate: true,
    onsubmit: async (event) => {
      event.preventDefault();
      const a = selected();
      const newRole = role.input.value;
      const dept = department.input.value.trim();
      if (NEEDS_DEPARTMENT.has(newRole) && !dept) { department.setError('Choose the department.'); return; }
      department.setError('');
      if (newRole !== a.role) {
        const yes = await confirmDialog({
          title: 'Change role?',
          message: `${a.full_name} will become ${roleLabel(newRole)} instead of ${roleLabel(a.role)}. It applies the next time they sign in.`,
          confirmText: 'Change role',
        });
        if (!yes) return;
      }
      await busy(submit, async () => {
        try {
          await rpc('admin_update_account', { p_user_id: a.id, p_full_name: a.full_name, p_role: newRole, p_department: dept || null });
          toast(`${a.full_name} is now ${roleLabel(newRole)}.`);
          ctx.reload();
        } catch (e) {
          toast(messageOf(e), 'error');
        }
      });
    },
  },
  h('datalist', { id: 'tt-role-departments' }, departments.map((d) => h('option', { value: d }))),
  h('p', { class: 'card-title' }, 'Assign a role'),
  current,
  h('div', { class: 'form-grid' }, h('div', { class: 'full' }, account.wrap), role.wrap, department.wrap),
  h('div', { class: 'spread' },
    h('p', { class: 'hint' }, 'Supplier accounts are not listed: their role comes from their company registration.'),
    submit));
  return form;
}

