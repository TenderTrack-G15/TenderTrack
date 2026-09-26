/*
 * Application activity (Deliverable 3, section 5.7): users signed in, event
 * volume, load time against the 3-second target, failed sign-ins, a live feed
 * and automatic flagging of irregular patterns. Refreshes every 30 seconds.
 */

import { sb, rpc, fetchAll, messageOf, timings } from '../client.js';
import { h, clear, tile, pageHead, note, badge, ago, date, dateTime, roleLabel } from '../ui.js';

const REFRESH_SECONDS = 30;
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const TARGET_MS = 3000;

const EVENT_LABELS = {
  password_ok: ['Password accepted', 'neutral'],
  mfa_enrolled: ['Authenticator set up', 'info'],
  mfa_verified: ['Signed in', 'success'],
  mfa_failed: ['Wrong 2FA code', 'danger'],
  not_admin: ['Not an administrator', 'danger'],
  signed_out: ['Signed out', 'neutral'],
  idle_timeout: ['Timed out', 'neutral'],
};

/** "Chrome on Windows" from a browser's user-agent text. */
function device(userAgent) {
  const ua = userAgent || '';
  const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : /iPhone|iPad/.test(ua) ? 'iOS' : '';
  return os ? `${browser} on ${os}` : browser;
}

async function load() {
  const since = new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString();
  const [summary, feed, access, accounts, week] = await Promise.all([
    rpc('admin_activity_summary'),
    sb.from('audit_trail').select('entity_type, action, detail, actor, created_at').order('created_at', { ascending: false }).limit(20),
    sb.from('admin_access_log').select('email, event, detail, user_agent, created_at').order('created_at', { ascending: false }).limit(25),
    rpc('admin_list_accounts'),
    fetchAll(() => sb.from('audit_trail').select('created_at').gte('created_at', since).order('created_at')),
  ]);
  for (const res of [feed, access]) if (res.error) throw new Error(messageOf(res.error));
  return { summary, feed: feed.data, access: access.data, accounts, week };
}

function eventsPerDay(rows) {
  const days = [];
  for (let i = 6; i >= 0; i -= 1) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    days.push({ start: d.getTime(), label: `${WEEKDAYS[d.getDay()]} ${date(d.toISOString())}`, count: 0 });
  }
  for (const r of rows) {
    const t = new Date(r.created_at).getTime();
    for (let i = days.length - 1; i >= 0; i -= 1) {
      if (t >= days[i].start) { days[i].count += 1; break; }
    }
  }
  return days;
}

