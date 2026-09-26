/*
 * TenderTrack — admin portal server
 *
 * Runs on the administrator's own computer and does two jobs:
 *
 *   1. Serves the admin portal (HTML, CSS, JavaScript) at
 *      http://localhost:5050/admin — and nothing else. It listens on the
 *      loopback address only, so no other computer, even on the same Wi-Fi,
 *      can reach it.
 *
 *   2. Performs the few account operations that need Supabase's secret key:
 *      creating a login, blocking or unblocking it, setting a temporary
 *      password and deleting it. The secret key stays in the .env file on
 *      this computer; it is never sent to the browser.
 *
 * Every operation is checked by the database first: the caller's own session
 * must belong to an active administrator who completed two-factor sign-in,
 * and the database applies the rules (not your own account, not the last
 * administrator, ...) and writes the audit trail before anything happens.
 *
 * No packages to install: this uses only what ships with Node.js (20 or newer).
 * Start it with:  node server.js
 */

'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// ---------------------------------------------------------------------------
// Configuration (.env in this folder)
// ---------------------------------------------------------------------------

/** Reads KEY=value lines from .env. Blank lines and # comments are ignored. */
function loadEnv(file) {
  const env = {};
  if (!fs.existsSync(file)) return env;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    env[key] = value;
  }
  return env;
}

const ROOT = __dirname;
const ENV = { ...loadEnv(path.join(ROOT, '.env')), ...process.env };
const PORT = Number(ENV.ADMIN_PORT || 5050);
const HOST = '127.0.0.1'; // loopback only — deliberately not configurable
const SUPABASE_URL = String(ENV.SUPABASE_URL || '').replace(/\/+$/, '');
const ANON_KEY = String(ENV.SUPABASE_ANON_KEY || '');
const SECRET_KEY = String(ENV.SUPABASE_SECRET_KEY || '');
const IDLE_MINUTES = Math.max(5, Math.min(60, Number(ENV.IDLE_MINUTES || 15)));
const PUBLIC_DIR = path.join(ROOT, 'public');

