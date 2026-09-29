/*
 * The frame around every page: header, navigation and footer, drawn from
 * who is signed in. Each page calls chrome('page-id') first.
 */

import { whoAmI, signOut } from './client.js';
import { h, clear, icon, loading, errorBox, fill, put } from './ui.js';

const LOGO_PATH = 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-3.06 16L7.4 14.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L10.94 18zM13 9V3.5L18.5 9H13z';

function logo() {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', LOGO_PATH);
  svg.appendChild(p);
  return h('a', { class: 'logo', href: '/' },
    h('span', { class: 'logo-mark' }, svg),
    h('div', {},
      h('div', { class: 'logo-name' }, 'eTender Demo Portal'),
      h('div', { class: 'logo-sub' }, 'Public-sector tenders · demonstration system')));
}

/**
 * Draws the header, navigation and footer. Returns { who, main } where who is
 * whoAmI() (or null) and main is the page's content element.
 */
export async function chrome(page) {
  let who = null;
  try { who = await whoAmI(); } catch { who = null; }
  const role = who && who.profile ? who.profile.role : null;

  // Header
  const header = clear(document.getElementById('site-header'));
  const user = h('div', { class: 'header-user' });
  if (who && who.profile) {
    put(user, 
      icon('person'),
      h('span', {}, who.profile.full_name, who.supplier ? ` · ${who.supplier.company_name}` : '',
        role === 'procurement_officer' ? ` · ${who.profile.department || 'Procurement officer'}` : ''),
      h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: () => signOut('/') }, 'Sign out'));
  } else {
    put(user, 
      h('a', { class: 'btn btn-secondary btn-sm', href: '/signin' }, 'Sign in'),
      h('a', { class: 'btn btn-primary btn-sm', href: '/register' }, 'Register as a supplier'));
  }
  header.appendChild(h('div', { class: 'header-inner' }, logo(), user));

  // Navigation
  const links = [
    ['home', '/', 'Home'],
    ['tenders', '/tenders', 'Tender opportunities'],
    ['awarded', '/tenders?tab=awarded', 'Awarded tenders'],
    ['how', '/how-it-works', 'How it works'],
  ];
  if (!who) links.push(['register', '/register', 'Supplier registration']);
  const right = [];
  if (role === 'supplier') {
    right.push(['account', '/account', 'My bids & awards']);
    right.push(['mailbox', '/mailbox', 'Demo mailbox']);
  }
  if (role === 'procurement_officer') right.push(['department', '/department', 'Department console']);
  if (!who) right.push(['signin', '/signin', 'Sign in']);

  const nav = clear(document.getElementById('site-nav'));
  nav.appendChild(h('div', { class: 'nav-inner' },
    links.map(([id, href, text]) => h('a', { href, class: id === page ? 'active' : null }, text)),
    h('span', { class: 'spacer' }),
    right.map(([id, href, text]) => h('a', { href, class: id === page ? 'active' : null }, text))));

  // Footer
  clear(document.getElementById('site-footer')).appendChild(h('div', { class: 'footer-inner' },
    h('div', {},
      h('strong', {}, 'eTender Demo Portal'),
      h('div', {}, 'A demonstration system built for the TenderTrack student project. It is not a government website and is not linked to any government system.')),
    h('div', {},
      h('div', {}, 'Suppliers find tenders in TenderTrack and bid here.'),
      h('div', {}, 'Awarded companies claim their contract in the TenderTrack app with the code they are emailed.'))));

  const main = clear(document.getElementById('main'));
  main.appendChild(loading());
  return { who, main };
}

/** Shows an error in place of the page. */
export function showError(main, message, retry) {
  clear(main).appendChild(errorBox(message, retry));
}

/** Sends visitors who are not signed in with the right role to the sign-in page. */
export function requireRole(who, role, main) {
  const actual = who && who.profile ? who.profile.role : null;
  if (actual === role) return true;
  const back = encodeURIComponent(window.location.pathname + window.location.search);
  if (!who) {
    window.location.replace(`/signin?next=${back}`);
    return false;
  }
  const words = role === 'supplier' ? 'a registered supplier' : 'a procurement officer';
  fill(main,
    h('div', { class: 'card' },
      h('p', { class: 'h2' }, 'This page is not for your account'),
      h('p', { class: 'meta mt' }, `It is only available to ${words}. You are signed in as ${who.profile ? who.profile.full_name : 'another user'}.`),
      h('div', { class: 'row mt' }, h('a', { class: 'btn btn-secondary', href: '/' }, 'Back to the home page'))));
  return false;
}
