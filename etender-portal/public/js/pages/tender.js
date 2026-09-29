/*
 * One tender: details, important dates, scope of work, eligibility,
 * documents for download, submission information, evaluation criteria,
 * contact information and tender updates.
 */

import { chrome, showError } from '../layout.js';
import { rpc } from '../client.js';
import { h, clear, icon, date, dateTime, money, daysUntil, badge, note, isOpen, downloadFile, toast, fill } from '../ui.js';
import { loadPack, lines, STANDARD_DOCUMENTS, placeholderDocument, statusBadge } from '../tenders-data.js';

const UPDATE_LABELS = { clarification: 'Clarification', question: 'Question & answer', amendment: 'Amendment', extension: 'Deadline extension' };
const UPDATE_TONES = { clarification: 'info', question: 'neutral', amendment: 'warn', extension: 'warn' };

async function render() {
  const { who, main } = await chrome('tenders');
  const id = new URLSearchParams(location.search).get('id');
  if (!id) { showError(main, 'No tender was chosen.', null); return; }

  let pack;
  let myBid = null;
  try {
    pack = await loadPack(id);
    if (who && who.profile && who.profile.role === 'supplier') {
      const bids = await rpc('supplier_bids');
      myBid = (bids || []).find((b) => b.tender_id === id && b.status !== 'withdrawn') || null;
    }
  } catch (e) {
    showError(main, e.message, render);
    return;
  }

  const t = pack.tender;
  const d = pack.details || {};
  document.title = `${t.reference_number} · eTender Demo Portal`;
  const isSupplier = who && who.profile && who.profile.role === 'supplier';
  const open = isOpen(t);

  const section = (title, ...content) => h('section', { class: 'card' }, h('h2', { class: 'h2 mb' }, title), content);
  const kv = (k, v) => h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, k), h('span', { class: 'kv-value' }, v || '—'));
  const block = (label, text) => h('div', { class: 'text-block' }, h('p', { class: 'label' }, label), h('p', { class: 'pre mt-sm' }, text || 'Not stated.'));
  const bullets = (label, text) => h('div', { class: 'text-block' }, h('p', { class: 'label' }, label),
    lines(text).length ? h('ul', { class: 'bullets' }, lines(text).map((l) => h('li', {}, l))) : h('p', { class: 'meta' }, 'Not stated.'));

  // Documents: the department's own files where attached, a demonstration document otherwise.
  const attached = new Map(pack.documents.map((doc) => [doc.name, doc]));
  // The department's list when it has one; otherwise the usual six.
  const docNames = pack.documents.length ? [...new Set(pack.documents.map((doc) => doc.name))] : STANDARD_DOCUMENTS;

  const side = h('aside', { class: 'side' }, h('div', { class: 'card' },
    h('p', { class: 'eyebrow' }, open ? 'Closes in' : 'Bidding'),
    open ? h('p', { class: 'countdown' }, `${daysUntil(t.closing_date)} days`) : h('p', { class: 'countdown' }, t.status === 'published' || t.status === 'under_evaluation' ? 'Closed' : 'Awarded'),
    h('p', { class: 'meta' }, `Closing date: ${dateTime(t.closing_date)}`),
    h('div', { class: 'mt' },
      open && isSupplier ? h('a', { class: 'btn btn-primary', href: `/bid?tender=${t.id}` }, icon('send'), myBid ? 'Change your bid' : 'Submit a bid') : null,
      open && !who ? h('div', { class: 'stack' },
        h('a', { class: 'btn btn-primary', href: `/signin?next=${encodeURIComponent(`/bid?tender=${t.id}`)}` }, 'Sign in to bid'),
        h('a', { class: 'btn btn-secondary', href: '/register' }, 'Register as a supplier')) : null,
      open && who && !isSupplier ? h('p', { class: 'meta' }, 'Only registered suppliers can bid.') : null),
    myBid ? h('div', { class: 'note note-success mt' }, icon('success'), h('div', {},
      h('p', { class: 'note-title' }, `Your bid ${myBid.reference}`),
      h('p', {}, `${money(myBid.bid_value)} · submitted ${dateTime(myBid.submitted_at)}`))) : null,
    t.awarded_supplier_name ? h('div', { class: 'mt kv-list' },
      kv('Awarded to', t.awarded_supplier_name), kv('Awarded value', money(t.awarded_value)),
      kv('Estimated budget', money(t.estimated_budget)), kv('Award date', date(t.awarded_at))) : null));

  fill(main,
    h('p', { class: 'crumbs' }, h('a', { href: '/' }, 'Home'), ' / ', h('a', { href: '/tenders' }, 'Tender opportunities'), ` / ${t.reference_number}`),
    h('div', { class: 'page-head' },
      h('div', {},
        h('p', { class: 'eyebrow' }, `${t.reference_number} · ${t.department}`),
        h('h1', { class: 'h1' }, t.title),
        h('div', { class: 'row mt' }, statusBadge(t), badge(t.category || 'General', 'neutral', false)))),

    h('div', { class: 'detail-layout' },
      h('div', { class: 'stack' },
        section('Tender details',
          kv('Tender number', t.reference_number), kv('Tender title', t.title), kv('Category', t.category),
          kv('Issuing department', t.department), kv('Status', statusBadge(t)),
          kv('Contract period', t.contract_period_months ? `${t.contract_period_months} months` : null),
          h('div', { class: 'mt' }, block('Description', t.description))),

        section('Important dates',
          kv('Publication date', date(t.published_at || t.created_at)),
          kv('Closing date and time', dateTime(t.closing_date)),
          kv('Briefing session', d.briefing_at ? `${dateTime(d.briefing_at)} · ${d.briefing_compulsory ? 'compulsory' : 'optional'}` : 'None'),
          kv('Briefing venue', d.briefing_venue || null),
          kv('Site visit', d.site_visit_at ? dateTime(d.site_visit_at) : 'None'),
          kv('Expected award date', d.expected_award_date ? date(d.expected_award_date) : null)),

        section('Scope of work',
          block('Project overview', d.scope_overview),
          bullets('Deliverables required', d.deliverables),
          block('Technical specifications', d.technical_specs),
          block('Quantity requirements', d.quantity_requirements),
          block('Expected outcomes', d.expected_outcomes)),

        section('Eligibility requirements',
          pack.eligibility.length
            ? pack.eligibility.map((r) => h('div', { class: 'kv' }, h('span', {}, r.requirement),
              badge(r.mandatory ? 'Required' : 'If applicable', r.mandatory ? 'danger' : 'neutral', false)))
            : h('p', { class: 'meta' }, 'No eligibility requirements have been published.')),

        section('Documents for download',
          h('p', { class: 'meta mb' }, 'Documents marked "demonstration" are generated by this portal because the department has not attached a file.'),
          docNames.map((name) => {
            const doc = attached.get(name);
            const url = doc && doc.file_url && /^https?:\/\//i.test(doc.file_url) ? doc.file_url : null;
            return h('div', { class: 'doc-row' },
              h('div', { class: 'row' }, icon('document'), h('div', {}, h('div', { class: 'primary' }, name),
                h('div', { class: 'secondary' }, url ? (doc.doc_type || 'Department file') : 'Demonstration document (text)'))),
              url
                ? h('a', { class: 'btn btn-secondary btn-sm', href: url, target: '_blank', rel: 'noopener noreferrer' }, icon('download'), 'Open')
                : h('button', { class: 'btn btn-secondary btn-sm', type: 'button',
                  onclick: () => { downloadFile(`${t.reference_number.replace(/[^A-Za-z0-9]+/g, '-')}-${name.replace(/[^A-Za-z0-9]+/g, '-')}.txt`, placeholderDocument(pack, name), 'text/plain;charset=utf-8'); toast(`Downloaded ${name}.`); } },
                icon('download'), 'Download'));
          })),

        section('Submission information',
          note('info', 'Bidding on this demonstration portal', 'Bids are submitted online here with "Submit a bid", whatever submission method the tender pack states.'),
          h('div', { class: 'mt' }),
          kv('Submission method', d.submission_method || 'Online on this portal'),
          kv('Submission deadline', dateTime(t.closing_date)),
          kv('Proposal format', d.submission_format || null),
          h('div', { class: 'mt' }, bullets('Required documents', d.required_documents)),
          kv('Queries', d.query_contact_email ? `${d.query_contact_name} · ${d.query_contact_email}` : null)),

        section('Evaluation criteria',
          h('div', { class: 'weights' },
            h('div', { class: 'weight' }, h('b', {}, d.technical_weight != null ? `${d.technical_weight}%` : '—'), 'Technical evaluation'),
            h('div', { class: 'weight' }, h('b', {}, d.financial_weight != null ? `${d.financial_weight}%` : '—'), 'Financial evaluation')),
          h('div', { class: 'mt' }, kv('Preference point system', d.preference_points || null)),
          kv('Compliance requirements', pack.eligibility.filter((r) => r.mandatory).length
            ? `${pack.eligibility.filter((r) => r.mandatory).length} mandatory requirements (see Eligibility)` : null)),

        section('Contact information',
          kv('Contact person', d.query_contact_name || null),
          kv('Email address', d.query_contact_email || null),
          kv('Telephone number', d.query_contact_phone || null)),

        section('Tender updates',
          pack.updates.length
            ? pack.updates.map((u) => h('div', { class: 'update' },
              h('div', { class: 'spread' }, h('div', { class: 'row' }, badge(UPDATE_LABELS[u.kind] || u.kind, UPDATE_TONES[u.kind] || 'neutral', false), h('span', { class: 'primary' }, u.title)),
                h('span', { class: 'secondary' }, date(u.published_at))),
              u.body ? h('p', { class: 'meta mt-sm pre' }, u.body) : null))
            : h('p', { class: 'meta' }, 'No clarifications, answers, amendments or extensions yet.'))),
      side));
}

render();
