/* Home page: search, figures, the latest advertised tenders and how to take part. */

import { chrome, showError } from '../layout.js';
import { h, clear, icon, date, daysUntil, empty, fill } from '../ui.js';
import { listTenders, tabOf } from '../tenders-data.js';

async function render() {
  const { who, main } = await chrome('home');
  let tenders;
  try {
    tenders = await listTenders();
  } catch (e) {
    showError(main, e.message, render);
    return;
  }

  const advertised = tenders.filter((t) => tabOf(t) === 'advertised');
  const closingSoon = advertised.filter((t) => (daysUntil(t.closing_date) ?? 99) <= 7);
  const awarded = tenders.filter((t) => tabOf(t) === 'awarded');
  const search = h('input', { class: 'input', type: 'search', name: 'q', placeholder: 'Search by tender number, description or department', 'aria-label': 'Search tenders' });

  fill(main,
    h('section', { class: 'hero' },
      h('p', { class: 'eyebrow' }, 'Demonstration procurement portal'),
      h('h1', { class: 'h1' }, 'Find and bid for public-sector tenders'),
      h('p', {}, 'Browse advertised tenders, download the tender documents and submit your bid online. '
        + 'Awarded companies receive a one-time award code by email and claim the contract in the TenderTrack app.'),
      h('form', { class: 'hero-search', action: '/tenders', method: 'get' },
        search,
        h('button', { class: 'btn btn-light', type: 'submit' }, icon('search'), 'Search tenders'))),

    h('div', { class: 'stats mt' },
      stat('Open for bids', advertised.length, 'Advertised tenders', '/tenders'),
      stat('Closing within 7 days', closingSoon.length, 'Submit before the deadline', '/tenders'),
      stat('Awarded', awarded.length, 'Published award results', '/tenders?tab=awarded'),
      stat('Departments', new Set(tenders.map((t) => t.department)).size, 'Advertising on this portal', '/tenders')),

    who && who.supplier ? h('div', { class: 'note note-info mt' }, icon('info'),
      h('div', {}, h('p', { class: 'note-title' }, `Signed in as ${who.supplier.company_name}`),
        h('p', {}, 'See your bids and any awards under ', h('a', { href: '/account' }, 'My bids & awards'), '.'))) : null,

    h('section', { class: 'section' },
      h('div', { class: 'spread' }, h('h2', { class: 'h2' }, 'Latest advertised tenders'), h('a', { href: '/tenders' }, 'View all tender opportunities')),
      advertised.length
        ? h('div', { class: 'table-wrap mt' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'Tender'), h('th', { class: 'hide-sm' }, 'Department'), h('th', {}, 'Closing'))),
          h('tbody', {}, advertised.slice(0, 6).map((t) => h('tr', {},
            h('td', {}, h('a', { class: 'primary', href: `/tender?id=${t.id}` }, t.title), h('div', { class: 'secondary' }, `${t.reference_number} · ${t.category}`)),
            h('td', { class: 'hide-sm' }, t.department),
            h('td', { class: 'nowrap' }, date(t.closing_date), h('div', { class: 'secondary' }, `${daysUntil(t.closing_date)} days left`)))))))
        : h('div', { class: 'card mt' }, empty('No tenders are open right now', 'Check again later, or look at recent awards.', 'inbox'))),

    h('section', { class: 'section' },
      h('h2', { class: 'h2' }, 'How to take part'),
      h('div', { class: 'steps mt' },
        step(1, 'Register your company', 'Here first: company, contact, compliance, banking, capabilities and documents. Then register for the TenderTrack app too.'),
        step(2, 'Find a tender', 'Search here, or in the TenderTrack app. Download the documents and read the requirements.'),
        step(3, 'Submit your bid', 'Enter your price, confirm the required documents and submit before the closing date.'),
        step(4, 'Claim the award', 'If you win, you are emailed a one-time code. Enter it in TenderTrack to claim the contract.')),
      h('p', { class: 'meta mt' }, h('a', { href: '/how-it-works' }, 'Read more about how it works'))),
  );
}

function stat(label, value, foot, href) {
  return h('a', { class: 'stat', href },
    h('div', { class: 'stat-label' }, label),
    h('div', { class: 'stat-value' }, String(value)),
    h('div', { class: 'stat-foot' }, foot));
}

function step(no, title, text) {
  return h('div', { class: 'step' }, h('div', { class: 'step-no' }, String(no)), h('p', { class: 'h3' }, title), h('p', { class: 'meta mt' }, text));
}

render();
