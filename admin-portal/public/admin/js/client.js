/*
 * The portal's connection to Supabase and to the local portal server.
 *
 * The browser only ever holds the PUBLIC key (the same one the app uses) and
 * the administrator's own session. Everything an administrator can do is
 * decided by the database, which requires two-factor sign-in. Operations
 * that need the secret key go to server.js on this computer instead.
 */

const config = window.TT_CONFIG || {};

if (!window.supabase || !config.supabaseUrl) {
  document.body.textContent = 'The portal is not configured. Start it with "node server.js" and open http://localhost:5050/admin.';
  throw new Error('TenderTrack admin portal: missing configuration.');
}

/**
 * The session is kept in sessionStorage, not localStorage: it disappears when
 * the tab or browser is closed, so nobody can reopen the portal later on a
 * shared computer and find the administrator still signed in.
 */
export const sb = window.supabase.createClient(config.supabaseUrl, config.anonKey, {
  auth: {
    storage: window.sessionStorage,
    storageKey: 'tendertrack-admin',
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: false,
  },
});

export const IDLE_MINUTES = config.idleMinutes || 15;

/**
 * How long each portal page took to load in this session, in milliseconds.
 * The Activity page compares the average with the 3-second target (NFR).
 */
export const timings = [];
export function recordTiming(page, ms) {
  timings.push({ page, ms: Math.round(ms), at: new Date().toISOString() });
  if (timings.length > 200) timings.shift();
}

/** Turns any error into a sentence for the administrator. */
export function messageOf(error) {
  if (!error) return 'Something went wrong. Please try again.';
  if (typeof error === 'string') return error;
  const raw = error.message || error.error_description || error.msg || '';
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) {
    return 'Could not reach the server. Check the internet connection and that "node server.js" is still running.';
  }
  if (/JWT expired|invalid JWT|session.*(missing|expired)/i.test(raw)) {
    return 'Your session has expired. Sign in again.';
  }
  return raw || 'Something went wrong. Please try again.';
}

/** Calls a database function. Throws an Error with a readable message. */
export async function rpc(name, args = {}) {
  const { data, error } = await sb.rpc(name, args);
  if (error) throw new Error(messageOf(error));
  return data;
}

/**
 * Reads every row of a query, 1000 at a time (Supabase returns at most 1000
 * rows per request). `build` returns a fresh query each time.
 */
export async function fetchAll(build, limit = 20000) {
  const rows = [];
  for (let from = 0; from < limit; from += 1000) {
    const { data, error } = await build().range(from, from + 999);
    if (error) throw new Error(messageOf(error));
    rows.push(...(data || []));
    if (!data || data.length < 1000) break;
  }
  return rows;
}

/** Calls the local portal server (server.js) with the administrator's session. */
export async function api(method, path, body) {
  const { data: { session } } = await sb.auth.getSession();
  if (!session) throw new Error('Your session has expired. Sign in again.');
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.access_token}` },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error('Could not reach the portal server. Is "node server.js" still running?');
  }
  let data = {};
  try { data = await res.json(); } catch { /* empty body */ }
  if (!res.ok) throw new Error(data.error || `The portal server answered ${res.status}.`);
  return data;
}

/** Records a step of signing in or out (see log_portal_event in the database). */
export async function logEvent(event, detail = '') {
  try {
    await sb.rpc('log_portal_event', { p_event: event, p_detail: detail, p_user_agent: navigator.userAgent.slice(0, 300) });
  } catch {
    // Logging must never stop the administrator from working.
  }
}

/** Signs out and returns to the sign-in page, with a reason to show there. */
export async function signOut(reason) {
  try { await sb.auth.signOut(); } catch { /* already signed out */ }
  sessionStorage.removeItem('tendertrack-admin');
  window.location.replace(`/admin/${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`);
}
