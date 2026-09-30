/*
 * Sign in — suppliers and procurement officers, with the same email and
 * password they use in TenderTrack (it is the same account).
 */

import { chrome } from '../layout.js';
import { sb, whoAmI, messageOf } from '../client.js';
import { h, clear, icon, note, field, busy, fill } from '../ui.js';

function destination(role) {
  const next = new URLSearchParams(location.search).get('next') || '';
  // Only a page on this portal: "/\evil.com" and "//evil.com" would leave it.
  let safeNext = '';
  try {
    const url = new URL(next, location.origin);
    if (next.startsWith('/') && url.origin === location.origin) safeNext = url.pathname + url.search;
  } catch { safeNext = ''; }
  if (role === 'supplier') return safeNext && !safeNext.startsWith('/department') ? safeNext : '/account';
  if (role === 'procurement_officer') return safeNext.startsWith('/department') ? safeNext : '/department';
  return null;
}

async function render() {
  const { who, main } = await chrome('signin');
  if (who && who.profile && destination(who.profile.role)) {
    window.location.replace(destination(who.profile.role));
    return;
  }

  const message = h('div', { class: 'mb', hidden: true });
  const email = field({ label: 'Email address', input: h('input', { class: 'input', type: 'email', autocomplete: 'username', placeholder: 'you@company.co.za' }) });
  const password = field({ label: 'Password', input: h('input', { class: 'input', type: 'password', autocomplete: 'current-password' }) });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Sign in');

  function show(tone, title, text) {
    clear(message).appendChild(note(tone, title, text));
    message.hidden = false;
  }

  const form = h('form', {
    class: 'card', novalidate: true,
    onsubmit: async (event) => {
      event.preventDefault();
      message.hidden = true;
      const e = email.input.value.trim().toLowerCase();
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) { email.setError('Enter your email address.'); return; }
      email.setError('');
      if (!password.input.value) { password.setError('Enter your password.'); return; }
      password.setError('');
      await busy(submit, async () => {
        const { error } = await sb.auth.signInWithPassword({ email: e, password: password.input.value });
        password.input.value = '';
        if (error) { show('danger', 'Could not sign in', messageOf(error)); return; }
        const me = await whoAmI();
        const role = me && me.profile ? me.profile.role : null;
        if (me && me.profile && me.profile.suspended) {
          await sb.auth.signOut();
          show('danger', 'Account suspended', 'This account has been suspended. Contact the department.');
          return;
        }
        if (role === 'supplier' && !me.supplier) {
          await sb.auth.signOut();
          show('warn', 'No company registration', 'This account has no company registration linked to it. Register your company first.');
          return;
        }
        // A company whose portal registration is not complete finishes it first.
        const to = me.supplier && !me.supplier.portal_registered_at ? '/register' : destination(role);
        if (!to) {
          await sb.auth.signOut();
          show('warn', 'Not for this portal', 'This portal is for registered suppliers and procurement officers. '
            + 'Auditors use TenderTrack; administrators use the TenderTrack admin portal.');
          return;
        }
        window.location.replace(to);
      });
    },
  },
  message,
  email.wrap, password.wrap, submit,
  h('p', { class: 'meta mt' }, 'Forgot your password? The TenderTrack administrator can issue a temporary one.'));

  fill(main,
    h('div', { class: 'grid-2' },
      h('div', {},
        h('p', { class: 'eyebrow' }, 'Suppliers and procurement officers'),
        h('h1', { class: 'h1 mb' }, 'Sign in'),
        form),
      h('div', { class: 'stack' },
        note('info', 'One account for the portal and TenderTrack', 'Use the email address and password you use in the TenderTrack app. The portal and the app share the same database.'),
        h('div', { class: 'card' },
          h('p', { class: 'h3' }, 'New supplier?'),
          h('p', { class: 'meta mt' }, 'Register your company here: company, contact, compliance, banking, capabilities and documents. Then register for the TenderTrack app as well, to follow tenders and claim awards.'),
          h('a', { class: 'btn btn-primary mt', href: '/register' }, icon('business'), 'Register as a supplier')))));
  email.input.focus();
}

render();
