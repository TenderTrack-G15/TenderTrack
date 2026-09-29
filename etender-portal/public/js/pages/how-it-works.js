/* How the portal and TenderTrack work together. */

import { chrome } from '../layout.js';
import { h, clear, icon, note, fill } from '../ui.js';

async function render() {
  const { main } = await chrome('how');
  const step = (no, title, who, text) => h('div', { class: 'step' },
    h('div', { class: 'step-no' }, String(no)), h('p', { class: 'eyebrow' }, who), h('p', { class: 'h3' }, title), h('p', { class: 'meta mt' }, text));

  fill(main,
    h('div', { class: 'page-head' }, h('div', {},
      h('p', { class: 'eyebrow' }, 'eTender Demo Portal'),
      h('h1', { class: 'h1' }, 'How it works'),
      h('p', { class: 'meta' }, 'Bidding happens here. Tracking the tender and the contract happens in TenderTrack.'))),

    note('warn', 'A demonstration system', 'This portal was built for the TenderTrack student project to stand in for a government tender website. It shares TenderTrack\'s database. It is not a government website.'),

    h('section', { class: 'section' },
      h('h2', { class: 'h2' }, 'From tender to contract'),
      h('div', { class: 'steps mt' },
        step(1, 'Tender published', 'Procurement officer · TenderTrack', 'The officer registers the tender and publishes it. It appears here and in TenderTrack at the same time.'),
        step(2, 'Company registers', 'Supplier · this portal', 'Once only: company, contact, compliance, banking, capabilities and documents. The officer verifies it in TenderTrack.'),
        step(3, 'Bid submitted', 'Supplier · this portal', 'Price, validity, preference points, required documents and a declaration, before the closing date.'),
        step(4, 'Bids evaluated', 'Procurement officer · this portal', 'After closing, the officer opens the bids, compares them and awards one.')),
      h('div', { class: 'steps mt' },
        step(5, 'Award code emailed', 'The database · this portal\'s server', 'The database creates a random 10-digit code, keeps only a scrambled copy, and the portal emails the code to the company.'),
        step(6, 'Award claimed', 'Supplier · TenderTrack', 'The company enters the code in TenderTrack under Awards. Only then is the contract theirs.'),
        step(7, 'Deliverables updated', 'Supplier · TenderTrack', 'For each phase, the company submits evidence of delivery.'),
        step(8, 'Delivery verified', 'Procurement officer', 'The officer verifies or returns the evidence. The public follows progress in TenderTrack.'))),

    h('section', { class: 'section grid-2' },
      h('div', { class: 'card' },
        h('p', { class: 'h3' }, 'Why an award code?'),
        h('ul', { class: 'bullets' },
          h('li', {}, 'It proves the award reached the company\'s own email address.'),
          h('li', {}, 'The department cannot see the code, and neither can anyone reading the database: only a scrambled copy is kept.'),
          h('li', {}, 'It works once, for 14 days, and locks after 5 wrong tries. The department can send a new one.'),
          h('li', {}, 'Until it is entered, the company cannot start the contract or update deliverables — the database enforces this.'))),
      h('div', { class: 'card' },
        h('p', { class: 'h3' }, 'One account, two places'),
        h('p', { class: 'meta mt' }, 'Suppliers and officers use the same email and password on this portal and in the TenderTrack app. There is no separate registration in TenderTrack: companies register here.'),
        h('div', { class: 'row mt' }, h('a', { class: 'btn btn-primary', href: '/register' }, icon('business'), 'Register a company'), h('a', { class: 'btn btn-secondary', href: '/tenders' }, 'Browse tenders')))));
}

render();
