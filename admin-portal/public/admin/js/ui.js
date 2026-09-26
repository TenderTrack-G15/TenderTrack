/*
 * Shared helpers for the admin portal: building the page safely, formatting,
 * dialogs, messages and file downloads.
 *
 * Security note: text from the database (names, citizen reports, audit
 * details) is only ever inserted with textContent, never as HTML, so nothing
 * stored in TenderTrack can run as script in the portal. The only HTML
 * inserted directly is the fixed icon artwork below.
 */

// ---------------------------------------------------------------------------
// Icons (Material Symbols paths, the same icon family as the app)
// ---------------------------------------------------------------------------

const PATHS = {
  briefcase: 'M10 16v-1H3.01L3 19c0 1.11.89 2 2 2h14c1.11 0 2-.89 2-2v-4h-7v1h-4zm10-9h-4.01V5l-2-2h-4l-2 2v2H4c-1.1 0-2 .9-2 2v3c0 1.11.89 2 2 2h6v-2h4v2h6c1.1 0 2-.9 2-2V9c0-1.1-.9-2-2-2zm-6 0h-4V5h4v2z',
  dashboard: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z',
  people: 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  shield: 'M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
  pulse: 'M3.5 18.49l6-6.01 4 4L22 6.92l-1.41-1.41-7.09 7.97-4-4L2 16.99z',
  history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
  gavel: 'M1 21h12v2H1v-2zM5.24 8.07l2.83-2.83 14.14 14.14-2.83 2.83L5.24 8.07zM12.32 1l5.66 5.66-2.83 2.83-5.66-5.66L12.32 1zM3.83 9.48l5.66 5.66-2.83 2.83L1 12.31l2.83-2.83z',
  campaign: 'M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
  lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
  logout: 'M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z',
  search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  flag: 'M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z',
  backup: 'M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z',
  refresh: 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  inbox: 'M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.11-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z',
};

/** An icon as an SVG element. Only the fixed paths above are ever used. */
export function icon(name) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', PATHS[name] || PATHS.info);
  svg.appendChild(path);
  return svg;
}

// ---------------------------------------------------------------------------
// Building elements safely
// ---------------------------------------------------------------------------

/**
 * h('div', { class: 'card', onclick: fn }, 'text', child, ...)
 * Strings become text nodes, never HTML.
 */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === false || value === null || value === undefined) continue;
    if (key === 'class') el.className = value;
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'value' || key === 'checked') el[key] = value;
    else if (key in el && typeof value !== 'string') el[key] = value;
    else el.setAttribute(key, value === true ? '' : String(value));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

// ---------------------------------------------------------------------------
// Formatting — identical to the app's core/Formatters.kt
// ---------------------------------------------------------------------------

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** R 17 950 000 */
export function money(amount) {
  if (amount === null || amount === undefined || amount === '') return '—';
  const rounded = Math.round(Number(amount));
  const grouped = Math.abs(rounded).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return rounded < 0 ? `-R ${grouped}` : `R ${grouped}`;
}

/** R 1.24 bn, R 812.5 m, R 45.0 k */
export function moneyCompact(amount) {
  const a = Number(amount || 0);
  const abs = Math.abs(a);
  if (abs >= 1e9) return `R ${(a / 1e9).toFixed(2)} bn`;
  if (abs >= 1e6) return `R ${(a / 1e6).toFixed(1)} m`;
  if (abs >= 1e3) return `R ${(a / 1e3).toFixed(1)} k`;
  return money(a);
}

