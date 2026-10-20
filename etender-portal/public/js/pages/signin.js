/*
 * Sign in — suppliers and procurement officers, with the same email and
 * password they use in TenderTrack (it is the same account).
 *
 * Two-factor sign-in, as on the admin portal:
 *   1. Email and password                 -> session at level "aal1"
 *   2. First time: scan a QR code with an authenticator app
 *      (Google Authenticator, Microsoft Authenticator, ...).
 *      Every time: type the 6-digit code  -> session at level "aal2"
 * The database refuses everything except the account's own profile until
 * step 2 is done, so skipping this page gets nothing.
 */

import { chrome } from '../layout.js';
import { sb, whoAmI, messageOf, twoFactorStatus, signOut } from '../client.js';
import { h, clear, icon, note, field, busy, fill } from '../ui.js';

const MAX_WRONG_CODES = 5;

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
  if (who && who.profile && !who.codePending && destination(who.profile.role)) {
    window.location.replace(destination(who.profile.role));
    return;
  }

  const message = h('div', { class: 'mb', hidden: true });
  const formSlot = h('div', {});
  const pips = h('div', { class: 'signin-steps' },
    h('span', { class: 'on' }, '1. Password'), h('span', {}, '2. Code from your app'));

  function show(tone, title, text) {
    clear(message).appendChild(note(tone, title, text));
    message.hidden = false;
  }
  function step(n) {
    pips.children[0].className = 'on';
    pips.children[1].className = n === 2 ? 'on' : '';
  }

  // ---- Step 1: email and password ------------------------------------------
  function passwordForm() {
    step(1);
    const email = field({ label: 'Email address', input: h('input', { class: 'input', type: 'email', autocomplete: 'username', placeholder: 'you@company.co.za' }) });
    const password = field({ label: 'Password', input: h('input', { class: 'input', type: 'password', autocomplete: 'current-password' }) });
    const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Next');
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
          await afterPassword();
        });
      },
    },
    email.wrap, password.wrap, submit,
    h('p', { class: 'meta mt' }, 'Forgot your password? The TenderTrack administrator can issue a temporary one.'));
    fill(formSlot, form);
    email.input.focus();
  }

  /** Password accepted: who is this, and do they owe a 6-digit code? */
  async function afterPassword() {
    const me = await whoAmI();
    const profile = me && me.profile;
    if (!profile) {
      await sb.auth.signOut();
      show('danger', 'Could not sign in', 'This account has no profile. Contact the TenderTrack administrator.');
      return passwordForm();
    }
    if (profile.suspended) {
      await sb.auth.signOut();
      show('danger', 'Account suspended', 'This account has been suspended. Contact the department.');
      return passwordForm();
    }
    if (!destination(profile.role)) {
      await sb.auth.signOut();
      show('warn', 'Not for this portal', 'This portal is for registered suppliers and procurement officers. '
        + 'Auditors use TenderTrack; administrators use the TenderTrack admin portal.');
      return passwordForm();
    }
    let status;
    try { status = await twoFactorStatus(); } catch (e) {
      await sb.auth.signOut();
      show('danger', 'Could not sign in', e.message);
      return passwordForm();
    }
    // Every sign-in asks for the code, also for a company registered today.
    if (status.required && status.aal !== 'aal2') return secondStep();
    return finish();
  }

  // ---- Step 2: the 6-digit code ---------------------------------------------
  async function secondStep() {
    step(2);
    message.hidden = true;
    const { data, error } = await sb.auth.mfa.listFactors();
    if (error) {
      await sb.auth.signOut();
      show('danger', 'Could not sign in', messageOf(error));
      return passwordForm();
    }
    // data.totp lists only authenticators that were set up completely.
    const verified = (data.totp || []).filter((f) => f.status === 'verified');
    if (verified.length) return codeForm(verified[0].id, false);

    // First time: remove half-finished set-ups from earlier attempts, then a QR code.
    for (const f of data.all || []) {
      if (f.factor_type === 'totp' && f.status !== 'verified') await sb.auth.mfa.unenroll({ factorId: f.id });
    }
    const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ');
    const enrolled = await sb.auth.mfa.enroll({ factorType: 'totp', friendlyName: `eTender portal ${stamp}`, issuer: 'TenderTrack' });
    if (enrolled.error) {
      await sb.auth.signOut();
      const raw = enrolled.error.message || '';
      show('danger', 'Two-factor sign-in is not available', /disabled|not enabled/i.test(raw)
        ? 'Two-factor sign-in (TOTP) is turned off in Supabase. Ask the TenderTrack administrator to turn it on.'
        : messageOf(enrolled.error));
      return passwordForm();
    }
    return codeForm(enrolled.data.id, true, enrolled.data.totp);
  }

  function codeForm(factorId, setUp, totp) {
    let wrong = 0;
    const code = field({
      label: '6-digit code',
      input: h('input', { class: 'input code-input', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: '6', pattern: '[0-9]*', placeholder: '000000' }),
      hint: setUp ? 'The code changes every 30 seconds. Type the newest one.' : 'Open your authenticator app and type the code shown for TenderTrack.',
    });
    const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, setUp ? 'Turn on and sign in' : 'Sign in');
    const secret = setUp ? (totp.secret.match(/.{1,4}/g) || []).join(' ') : '';
    const form = h('form', {
      class: 'card', novalidate: true,
      onsubmit: async (event) => {
        event.preventDefault();
        const value = code.input.value.replace(/\D/g, '');
        if (value.length !== 6) { code.setError('Enter the 6-digit code from the app.'); return; }
        code.setError('');
        await busy(submit, async () => {
          const { error } = await sb.auth.mfa.challengeAndVerify({ factorId, code: value });
          code.input.value = '';
          if (error) {
            wrong += 1;
            if (wrong >= MAX_WRONG_CODES) {
              await sb.auth.signOut();
              show('danger', 'Too many wrong codes', 'You have been signed out. Sign in again to try again.');
              return passwordForm();
            }
            const left = MAX_WRONG_CODES - wrong;
            code.setError(`That code is not right. Check that the time on your phone is set automatically. ${left} ${left === 1 ? 'try' : 'tries'} left.`);
            code.input.focus();
            return;
          }
          await finish();
        });
      },
    },
    setUp
      ? h('div', {},
        h('p', { class: 'h3' }, 'Set up two-factor sign-in'),
        h('ol', { class: 'bullets mt-sm' },
          h('li', {}, 'Install an authenticator app on your phone, such as Google Authenticator or Microsoft Authenticator.'),
          h('li', {}, 'In the app, add an account and scan this QR code.'),
          h('li', {}, 'Type the 6-digit code the app shows.')),
        h('div', { class: 'qr-box' }, h('img', { src: totp.qr_code, alt: 'QR code for your authenticator app' })),
        h('p', { class: 'meta' }, 'Cannot scan it? Enter this key in the app instead:'),
        h('p', { class: 'secret-key' }, secret),
        h('p', { class: 'hint mb' }, 'This is the same authenticator you use in the TenderTrack app. Set it up once, use it in both.'))
      : h('div', {},
        h('p', { class: 'h3' }, 'Enter your code'),
        h('p', { class: 'meta mt-sm mb' }, 'Your account uses two-factor sign-in. Open your authenticator app and type the 6-digit code for TenderTrack.')),
    code.wrap, submit,
    h('button', { class: 'btn btn-secondary mt', type: 'button', onclick: () => signOut('/signin') }, 'Use a different account'));
    fill(formSlot, form);
    code.input.focus();
  }

  /** Signed in (with the code where it is needed): open the right page. */
  async function finish() {
    const me = await whoAmI();
    if (!me || !me.profile || me.codePending) {
      show('danger', 'Could not sign in', 'The code was accepted, but the database still needs two-factor sign-in. Sign in again.');
      await sb.auth.signOut();
      return passwordForm();
    }
    const role = me.profile.role;
    if (role === 'supplier' && !me.supplier) {
      await sb.auth.signOut();
      show('warn', 'No company registration', 'This account has no company registration linked to it. Register your company first.');
      return passwordForm();
    }
    // A company whose portal registration is not complete finishes it first.
    const to = me.supplier && !me.supplier.portal_registered_at ? '/register' : destination(role);
    window.location.replace(to);
  }

  fill(main,
    h('div', { class: 'grid-2' },
      h('div', {},
        h('p', { class: 'eyebrow' }, 'Suppliers and procurement officers'),
        h('h1', { class: 'h1 mb' }, 'Sign in'),
        pips, message, formSlot),
      h('div', { class: 'stack' },
        note('info', 'One account for the portal and TenderTrack', 'Use the email address and password you use in the TenderTrack app. The portal and the app share the same database.'),
        note('neutral', 'Two-factor sign-in', 'After your password, enter the 6-digit code from your authenticator app. The first time, you scan a QR code to set it up.', 'lock'),
        h('div', { class: 'card' },
          h('p', { class: 'h3' }, 'New supplier?'),
          h('p', { class: 'meta mt' }, 'Register your company here: company, contact, compliance, banking, capabilities and documents. Then register for the TenderTrack app as well, to follow tenders and claim awards.'),
          h('a', { class: 'btn btn-primary mt', href: '/register' }, icon('business'), 'Register as a supplier')))));

  // Came back here with the password done but not the code (for example after a refresh).
  if (who && who.codePending) {
    if (!destination(who.profile.role)) { await sb.auth.signOut(); passwordForm(); return; }
    secondStep();
  } else {
    passwordForm();
  }
}

render();