/** Decodes a JWT's payload without verifying it — only used to sanity-check keys at start-up. */
function jwtPayload(token) {
  try {
    const part = token.split('.')[1];
    return JSON.parse(Buffer.from(part.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
  } catch {
    return null;
  }
}

/** Refuses to start with a configuration that would not work, and says why. */
function checkConfig() {
  const problems = [];
  if (!/^https:\/\/[a-z0-9-]+\.supabase\.co$/.test(SUPABASE_URL)) {
    problems.push('SUPABASE_URL must look like https://abcdefgh.supabase.co (nothing after .supabase.co).');
  }
  if (!ANON_KEY) problems.push('SUPABASE_ANON_KEY is missing (the same public key the app uses).');
  if (!SECRET_KEY) problems.push('SUPABASE_SECRET_KEY is missing (Project Settings -> API Keys).');
  if (SECRET_KEY && SECRET_KEY === ANON_KEY) {
    problems.push('SUPABASE_SECRET_KEY is the same as the anon key. Use the secret (service role) key.');
  }
  if (SECRET_KEY.startsWith('sb_publishable_')) {
    problems.push('SUPABASE_SECRET_KEY is a publishable key. Use the key starting sb_secret_ (or the legacy service_role key).');
  }
  if (SECRET_KEY.startsWith('eyJ') && jwtPayload(SECRET_KEY)?.role !== 'service_role') {
    problems.push('SUPABASE_SECRET_KEY is a JWT but not the service_role key.');
  }
  if (ANON_KEY.startsWith('sb_secret_') || jwtPayload(ANON_KEY)?.role === 'service_role') {
    problems.push('SUPABASE_ANON_KEY is a secret key. It goes to the browser, so it must be the public anon or publishable key.');
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Talking to Supabase
// ---------------------------------------------------------------------------

/** A failure with a message that is safe to show to the administrator. */
class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * Calls a database function as the signed-in administrator, using their own
 * session. The database checks who they are and applies its rules.
 */
async function rpc(name, args, userToken) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: 'POST',
    headers: {
      apikey: ANON_KEY,
      Authorization: `Bearer ${userToken}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(args || {}),
  });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!res.ok) {
    const message = (body && (body.message || body.msg)) || `Database request failed (${res.status}).`;
    // PostgREST answers 401 for an expired or invalid session.
    throw new HttpError(res.status === 401 ? 401 : 403, message);
  }
  return body;
}

/** Headers for Supabase Auth's admin API, using the secret key. */
function adminHeaders() {
  const headers = { apikey: SECRET_KEY, 'Content-Type': 'application/json', Accept: 'application/json' };
  // New secret keys (sb_secret_...) go only in the apikey header; the older
  // service_role JWT key is also sent as a bearer token.
  if (SECRET_KEY.startsWith('eyJ')) headers.Authorization = `Bearer ${SECRET_KEY}`;
  return headers;
}

/** Calls Supabase Auth's admin API. Only this server ever does. */
async function authAdmin(method, route, body) {
  const res = await fetch(`${SUPABASE_URL}/auth/v1/admin/${route}`, {
    method,
    headers: adminHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    throw new HttpError(502, friendlyAuthError(data, res.status));
  }
  return data;
}

/** Supabase Auth's errors, in plain language. */
function friendlyAuthError(data, status) {
  const raw = (data && (data.msg || data.message || data.error_description || data.error)) || '';
  const code = (data && (data.error_code || data.code)) || '';
  if (code === 'email_exists' || /already been registered/i.test(raw)) {
    return 'An account with this email already exists.';
  }
  if (code === 'weak_password' || /password/i.test(raw) && /weak|characters|should/i.test(raw)) {
    return 'Supabase rejected the temporary password as too weak. Check Authentication -> Providers -> Email -> password requirements.';
  }
  if (/Database error/i.test(raw)) {
    return 'The database refused the new account. Check the role and department, then try again.';
  }
  if (status === 401 || status === 403 || /invalid api key|jwt/i.test(raw)) {
    return 'Supabase refused the secret key. Check SUPABASE_SECRET_KEY in the .env file.';
  }
  return raw || `Supabase Auth request failed (${status}).`;
}

// ---------------------------------------------------------------------------
// Temporary passwords
// ---------------------------------------------------------------------------

/**
 * A 14-character password such as "K7mQ-x9pR-t4wZ" with upper and lower case
 * letters, digits and symbols (the dashes), so it passes Supabase's strictest
 * password rules. Letters that are easily confused (0/O, 1/l/I) are left out, because
 * the administrator reads it out or hands it over in person.
 */
function temporaryPassword() {
  const upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  const lower = 'abcdefghijkmnpqrstuvwxyz';
  const digits = '23456789';
  const all = upper + lower + digits;
  const pick = (set) => set[crypto.randomInt(set.length)];
  for (;;) {
    const chars = Array.from({ length: 12 }, () => pick(all));
    const text = chars.join('');
    if (/[A-Z]/.test(text) && /[a-z]/.test(text) && /[0-9]/.test(text)) {
      return `${text.slice(0, 4)}-${text.slice(4, 8)}-${text.slice(8, 12)}`;
    }
  }
}

// ---------------------------------------------------------------------------
// The API the portal calls
// ---------------------------------------------------------------------------

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Confirms the caller is an active administrator with two-factor sign-in. */
async function requireAdmin(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) throw new HttpError(401, 'Sign in first.');
  const admin = await rpc('admin_whoami', {}, token);
  if (!admin || admin.role !== 'administrator' || admin.aal !== 'aal2') {
    throw new HttpError(403, 'Only an administrator signed in with two-factor authentication can do that.');
  }
  return { token, admin };
}

/** After a step fails in Supabase Auth, records that in the audit trail. */
async function reportFailure(token, userId, action, error) {
  try {
    await rpc('admin_report_auth_failure', { p_user_id: userId, p_action: action, p_message: error.message }, token);
  } catch {
    // The original error is what matters to the administrator.
  }
}

const routes = [
  // CREATE — a new staff account
  {
    method: 'POST', pattern: /^\/api\/accounts$/,
    async handle(req, body, { token, admin }) {
      const checked = await rpc('admin_check_new_account', {
        p_email: body.email, p_full_name: body.fullName, p_role: body.role, p_department: body.department || null,
      }, token);
      const password = temporaryPassword();
      const user = await authAdmin('POST', 'users', {
        email: checked.email,
        password,
        email_confirm: true, // no confirmation email needed
        user_metadata: { full_name: checked.full_name },
        // Only the secret key can set app_metadata, which is why the database
        // trusts it to carry the role (see apply_admin_created_role).
        app_metadata: {
          tt_role: checked.role,
          tt_department: checked.department || '',
          tt_full_name: checked.full_name,
          tt_created_by: admin.full_name,
        },
      });
      log(admin, `created ${checked.email} as ${checked.role}`);
      return { status: 201, body: { id: user.id, email: checked.email, role: checked.role, temporaryPassword: password } };
    },
  },
  // UPDATE (soft delete) — suspend
  {
    method: 'POST', pattern: /^\/api\/accounts\/([^/]+)\/suspend$/,
    async handle(req, body, { token, admin }, id) {
      const account = await rpc('admin_set_suspended', { p_user_id: id, p_suspended: true, p_reason: body.reason }, token);
      try {
        await authAdmin('PUT', `users/${id}`, { ban_duration: '876000h' }); // about 100 years
      } catch (error) {
        await reportFailure(token, id, 'Sign-in block', error);
        return { status: 200, body: { account, warning: `Suspended in the database, but the sign-in block failed: ${error.message}` } };
      }
      log(admin, `suspended ${account.email}`);
      return { status: 200, body: { account } };
    },
  },
  // UPDATE — reinstate
  {
    method: 'POST', pattern: /^\/api\/accounts\/([^/]+)\/reinstate$/,
    async handle(req, body, { token, admin }, id) {
      const account = await rpc('admin_set_suspended', { p_user_id: id, p_suspended: false, p_reason: null }, token);
      try {
        await authAdmin('PUT', `users/${id}`, { ban_duration: 'none' });
      } catch (error) {
        await reportFailure(token, id, 'Sign-in unblock', error);
        return { status: 200, body: { account, warning: `Reinstated in the database, but the sign-in unblock failed: ${error.message}` } };
      }
      log(admin, `reinstated ${account.email}`);
      return { status: 200, body: { account } };
    },
  },
  // UPDATE — temporary password
  {
    method: 'POST', pattern: /^\/api\/accounts\/([^/]+)\/reset-password$/,
    async handle(req, body, { token, admin }, id) {
      const account = await rpc('admin_prepare_password_reset', { p_user_id: id }, token);
      const password = temporaryPassword();
      try {
        await authAdmin('PUT', `users/${id}`, { password });
      } catch (error) {
        await reportFailure(token, id, 'Password reset', error);
        throw error;
      }
      log(admin, `reset the password of ${account.email}`);
      return { status: 200, body: { email: account.email, temporaryPassword: password } };
    },
  },
  // DELETE — only accounts that never created records
  {
    method: 'DELETE', pattern: /^\/api\/accounts\/([^/]+)$/,
    async handle(req, body, { token, admin }, id) {
      const account = await rpc('admin_prepare_delete', { p_user_id: id }, token);
      try {
        await authAdmin('DELETE', `users/${id}`);
      } catch (error) {
        await reportFailure(token, id, 'Delete', error);
        throw error;
      }
      log(admin, `deleted ${account.email}`);
      return { status: 200, body: { deleted: account.email } };
    },
  },
];

function log(admin, what) {
  console.log(`${new Date().toISOString()}  ${admin.email}  ${what}`);
}

// ---------------------------------------------------------------------------
// HTTP: security checks, static files and the API
// ---------------------------------------------------------------------------

/** Host names this server answers to. Anything else is refused (DNS rebinding). */
const ALLOWED_HOSTS = new Set([
  `localhost:${PORT}`, `127.0.0.1:${PORT}`, `[::1]:${PORT}`, `tendertrack.local:${PORT}`,
]);
const ALLOWED_ORIGINS = new Set([...ALLOWED_HOSTS].map((h) => `http://${h}`));

function securityHeaders(res) {
  const supabase = SUPABASE_URL || 'https://*.supabase.co';
  res.setHeader('Content-Security-Policy', [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self' ${supabase} ${supabase.replace('https://', 'wss://')}`,
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "object-src 'none'",
  ].join('; '));
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Robots-Tag', 'noindex, nofollow');
}

function send(res, status, body, type = 'application/json; charset=utf-8') {
  res.statusCode = status;
  res.setHeader('Content-Type', type);
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
};

/** Serves a file from public/admin, refusing anything outside it. */
function serveStatic(res, urlPath) {
  const base = path.join(PUBLIC_DIR, 'admin');
  let relative;
  try {
    relative = decodeURIComponent(urlPath.replace(/^\/admin\/?/, '')) || 'index.html';
  } catch {
    return send(res, 400, 'Bad request', 'text/plain');
  }
  const file = path.resolve(base, relative === 'portal' ? 'portal.html' : relative);
  if (!file.startsWith(base + path.sep) && file !== base) return send(res, 404, 'Not found', 'text/plain');
  const type = MIME[path.extname(file).toLowerCase()];
  if (!type || !fs.existsSync(file) || !fs.statSync(file).isFile()) return send(res, 404, 'Not found', 'text/plain');
  send(res, 200, fs.readFileSync(file), type);
}

/** The browser's configuration: the project URL and the PUBLIC key only. */
function configScript() {
  const config = { supabaseUrl: SUPABASE_URL, anonKey: ANON_KEY, idleMinutes: IDLE_MINUTES };
  return `window.TT_CONFIG = Object.freeze(${JSON.stringify(config)});\n`;
}

/** A small per-address limit on API calls. */
const hits = new Map();
function rateLimited(key) {
  const now = Date.now();
  const recent = (hits.get(key) || []).filter((t) => now - t < 60_000);
  recent.push(now);
  hits.set(key, recent);
  return recent.length > 30;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    let tooLarge = false;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 10_000) {
        // Answer straight away and ignore the rest; Node closes the connection.
        if (!tooLarge) reject(new HttpError(413, 'Request too large.'));
        tooLarge = true;
      } else {
        chunks.push(chunk);
      }
    });
    req.on('end', () => {
      if (tooLarge) return;
      if (!chunks.length) return resolve({});
      try {
        const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        resolve(value && typeof value === 'object' ? value : {});
      } catch {
        reject(new HttpError(400, 'The request was not valid JSON.'));
      }
    });
    req.on('error', reject);
  });
}

