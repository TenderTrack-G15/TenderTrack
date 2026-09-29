/*
 * Submit a bid (or change it) before the closing date. The database checks
 * everything again: the tender is open, the company is registered, every
 * required document is confirmed and the declaration is accepted.
 */

import { chrome, requireRole, showError } from '../layout.js';
import { rpc, flushOutbox, REAL_EMAIL } from '../client.js';
import { h, clear, icon, note, money, dateTime, daysUntil, busy, toast, confirmDialog, isOpen, fill } from '../ui.js';
import { loadPack, lines, preferencePoints } from '../tenders-data.js';

async function render() {
  const { who, main } = await chrome('tenders');
  if (!requireRole(who, 'supplier', main)) return;
  const tenderId = new URLSearchParams(location.search).get('tender');
  if (!tenderId) { showError(main, 'No tender was chosen.', null); return; }

  let pack;
  let existing = null;
  try {
    pack = await loadPack(tenderId);
    const bids = await rpc('supplier_bids');
    existing = (bids || []).find((b) => b.tender_id === tenderId && b.status !== 'withdrawn') || null;
  } catch (e) {
    showError(main, e.message, render);
    return;
  }

  const t = pack.tender;
  const d = pack.details || {};
  const supplier = who.supplier;
  const required = lines(d.required_documents);
  const header = h('div', {},
    h('p', { class: 'crumbs' }, h('a', { href: '/tenders' }, 'Tender opportunities'), ' / ', h('a', { href: `/tender?id=${t.id}` }, t.reference_number), ' / Bid'),
    h('div', { class: 'page-head' }, h('div', {},
      h('p', { class: 'eyebrow' }, `${t.reference_number} · ${t.department}`),
      h('h1', { class: 'h1' }, existing ? 'Change your bid' : 'Submit a bid'),
      h('p', { class: 'meta' }, t.title))));

  if (!isOpen(t)) {
    fill(main, header, note('warn', 'Bidding has closed', `The closing date was ${dateTime(t.closing_date)}.`),
      h('a', { class: 'btn btn-secondary mt', href: `/tender?id=${t.id}` }, 'Back to the tender'));
    return;
  }
  if (existing && existing.status !== 'submitted') {
    fill(main, header, note('info', `Your bid is ${existing.status}`, 'It can no longer be changed.'));
    return;
  }
  if (supplier.status === 'not_approved') {
    fill(main, header, note('danger', 'Registration not approved',
      `${supplier.decision_reason || 'Correct your registration'} — then resubmit it in the TenderTrack app (My company) before bidding.`));
    return;
  }

  const priceInput = h('input', { class: 'input', inputmode: 'decimal', placeholder: '0.00', value: existing ? String(existing.bid_value) : '', id: 'price' });
  const priceError = h('span', { class: 'field-error', hidden: true });
  const validity = h('select', { class: 'select', id: 'validity' },
    [60, 90, 120, 180].map((n) => h('option', { value: String(n), selected: (existing ? existing.validity_days : 90) === n }, `${n} days`)));
  const level = Number(supplier.bbbee_level) || 0;
  const preferenceOptions = [
    ...[1, 2, 3, 4, 5, 6, 7, 8].map((n) => [`B-BBEE Level ${n} (${preferencePoints(n)} points)`, n]),
    ['Non-compliant or not claimed (0 points)', 0],
  ];
  const preference = h('select', { class: 'select', id: 'preference' },
    preferenceOptions.map(([label, n]) => h('option', { value: label, selected: existing ? existing.preference_claim === label : n === level }, label)));
  const docChecks = required.map((name) => h('input', { type: 'checkbox', value: name, checked: existing ? (existing.submitted_documents || []).includes(name) : false }));
  const docsError = h('span', { class: 'field-error', hidden: true });
  const notes = h('textarea', { class: 'textarea', maxlength: '1000', placeholder: 'Optional: a short cover note, assumptions or qualifications.', value: existing ? existing.notes || '' : '' });
  const declaration = h('input', { type: 'checkbox', id: 'declaration' });
  const declarationError = h('span', { class: 'field-error', hidden: true });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, icon('send'), existing ? 'Submit changed bid' : 'Submit bid');

  // A message already shown disappears once the item is fixed.
  const recheck = () => {
    const price = Number(String(priceInput.value).replace(/[R\s,]/gi, ''));
    if (!priceError.hidden && price > 0) priceError.hidden = true;
    if (!docsError.hidden && docChecks.every((c) => c.checked)) docsError.hidden = true;
    if (!declarationError.hidden && declaration.checked) declarationError.hidden = true;
  };

  const form = h('form', {
    class: 'card', novalidate: true, oninput: recheck, onchange: recheck,
    onsubmit: async (e) => {
      e.preventDefault();
      const price = Number(String(priceInput.value).replace(/[R\s,]/gi, ''));
      let ok = true;
      priceError.hidden = price > 0; priceError.textContent = 'Enter your total bid price in rand, including VAT.';
      if (!(price > 0)) ok = false;
      const chosen = docChecks.filter((c) => c.checked).map((c) => c.value);
      docsError.hidden = chosen.length === required.length; docsError.textContent = 'Confirm every required document.';
      if (chosen.length !== required.length) ok = false;
      declarationError.hidden = declaration.checked; declarationError.textContent = 'Accept the declaration to submit.';
      if (!declaration.checked) ok = false;
      if (!ok) { toast('Please complete the highlighted items.', 'warn'); return; }

      const confirmed = await confirmDialog({
        title: existing ? 'Submit the changed bid?' : 'Submit this bid?',
        message: `${t.reference_number}: total price ${money(price)}, valid for ${validity.value} days. You can change or withdraw it until ${dateTime(t.closing_date)}.`,
        confirmText: 'Submit bid',
      });
      if (!confirmed) return;

      await busy(submit, async () => {
        try {
          const bid = await rpc('submit_bid', {
            p_tender_id: t.id, p_bid_value: price, p_validity_days: Number(validity.value), p_documents: chosen,
            p_preference_claim: preference.value, p_notes: notes.value, p_declaration: true,
          });
          flushOutbox();
          success(bid, price);
        } catch (err) {
          toast(err.message, 'error');
        }
      });
    },
  },
  h('h2', { class: 'h2 mb' }, 'Your bid'),
  h('div', { class: 'form-grid' },
    h('div', { class: 'field' }, h('label', { class: 'label', for: 'price' }, 'Total bid price (incl. VAT) ', h('span', { class: 'req' }, '*')),
      h('div', { class: 'money-input' }, h('span', {}, 'R'), priceInput), priceError),
    h('div', { class: 'field' }, h('label', { class: 'label', for: 'validity' }, 'Bid validity period'), validity),
    h('div', { class: 'field full' }, h('label', { class: 'label', for: 'preference' }, 'Preference points claimed'), preference,
      h('span', { class: 'hint' }, `From your registration: ${level ? `B-BBEE Level ${level}` : 'no B-BBEE level recorded'}. Preference system: ${d.preference_points || 'as stated in the tender'}.`))),

  h('h2', { class: 'h2 mt mb' }, 'Required documents'),
  h('p', { class: 'meta mb' }, 'Confirm that each document is part of your bid. (Files are not uploaded in this demonstration.)'),
  required.length
    ? required.map((name, i) => h('label', { class: 'check' }, docChecks[i], name))
    : h('p', { class: 'meta' }, 'This tender lists no required documents.'),
  docsError,

  h('div', { class: 'field mt' }, h('label', { class: 'label' }, 'Cover note'), notes),

  h('h2', { class: 'h2 mt mb' }, 'Declaration'),
  h('label', { class: 'check' }, declaration,
    h('span', {}, `I, ${who.profile.full_name}, declare on behalf of ${supplier.company_name} that the information in this bid is true and complete; `
      + 'that the company, its directors and employees have no conflict of interest with the department (declaration of interest, SBD 4); '
      + 'that we have not colluded with any other bidder; and that the price is firm for the validity period.')),
  declarationError,
  h('div', { class: 'row mt' }, submit, h('a', { class: 'btn btn-secondary', href: `/tender?id=${t.id}` }, 'Cancel'),
    existing ? h('button', { class: 'btn btn-danger', type: 'button', onclick: withdraw }, 'Withdraw bid') : null));

  async function withdraw() {
    const yes = await confirmDialog({ title: 'Withdraw your bid?', message: `Your bid ${existing.reference} for ${t.reference_number} will be withdrawn. You can submit a new one before the closing date.`, confirmText: 'Withdraw', danger: true });
    if (!yes) return;
    try {
      await rpc('withdraw_bid', { p_bid_id: existing.id });
      toast('Bid withdrawn.');
      window.location.assign('/account');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function success(bid, price) {
    fill(main, header,
      h('div', { class: 'note note-success' }, icon('success'), h('div', {},
        h('p', { class: 'note-title' }, `Bid ${bid.reference} received`),
        h('p', {}, `${t.reference_number} · ${money(price)} · submitted ${dateTime(bid.submitted_at)}`))),
      h('div', { class: 'card mt' },
        h('p', { class: 'h3' }, 'What happens next'),
        h('ul', { class: 'bullets' },
          h('li', {}, REAL_EMAIL ? `A receipt has been sent to ${supplier.contact_email}. If it does not arrive, look in the Demo mailbox.` : 'A receipt is in your Demo mailbox.'),
          h('li', {}, `You can change or withdraw the bid until ${dateTime(t.closing_date)}.`),
          h('li', {}, 'After the closing date the department evaluates the bids. If you are awarded the tender, a 10-digit award code is emailed to you — enter it in the TenderTrack app to claim the contract.')),
        h('div', { class: 'row mt' }, h('a', { class: 'btn btn-primary', href: '/account' }, 'My bids & awards'),
          h('a', { class: 'btn btn-secondary', href: '/mailbox' }, icon('mail'), 'Demo mailbox'))));
    window.scrollTo(0, 0);
  }

  const summary = h('aside', { class: 'side' }, h('div', { class: 'card' },
    h('p', { class: 'eyebrow' }, 'Closes in'),
    h('p', { class: 'countdown' }, `${daysUntil(t.closing_date)} days`),
    h('p', { class: 'meta' }, dateTime(t.closing_date)),
    h('div', { class: 'mt' },
      h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Bidder'), h('span', { class: 'kv-value' }, supplier.company_name)),
      h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'CSD number'), h('span', { class: 'kv-value' }, supplier.csd_number)),
      h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Evaluation'), h('span', { class: 'kv-value' },
        d.technical_weight != null ? `${d.technical_weight}% technical / ${d.financial_weight}% financial` : '—'))),
    supplier.status === 'awaiting_verification' ? h('div', { class: 'mt' }, note('warn', 'Registration under review',
      'You can bid now, but a tender can only be awarded to a verified supplier.')) : null));

  fill(main, header, h('div', { class: 'detail-layout' }, form, summary));
}

render();
