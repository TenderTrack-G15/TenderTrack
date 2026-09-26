/*
 * Admin portal sign-in: password first, then a 6-digit code from an
 * authenticator app (two-factor authentication).
 *
 *   1. Email and password     -> Supabase Auth (session at level "aal1")
 *   2. Is this an administrator? A non-administrator is signed out and the
 *      attempt is recorded.
 *   3. First time: scan a QR code to set up the authenticator app.
 *      Every time: type the 6-digit code  -> session at level "aal2"
 *   4. Open the portal.
 *
 * The database itself refuses administrator access until step 3 is done
 * (auth_role() in admin_portal.sql checks for "aal2"), so skipping this page
 * and opening /admin/portal directly gets nothing.
 */

import { sb, rpc, logEvent, messageOf } from './client.js';
import { note, clear, busy } from './ui.js';

const $ = (id) => document.getElementById(id);

const steps = {
  password: $('step-password'),
  enrol: $('step-enrol'),
  code: $('step-code'),
};

const MAX_WRONG_CODES = 5;
let factorId = null;
let wrongCodes = 0;

/** Messages shown when another page sends the administrator back here. */
const REASONS = {
  idle: ['info', 'Signed out', 'You were signed out after a period of inactivity.'],
  expired: ['info', 'Session ended', 'Your session has ended. Sign in again.'],
  signed_out: ['neutral', 'Signed out', 'You have signed out of the admin portal.'],
  denied: ['danger', 'Access refused', 'The portal needs an active administrator account signed in with two-factor authentication.'],
};

// ---------------------------------------------------------------------------
// Screen helpers
// ---------------------------------------------------------------------------

function showMessage(tone, title, text) {
  const box = clear($('message'));
  box.appendChild(note(tone, title, text));
  box.hidden = false;
}

function hideMessage() {
  clear($('message')).hidden = true;
}

function showStep(name) {
  for (const [key, form] of Object.entries(steps)) form.hidden = key !== name;
  $('pip-1').className = 'on';
  $('pip-2').className = name === 'password' ? '' : 'on';
  $('back-wrap').hidden = name === 'password';
  const first = steps[name].querySelector('input');
  if (first) setTimeout(() => first.focus(), 0);
}

function setError(id, message) {
  const el = $(id);
  el.hidden = !message;
  el.textContent = message || '';
  const input = el.parentElement.querySelector('input');
  if (input) input.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function resetToPassword() {
  factorId = null;
  wrongCodes = 0;
  for (const id of ['password', 'code', 'enrol-code']) $(id).value = '';
  for (const id of ['email-error', 'password-error', 'code-error', 'enrol-error']) setError(id, '');
  $('qr').removeAttribute('src');
  $('secret').textContent = '';
  showStep('password');
}

async function endSession() {
  try { await sb.auth.signOut(); } catch { /* already signed out */ }
}

// ---------------------------------------------------------------------------
// Step 1: email and password
// ---------------------------------------------------------------------------

steps.password.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideMessage();
  const email = $('email').value.trim().toLowerCase();
  const password = $('password').value;

  let ok = true;
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { setError('email-error', 'Enter a valid email address.'); ok = false; } else setError('email-error', '');
  if (!password) { setError('password-error', 'Enter your password.'); ok = false; } else setError('password-error', '');
  if (!ok) return;

  await busy($('password-submit'), async () => {
    const { data, error } = await sb.auth.signInWithPassword({ email, password });
    $('password').value = '';
    if (error) {
      const raw = error.message || '';
      if (/banned/i.test(raw)) {
        showMessage('danger', 'Account suspended', 'This account has been suspended. Contact another administrator.');
      } else if (/rate limit|too many/i.test(raw) || error.status === 429) {
        showMessage('warn', 'Too many attempts', 'Wait a few minutes before trying again.');
      } else if (/fetch|network/i.test(raw)) {
        showMessage('danger', 'No connection', messageOf(error));
      } else {
        // Deliberately vague: never say whether the email exists.
        showMessage('danger', 'Could not sign in', 'Incorrect email or password.');
      }
      return;
    }
    await afterPassword(data.user);
  });
});

// ---------------------------------------------------------------------------
// Step 2: is this an administrator?
// ---------------------------------------------------------------------------

async function afterPassword(user) {
  // Everyone may read their own profile row, even before the 6-digit code.
  const { data: profile, error } = await sb
    .from('profiles')
    .select('role, suspended, full_name')
    .eq('id', user.id)
    .maybeSingle();

  if (error) {
    await endSession();
    showMessage('danger', 'Could not sign in', messageOf(error));
    return resetToPassword();
  }

  if (!profile || profile.role !== 'administrator') {
    await logEvent('not_admin', profile ? `Role: ${profile.role}` : 'No profile');
    await endSession();
    showMessage('danger', 'Not an administrator', 'This account is not an administrator. The attempt has been recorded.');
    return resetToPassword();
  }

  if (profile.suspended) {
    await endSession();
    showMessage('danger', 'Account suspended', 'This administrator account has been suspended.');
    return resetToPassword();
  }

  await logEvent('password_ok');
  await chooseSecondStep();
}

// ---------------------------------------------------------------------------
// Step 3: the 6-digit code
// ---------------------------------------------------------------------------

