/*
 * Demo mailbox. When the portal server has no email account in .env, the
 * emails it would send (award codes, bid receipts) are kept on this computer
 * and shown here — only to the supplier they are addressed to.
 */

import { chrome, requireRole, showError } from '../layout.js';
import { api } from '../client.js';
import { h, clear, icon, note, dateTime, empty, fill } from '../ui.js';

async function render() {
  const { who, main } = await chrome('mailbox');
  if (!requireRole(who, 'supplier', main)) return;

  const list = h('div');
  const reader = h('div', { class: 'card' });
  let messages = [];
  let selected = null;

  async function load() {
    const data = await api('GET', '/api/mailbox', undefined, true);
    messages = data.messages || [];
    return data;
  }

  /** The body as text, with the award code picked out. Never inserted as HTML. */
  function bodyWithCode(text) {
    const out = h('div', { class: 'mail-body' });
    const parts = String(text).split(/(\b\d{5} \d{5}\b)/);
    for (const part of parts) {
      out.appendChild(/^\d{5} \d{5}$/.test(part) ? h('span', { class: 'code-highlight' }, part) : document.createTextNode(part));
    }
    return out;
  }

  function draw() {
    clear(list);
    if (!messages.length) {
      list.appendChild(h('div', { class: 'card' }, empty('No emails yet', 'Bid receipts and award codes for your company appear here.', 'mail')));
      clear(reader).appendChild(h('p', { class: 'meta' }, 'Select an email to read it.'));
      return;
    }
    if (!selected || !messages.some((m) => m.id === selected)) selected = messages[0].id;
    for (const m of messages) {
      list.appendChild(h('button', { class: `mail-item${m.id === selected ? ' active' : ''}`, type: 'button', onclick: () => { selected = m.id; draw(); } },
        h('div', { class: 'spread' }, h('span', { class: 'primary' }, m.kind === 'award_code' ? 'Award code' : 'Bid receipt'), h('span', { class: 'secondary' }, dateTime(m.receivedAt))),
        h('div', { class: 'meta' }, m.subject),
        h('div', { class: 'secondary' }, `To: ${m.to}`)));
    }
    const m = messages.find((x) => x.id === selected);
    fill(reader,
      h('p', { class: 'eyebrow' }, dateTime(m.receivedAt)),
      h('h2', { class: 'h2' }, m.subject),
      h('p', { class: 'meta mb' }, `From: eTender Demo Portal · To: ${m.toName ? `${m.toName} ` : ''}<${m.to}>`),
      bodyWithCode(m.body),
      m.kind === 'award_code' ? h('div', { class: 'mt' }, note('info', 'Next step',
        'Open the TenderTrack app, sign in with your supplier account, go to Awards, choose this tender and enter the 10-digit code.')) : null);
  }

  let data;
  try {
    data = await load();
  } catch (e) {
    showError(main, e.message, render);
    return;
  }

  fill(main,
    h('div', { class: 'page-head' }, h('div', {},
      h('p', { class: 'eyebrow' }, 'Demonstration'),
      h('h1', { class: 'h1' }, 'Demo mailbox'),
      h('p', { class: 'meta' }, `Emails to ${data.addresses.join(' or ')}.`)),
      h('button', { class: 'btn btn-secondary', type: 'button', onclick: async () => { try { await load(); draw(); } catch (e) { showError(main, e.message, render); } } }, icon('refresh'), 'Refresh')),
    data.realEmail
      ? h('div', { class: 'mb' }, note('info', 'Real email is switched on', 'Emails to addresses the portal is allowed to email go to the real inbox. Emails to any other address (for example the made-up companies in the sample data) are kept here instead.'))
      : h('div', { class: 'mb' }, note('warn', 'Why emails appear here', 'The portal server has no email account set up (SMTP settings in .env), so it keeps the emails it would send on this computer and shows them only to the company they are addressed to.')),
    h('div', { class: 'mail-layout mt' }, list, reader));
  draw();

  // New emails (for example an award code) appear without a refresh.
  setInterval(async () => { try { const before = messages.length; await load(); if (messages.length !== before) draw(); } catch { /* keep the last view */ } }, 10000);
}

render();
