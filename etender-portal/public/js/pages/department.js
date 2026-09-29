/*
 * Department console — for procurement officers.
 *   Open for bids   extend the closing date, close bidding (bids stay sealed)
 *   Evaluation      compare the bids and award one
 *   Awarded         the award code: sent, claimed, expired or locked; send a new one;
 *                   review the supplier's deliverable evidence once claimed
 * The award code itself is created and checked by the database; this page
 * never sees it.
 */

import { chrome, requireRole, showError } from '../layout.js';
import { sb, rpc, rows, flushOutbox } from '../client.js';
import { h, clear, icon, note, badge, money, date, dateTime, daysUntil, empty, toast, modal, field, busy, confirmDialog, fill, put } from '../ui.js';
import { statusBadge } from '../tenders-data.js';

const TABS = [['open', 'Open for bids'], ['evaluation', 'Evaluation'], ['awarded', 'Awarded'], ['all', 'All']];
const REG = { awaiting_verification: ['Awaiting verification', 'warn'], verified: ['Verified', 'success'], not_approved: ['Not approved', 'danger'] };
const CLAIM = {
  pending: ['Code sent · waiting for the supplier', 'warn'],
  claimed: ['Claimed by the supplier', 'success'],
  expired: ['Code expired', 'danger'],
  locked: ['Code locked (5 wrong attempts)', 'danger'],
  no_code: ['No code sent yet', 'neutral'],
};
const DELIV = { not_started: ['Not started', 'neutral'], awaiting_verification: ['Evidence submitted', 'warn'], verified: ['Verified', 'success'], overdue: ['Overdue', 'danger'] };

function tabOf(t) {
  if (t.status === 'published') return 'open';
  if (t.status === 'under_evaluation') return 'evaluation';
  if (['awarded', 'in_progress', 'completed'].includes(t.status)) return 'awarded';
  return 'other';
}

