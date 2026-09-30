/*
 * eTender Demo Portal — server
 *
 * A demonstration procurement portal for the TenderTrack student project. It
 * stands in for the government's own tender website and uses the same
 * Supabase database as the TenderTrack app. It is NOT a government website.
 *
 * This server does four jobs:
 *   1. Serves the portal at http://localhost:5070 — on this computer only.
 *   2. Registers a new supplier's login, and records the company as
 *      registered on the portal once its registration is complete (both need
 *      Supabase's secret key, which never reaches the browser).
 *   3. Delivers the database's email outbox: award codes and bid receipts.
 *      With SMTP settings in .env it sends real emails (for example through
 *      Gmail); without them it keeps them in a demo mailbox on this computer.
 *   4. Shows a signed-in supplier the demo mailbox messages meant for them.
 *
 * No packages to install: only what ships with Node.js 20 or newer.
 * Start it with:  node server.js
 */

'use strict';

const http = require('node:http');
const net = require('node:net');
const tls = require('node:tls');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// ---------------------------------------------------------------------------
// Configuration (.env in this folder)
// ---------------------------------------------------------------------------

function loadEnv(file) {
  const env = {};
  if (!fs.existsSync(file)) return env;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    env[line.slice(0, eq).trim()] = value;
  }
  return env;
}

const ROOT = __dirname;
/** Shown in the page footer and when the server starts, to check which files are running. */
const PORTAL_VERSION = '2026-09-30b';
const ENV = { ...loadEnv(path.join(ROOT, '.env')), ...process.env };
const PORT = Number(ENV.PORTAL_PORT || 5070);
const HOST = '127.0.0.1'; // loopback only — deliberately not configurable
const SUPABASE_URL = String(ENV.SUPABASE_URL || '').replace(/\/+$/, '');
const ANON_KEY = String(ENV.SUPABASE_ANON_KEY || '');
const SECRET_KEY = String(ENV.SUPABASE_SECRET_KEY || '');
const OUTBOX_SECONDS = Math.max(5, Math.min(300, Number(ENV.OUTBOX_SECONDS || 10)));
const SMTP = {
  host: String(ENV.SMTP_HOST || '').trim(),
  port: Number(ENV.SMTP_PORT || 465),
  tlsMode: String(ENV.SMTP_TLS || '').trim().toLowerCase(),   // ssl | starttls | none (none: localhost only)
  user: String(ENV.SMTP_USER || '').trim(),
  pass: String(ENV.SMTP_PASS || '').replace(/\s+/g, ''),     // Gmail shows app passwords in groups of four
  fromName: String(ENV.SMTP_FROM_NAME || 'eTender Demo Portal').trim(),
};
SMTP.enabled = Boolean(SMTP.host && SMTP.user && SMTP.pass);
if (!SMTP.tlsMode) SMTP.tlsMode = SMTP.port === 465 ? 'ssl' : 'starttls';

/**
 * Who may receive a real email. The seed data has made-up companies whose
 * addresses may belong to real businesses, so by default only addresses at the
 * sending account's own domain (e.g. gmail.com) get real email. Add more in
 * EMAIL_ALLOWLIST: whole addresses or @domains, separated by commas.
 * Everything else goes to the demo mailbox.
 */
const EMAIL_ALLOWLIST = new Set(String(ENV.EMAIL_ALLOWLIST || '').split(',').map((x) => x.trim().toLowerCase()).filter(Boolean));
const SENDER_DOMAIN = (SMTP.user.split('@')[1] || '').toLowerCase();

function mayEmail(address) {
  const to = String(address || '').trim().toLowerCase();
  const domain = to.split('@')[1] || '';
  return Boolean(domain) && (domain === SENDER_DOMAIN || EMAIL_ALLOWLIST.has(to) || EMAIL_ALLOWLIST.has(`@${domain}`));
}

const PUBLIC_DIR = path.join(ROOT, 'public');
const DATA_DIR = path.join(ROOT, 'data');
const MAILBOX_FILE = path.join(DATA_DIR, 'mailbox.json');