async function handleApi(req, res, urlPath) {
  // Requests must come from the portal page itself (blocks other websites).
  if (!ALLOWED_ORIGINS.has(req.headers.origin || '')) {
    return send(res, 403, { error: 'Requests must come from the admin portal.' });
  }
  if (req.method !== 'DELETE' && !(req.headers['content-type'] || '').startsWith('application/json')) {
    return send(res, 415, { error: 'Expected JSON.' });
  }
  if (rateLimited(req.socket.remoteAddress || 'local')) {
    return send(res, 429, { error: 'Too many requests. Wait a minute and try again.' });
  }

  const route = routes.find((r) => r.method === req.method && r.pattern.test(urlPath));
  if (!route) return send(res, 404, { error: 'Not found.' });
  const id = (urlPath.match(route.pattern) || [])[1];
  if (id !== undefined && !UUID.test(id)) return send(res, 400, { error: 'That is not a valid account id.' });

  try {
    const body = await readJson(req);
    const caller = await requireAdmin(req);
    const result = await route.handle(req, body, caller, id);
    send(res, result.status, result.body);
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    const message = error instanceof HttpError ? error.message : 'Something went wrong on the portal server.';
    if (!(error instanceof HttpError)) console.error(error);
    send(res, status, { error: message });
  }
}

