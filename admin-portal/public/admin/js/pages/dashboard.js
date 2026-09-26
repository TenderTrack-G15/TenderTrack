/*
 * Dashboard — the administrator's overview (Deliverable 3, section 5.7):
 * user accounts, roles and permissions, application activity, backups and
 * audit logs. No tender actions.
 */

import { sb, rpc, messageOf } from '../client.js';
import { h, clear, icon, tile, pageHead, note, badge, ago, dateTime } from '../ui.js';
import { tenderConcerns, SEVERITY_TONE } from '../rules.js';

function greeting() {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

export async function render(view, ctx) {
  const [summary, tendersRes, auditRes, liveRes] = await Promise.all([
    rpc('admin_activity_summary'),
    sb.from('tenders').select('id, reference_number, title, status, estimated_budget, awarded_value, awarded_supplier_name, paid_to_date, closing_date, open_flag_count'),
    sb.from('audit_trail').select('action, detail, actor, created_at').order('created_at', { ascending: false }).limit(8),
    sb.from('announcements').select('id, published, starts_at, ends_at'),
  ]);
  for (const res of [tendersRes, auditRes, liveRes]) if (res.error) throw new Error(messageOf(res.error));

  const now = Date.now();
  const concerned = tendersRes.data
    .map((t) => ({ t, concerns: tenderConcerns(t, now) }))
    .filter((x) => x.concerns.length > 0);
  const live = liveRes.data.filter((a) => a.published && new Date(a.starts_at).getTime() <= now
    && (!a.ends_at || new Date(a.ends_at).getTime() > now)).length;
  const irregular = summary.irregular || [];
  const firstName = String(ctx.me.full_name || '').split(' ')[0];

  clear(view).append(
    pageHead('Administrator', `${greeting()}, ${firstName}`, 'Accounts, roles, activity and records for TenderTrack.'),

    irregular.length
      ? h('div', { class: 'mb-lg' }, note('danger', `${irregular.length} irregular ${irregular.length === 1 ? 'pattern' : 'patterns'} detected in the last 7 days`,
        'Review them on the Application activity page.'))
      : null,

    h('div', { class: 'tiles' },
      tile({ label: 'User accounts', value: summary.accounts_total, foot: `${summary.accounts_suspended} suspended`, onclick: () => ctx.go('accounts') }),
      tile({ label: 'Active administrators', value: summary.administrators, foot: 'Two-factor sign-in required', onclick: () => ctx.go('roles') }),
      tile({ label: 'Signed in today', value: summary.signed_in_today, foot: `${summary.signed_in_last_hour} in the last hour`, onclick: () => ctx.go('activity') }),
      tile({ label: 'Recorded events today', value: summary.events_today, foot: `${summary.events_7_days} in the last 7 days`, onclick: () => ctx.go('audit') }),
      tile({ label: 'Irregular patterns', value: irregular.length, foot: 'Last 7 days', alert: irregular.length > 0, onclick: () => ctx.go('activity') }),
      tile({ label: 'Wrong 2FA codes', value: summary.mfa_failures_24h, foot: 'Admin portal, last 24 hours', alert: summary.mfa_failures_24h >= 3, onclick: () => ctx.go('activity') }),
      tile({ label: 'Tenders to look at', value: concerned.length, foot: `Of ${tendersRes.data.length} tenders`, alert: concerned.some((x) => x.concerns[0].severity === 'high'), onclick: () => ctx.go('tenders') }),
      tile({ label: 'Live announcements', value: live, foot: 'Showing in the app now', onclick: () => ctx.go('announcements') })),

    h('h2', { class: 'h2 section-title' }, 'Your responsibilities'),
    h('div', { class: 'duties' },
      duty('people', 'User accounts', 'Create staff accounts, change roles, suspend and reinstate. Changes reach the app at the next sign-in.', () => ctx.go('accounts')),
      duty('shield', 'Roles & permissions', 'What each of the seven roles can see and do, and where they sign in.', () => ctx.go('roles')),
      duty('pulse', 'Application activity', 'Sign-ins, event volume, load time against the 3-second target, and irregular patterns.', () => ctx.go('activity')),
      duty('backup', 'Backups & audit logs', 'Search and export the audit trail (FR3), and download reports and backup snapshots (FR17).', () => ctx.go('reports'))),

    h('div', { class: 'grid-2 mt-lg' },
      h('div', { class: 'card' },
        h('div', { class: 'card-head' }, h('p', { class: 'card-title' }, 'Tenders to look at'),
          h('button', { class: 'link-btn', type: 'button', onclick: () => ctx.go('tenders') }, 'Open')),
        concerned.length
          ? concerned.slice(0, 5).map(({ t, concerns }) => h('div', { class: 'feed-item' },
            h('div', { class: 'feed-top' }, h('span', { class: 'primary' }, `${t.reference_number} · ${t.title}`), badge(concerns[0].severity, SEVERITY_TONE[concerns[0].severity])),
            h('p', { class: 'meta' }, concerns.map((c) => c.text).join(' · '))))
          : h('p', { class: 'meta' }, 'Nothing stands out at the moment.')),
      h('div', { class: 'card' },
        h('div', { class: 'card-head' }, h('p', { class: 'card-title' }, 'Latest recorded events'),
          h('button', { class: 'link-btn', type: 'button', onclick: () => ctx.go('audit') }, 'Open')),
        auditRes.data.length
          ? auditRes.data.map((e) => h('div', { class: 'feed-item' },
            h('div', { class: 'feed-top' }, h('span', { class: 'primary' }, e.action), h('span', { class: 'tiny', title: dateTime(e.created_at) }, ago(e.created_at))),
            h('p', { class: 'meta' }, `${e.detail} — ${e.actor}`)))
          : h('p', { class: 'meta' }, 'No events recorded yet.'))),
  );
}

function duty(iconName, title, text, onclick) {
  return h('button', { class: 'tile duty', type: 'button', onclick },
    icon(iconName),
    h('div', {}, h('p', { class: 'card-title' }, title), h('p', { class: 'meta' }, text)));
}