function draw(view, data, ctx, refreshedAt) {
  const { summary, feed, access, accounts, week } = data;
  const irregular = summary.irregular || [];
  const average = timings.length ? timings.reduce((sum, t) => sum + t.ms, 0) / timings.length : 0;
  const days = eventsPerDay(week);
  const busiest = Math.max(1, ...days.map((d) => d.count));
  const recent = accounts.filter((a) => a.last_sign_in_at)
    .sort((a, b) => new Date(b.last_sign_in_at) - new Date(a.last_sign_in_at)).slice(0, 8);

  clear(view).append(
    pageHead('Monitoring', 'Application activity', `Refreshes every ${REFRESH_SECONDS} seconds · last updated ${dateTime(refreshedAt)}`),

    h('div', { class: 'tiles' },
      tile({ label: 'Signed in, last hour', value: summary.signed_in_last_hour, foot: 'Approximate users online' }),
      tile({ label: 'Signed in today', value: summary.signed_in_today, foot: `${summary.accounts_total} accounts in total` }),
      tile({ label: 'Recorded events today', value: summary.events_today, foot: `${summary.events_7_days} in the last 7 days` }),
      tile({
        label: 'Average page load', value: timings.length ? `${(average / 1000).toFixed(2)} s` : '—',
        foot: timings.length ? `Target 3 s · ${timings.length} portal pages measured` : 'Measured as you use the portal',
        alert: average > TARGET_MS,
      }),
      tile({ label: 'Wrong 2FA codes', value: summary.mfa_failures_24h, foot: 'Admin portal, last 24 hours', alert: summary.mfa_failures_24h >= 3 }),
      tile({ label: 'Portal sign-ins', value: summary.portal_sign_ins_24h, foot: 'Last 24 hours' })),

    h('h2', { class: 'h2 section-title' }, 'Irregular patterns'),
    irregular.length
      ? h('div', { class: 'card' }, irregular.map((x) => h('div', { class: 'feed-item' },
        h('div', { class: 'feed-top' }, h('span', { class: 'inline' }, badge(x.kind, 'danger')), h('span', { class: 'tiny' }, dateTime(x.at))),
        h('p', { class: 'meta' }, x.detail))))
      : note('info', 'Nothing irregular in the last 7 days',
        'Checked automatically: three or more wrong 2FA codes within 15 minutes, a non-administrator at the portal, a new administrator, and ten or more account changes by one person within 10 minutes.'),

    h('div', { class: 'grid-2 mt-lg' },
      h('div', { class: 'card' },
        h('p', { class: 'card-title' }, 'Live feed'),
        h('p', { class: 'meta mb-lg' }, 'The latest entries in the audit trail, from the app and this portal.'),
        feed.length ? feed.map((e) => h('div', { class: 'feed-item' },
          h('div', { class: 'feed-top' }, h('span', { class: 'primary' }, e.action), h('span', { class: 'tiny', title: dateTime(e.created_at) }, ago(e.created_at))),
          h('p', { class: 'meta' }, `${e.detail} — ${e.actor}`))) : h('p', { class: 'meta' }, 'No events yet.')),
      h('div', {},
        h('div', { class: 'card' },
          h('p', { class: 'card-title' }, 'Event volume, last 7 days'),
          days.map((d) => h('div', { class: 'feed-item' },
            h('div', { class: 'feed-top' }, h('span', { class: 'meta' }, d.label), h('span', { class: 'primary' }, String(d.count))),
            h('div', { class: 'progress' }, bar(d.count / busiest))))),
        h('div', { class: 'card' },
          h('p', { class: 'card-title' }, 'Recent sign-ins'),
          recent.length ? recent.map((a) => h('div', { class: 'feed-item' },
            h('div', { class: 'feed-top' }, h('span', { class: 'primary' }, a.full_name), h('span', { class: 'tiny', title: dateTime(a.last_sign_in_at) }, ago(a.last_sign_in_at))),
            h('p', { class: 'meta' }, `${roleLabel(a.role)} · ${a.email}`))) : h('p', { class: 'meta' }, 'Nobody has signed in yet.')))),

    h('h2', { class: 'h2 section-title' }, 'Admin portal access log'),
    h('p', { class: 'meta mb-lg' }, 'Every step of every sign-in to this portal. Wrong passwords, in the app or here, are recorded by Supabase itself: Supabase dashboard → Logs → Auth.'),
    h('div', { class: 'table-wrap' },
      h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Account'), h('th', {}, 'Event'), h('th', {}, 'Detail'), h('th', {}, 'Device'))),
        h('tbody', {}, access.length ? access.map((r) => {
          const [label, tone] = EVENT_LABELS[r.event] || [r.event, 'neutral'];
          return h('tr', {},
            h('td', {}, dateTime(r.created_at)),
            h('td', {}, r.email || '—'),
            h('td', {}, badge(label, tone)),
            h('td', {}, r.detail || '—'),
            h('td', {}, device(r.user_agent)));
        }) : h('tr', {}, h('td', { colspan: '5' }, h('span', { class: 'meta' }, 'No portal sign-ins recorded yet.')))))),
  );
}

function bar(fraction) {
  const span = h('span');
  span.style.width = `${Math.round(Math.min(1, fraction) * 100)}%`;   // CSSOM, allowed by the security policy
  return span;
}

export async function render(view, ctx) {
  draw(view, await load(), ctx, new Date().toISOString());
  let running = false;
  const timer = setInterval(async () => {
    if (running || !view.isConnected) return;
    running = true;
    try {
      draw(view, await load(), ctx, new Date().toISOString());
    } catch {
      // Keep the last good view; the next refresh tries again.
    } finally {
      running = false;
    }
  }, REFRESH_SECONDS * 1000);
  return () => clearInterval(timer);
}
