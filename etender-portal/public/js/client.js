/*
 * The portal's connection to Supabase (the same database as the TenderTrack
 * app) and to its own server. The browser only ever holds the PUBLIC key and
 * the signed-in person's own session; the database decides what they may do.
 */

const config = window.ET_CONFIG || {};

if (!window.supabase || !config.supabaseUrl) {
  document.body.textContent = 'The portal is not configured. Start it with "node server.js" and open http://localhost:5070.';
  throw new Error('eTender demo portal: missing configuration.');
}

export const sb = window.supabase.createClient(config.supabaseUrl, config.anonKey, {
  auth: {
    storage: window.sessionStorage,     // signed out when the tab is closed
    storageKey: 'etender-demo',
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: false,
  },
});

export const REAL_EMAIL = Boolean(config.realEmail);
export const PORTAL_VERSION = String(config.version || 'unknown');

export function messageOf(error) {
  if (!error) return 'Something went wrong. Please try again.';
  if (typeof error === 'string') return error;
  const raw = error.message || error.error_description || error.msg || '';
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) {
    return 'Could not reach the server. Check the internet connection and that "node server.js" is still running.';
  }
  if (/Invalid login credentials/i.test(raw)) return 'Incorrect email or password.';
  if (/banned/i.test(raw)) return 'This account has been suspended. Contact the department.';
  if (/JWT expired|invalid JWT/i.test(raw)) return 'Your session has expired. Sign in again.';
  if (error.hint === 'mfa_required' || /Two-factor sign-in required/i.test(raw)) {
    return 'Two-factor sign-in is needed: sign in again and enter the 6-digit code from your authenticator app.';
  }
  if (/permission denied|row-level security/i.test(raw)) return 'Your account is not allowed to do that.';
  return raw || 'Something went wrong. Please try again.';
}

/** Calls a database function; throws an Error with a readable message. */
export async function rpc(name, args = {}) {
  const { data, error } = await sb.rpc(name, args);
  if (error) throw new Error(messageOf(error));
  return data;
}

/** Runs a query and returns its rows; throws an Error with a readable message. */
export async function rows(query) {
  const { data, error } = await query;
  if (error) throw new Error(messageOf(error));
  return data || [];
}

/** Calls this portal's own server. */
export async function api(method, path, body, auth = false) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) {
    const { data: { session } } = await sb.auth.getSession();
    if (!session) throw new Error('Sign in first.');
    headers.Authorization = `Bearer ${session.access_token}`;
  }
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new Error('Could not reach the portal server. Is "node server.js" still running?');
  }
  let data = {};
  try { data = await res.json(); } catch { /* empty */ }
  if (!res.ok) throw new Error(data.error || `The portal server answered ${res.status}.`);
  return data;
}

/** Asks the server to send queued emails now instead of at its next check. */
export function flushOutbox() {
  return api('POST', '/api/outbox/run', {}).catch(() => {});
}

/** Accounts that sign in with a password and then a 6-digit code (two-factor). */
export const TWO_FACTOR_ROLES = ['supplier', 'procurement_officer', 'auditor'];

/**
 * Where the signed-in account stands with two-factor sign-in:
 * { required, satisfied, aal, verified_factor_id, in_grace }.
 * The database decides; it refuses everything else until the code is entered.
 */
export async function twoFactorStatus() {
  const { data, error } = await sb.rpc('mfa_status');
  if (error) throw new Error(messageOf(error));
  return data || { required: false, satisfied: true };
}

/**
 * Who is signed in: { session, profile, supplier, codePending } or null.
 * profile.role decides where they go: supplier or procurement_officer.
 * codePending: the password was right but the 6-digit code is still owed.
 */
export async function whoAmI() {
  const { data: { session } } = await sb.auth.getSession();
  if (!session) return null;
  const { data: profile } = await sb.from('profiles')
    .select('id, email, full_name, role, department, suspended').eq('id', session.user.id).maybeSingle();
  if (profile && TWO_FACTOR_ROLES.includes(profile.role)) {
    const status = await twoFactorStatus().catch(() => ({ required: true, satisfied: false }));
    // A new company's own registration session may finish without the code (in_grace).
    if (status.required && !status.satisfied && !status.in_grace) {
      return { session, profile, supplier: null, codePending: true, twoFactor: status };
    }
  }
  let supplier = null;
  if (profile && profile.role === 'supplier') {
    // Lets the database bring the company's registration status up to date first.
    await sb.rpc('tendertrack_registration_status').then(() => null, () => null);
    const { data } = await sb.from('suppliers').select('*').eq('owner_id', session.user.id).maybeSingle();
    supplier = data || null;
  }
  return { session, profile, supplier };
}

export async function signOut(to = '/') {
  try { await sb.auth.signOut(); } catch { /* already signed out */ }
  sessionStorage.removeItem('etender-demo');
  window.location.assign(to);
}