function jwtPayload(token) {
  try {
    return JSON.parse(Buffer.from(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
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
  if (SECRET_KEY && SECRET_KEY === ANON_KEY) problems.push('SUPABASE_SECRET_KEY is the same as the public key.');
  if (SECRET_KEY.startsWith('sb_publishable_')) problems.push('SUPABASE_SECRET_KEY is a publishable key. Use the sb_secret_ key.');
  if (SECRET_KEY.startsWith('eyJ') && jwtPayload(SECRET_KEY)?.role !== 'service_role') {
    problems.push('SUPABASE_SECRET_KEY is a JWT but not the service_role key.');
  }
  if (ANON_KEY.startsWith('sb_secret_') || jwtPayload(ANON_KEY)?.role === 'service_role') {
    problems.push('SUPABASE_ANON_KEY is a secret key. It goes to the browser, so it must be the public key.');
  }
  // Browsers refuse to open these ports (ERR_UNSAFE_PORT), so the portal would not load.
  const BLOCKED_BY_BROWSERS = [2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080];
  if (!Number.isInteger(PORT) || PORT < 1024 || PORT > 65535 || BLOCKED_BY_BROWSERS.includes(PORT)) {
    problems.push(`PORTAL_PORT=${ENV.PORTAL_PORT} cannot be used (browsers block some ports). Use 5070.`);
  }
  if ((ENV.SMTP_HOST || ENV.SMTP_USER || ENV.SMTP_PASS) && !SMTP.enabled) {
    problems.push('SMTP settings are incomplete: set SMTP_HOST, SMTP_USER and SMTP_PASS, or remove all three.');
  }
  if (SMTP.enabled && !['ssl', 'starttls', 'none'].includes(SMTP.tlsMode)) {
    problems.push('SMTP_TLS must be ssl, starttls or none.');
  }
  if (SMTP.enabled && SMTP.tlsMode === 'none' && !['127.0.0.1', 'localhost'].includes(SMTP.host)) {
    problems.push('SMTP_TLS=none is only allowed for a mail server on this computer.');
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function secretHeaders(extra = {}) {
  const headers = { apikey: SECRET_KEY, 'Content-Type': 'application/json', Accept: 'application/json', ...extra };
  if (SECRET_KEY.startsWith('eyJ')) headers.Authorization = `Bearer ${SECRET_KEY}`;
  return headers;
}

async function readBody(res) {
  const text = await res.text();
  try { return text ? JSON.parse(text) : null; } catch { return text; }
}

/** PostgREST as the server itself (secret key). Used only for the email outbox. */
async function restAsServer(method, route, body, extraHeaders) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${route}`, {
    method,
    headers: secretHeaders(extraHeaders),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await readBody(res);
  if (!res.ok) throw new Error((data && (data.message || data.msg)) || `Supabase answered ${res.status}`);
  return data;
}

/** A database function called with the public key (as an anonymous visitor). */
async function rpcAnon(name, args) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: 'POST',
    headers: { apikey: ANON_KEY, Authorization: `Bearer ${ANON_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args || {}),
  });
  const data = await readBody(res);
  if (!res.ok) throw new HttpError(400, (data && data.message) || 'The database refused the request.');
  return data;
}

/** Who a browser session belongs to (checked by Supabase Auth itself). */
async function userFromToken(token) {
  const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: ANON_KEY, Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new HttpError(401, 'Your session has expired. Sign in again.');
  return res.json();
}

/** The caller's own supplier row, read with their own session (RLS applies). */
async function supplierOf(token, userId) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/suppliers?select=id,company_name,contact_email&owner_id=eq.${encodeURIComponent(userId)}`, {
    headers: { apikey: ANON_KEY, Authorization: `Bearer ${token}`, Accept: 'application/json' },
  });
  const rows = await readBody(res);
  return Array.isArray(rows) && rows.length ? rows[0] : null;
}

function friendlyAuthError(data, status) {
  const raw = (data && (data.msg || data.message || data.error_description || data.error)) || '';
  const code = (data && (data.error_code || data.code)) || '';
  if (code === 'email_exists' || /already been registered/i.test(raw)) {
    return 'An account with this email address already exists. Sign in instead.';
  }
  if (code === 'weak_password' || (/password/i.test(raw) && /weak|characters|should/i.test(raw))) {
    return 'Supabase rejected the password as too weak. Use at least 8 characters with letters and numbers.';
  }
  if (/Database error/i.test(raw)) {
    return 'The registration could not be saved. Check the CSD and company registration numbers, then try again.';
  }
  if (status === 401 || status === 403 || /invalid api key|jwt/i.test(raw)) {
    return 'The portal server is not set up correctly (Supabase refused the secret key). Check .env.';
  }
  return raw || `Supabase Auth request failed (${status}).`;
}

// ---------------------------------------------------------------------------
// Registration — creates the supplier's login
// ---------------------------------------------------------------------------

const RE = {
  email: /^[^@\s]+@[^@\s]+\.[^@\s]+$/,
  registration: /^\d{4}\/\d{6}\/\d{2}$/,
  csd: /^MAAA\d{7}$/,
  mobile: /^(\+27|0)[6-8]\d{8}$/,
};

function clean(value, max = 300) {
  return String(value === undefined || value === null ? '' : value).trim().slice(0, max);
}

async function registerSupplier(body) {
  const email = clean(body.email, 200).toLowerCase();
  const password = String(body.password || '');
  const c = body.company || {};
  const company = {
    company_name: clean(c.company_name, 200),
    registration_number: clean(c.registration_number, 20),
    csd_number: clean(c.csd_number, 20).toUpperCase(),
    business_type: clean(c.business_type, 60),
    tax_number: clean(c.tax_number, 30),
    contact_person: clean(c.contact_person, 120),
    job_title: clean(c.job_title, 120),
    mobile_number: clean(c.mobile_number, 20).replace(/\s+/g, ''),
    province: clean(c.province, 60),
    bbbee_level: c.bbbee_level ? String(Number(c.bbbee_level) || '') : '',
  };

  if (!RE.email.test(email)) throw new HttpError(400, 'Enter a valid email address for signing in.');
  if (password.length < 8 || !/[A-Za-z]/.test(password) || !/\d/.test(password)) {
    throw new HttpError(400, 'The password needs at least 8 characters, with letters and numbers.');
  }
  if (company.company_name.length < 2) throw new HttpError(400, 'Enter the company name.');
  if (!RE.registration.test(company.registration_number)) {
    throw new HttpError(400, 'The company registration number must look like 2019/451236/07.');
  }
  if (!RE.csd.test(company.csd_number)) throw new HttpError(400, 'The CSD number must look like MAAA0451236.');
  if (company.contact_person.length < 2) throw new HttpError(400, 'Enter the contact person.');
  if (!RE.mobile.test(company.mobile_number)) {
    throw new HttpError(400, 'The mobile number must be a South African mobile, e.g. 0821234567.');
  }

  const problem = await rpcAnon('supplier_registration_problem', {
    p_registration_number: company.registration_number, p_csd_number: company.csd_number,
  });
  if (problem) throw new HttpError(409, problem);

  const res = await fetch(`${SUPABASE_URL}/auth/v1/admin/users`, {
    method: 'POST',
    headers: secretHeaders(),
    body: JSON.stringify({
      email,
      password,
      email_confirm: true,
      // Read by handle_new_user(), which creates the supplier registration with
      // status "awaiting verification". Supplier is the only role this can create.
      user_metadata: { account_type: 'supplier', full_name: company.contact_person, representative: company.contact_person, ...company },
    }),
  });
  const data = await readBody(res);
  if (!res.ok) throw new HttpError(res.status === 422 ? 409 : 400, friendlyAuthError(data, res.status));

  log(`registered ${company.company_name} (${company.csd_number}) as ${email}`);
  // The page now saves the profile, compliance, banking and documents with the
  // new login, then calls /api/register/complete, which records the portal
  // registration once everything is there.
  return { ok: true, email };
}

/** The documents a portal registration must include (names as in default_supplier_documents). */
const REQUIRED_DOCUMENTS = [
  'Company registration certificate (CIPC)', 'Tax clearance certificate / PIN', 'Proof of business address',
  'Company profile', 'Proof of banking details',
];

/**
 * Records that the signed-in supplier's company is registered on the portal,
 * once the registration is complete: contact details, banking and the required
 * documents. Called at the end of every registration on the portal, including
 * a company finishing one that was started elsewhere (for example the older
 * sign-up in the app). Only this server can set the flag (secret key), and it
 * checks the records itself rather than trusting the page.
 */
async function completeRegistration(token) {
  const user = await userFromToken(token);
  const rows = await restAsServer('GET',
    `suppliers?select=id,company_name,csd_number,contact_email,contact_person,physical_address,mobile_number,portal_registered_at&owner_id=eq.${encodeURIComponent(user.id)}`);
  const supplier = Array.isArray(rows) ? rows[0] : null;
  if (!supplier) throw new HttpError(404, 'This account has no company registration. Register your company first.');
  if (supplier.portal_registered_at) return { ok: true, already: true, company: supplier.company_name };

  const missing = [];
  if (!supplier.contact_email || !supplier.contact_person || !supplier.physical_address || !supplier.mobile_number) {
    missing.push('contact information');
  }
  const banking = await restAsServer('GET', `supplier_banking?select=supplier_id&supplier_id=eq.${supplier.id}`);
  if (!Array.isArray(banking) || banking.length === 0) missing.push('banking information');
  const documents = await restAsServer('GET', `supplier_documents?select=name,status,reference&supplier_id=eq.${supplier.id}`);
  const provided = new Set((documents || [])
    .filter((d) => ['submitted', 'verified'].includes(d.status) && String(d.reference || '').trim())
    .map((d) => d.name));
  const missingDocuments = REQUIRED_DOCUMENTS.filter((name) => !provided.has(name));
  if (missingDocuments.length) missing.push(`current documents: ${missingDocuments.join('; ')}`);
  if (missing.length) {
    throw new HttpError(400, `The registration is not complete yet. Still needed: ${missing.join(', ')}.`);
  }

  await restAsServer('PATCH', `suppliers?id=eq.${supplier.id}`,
    { portal_registered_at: new Date().toISOString() }, { Prefer: 'return=minimal' });
  log(`portal registration complete: ${supplier.company_name} (${supplier.csd_number})`);
  return { ok: true, already: false, company: supplier.company_name };
}

// ---------------------------------------------------------------------------
// Email: SMTP (real) or the demo mailbox (this computer)
// ---------------------------------------------------------------------------

function encodeHeader(text) {
  return /^[\x20-\x7E]*$/.test(text) ? text : `=?UTF-8?B?${Buffer.from(text, 'utf8').toString('base64')}?=`;
}

function buildMessage({ from, fromName, to, toName, subject, text }) {
  const body = Buffer.from(text.replace(/\r?\n/g, '\r\n'), 'utf8').toString('base64').replace(/.{76}/g, '$&\r\n');
  const domain = from.split('@')[1] || 'localhost';
  return [
    `From: ${encodeHeader(fromName)} <${from}>`,
    `To: ${toName ? `${encodeHeader(toName)} ` : ''}<${to}>`,
    `Subject: ${encodeHeader(subject)}`,
    `Date: ${new Date().toUTCString().replace('GMT', '+0000')}`,
    `Message-ID: <${crypto.randomUUID()}@${domain}>`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=utf-8',
    'Content-Transfer-Encoding: base64',
    '',
    body,
  ].join('\r\n');
}

/** A minimal SMTP client (AUTH LOGIN over TLS), enough for Gmail or Outlook. */
function smtpSend(mail) {
  return new Promise((resolve, reject) => {
    let socket;
    let buffer = '';
    let waiting = null;
    let done = false;
    const timer = setTimeout(() => fail(new Error('The mail server did not answer in time.')), 20000);

    function fail(error) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { socket.destroy(); } catch { /* already closed */ }
      reject(error);
    }

    function onData(chunk) {
      buffer += chunk.toString('utf8');
      const lines = buffer.split('\r\n');
      for (let i = 0; i < lines.length - 1; i += 1) {
        if (/^\d{3} /.test(lines[i]) && waiting) {
          const reply = { code: Number(lines[i].slice(0, 3)), text: lines.slice(0, i + 1).join('\n') };
          buffer = lines.slice(i + 1).join('\r\n');
          const w = waiting;
          waiting = null;
          w(reply);
          return onData('');
        }
      }
    }

    function reply() {
      return new Promise((res) => { waiting = res; onData(''); });
    }

    // label: what an error message shows instead of the command (never the user name or password).
    async function expect(command, codes, label) {
      if (command !== null) socket.write(`${command}\r\n`);
      const r = await reply();
      if (!codes.includes(r.code)) {
        const shown = label || (command ? command.split(':')[0] : 'greeting');
        const hint = r.code === 535 ? ' Check SMTP_USER and SMTP_PASS (for Gmail, an app password).' : '';
        throw new Error(`Mail server refused ${shown}: ${r.text.split('\n').pop()}${hint}`);
      }
      return r;
    }

    function attach(s) {
      socket = s;
      socket.on('data', onData);
      socket.on('error', fail);
    }

    async function conversation() {
      const helloName = 'etender-demo.local';
      await expect(null, [220]);
      await expect(`EHLO ${helloName}`, [250]);
      if (SMTP.tlsMode === 'starttls') {
        await expect('STARTTLS', [220]);
        socket.removeListener('data', onData);
        buffer = '';
        const secure = tls.connect({ socket, servername: SMTP.host });
        await new Promise((res, rej) => { secure.once('secureConnect', res); secure.once('error', rej); });
        attach(secure);
        await expect(`EHLO ${helloName}`, [250]);
      }
      await expect('AUTH LOGIN', [334], 'AUTH');
      await expect(Buffer.from(SMTP.user).toString('base64'), [334], 'the user name');
      await expect(Buffer.from(SMTP.pass).toString('base64'), [235], 'the sign-in');
      await expect(`MAIL FROM:<${SMTP.user}>`, [250]);
      await expect(`RCPT TO:<${mail.to}>`, [250, 251]);
      await expect('DATA', [354]);
      socket.write(`${buildMessage({ from: SMTP.user, fromName: SMTP.fromName, ...mail })}\r\n.\r\n`);
      await expect(null, [250]);
      socket.write('QUIT\r\n');
      done = true;
      clearTimeout(timer);
      socket.end();
      resolve();
    }

    const connected = () => conversation().catch(fail);
    if (SMTP.tlsMode === 'ssl') {
      attach(tls.connect({ host: SMTP.host, port: SMTP.port, servername: SMTP.host }, connected));
    } else {
      attach(net.connect({ host: SMTP.host, port: SMTP.port }, connected));
    }
  });
}

function readMailbox() {
  try { return JSON.parse(fs.readFileSync(MAILBOX_FILE, 'utf8')); } catch { return []; }
}

function writeMailbox(messages) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmp = `${MAILBOX_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(messages.slice(-500), null, 2));
  fs.renameSync(tmp, MAILBOX_FILE);
}

let outboxRunning = false;
let lastOutboxError = '';

/** Sends everything waiting in the database's email outbox. */
async function runOutbox() {
  if (outboxRunning) return { skipped: true };
  outboxRunning = true;
  let sent = 0;
  try {
    const pending = await restAsServer('GET', 'email_outbox?select=*&sent_at=is.null&attempts=lt.5&order=created_at.asc&limit=20');
    for (const item of pending || []) {
      const mail = { to: item.to_email, toName: item.to_name, subject: item.subject, text: item.body };
      try {
        let via;
        if (SMTP.enabled && mayEmail(item.to_email)) {
          await smtpSend(mail);
          via = 'smtp';
        } else {
          const box = readMailbox();
          box.push({ id: item.id, to: item.to_email, toName: item.to_name, toUser: item.to_user || null,
                     subject: item.subject, body: item.body, kind: item.kind, receivedAt: new Date().toISOString() });
          writeMailbox(box);
          via = 'demo-mailbox';
        }
        // The award code leaves the database once it is delivered.
        await restAsServer('PATCH', `email_outbox?id=eq.${item.id}`,
          { sent_at: new Date().toISOString(), delivered_via: via, body: item.body_after_send, last_error: null },
          { Prefer: 'return=minimal' });
        sent += 1;
        log(`email to ${maskEmail(item.to_email)} (${item.kind}) via ${via}`);
      } catch (error) {
        const attempts = (item.attempts || 0) + 1;
        const update = { attempts, last_error: String(error.message).slice(0, 300) };
        // After the last try the code is removed too; the department can send a new one.
        if (attempts >= 5) update.body = item.body_after_send;
        await restAsServer('PATCH', `email_outbox?id=eq.${item.id}`, update, { Prefer: 'return=minimal' }).catch(() => {});
        log(`email to ${maskEmail(item.to_email)} failed${attempts >= 5 ? ' (gave up; code removed)' : ''}: ${error.message}`);
      }
    }
    lastOutboxError = '';
  } catch (error) {
    if (error.message !== lastOutboxError) log(`outbox check failed: ${error.message}`);
    lastOutboxError = error.message;
  } finally {
    outboxRunning = false;
  }
  return { sent };
}

function maskEmail(email) {
  const at = String(email || '').indexOf('@');
  return at < 2 ? String(email || '') : `${email[0]}•••${email.slice(at - 1)}`;
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

const ALLOWED_HOSTS = new Set([`localhost:${PORT}`, `127.0.0.1:${PORT}`, `[::1]:${PORT}`, `etender.local:${PORT}`]);
const ALLOWED_ORIGINS = new Set([...ALLOWED_HOSTS].map((h) => `http://${h}`));

function securityHeaders(res) {
  const supabase = SUPABASE_URL || 'https://*.supabase.co';
  res.setHeader('Content-Security-Policy', [
    "default-src 'self'", "script-src 'self'", "style-src 'self'", "img-src 'self' data:", "font-src 'self'",
    `connect-src 'self' ${supabase} ${supabase.replace('https://', 'wss://')}`,
    "frame-ancestors 'none'", "base-uri 'none'", "form-action 'self'", "object-src 'none'",
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
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.ttf': 'font/ttf', '.woff2': 'font/woff2',
};

/** Clean page addresses: /tenders -> public/tenders.html */
const PAGES = new Set(['', 'tenders', 'tender', 'register', 'signin', 'account', 'bid', 'department', 'mailbox', 'how-it-works']);

function serveStatic(res, urlPath) {
  let relative;
  try { relative = decodeURIComponent(urlPath.replace(/^\/+/, '')); } catch { return send(res, 400, 'Bad request', 'text/plain'); }
  if (PAGES.has(relative)) relative = `${relative || 'index'}.html`;
  const file = path.resolve(PUBLIC_DIR, relative);
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return send(res, 404, 'Not found', 'text/plain');
  const type = MIME[path.extname(file).toLowerCase()];
  if (!type || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    return send(res, 404, 'Not found', 'text/plain');
  }
  send(res, 200, fs.readFileSync(file), type);
}

function configScript() {
  const config = { supabaseUrl: SUPABASE_URL, anonKey: ANON_KEY, realEmail: SMTP.enabled, version: PORTAL_VERSION };
  return `window.ET_CONFIG = Object.freeze(${JSON.stringify(config)});\n`;
}

const hits = new Map();
function rateLimited(key, limit) {
  const now = Date.now();
  const recent = (hits.get(key) || []).filter((t) => now - t < 60_000);
  recent.push(now);
  hits.set(key, recent);
  return recent.length > limit;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    let tooLarge = false;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 20_000) {
        if (!tooLarge) reject(new HttpError(413, 'Request too large.'));
        tooLarge = true;
      } else chunks.push(chunk);
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

function bearer(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) throw new HttpError(401, 'Sign in first.');
  return token;
}

/**
 * Only the portal's own pages may call /api. Browsers send Origin with every
 * POST; a same-origin GET carries no Origin, only Sec-Fetch-Site.
 */
function fromPortal(req) {
  const origin = req.headers.origin;
  if (origin) return ALLOWED_ORIGINS.has(origin);
  if (req.method !== 'GET') return false;
  const site = req.headers['sec-fetch-site'];
  return !site || site === 'same-origin';
}

async function handleApi(req, res, urlPath) {
  if (!fromPortal(req)) {
    return send(res, 403, { error: 'Requests must come from the portal itself.' });
  }
  if (req.method === 'POST' && !(req.headers['content-type'] || '').startsWith('application/json')) {
    return send(res, 415, { error: 'Expected JSON.' });
  }
  try {
    if (req.method === 'POST' && urlPath === '/api/register') {
      if (rateLimited(`register:${req.socket.remoteAddress}`, 10)) throw new HttpError(429, 'Too many attempts. Wait a minute.');
      return send(res, 201, await registerSupplier(await readJson(req)));
    }
    if (req.method === 'POST' && urlPath === '/api/register/complete') {
      if (rateLimited(`complete:${req.socket.remoteAddress}`, 20)) throw new HttpError(429, 'Too many attempts. Wait a minute.');
      const token = bearer(req);
      await readJson(req);
      return send(res, 200, await completeRegistration(token));
    }
    if (req.method === 'POST' && urlPath === '/api/outbox/run') {
      if (rateLimited(`outbox:${req.socket.remoteAddress}`, 30)) throw new HttpError(429, 'Too many requests.');
      await readJson(req);
      return send(res, 200, await runOutbox());
    }
    if (req.method === 'GET' && urlPath === '/api/mailbox') {
      if (rateLimited(`mailbox:${req.socket.remoteAddress}`, 60)) throw new HttpError(429, 'Too many requests.');
      const token = bearer(req);
      const user = await userFromToken(token);
      const supplier = await supplierOf(token, user.id);
      const addresses = new Set([String(user.email || '').toLowerCase(),
                                 String(supplier?.contact_email || '').toLowerCase()].filter(Boolean));
      // Only emails meant for this account: the database records who each one is for,
      // so changing the company's contact email does not reveal anyone else's mail.
      const messages = readMailbox().filter((m) => m.toUser === user.id).reverse();
      return send(res, 200, { realEmail: SMTP.enabled, addresses: [...addresses], messages });
    }
    return send(res, 404, { error: 'Not found.' });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    if (!(error instanceof HttpError)) console.error(error);
    return send(res, status, { error: error instanceof HttpError ? error.message : 'Something went wrong on the portal server.' });
  }
}

function createServer() {
  return http.createServer((req, res) => {
    securityHeaders(res);
    if (!ALLOWED_HOSTS.has(String(req.headers.host || '').toLowerCase())) return send(res, 403, 'Forbidden', 'text/plain');
    const urlPath = new URL(req.url, 'http://localhost').pathname;
    if (urlPath.startsWith('/api/')) return void handleApi(req, res, urlPath);
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed', 'text/plain');
    if (urlPath === '/config.js') return send(res, 200, configScript(), MIME['.js']);
    if (urlPath.startsWith('/data/') || urlPath.includes('..')) return send(res, 404, 'Not found', 'text/plain');
    return serveStatic(res, urlPath);
  });
}

function log(what) {
  console.log(`${new Date().toISOString()}  ${what}`);
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

if (require.main === module) {
  const problems = checkConfig();
  if (problems.length) {
    console.error('\nThe eTender demo portal cannot start:\n');
    for (const p of problems) console.error(`  - ${p}`);
    console.error('\nEdit the .env file in this folder (copy .env.example if it is missing), then run node server.js again.\n');
    process.exit(1);
  }
  if (Number(process.versions.node.split('.')[0]) < 20) {
    console.error(`Node.js 20 or newer is needed (this is ${process.versions.node}).`);
    process.exit(1);
  }
  const server = createServer();
  server.on('error', (error) => {
    console.error(error.code === 'EADDRINUSE'
      ? `\nPort ${PORT} is already in use: the portal may already be running. Close that window, or set PORTAL_PORT=5071 in .env.\n`
      : `\nThe portal could not start: ${error.message}\n`);
    process.exit(1);
  });
  server.listen(PORT, HOST, () => {
    console.log(`\n  eTender Demo Portal ${PORTAL_VERSION} (demonstration only — not a government website)`);
    console.log(`  Files: ${ROOT}`);
    console.log(`  Open  http://localhost:${PORT}`);
    console.log(`  Email: ${SMTP.enabled
      ? `sent through ${SMTP.host} as ${SMTP.user} to @${SENDER_DOMAIN}${EMAIL_ALLOWLIST.size ? ` and ${[...EMAIL_ALLOWLIST].join(', ')}` : ''}; other addresses use the demo mailbox`
      : 'kept in the demo mailbox on this computer (no SMTP settings in .env)'}`);
    console.log('  Reachable from this computer only. Press Ctrl+C to stop.\n');
  });
  setInterval(runOutbox, OUTBOX_SECONDS * 1000);
  runOutbox();
}

module.exports = { createServer, checkConfig, runOutbox, smtpSend, buildMessage, registerSupplier, completeRegistration, readMailbox, mayEmail, SMTP_ENABLED: SMTP.enabled };