async function render() {
  const { who, main } = await chrome('department');
  if (!requireRole(who, 'procurement_officer', main)) return;

  const params = new URLSearchParams(location.search);
  const state = { tab: 'open', mine: Boolean(who.profile.department), selected: params.get('id') };
  let tenders = [];
  let bids = [];

  async function load() {
    [tenders, bids] = await Promise.all([
      rows(sb.from('tenders').select('id, reference_number, title, description, department, category, status, closing_date, published_at, created_at, estimated_budget, awarded_supplier_id, awarded_supplier_name, awarded_value, awarded_at')
        .neq('status', 'registered').order('closing_date', { ascending: false })),
      rows(sb.from('bids').select('id, tender_id, reference, supplier_id, supplier_name, bid_value, submitted_at, status, documents_received, documents_required, preference_claim, validity_days, notes')),
    ]);
    if (state.selected) {
      const t = tenders.find((x) => x.id === state.selected);
      if (t) state.tab = tabOf(t);
    }
  }

  try {
    await load();
  } catch (e) {
    showError(main, e.message, render);
    return;
  }
  if (state.mine && !tenders.some((t) => t.department === who.profile.department)) state.mine = false;
  // Opened on another department's tender (for example from a link): show all departments.
  const opened = tenders.find((t) => t.id === state.selected);
  if (state.mine && opened && opened.department !== who.profile.department) state.mine = false;

  const tabsEl = h('div', { class: 'tabs' });
  const listEl = h('div', { class: 'stack' });
  const detailEl = h('div');

  function visible() {
    return tenders.filter((t) => (state.tab === 'all' || tabOf(t) === state.tab) && (!state.mine || t.department === who.profile.department));
  }

  function drawTabs() {
    clear(tabsEl);
    for (const [id, label] of TABS) {
      const n = tenders.filter((t) => (id === 'all' || tabOf(t) === id) && (!state.mine || t.department === who.profile.department)).length;
      tabsEl.appendChild(h('button', { type: 'button', class: state.tab === id ? 'active' : null, onclick: () => { state.tab = id; drawTabs(); drawList(); } },
        label, h('span', { class: 'count' }, `(${n})`)));
    }
  }

  function drawList() {
    clear(listEl);
    const list = visible();
    if (!list.length) {
      listEl.appendChild(h('div', { class: 'card' }, empty('No tenders here', state.mine ? 'Try "All departments".' : 'Nothing in this list.', 'inbox')));
      return;
    }
    for (const t of list) {
      const count = bids.filter((b) => b.tender_id === t.id && b.status !== 'withdrawn').length;
      listEl.appendChild(h('button', {
        type: 'button', class: `mail-item${t.id === state.selected ? ' active' : ''}`,
        onclick: () => { state.selected = t.id; const u = new URL(location.href); u.searchParams.set('id', t.id); history.replaceState(null, '', u); drawList(); drawDetail(); },
      },
      h('div', { class: 'spread' }, h('span', { class: 'primary' }, t.reference_number), statusBadge(t)),
      h('div', { class: 'meta' }, t.title),
      h('div', { class: 'secondary' }, `${count} ${count === 1 ? 'bid' : 'bids'} · closes ${date(t.closing_date)}`)));
    }
  }

  async function refresh(message) {
    if (message) toast(message);
    await load();
    drawTabs(); drawList(); drawDetail();
  }

  // ---- actions --------------------------------------------------------------

  function extendClosing(t) {
    const suggested = new Date(Math.max(Date.now(), new Date(t.closing_date).getTime()) + 14 * 86400000);
    suggested.setHours(12, 0, 0, 0);
    const pad = (n) => String(n).padStart(2, '0');
    const when = field({ label: 'New closing date and time', input: h('input', { class: 'input', type: 'datetime-local',
      value: `${suggested.getFullYear()}-${pad(suggested.getMonth() + 1)}-${pad(suggested.getDate())}T12:00` }) });
    const reason = field({ label: 'Reason (published as a tender update)', input: h('textarea', { class: 'textarea', maxlength: '500', placeholder: 'e.g. Extended at the request of bidders after the briefing session' }) });
    modal('Extend the closing date', `${t.reference_number} · now closes ${dateTime(t.closing_date)}`, (close) => {
      const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Extend');
      return h('form', { novalidate: true, onsubmit: async (e) => {
        e.preventDefault();
        if (!when.input.value) { when.setError('Choose the new date and time.'); return; }
        if (reason.input.value.trim().length < 5) { reason.setError('Give a reason (at least 5 characters).'); return; }
        await busy(submit, async () => {
          try {
            await rpc('extend_tender_closing', { p_tender_id: t.id, p_new_closing: new Date(when.input.value).toISOString(), p_reason: reason.input.value.trim() });
            close();
            refresh('Closing date extended. Bidders see the extension under Tender updates.');
          } catch (err) { when.setError(err.message); }
        });
      } }, when.wrap, reason.wrap, h('div', { class: 'modal-actions' }, h('button', { class: 'btn btn-secondary', type: 'button', onclick: close }, 'Cancel'), submit));
    });
  }

  function closeBidding(t) {
    const early = new Date(t.closing_date).getTime() > Date.now();
    const reason = field({ label: 'Reason (recorded in the audit trail)', input: h('textarea', { class: 'textarea', maxlength: '300',
      value: early ? '' : 'Closing date reached; bids opened for evaluation' }) });
    modal(early ? 'Close bidding early?' : 'Open the bids for evaluation', t.reference_number, (close) => {
      const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, early ? 'Close bidding now' : 'Start evaluation');
      return h('form', { novalidate: true, onsubmit: async (e) => {
        e.preventDefault();
        if (reason.input.value.trim().length < 5) { reason.setError('Give a reason (at least 5 characters).'); return; }
        await busy(submit, async () => {
          try {
            await rpc('close_bidding', { p_tender_id: t.id, p_reason: reason.input.value.trim() });
            close();
            state.tab = 'evaluation';
            refresh('Bidding closed. The bids are open for evaluation.');
          } catch (err) { reason.setError(err.message); }
        });
      } },
      early ? note('warn', 'The closing date has not passed', `Bidding is meant to stay open until ${dateTime(t.closing_date)}. Close early only for the demonstration, or if the tender is being cancelled.`) : null,
      h('div', { class: 'mt' }, reason.wrap),
      h('div', { class: 'modal-actions' }, h('button', { class: 'btn btn-secondary', type: 'button', onclick: close }, 'Cancel'), submit));
    });
  }

  function awardBid(t, b, supplier) {
    const comment = field({ label: 'Reason for the award (recorded with the approval)', input: h('textarea', { class: 'textarea', maxlength: '500', placeholder: 'e.g. Highest combined score: full functionality points and lowest compliant price' }) });
    modal('Award this tender?', `${t.reference_number} · ${t.title}`, (close) => {
      const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, icon('send'), 'Award and email the code');
      return h('form', { novalidate: true, onsubmit: async (e) => {
        e.preventDefault();
        await busy(submit, async () => {
          try {
            const result = await rpc('award_bid', { p_bid_id: b.id, p_comment: comment.input.value.trim() });
            close();
            flushOutbox();
            refresh(`Awarded to ${result.supplier}. A 10-digit award code was emailed to ${result.sent_to}.`);
          } catch (err) { comment.setError(err.message); }
        });
      } },
      h('div', { class: 'kv-list' },
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Supplier'), h('span', { class: 'kv-value' }, b.supplier_name)),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'CSD number'), h('span', { class: 'kv-value' }, supplier ? supplier.csd_number : '—')),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Bid price'), h('span', { class: 'kv-value' }, money(b.bid_value))),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Estimated budget'), h('span', { class: 'kv-value' }, money(t.estimated_budget)))),
      h('div', { class: 'mt' }, note('info', 'What happens next', 'The database creates a one-time 10-digit award code and this portal emails it to the supplier. The supplier claims the contract by entering the code in TenderTrack. Until then the contract is not theirs.')),
      h('div', { class: 'mt' }, comment.wrap),
      h('div', { class: 'modal-actions' }, h('button', { class: 'btn btn-secondary', type: 'button', onclick: close }, 'Cancel'), submit));
    });
  }

  async function sendCode(t, status) {
    const yes = await confirmDialog({
      title: status === 'no_code' ? 'Send the award code?' : 'Send a new award code?',
      message: status === 'no_code'
        ? `A 10-digit code will be emailed to ${t.awarded_supplier_name}.`
        : `A new code will be emailed to ${t.awarded_supplier_name}. The previous code stops working immediately.`,
      confirmText: 'Send code',
    });
    if (!yes) return;
    try {
      const result = await rpc('send_award_code', { p_tender_id: t.id });
      flushOutbox();
      refresh(`Award code emailed to ${result.sent_to}.`);
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  function reviewDeliverable(t, d, accept) {
    if (accept) {
      confirmDialog({ title: 'Verify this deliverable?', message: `${d.phase_name} (${money(d.phase_value)}) will be marked as verified.`, confirmText: 'Verify' })
        .then(async (yes) => {
          if (!yes) return;
          try { await rpc('review_deliverable', { p_deliverable_id: d.id, p_accept: true, p_comment: '' }); refresh('Deliverable verified.'); } catch (e) { toast(e.message, 'error'); }
        });
      return;
    }
    const comment = field({ label: 'What is missing?', input: h('textarea', { class: 'textarea', maxlength: '500' }) });
    modal('Return the evidence', d.phase_name, (close) => {
      const submit = h('button', { class: 'btn btn-danger', type: 'submit' }, 'Return to supplier');
      return h('form', { novalidate: true, onsubmit: async (e) => {
        e.preventDefault();
        if (comment.input.value.trim().length < 5) { comment.setError('Tell the supplier what is missing (at least 5 characters).'); return; }
        await busy(submit, async () => {
          try { await rpc('review_deliverable', { p_deliverable_id: d.id, p_accept: false, p_comment: comment.input.value.trim() }); close(); refresh('Evidence returned to the supplier.'); } catch (err) { comment.setError(err.message); }
        });
      } }, comment.wrap, h('div', { class: 'modal-actions' }, h('button', { class: 'btn btn-secondary', type: 'button', onclick: close }, 'Cancel'), submit));
    });
  }

  // ---- detail ---------------------------------------------------------------

  async function drawDetail() {
    clear(detailEl);
    const t = tenders.find((x) => x.id === state.selected);
    if (!t) {
      detailEl.appendChild(h('div', { class: 'card' }, empty('Choose a tender', 'Pick a tender on the left to manage it.', 'document')));
      return;
    }
    const tenderBids = bids.filter((b) => b.tender_id === t.id);
    const live = tenderBids.filter((b) => b.status !== 'withdrawn').sort((a, b) => Number(a.bid_value) - Number(b.bid_value));
    const kv = (k, v) => h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, k), h('span', { class: 'kv-value' }, v || '—'));
    const card = h('div', { class: 'card' },
      h('div', { class: 'card-head' },
        h('div', {}, h('p', { class: 'eyebrow' }, `${t.reference_number} · ${t.department}`), h('h2', { class: 'h2' }, t.title)),
        statusBadge(t)),
      kv('Published', date(t.published_at || t.created_at)),
      kv('Closing date', dateTime(t.closing_date)),
      kv('Estimated budget (internal)', money(t.estimated_budget)),
      kv('Bids received', String(live.length)),
      h('div', { class: 'row mt' }, h('a', { class: 'btn btn-secondary btn-sm', href: `/tender?id=${t.id}` }, icon('document'), 'Public tender page')));
    detailEl.appendChild(card);

    if (t.status === 'published') {
      const open = new Date(t.closing_date).getTime() > Date.now();
      detailEl.appendChild(h('div', { class: 'card' },
        h('p', { class: 'h3' }, open ? `Open for bids · closes in ${daysUntil(t.closing_date)} days` : 'The closing date has passed'),
        h('p', { class: 'meta mt-sm' }, open
          ? `Bids stay sealed until bidding closes. ${live.length} ${live.length === 1 ? 'bid has' : 'bids have'} been received.`
          : 'Open the bids for evaluation to compare them and award the tender.'),
        h('div', { class: 'row mt' },
          open ? h('button', { class: 'btn btn-secondary', type: 'button', onclick: () => extendClosing(t) }, icon('clock'), 'Extend closing date') : null,
          h('button', { class: 'btn btn-primary', type: 'button', onclick: () => closeBidding(t) }, open ? 'Close bidding now' : 'Start evaluation'))));
      return;
    }

    // Suppliers behind the bids: verification decides who can be awarded.
    const supplierIds = [...new Set(tenderBids.map((b) => b.supplier_id).filter(Boolean))];
    const suppliers = supplierIds.length
      ? await rows(sb.from('suppliers').select('id, company_name, status, csd_number, bbbee_level').in('id', supplierIds)).catch(() => [])
      : [];
    const supplierOf = (id) => suppliers.find((s) => s.id === id);

    const bidsTable = (awardable) => (live.length
      ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Bidder'), h('th', { class: 'num' }, 'Price'), h('th', {}, 'Documents'), h('th', {}, 'Preference'), h('th', {}, 'Submitted'), h('th', {}, awardable ? '' : 'Result'))),
        h('tbody', {}, live.map((b, i) => {
          const s = supplierOf(b.supplier_id);
          const [regLabel, regTone] = s ? (REG[s.status] || [s.status, 'neutral']) : ['Not registered', 'neutral'];
          return h('tr', {},
            h('td', {}, h('div', { class: 'primary' }, b.supplier_name), h('div', { class: 'secondary' }, `${b.reference}${s ? ` · ${s.csd_number}` : ''}`), h('div', { class: 'mt-sm' }, badge(regLabel, regTone))),
            h('td', { class: 'num' }, money(b.bid_value), i === 0 ? h('div', { class: 'secondary' }, 'Lowest price') : null),
            h('td', {}, `${b.documents_received} of ${b.documents_required}`),
            h('td', {}, b.preference_claim || '—'),
            h('td', { class: 'nowrap' }, dateTime(b.submitted_at)),
            h('td', {}, awardable
              ? h('button', { class: 'btn btn-primary btn-sm', type: 'button', disabled: !s || s.status !== 'verified',
                title: !s || s.status !== 'verified' ? 'Verify the supplier in TenderTrack first' : null,
                onclick: () => awardBid(t, b, s) }, 'Award')
              : badge(b.status === 'awarded' ? 'Awarded' : b.status === 'disqualified' ? 'Disqualified' : 'Not successful',
                b.status === 'awarded' ? 'success' : b.status === 'disqualified' ? 'danger' : 'neutral')));
        }))))
      : h('div', { class: 'card' }, empty('No bids were received', 'Nobody bid for this tender.', 'inbox')));

    if (t.status === 'under_evaluation') {
      put(detailEl, 
        h('h3', { class: 'h2 section' }, 'Bids received'),
        h('p', { class: 'meta mb' }, 'Sorted by price. Only a verified supplier can be awarded; verify registrations in TenderTrack (Supplier registrations).'),
        bidsTable(true));
      return;
    }

    // Awarded, in progress or completed.
    let status = { status: 'no_code' };
    try { status = await rpc('award_code_status', { p_tender_id: t.id }); } catch { /* shown as not sent */ }
    const [claimLabel, claimTone] = CLAIM[status.status] || [status.status, 'neutral'];
    const deliverables = await rows(sb.from('deliverables').select('id, phase_name, target_date, phase_value, status, evidence_url, evidence_note, evidence_at').eq('tender_id', t.id).order('target_date')).catch(() => []);
    let codeText;
    if (status.status === 'pending') codeText = `Emailed to ${status.sent_to} on ${dateTime(status.issued_at)}. Expires ${date(status.expires_at)} · ${status.attempts_left} attempts left.`;
    else if (status.status === 'claimed') codeText = `The supplier entered the code in TenderTrack on ${dateTime(status.claimed_at)}. The contract is theirs.`;
    else if (status.status === 'expired') codeText = `The code expired on ${date(status.expires_at)} without being used.`;
    else if (status.status === 'locked') codeText = 'Five wrong codes were entered. Send a new code if the supplier asks for one.';
    else codeText = 'No award code has been sent for this award.';

    put(detailEl, 
      h('div', { class: 'card' },
        h('div', { class: 'card-head' }, h('p', { class: 'h3' }, 'Award'), badge(claimLabel, claimTone)),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Awarded to'), h('span', { class: 'kv-value' }, t.awarded_supplier_name)),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Awarded value'), h('span', { class: 'kv-value' }, money(t.awarded_value))),
        h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, 'Award date'), h('span', { class: 'kv-value' }, date(t.awarded_at))),
        h('p', { class: 'mt' }, codeText),
        t.status === 'awarded' && status.status !== 'claimed'
          ? h('button', { class: `btn ${status.status === 'pending' ? 'btn-secondary' : 'btn-primary'} mt`, type: 'button', onclick: () => sendCode(t, status.status) },
            icon('send'), status.status === 'no_code' ? 'Send award code' : 'Send a new award code')
          : null),
      h('h3', { class: 'h2 section' }, 'Deliverables'),
      deliverables.length
        ? h('div', { class: 'table-wrap' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'Phase'), h('th', {}, 'Target'), h('th', { class: 'num' }, 'Value'), h('th', {}, 'Status'), h('th', {}, 'Evidence'), h('th', {}, ''))),
          h('tbody', {}, deliverables.map((d) => {
            const [label, tone] = DELIV[d.status] || [d.status, 'neutral'];
            return h('tr', {},
              h('td', {}, h('span', { class: 'primary' }, d.phase_name)),
              h('td', { class: 'nowrap' }, date(d.target_date)),
              h('td', { class: 'num' }, money(d.phase_value)),
              h('td', {}, badge(label, tone)),
              h('td', {}, d.evidence_url && /^https?:\/\//i.test(d.evidence_url) ? h('a', { href: d.evidence_url, target: '_blank', rel: 'noopener noreferrer' }, 'Open link') : null,
                d.evidence_note ? h('div', { class: 'secondary pre' }, d.evidence_note) : null,
                d.evidence_at ? h('div', { class: 'secondary' }, dateTime(d.evidence_at)) : null),
              h('td', {}, d.status === 'awaiting_verification' ? h('div', { class: 'row' },
                h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: () => reviewDeliverable(t, d, true) }, 'Verify'),
                h('button', { class: 'btn btn-danger btn-sm', type: 'button', onclick: () => reviewDeliverable(t, d, false) }, 'Return')) : null));
          }))))
        : h('p', { class: 'meta' }, 'The contract phases are created when the award is made.'),
      h('h3', { class: 'h2 section' }, 'Bids'),
      bidsTable(false));
  }

  fill(main,
    h('div', { class: 'page-head' },
      h('div', {}, h('p', { class: 'eyebrow' }, `Procurement officer · ${who.profile.department || 'All departments'}`),
        h('h1', { class: 'h1' }, 'Department console'),
        h('p', { class: 'meta' }, 'Close bidding, evaluate and award. Tenders are registered and published in TenderTrack.')),
      who.profile.department ? h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: state.mine,
        onchange: (e) => { state.mine = e.target.checked; drawTabs(); drawList(); } }), `Only ${who.profile.department}`) : null),
    tabsEl,
    h('div', { class: 'mail-layout' }, listEl, detailEl));
  drawTabs();
  drawList();
  drawDetail();
}

render();
