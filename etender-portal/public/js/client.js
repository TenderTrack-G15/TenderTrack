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

/**
 * Who is signed in: { session, profile, supplier } or null.
 * profile.role decides where they go: supplier or procurement_officer.
 */
export async function whoAmI() {
  const { data: { session } } = await sb.auth.getSession();
  if (!session) return null;
  const { data: profile } = await sb.from('profiles')
    .select('id, email, full_name, role, department, suspended').eq('id', session.user.id).maybeSingle();
  let supplier = null;
  if (profile && profile.role === 'supplier') {
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
