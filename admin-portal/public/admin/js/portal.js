/*
 * The portal after sign-in: checks the session, draws the menu, switches
 * between pages and signs the administrator out after inactivity.
 */

import { sb, rpc, logEvent, signOut, IDLE_MINUTES, recordTiming } from './client.js';
import { h, clear, icon, initials, errorBox, loading, toast } from './ui.js';

import * as dashboard from './pages/dashboard.js';
import * as accounts from './pages/accounts.js';
import * as roles from './pages/roles.js';
import * as activity from './pages/activity.js';
import * as audit from './pages/audit.js';
import * as tenders from './pages/tenders.js';
import * as announcements from './pages/announcements.js';
import * as reports from './pages/reports.js';

/** The menu. Deliberately no tender actions (Deliverable 3, section 5.7). */
const PAGES = [
  { id: 'dashboard', label: 'Dashboard', icon: 'dashboard', page: dashboard },
  { id: 'accounts', label: 'User accounts', icon: 'people', page: accounts },
  { id: 'roles', label: 'Roles & permissions', icon: 'shield', page: roles },
  { id: 'activity', label: 'Application activity', icon: 'pulse', page: activity },
  { id: 'audit', label: 'Audit logs', icon: 'history', page: audit },
  { id: 'tenders', label: 'Tender oversight', icon: 'gavel', page: tenders },
  { id: 'announcements', label: 'Announcements', icon: 'campaign', page: announcements },
  { id: 'reports', label: 'Reports & backups', icon: 'download', page: reports },
];

const $ = (id) => document.getElementById(id);
let leaving = false;

/** Leaves the portal once, even if several things trigger it at the same time. */
async function leave(reason, logAs) {
  if (leaving) return;
  leaving = true;
  if (logAs) await logEvent(logAs);
  await signOut(reason);
}

// ---------------------------------------------------------------------------
// Session check — nothing is drawn until the database says yes
// ---------------------------------------------------------------------------

async function boot() {
  const { data: { session } } = await sb.auth.getSession();
  if (!session) {
    window.location.replace('/admin/?reason=expired');
    return;
  }

  let me;
  try {
    me = await rpc('admin_whoami');
  } catch {
    await leave('denied');
    return;
  }

  // If Supabase ends the session (for example the account was suspended and
  // the token could not be refreshed), go back to the sign-in page.
  sb.auth.onAuthStateChange((event) => {
    if (event === 'SIGNED_OUT' && !leaving) {
      leaving = true;
      window.location.replace('/admin/?reason=expired');
    }
  });

  $('me-name').textContent = me.full_name;
  $('me-email').textContent = me.email;
  $('me-initials').textContent = initials(me.full_name);
  clear($('session-line')).append(icon('lock'), `Signed in with two-factor authentication · signs out after ${IDLE_MINUTES} minutes without activity`);
  $('sign-out').addEventListener('click', () => leave('signed_out', 'signed_out'));

  buildNav();
  $('boot').remove();
  $('shell').hidden = false;

  const context = {
    me,
    go: (id) => { window.location.hash = `#/${id}`; },
    reload: () => route(),
  };
  window.addEventListener('hashchange', () => route(context));
  startIdleTimer();
  route(context);
}

// ---------------------------------------------------------------------------
// Menu and pages
// ---------------------------------------------------------------------------

function buildNav() {
  const nav = clear($('nav'));
  for (const p of PAGES) {
    nav.appendChild(h('a', { href: `#/${p.id}`, dataset: { page: p.id } }, icon(p.icon), p.label));
  }
}

let cleanup = null;
let routeToken = 0;
let lastContext = null;

async function route(context) {
  const ctx = context || lastContext;
  lastContext = ctx;
  const id = window.location.hash.replace(/^#\/?/, '').split('?')[0] || 'dashboard';
  const entry = PAGES.find((p) => p.id === id) || PAGES[0];

  if (cleanup) { try { cleanup(); } catch { /* ignore */ } cleanup = null; }
  const token = ++routeToken;

  for (const a of $('nav').querySelectorAll('a')) a.classList.toggle('active', a.dataset.page === entry.id);
  document.title = `${entry.label} · TenderTrack Administration`;

  // Each visit draws into its own container, so a slow page that finishes
  // after the administrator has moved on draws into nothing.
  const view = h('div', {}, loading());
  clear($('content')).appendChild(view);
  window.scrollTo(0, 0);

  const started = performance.now();
  try {
    const result = await entry.page.render(view, ctx);
    if (token !== routeToken) {
      if (typeof result === 'function') result();
      return;
    }
    cleanup = typeof result === 'function' ? result : null;
    recordTiming(entry.label, performance.now() - started);
  } catch (e) {
    if (token !== routeToken) return;
    if (/two-factor|Only an administrator/i.test(e.message)) {
      await leave('denied');
      return;
    }
    clear(view).appendChild(errorBox(e.message, () => route()));
  }
}

// ---------------------------------------------------------------------------
// Inactivity
// ---------------------------------------------------------------------------

function startIdleTimer() {
  let lastActive = Date.now();
  let warned = false;
  const mark = () => { lastActive = Date.now(); warned = false; };
  for (const name of ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart']) {
    window.addEventListener(name, mark, { passive: true });
  }
  setInterval(() => {
    const idleMs = Date.now() - lastActive;
    const limitMs = IDLE_MINUTES * 60 * 1000;
    if (idleMs >= limitMs) {
      leave('idle', 'idle_timeout');
    } else if (!warned && idleMs >= limitMs - 60 * 1000) {
      warned = true;
      toast('You will be signed out in 1 minute because of inactivity. Move the mouse to stay signed in.', 'warn');
    }
  }, 10 * 1000);
}

boot().catch(() => leave('expired'));