function createServer() {
  return http.createServer((req, res) => {
    securityHeaders(res);

    if (!ALLOWED_HOSTS.has(String(req.headers.host || '').toLowerCase())) {
      return send(res, 403, 'Forbidden', 'text/plain');
    }

    const urlPath = new URL(req.url, 'http://localhost').pathname;

    if (urlPath.startsWith('/api/')) return void handleApi(req, res, urlPath);

    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed', 'text/plain');
    if (urlPath === '/admin') {
      res.statusCode = 302;
      res.setHeader('Location', '/admin/');
      return res.end();
    }
    if (urlPath === '/admin/config.js') return send(res, 200, configScript(), MIME['.js']);
    if (urlPath.startsWith('/admin/')) return serveStatic(res, urlPath);

    // Nothing lives outside /admin — not even a home page.
    send(res, 404, 'Not found', 'text/plain');
  });
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

if (require.main === module) {
  const problems = checkConfig();
  if (problems.length) {
    console.error('\nThe admin portal cannot start:\n');
    for (const p of problems) console.error(`  - ${p}`);
    console.error('\nEdit the .env file in this folder (copy .env.example if it is missing), then run node server.js again.\n');
    process.exit(1);
  }
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 20) {
    console.error(`Node.js 20 or newer is needed (this is ${process.versions.node}). Install the LTS version from nodejs.org.`);
    process.exit(1);
  }
  const server = createServer();
  server.on('error', (error) => {
    if (error.code === 'EADDRINUSE') {
      console.error(`\nPort ${PORT} is already in use: the portal may already be running in another window.`);
      console.error('Close that window, or set ADMIN_PORT=5051 in .env.\n');
    } else {
      console.error(`\nThe admin portal could not start: ${error.message}\n`);
    }
    process.exit(1);
  });
  server.listen(PORT, HOST, () => {
    console.log('\n  TenderTrack admin portal');
    console.log(`  Open  http://localhost:${PORT}/admin`);
    console.log('  Reachable from this computer only. Press Ctrl+C to stop.\n');
  });
}

module.exports = { createServer, temporaryPassword, checkConfig, friendlyAuthError };
