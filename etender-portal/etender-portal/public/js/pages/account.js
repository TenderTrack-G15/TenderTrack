/* A supplier's own page: registration status, awards and bids. */

import { chrome, requireRole, showError } from '../layout.js';
import { rpc } from '../client.js';
import { h, clear, icon, note, badge, money, date, dateTime, empty, toast, confirmDialog, fill } from '../ui.js';

const REG = {
  awaiting_verification: ['Awaiting verification', 'warn'],
  verified: ['Verified', 'success'],
  not_approved: ['Not approved', 'danger'],
};
const OUTCOME = {
  open: ['Submitted', 'info'],
  under_evaluation: ['Under evaluation', 'warn'],
  awarded: ['Awarded to you', 'success'],
  unsuccessful: ['Not successful', 'neutral'],
  withdrawn: ['Withdrawn', 'neutral'],
  disqualified: ['Disqualified', 'danger'],
};
const CLAIM = {
  pending: ['Code sent · not yet claimed', 'warn'],
  claimed: ['Claimed', 'success'],
  expired: ['Code expired', 'danger'],
  locked: ['Code locked', 'danger'],
  no_code: ['Code not sent yet', 'neutral'],
};

async function render() {
  const { who, main } = await chrome('account');
  if (!requireRole(who, 'supplier', main)) return;
  const s = who.supplier;

  let bids;
  let awards;
  try {
    [bids, awards] = await Promise.all([rpc('supplier_bids'), rpc('supplier_awards')]);
  } catch (e) {
    showError(main, e.message, render);
    return;
  }
  const [regLabel, regTone] = REG[s.status] || [s.status, 'neutral'];
  const kv = (k, v) => h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, k), h('span', { class: 'kv-value' }, v || '—'));

  async function withdraw(b) {
    const yes = await confirmDialog({ title: 'Withdraw your bid?', message: `${b.reference} for ${b.tender_reference} will be withdrawn. You can submit a new bid before the closing date.`, confirmText: 'Withdraw', danger: true });
    if (!yes) return;
    try {
      await rpc('withdraw_bid', { p_bid_id: b.id });
      toast('Bid withdrawn.');
      render();
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  fill(main,
    h('div', { class: 'page-head' }, h('div', {},
      h('p', { class: 'eyebrow' }, 'My account'),
      h('h1', { class: 'h1' }, s.company_name),
      h('p', { class: 'meta' }, `Signed in as ${who.profile.full_name} (${who.profile.email})`)),
      h('a', { class: 'btn btn-primary', href: '/tenders' }, icon('search'), 'Find a tender')),

    h('div', { class: 'grid-2' },
      h('div', { class: 'card' },
        h('div', { class: 'card-head' }, h('p', { class: 'h2' }, 'Registration'), badge(regLabel, regTone)),
        kv('CSD number', s.csd_number), kv('Registration number', s.registration_number),
        kv('Contact email', s.contact_email), kv('Documents provided', `${s.documents_received} (${s.documents_required} required)`),
        kv('eTender portal', s.portal_registered_at ? `Registered ${date(s.portal_registered_at)}` : 'Not complete'),
        kv('TenderTrack app', s.app_registered_at ? `Registered ${date(s.app_registered_at)}` : 'Not registered yet'),
        !s.portal_registered_at ? h('div', { class: 'mt' }, note('warn', 'Finish your eTender registration',
          'Your company\'s registration on this portal is not complete, so you cannot bid or register for TenderTrack yet.'),
          h('a', { class: 'btn btn-primary btn-sm mt', href: '/register' }, 'Finish registration')) : null,
        s.app_registered_at || !s.portal_registered_at ? null : h('div', { class: 'mt' }, note('warn', 'Register for TenderTrack as well',
          `Awards are claimed in the TenderTrack app. Open Supplier login → Register an account, and use this email and password, CSD ${s.csd_number} and registration number ${s.registration_number}.`)),
        s.status === 'not_approved' ? h('div', { class: 'mt' }, note('danger', 'Not approved', s.decision_reason || 'Correct your details and resubmit in the TenderTrack app.')) : null,
        s.status === 'awaiting_verification' ? h('p', { class: 'hint mt' }, 'A procurement officer verifies registrations in TenderTrack. You can bid meanwhile; awards need a verified registration.') : null,
        h('p', { class: 'hint mt' }, 'To change your company details, banking or documents, use My company in the TenderTrack app.')),

      h('div', { class: 'card' },
        h('p', { class: 'h2 mb' }, 'Awards'),
        awards.length ? awards.map((a) => {
          const [label, tone] = CLAIM[a.claim_status] || [a.claim_status, 'neutral'];
          let advice;
          if (a.claim_status === 'pending') advice = `A 10-digit award code was emailed to ${a.sent_to}. Enter it in the TenderTrack app under Awards before ${date(a.expires_at)} (${a.attempts_left} attempts left).`;
          else if (a.claim_status === 'claimed') advice = `Claimed on ${date(a.claimed_at)}. Update the contract's deliverables in the TenderTrack app.`;
          else advice = 'Ask the department to send a new award code.';
          return h('div', { class: 'update' },
            h('div', { class: 'spread' }, h('span', { class: 'primary' }, `${a.reference_number} · ${a.title}`), badge(label, tone)),
            h('p', { class: 'meta mt-sm' }, `${a.department} · ${money(a.awarded_value)} · awarded ${date(a.awarded_at)}`),
            h('p', { class: 'mt-sm' }, advice),
            a.claim_status === 'pending' ? h('a', { class: 'btn btn-secondary btn-sm mt', href: '/mailbox' }, icon('mail'), 'Open demo mailbox') : null);
        }) : h('p', { class: 'meta' }, 'No tenders have been awarded to your company yet.'))),

    h('section', { class: 'section' },
      h('h2', { class: 'h2' }, 'My bids'),
      bids.length
        ? h('div', { class: 'table-wrap mt' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'Tender'), h('th', {}, 'Bid'), h('th', { class: 'num' }, 'Price'), h('th', {}, 'Submitted'), h('th', {}, 'Closing'), h('th', {}, 'Outcome'), h('th', {}, ''))),
          h('tbody', {}, bids.map((b) => {
            const [label, tone] = OUTCOME[b.outcome] || [b.outcome, 'neutral'];
            const canChange = b.outcome === 'open' && b.status === 'submitted';
            return h('tr', {},
              h('td', {}, h('a', { class: 'primary', href: `/tender?id=${b.tender_id}` }, b.tender_title), h('div', { class: 'secondary' }, `${b.tender_reference} · ${b.department}`)),
              h('td', { class: 'nowrap' }, b.reference),
              h('td', { class: 'num' }, money(b.bid_value)),
              h('td', { class: 'nowrap' }, dateTime(b.submitted_at)),
              h('td', { class: 'nowrap' }, dateTime(b.closing_date)),
              h('td', {}, badge(label, tone)),
              h('td', {}, canChange ? h('div', { class: 'row' },
                h('a', { class: 'btn btn-secondary btn-sm', href: `/bid?tender=${b.tender_id}` }, 'Change'),
                h('button', { class: 'btn btn-danger btn-sm', type: 'button', onclick: () => withdraw(b) }, 'Withdraw')) : null));
          }))))
        : h('div', { class: 'card mt' }, empty('No bids yet', 'Find an advertised tender and submit your first bid.', 'send'))),
  );
}

render();