function parseDate(iso) {
  if (!iso) return null;
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** 18 Sep 2026 */
export function date(iso) {
  const d = parseDate(iso);
  if (!d) return '—';
  return `${String(d.getDate()).padStart(2, '0')} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

/** 18 Sep 2026 · 11:00 */
export function dateTime(iso) {
  const d = parseDate(iso);
  if (!d) return '—';
  return `${date(iso)} · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** "3 min ago", "2 h ago", or the date. */
export function ago(iso) {
  const d = parseDate(iso);
  if (!d) return 'Never';
  const minutes = Math.round((Date.now() - d.getTime()) / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 60 * 24) return `${Math.round(minutes / 60)} h ago`;
  return date(iso);
}

export function percent(fraction) {
  return `${Math.round(Number(fraction || 0) * 100)}%`;
}

export function initials(name) {
  return String(name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
}

export const ROLE_LABELS = {
  public: 'Public',
  supplier: 'Supplier',
  procurement_officer: 'Procurement Officer',
  evaluation_committee: 'Evaluation Committee',
  finance_officer: 'Finance Officer',
  auditor: 'Auditor',
  administrator: 'Administrator',
};

export const STATUS_LABELS = {
  registered: 'Registered',
  published: 'Open for bids',
  under_evaluation: 'Under evaluation',
  awarded: 'Awarded',
  in_progress: 'In progress',
  completed: 'Completed',
};

export function roleLabel(role) { return ROLE_LABELS[role] || role || '—'; }

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

/** tone: success | warn | danger | info | neutral */
export function badge(text, tone = 'neutral', dot = true) {
  return h('span', { class: `badge badge-${tone}${dot ? '' : ' no-dot'}` }, text);
}

export function note(tone, title, text, iconName) {
  const names = { warn: 'warning', danger: 'warning', info: 'info', neutral: 'info' };
  return h('div', { class: `note note-${tone}`, role: tone === 'danger' ? 'alert' : 'note' },
    icon(iconName || names[tone] || 'info'),
    h('div', {}, title ? h('p', { class: 'note-title' }, title) : null, h('p', {}, text)));
}

export function tile({ label, value, foot, alert = false, money: isMoney = false, onclick }) {
  const tag = onclick ? 'button' : 'div';
  return h(tag, { class: `tile${alert ? ' alert' : ''}`, type: onclick ? 'button' : null, onclick },
    h('div', { class: 'tile-label' }, label),
    h('div', { class: `tile-value${isMoney ? ' money' : ''}` }, value),
    foot ? h('div', { class: 'tile-foot' }, foot) : null);
}

export function kv(key, value) {
  return h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, key), h('span', { class: 'kv-value' }, value));
}

export function loading(message = 'Loading…') {
  return h('div', { class: 'loading' }, h('span', { class: 'spinner' }), message);
}

export function empty(title, message, iconName = 'inbox') {
  return h('div', { class: 'empty' }, icon(iconName), h('p', { class: 'card-title' }, title), h('p', { class: 'meta' }, message));
}

export function errorBox(message, onRetry) {
  return h('div', {},
    note('danger', 'Could not load this page', message),
    onRetry ? h('p', {}, h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: onRetry }, icon('refresh'), 'Try again')) : null);
}

export function pageHead(eyebrow, title, subtitle, ...actions) {
  return h('div', { class: 'page-head' },
    h('div', {}, h('p', { class: 'eyebrow' }, eyebrow), h('h1', { class: 'h1' }, title), subtitle ? h('p', { class: 'meta' }, subtitle) : null),
    actions.length ? h('div', { class: 'row-actions' }, actions) : null);
}

export function searchBox(placeholder, oninput) {
  return h('div', { class: 'search grow' }, icon('search'),
    h('input', { class: 'input', type: 'search', placeholder, 'aria-label': placeholder, oninput }));
}

export function select(options, value, onchange, label) {
  const el = h('select', { class: 'select', onchange, 'aria-label': label || null });
  for (const [v, text] of options) {
    el.appendChild(h('option', { value: v, selected: v === value }, text));
  }
  return el;
}

/** A button that shows a spinner while its action runs. */
export async function busy(button, action) {
  const original = [...button.childNodes];
  button.disabled = true;
  clear(button).appendChild(h('span', { class: 'spinner' }));
  try {
    return await action();
  } finally {
    button.disabled = false;
    clear(button);
    original.forEach((n) => button.appendChild(n));
  }
}

// ---------------------------------------------------------------------------
// Messages and dialogs
// ---------------------------------------------------------------------------