async function chooseSecondStep() {
  const { data, error } = await sb.auth.mfa.listFactors();
  if (error) {
    await endSession();
    showMessage('danger', 'Could not sign in', messageOf(error));
    return resetToPassword();
  }

  // data.totp lists only authenticators that were set up completely.
  const verified = (data.totp || []).filter((f) => f.status === 'verified');
  if (verified.length > 0) {
    factorId = verified[0].id;
    showStep('code');
    return;
  }
  await beginEnrolment(data.all || []);
}

/** First sign-in: show a QR code for the authenticator app. */
async function beginEnrolment(allFactors) {
  // Remove half-finished set-ups from earlier attempts.
  for (const f of allFactors) {
    if (f.factor_type === 'totp' && f.status !== 'verified') {
      await sb.auth.mfa.unenroll({ factorId: f.id });
    }
  }

  const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ');
  const { data, error } = await sb.auth.mfa.enroll({
    factorType: 'totp',
    friendlyName: `Admin portal ${stamp}`,
    issuer: 'TenderTrack',
  });
  if (error) {
    await endSession();
    const raw = error.message || '';
    showMessage('danger', 'Two-factor sign-in is not available',
      /disabled|not enabled/i.test(raw)
        ? 'Turn on TOTP multi-factor authentication in Supabase (Authentication → Multi-Factor), then sign in again.'
        : messageOf(error));
    return resetToPassword();
  }

  factorId = data.id;
  $('qr').src = data.totp.qr_code;                       // a data: image, allowed by the page's security policy
  $('secret').textContent = (data.totp.secret.match(/.{1,4}/g) || []).join(' ');
  showStep('enrol');
}

steps.enrol.addEventListener('submit', async (event) => {
  event.preventDefault();
  const code = $('enrol-code').value.replace(/\D/g, '');
  if (code.length !== 6) return setError('enrol-error', 'Enter the 6-digit code from the app.');
  setError('enrol-error', '');

  await busy($('enrol-submit'), async () => {
    const { error } = await sb.auth.mfa.challengeAndVerify({ factorId, code });
    if (error) {
      $('enrol-code').value = '';
      await logEvent('mfa_failed', 'During set-up');
      setError('enrol-error', 'That code is not right. Check that the time on your phone is set automatically, then type the newest code.');
      return;
    }
    await logEvent('mfa_enrolled');
    await finish();
  });
});

steps.code.addEventListener('submit', async (event) => {
  event.preventDefault();
  const code = $('code').value.replace(/\D/g, '');
  if (code.length !== 6) return setError('code-error', 'Enter the 6-digit code from the app.');
  setError('code-error', '');

  await busy($('code-submit'), async () => {
    const { error } = await sb.auth.mfa.challengeAndVerify({ factorId, code });
    if (error) {
      wrongCodes += 1;
      $('code').value = '';
      await logEvent('mfa_failed', `Attempt ${wrongCodes}`);
      if (wrongCodes >= MAX_WRONG_CODES) {
        await endSession();
        showMessage('danger', 'Too many wrong codes', 'You have been signed out. The attempts have been recorded.');
        return resetToPassword();
      }
      const left = MAX_WRONG_CODES - wrongCodes;
      setError('code-error', `That code is not right. ${left} ${left === 1 ? 'attempt' : 'attempts'} left.`);
      return;
    }
    await finish();
  });
});

/** Submit automatically once six digits are typed. Digits only. */
for (const [inputId, form] of [['code', steps.code], ['enrol-code', steps.enrol]]) {
  $(inputId).addEventListener('input', (event) => {
    const input = event.target;
    input.value = input.value.replace(/\D/g, '').slice(0, 6);
    const button = form.querySelector('button[type="submit"]');
    if (input.value.length === 6 && !button.disabled) form.requestSubmit();
  });
}

// ---------------------------------------------------------------------------
// Step 4: open the portal
// ---------------------------------------------------------------------------

async function finish() {
  try {
    await rpc('admin_whoami');                           // the database confirms: administrator + two-factor
  } catch (e) {
    await endSession();
    showMessage('danger', 'Access refused', e.message);
    return resetToPassword();
  }
  await logEvent('mfa_verified');
  window.location.replace('/admin/portal');
}

$('back').addEventListener('click', async () => {
  await logEvent('signed_out', 'Left the sign-in page');
  await endSession();
  hideMessage();
  resetToPassword();
});

// ---------------------------------------------------------------------------
// Opening the page
// ---------------------------------------------------------------------------

async function start() {
  const reason = new URLSearchParams(window.location.search).get('reason');
  if (reason && REASONS[reason]) showMessage(...REASONS[reason]);
  if (window.location.search) window.history.replaceState(null, '', '/admin/');

  const { data: { session } } = await sb.auth.getSession();
  if (!session) return showStep('password');

  // A session already exists in this tab (for example after a page refresh).
  const { data: level } = await sb.auth.mfa.getAuthenticatorAssuranceLevel();
  if (level && level.currentLevel === 'aal2') {
    try {
      await rpc('admin_whoami');
      window.location.replace('/admin/portal');
      return;
    } catch {
      await endSession();
      return showStep('password');
    }
  }
  await afterPassword(session.user);                     // password done, code still needed
}

start().catch((e) => {
  showMessage('danger', 'Something went wrong', messageOf(e));
  showStep('password');
});