export function toast(message, kind = 'success') {
  let box = document.querySelector('.toasts');
  if (!box) {
    box = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite' });
    document.body.appendChild(box);
  }
  const t = h('div', { class: `toast${kind === 'success' ? '' : ` ${kind}`}` }, message);
  box.appendChild(t);
  while (box.children.length > 3) box.firstChild.remove();   // keep the newest three
  setTimeout(() => t.remove(), kind === 'error' ? 8000 : 5000);
}

/**
 * Opens a dialog. `build(close)` returns the content. The dialog also closes
 * on Escape or a click outside it, in which case `onDismiss` is called.
 * Returns a function that closes it.
 */
export function modal(title, subtitle, build, onDismiss) {
  const backdrop = h('div', { class: 'modal-backdrop' });
  const box = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
  let open = true;
  const close = () => {
    if (!open) return;
    open = false;
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
  };
  const dismiss = () => { if (open) { close(); if (onDismiss) onDismiss(); } };
  // Escape closes only the top dialog when one is open over another.
  const onKey = (e) => {
    if (e.key === 'Escape' && [...document.querySelectorAll('.modal-backdrop')].pop() === backdrop) dismiss();
  };
  box.append(h('div', { class: 'modal-head' }, h('h2', { class: 'h2' }, title), subtitle ? h('p', { class: 'meta' }, subtitle) : null));
  append(box, [build(close)]);
  backdrop.appendChild(box);
  backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) dismiss(); });
  document.addEventListener('keydown', onKey);
  document.body.appendChild(backdrop);
  const first = box.querySelector('input, select, textarea, button');
  if (first) first.focus();
  return close;
}

/** A yes/no confirmation. Resolves true or false. */
export function confirmDialog({ title, message, confirmText = 'Confirm', danger = false }) {
  return new Promise((resolve) => {
    modal(title, null, (close) => h('div', {},
      h('p', {}, message),
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: () => { close(); resolve(false); } }, 'Cancel'),
        h('button', { class: `btn ${danger ? 'btn-danger' : 'btn-primary'} btn-sm`, type: 'button', onclick: () => { close(); resolve(true); } }, confirmText))),
    () => resolve(false));
  });
}

/** Shows a one-time temporary password with a copy button. */
export function showPassword(title, email, password) {
  modal(title, email, (close) => h('div', {},
    note('warn', 'Shown once', 'Give this to the person directly, not by email. They sign in to the app with it, and nobody can see it again after you close this window.'),
    h('div', { class: 'password-box mono', id: 'temp-password' }, password),
    h('div', { class: 'modal-actions' },
      h('button', {
        class: 'btn btn-secondary btn-sm', type: 'button',
        onclick: async (e) => {
          const button = e.currentTarget;             // read before await: it is cleared afterwards
          try { await navigator.clipboard.writeText(password); button.textContent = 'Copied'; } catch { toast('Select the password and copy it manually.', 'warn'); }
        },
      }, 'Copy'),
      h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: close }, 'Done'))));
}

// ---------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------

/** A labelled field. Returns { wrap, input, setError }. */
export function field({ label, input, hint }) {
  const error = h('span', { class: 'field-error', hidden: true });
  const wrap = h('label', { class: 'field' }, h('span', { class: 'label' }, label), input, hint ? h('span', { class: 'hint' }, hint) : null, error);
  return {
    wrap,
    input,
    setError(message) {
      error.hidden = !message;
      error.textContent = message || '';
      input.setAttribute('aria-invalid', message ? 'true' : 'false');
    },
  };
}

// ---------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------

function csvCell(value) {
  let text = value === null || value === undefined ? '' : String(value);
  // Stops a spreadsheet treating a value as a formula (CSV injection).
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** Downloads rows as a CSV file that opens in Excel. */
export function downloadCsv(fileName, columns, rows) {
  const lines = [columns.map(([, header]) => csvCell(header)).join(',')];
  for (const row of rows) lines.push(columns.map(([key]) => csvCell(typeof key === 'function' ? key(row) : row[key])).join(','));
  // The byte-order mark makes Excel read "—" and "é" correctly.
  downloadFile(fileName, '﻿' + lines.join('\r\n'), 'text/csv;charset=utf-8');
}

export function downloadFile(fileName, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = h('a', { href: url, download: fileName });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function stamp() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
