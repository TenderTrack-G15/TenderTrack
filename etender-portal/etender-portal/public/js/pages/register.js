/*
 * Supplier registration — company, contact, compliance, banking, business
 * capabilities and supporting documents, then the account.
 *
 * A company registers here first (the government's record), then registers
 * for TenderTrack in the app with the same account, its CSD and registration
 * numbers and a 6-digit code emailed to its contact address. The new
 * registration starts as "awaiting verification"; a procurement officer
 * verifies it in TenderTrack.
 */

import { chrome } from '../layout.js';
import { sb, rpc, api, messageOf } from '../client.js';
import { h, clear, icon, note, busy, toast, fill } from '../ui.js';

const PROVINCES = ['Eastern Cape', 'Free State', 'Gauteng', 'KwaZulu-Natal', 'Limpopo', 'Mpumalanga', 'Northern Cape', 'North West', 'Western Cape'];
const BUSINESS_TYPES = ['Private company (Pty) Ltd', 'Public company (Ltd)', 'Close corporation (CC)', 'Sole proprietor', 'Partnership',
  'Non-profit company (NPC)', 'Non-governmental organisation (NGO)', 'Trust', 'Co-operative'];
const BANKS = ['ABSA', 'African Bank', 'Bidvest Bank', 'Capitec', 'Discovery Bank', 'First National Bank (FNB)', 'Investec', 'Nedbank', 'Standard Bank', 'TymeBank', 'Other'];
const CATEGORIES = ['IT infrastructure', 'Software development', 'Cloud services', 'Cyber security', 'Networking', 'Hardware supply',
  'IT consulting', 'Support and maintenance', 'Training', 'Other'];
const INDUSTRIES = ['Information and communication technology', 'Construction and engineering', 'Professional services', 'Health', 'Education', 'Manufacturing', 'Other'];
const THIS_YEAR = new Date().getFullYear();

/** Document names match default_supplier_documents in the database. */
const DOCUMENTS = [
  ['Company registration certificate (CIPC)', true],
  ['Tax clearance certificate / PIN', true],
  ['B-BBEE certificate or affidavit', false],
  ['Proof of business address', true],
  ['Company profile', true],
  ['Proof of banking details', true],
  ['Industry licences or certifications', false],
];

const RX = {
  registration: [/^\d{4}\/\d{6}\/\d{2}$/, 'Use the CIPC format, e.g. 2019/451236/07.'],
  csd: [/^MAAA\d{7}$/, 'Use the CSD format, e.g. MAAA0451236.'],
  tax: [/^\d{10}$/, 'The income tax number has 10 digits.'],
  vat: [/^4\d{9}$/, 'A VAT number has 10 digits and starts with 4.'],
  email: [/^[^@\s]+@[^@\s]+\.[^@\s]+$/, 'Enter a valid email address.'],
  mobile: [/^(\+27|0)[6-8]\d{8}$/, 'Use a South African mobile number, e.g. 0821234567.'],
  phone: [/^(\+27|0)\d{9}$/, 'Use a 10-digit number, e.g. 0112345678.'],
  account: [/^\d{6,17}$/, 'An account number has 6 to 17 digits.'],
  branch: [/^\d{6}$/, 'A branch code has 6 digits.'],
  url: [/^https?:\/\/\S+$/i, 'A link must start with http:// or https://'],
};

/** Every field, by step. */
const STEPS = [
  { id: 'company', title: 'Company information', intro: 'As registered with CIPC and on the Central Supplier Database (CSD).', fields: [
    { key: 'company_name', label: 'Company or business name', required: true, full: true },
    { key: 'registration_number', label: 'Registration number', required: true, rx: 'registration', placeholder: '2019/451236/07' },
    { key: 'csd_number', label: 'CSD supplier number', required: true, rx: 'csd', placeholder: 'MAAA0451236', upper: true },
    { key: 'tax_number', label: 'Income tax number', required: true, rx: 'tax', placeholder: '9012345678' },
    { key: 'vat_number', label: 'VAT number (if applicable)', rx: 'vat', placeholder: '4012345678' },
    { key: 'business_type', label: 'Business type', required: true, type: 'select', options: BUSINESS_TYPES },
    { key: 'year_established', label: 'Year established', type: 'number', min: 1800, max: THIS_YEAR, placeholder: String(THIS_YEAR - 5) },
  ] },
  { id: 'contact', title: 'Contact information', intro: 'The person the department should contact about bids and awards.', fields: [
    { key: 'contact_person', label: 'Contact person name', required: true },
    { key: 'job_title', label: 'Job title', required: true },
    { key: 'contact_email', label: 'Email address', required: true, rx: 'email', type: 'email', hint: 'Award codes are emailed here.' },
    { key: 'phone_number', label: 'Phone number', rx: 'phone', type: 'tel', placeholder: '0112345678' },
    { key: 'mobile_number', label: 'Mobile number', required: true, rx: 'mobile', type: 'tel', placeholder: '0821234567' },
    { key: 'province', label: 'Province', required: true, type: 'select', options: PROVINCES },
    { key: 'physical_address', label: 'Physical address', required: true, type: 'textarea', full: true },
    { key: 'postal_address', label: 'Postal address', type: 'textarea', full: true, hint: 'Leave empty if it is the same as the physical address.' },
  ] },
  { id: 'compliance', title: 'Compliance information', intro: 'Tax, B-BBEE, licences and professional registrations. The certificates themselves are listed under Supporting documents.', fields: [
    { key: 'tax_clearance_status', label: 'Tax clearance status', required: true, type: 'select',
      options: [['valid', 'Tax compliant (valid PIN)'], ['pending', 'Application pending'], ['not_submitted', 'Not yet obtained']] },
    { key: 'tax_clearance_expiry', label: 'Tax clearance valid until', type: 'date', requiredIf: (d) => d.tax_clearance_status === 'valid' },
    { key: 'bbbee_level', label: 'B-BBEE status level (where applicable)', type: 'select',
      options: [['', 'Not applicable / non-compliant'], ...[1, 2, 3, 4, 5, 6, 7, 8].map((n) => [String(n), `Level ${n}`])] },
    { key: 'bbbee_expiry', label: 'B-BBEE certificate valid until', type: 'date', requiredIf: (d) => Boolean(d.bbbee_level) },
    { key: 'industry_licences', label: 'Industry licences and certifications', type: 'textarea', full: true, placeholder: 'e.g. ICASA ECNS licence; ISO 27001 certificate' },
    { key: 'professional_registrations', label: 'Professional registrations', type: 'textarea', full: true, placeholder: 'e.g. ECSA, IITPSA, CIDB grading' },
  ] },
  { id: 'banking', title: 'Banking information', intro: 'Kept confidential: only your company and a finance officer can see it, and it is used only after a contract is awarded.', fields: [
    { key: 'bank_name', label: 'Bank name', required: true, type: 'select', options: BANKS },
    { key: 'account_name', label: 'Account name', required: true, hint: 'The name the account is held in.' },
    { key: 'account_number', label: 'Account number', required: true, rx: 'account', inputmode: 'numeric' },
    { key: 'branch_code', label: 'Branch code', required: true, rx: 'branch', inputmode: 'numeric', placeholder: '250655' },
    { key: 'proof_url', label: 'Proof of banking details (link)', rx: 'url', full: true, hint: 'Optional. A link to a bank-stamped letter. Files are not uploaded in this demo.' },
  ] },
  { id: 'capabilities', title: 'Business capabilities', intro: 'What you supply and where. Used by departments to understand your company.', fields: [
    { key: 'categories', label: 'Goods and services categories', required: true, type: 'checks', options: CATEGORIES, full: true },
    { key: 'industry', label: 'Industry or sector', required: true, type: 'select', options: INDUSTRIES },
    { key: 'employees', label: 'Number of employees', type: 'number', min: 0, max: 1000000 },
    { key: 'expertise', label: 'Areas of expertise', type: 'textarea', full: true, placeholder: 'e.g. Network design, structured cabling, managed Wi-Fi' },
    { key: 'geographic_areas', label: 'Geographic areas served', required: true, type: 'checks', options: ['National', ...PROVINCES], full: true },
    { key: 'company_profile', label: 'Company profile', type: 'textarea', full: true, placeholder: 'A short description of the company, its history and major clients.' },
  ] },
  { id: 'documents', title: 'Supporting documents', intro: 'For each document, record its reference number and, if it expires, the expiry date. Files are not uploaded in this demo: add a link if the document is online.' },
  { id: 'account', title: 'Account and declaration', intro: 'You sign in to this portal with this email and password, and use the same ones to register for the TenderTrack app afterwards.', fields: [
    { key: 'email', label: 'Sign-in email address', required: true, rx: 'email', type: 'email', autocomplete: 'username' },
    { key: 'password', label: 'Password', required: true, type: 'password', autocomplete: 'new-password', hint: 'At least 8 characters, with letters and numbers.' },
    { key: 'password2', label: 'Confirm password', required: true, type: 'password', autocomplete: 'new-password' },
    { key: 'declaration', label: 'I declare that the information in this registration is true and correct, and that I am authorised to register this company. I understand that this is a demonstration system and that I have not entered real banking details.', required: true, type: 'checkbox', full: true },
  ] },
  { id: 'review', title: 'Review and submit' },
];

async function render() {
  const { who, main } = await chrome('register');
  if (who && who.profile) {
    clear(main).appendChild(h('div', { class: 'card' },
      h('p', { class: 'h2' }, 'You are already signed in'),
      h('p', { class: 'meta mt' }, `Signed in as ${who.profile.full_name}. Sign out first to register another company.`),
      h('a', { class: 'btn btn-secondary mt', href: '/account' }, 'Go to my account')));
    return;
  }

  const data = { business_type: '', province: '', tax_clearance_status: 'valid', bbbee_level: '', bank_name: '', industry: INDUSTRIES[0], categories: [], geographic_areas: [], declaration: false };
  const docs = Object.fromEntries(DOCUMENTS.map(([name]) => [name, { provided: false, reference: '', expires: '', link: '' }]));
  let current = 0;
  const errors = {};

  const stepsList = h('ol', { class: 'wizard-steps' });
  const panel = h('div', { class: 'card' });

  function validateField(f) {
    const v = data[f.key];
    const empty = v === undefined || v === null || v === '' || v === false || (Array.isArray(v) && v.length === 0);
    const required = f.required || (f.requiredIf && f.requiredIf(data));
    if (required && empty) return f.type === 'checkbox' ? 'Tick the declaration to continue.' : f.type === 'checks' ? 'Choose at least one.' : 'This is required.';
    if (!empty && f.rx && !RX[f.rx][0].test(String(v).trim())) return RX[f.rx][1];
    if (!empty && f.type === 'number') {
      const n = Number(v);
      if (!Number.isInteger(n) || n < f.min || n > f.max) return `Enter a whole number from ${f.min} to ${f.max}.`;
    }
    if (f.key === 'password' && !empty && (String(v).length < 8 || !/[A-Za-z]/.test(v) || !/\d/.test(v))) return 'Use at least 8 characters, with letters and numbers.';
    if (f.key === 'password2' && !empty && v !== data.password) return 'The passwords do not match.';
    return '';
  }

  function validateDocs() {
    let ok = true;
    for (const [name, required] of DOCUMENTS) {
      const d = docs[name];
      d.error = '';
      if (required && !d.provided) { d.error = 'This document is required.'; ok = false; }
      else if (d.provided && d.reference.trim().length < 2) { d.error = 'Enter the document reference or number.'; ok = false; }
      else if (d.link && !RX.url[0].test(d.link.trim())) { d.error = RX.url[1]; ok = false; }
    }
    return ok;
  }

  function validateStep(i) {
    const step = STEPS[i];
    if (step.id === 'documents') return validateDocs();
    let ok = true;
    for (const f of step.fields || []) {
      errors[f.key] = validateField(f);
      if (errors[f.key]) ok = false;
    }
    return ok;
  }

  function drawSteps() {
    clear(stepsList);
    STEPS.forEach((s, i) => stepsList.appendChild(h('li', { class: i === current ? 'current' : i < current ? 'done' : null },
      h('span', { class: 'no' }, i < current ? '✓' : String(i + 1)), s.title)));
  }

  function input(f) {
    const common = { id: `f-${f.key}`, 'aria-invalid': errors[f.key] ? 'true' : 'false' };
    const set = (value) => { data[f.key] = value; if (errors[f.key]) { errors[f.key] = validateField(f); refreshError(f); } };
    if (f.type === 'select') {
      const opts = f.options.map((o) => (Array.isArray(o) ? o : [o, o]));
      const el = h('select', { class: 'select', ...common, onchange: (e) => { set(e.target.value); if (f.key === 'tax_clearance_status' || f.key === 'bbbee_level') drawPanel(); } },
        f.required && !opts.some(([v]) => v === '') ? h('option', { value: '' }, 'Choose…') : null,
        opts.map(([v, t]) => h('option', { value: v, selected: String(data[f.key] ?? '') === v }, t)));
      return el;
    }
    if (f.type === 'textarea') return h('textarea', { class: 'textarea', ...common, maxlength: '1000', placeholder: f.placeholder || '', value: data[f.key] || '', oninput: (e) => set(e.target.value) });
    if (f.type === 'checks') {
      const chosen = new Set(data[f.key] || []);
      return h('div', { class: 'checks', id: common.id, role: 'group' }, f.options.map((o) => h('label', { class: 'check' },
        h('input', { type: 'checkbox', checked: chosen.has(o), onchange: (e) => { if (e.target.checked) chosen.add(o); else chosen.delete(o); set([...chosen]); } }), o)));
    }
    if (f.type === 'checkbox') {
      return h('label', { class: 'check' }, h('input', { type: 'checkbox', ...common, checked: Boolean(data[f.key]), onchange: (e) => set(e.target.checked) }), h('span', {}, f.label));
    }
    return h('input', {
      class: f.upper ? 'input upper' : 'input', ...common, type: f.type || 'text', placeholder: f.placeholder || '', value: data[f.key] ?? '',
      autocomplete: f.autocomplete || 'off', inputmode: f.inputmode || null, min: f.min != null ? String(f.min) : null, max: f.max != null ? String(f.max) : null,
      oninput: (e) => set(f.upper ? e.target.value.toUpperCase() : e.target.value),
    });
  }

  function refreshError(f) {
    const el = panel.querySelector(`[data-error-for="${f.key}"]`);
    if (el) { el.textContent = errors[f.key] || ''; el.hidden = !errors[f.key]; }
    const control = panel.querySelector(`#f-${f.key}`);
    if (control) control.setAttribute('aria-invalid', errors[f.key] ? 'true' : 'false');
  }

  function fieldBlock(f) {
    const required = f.required || (f.requiredIf && f.requiredIf(data));
    return h('div', { class: `field${f.full ? ' full' : ''}` },
      f.type === 'checkbox' ? null : h('label', { class: 'label', for: `f-${f.key}` }, f.label, required ? h('span', { class: 'req' }, ' *') : null),
      input(f),
      f.hint ? h('span', { class: 'hint' }, f.hint) : null,
      h('span', { class: 'field-error', 'data-error-for': f.key, hidden: !errors[f.key] }, errors[f.key] || ''));
  }

  function documentsPanel() {
    return DOCUMENTS.map(([name, required]) => {
      const d = docs[name];
      const detail = h('div', { class: 'form-grid mt', hidden: !d.provided },
        h('div', { class: 'field' }, h('label', { class: 'label' }, 'Reference or number'), h('input', { class: 'input', value: d.reference, maxlength: '120', oninput: (e) => { d.reference = e.target.value; } })),
        h('div', { class: 'field' }, h('label', { class: 'label' }, 'Expiry date (if any)'), h('input', { class: 'input', type: 'date', value: d.expires, oninput: (e) => { d.expires = e.target.value; } })),
        h('div', { class: 'field full' }, h('label', { class: 'label' }, 'Link to the document (optional)'), h('input', { class: 'input', value: d.link, placeholder: 'https://', oninput: (e) => { d.link = e.target.value; } })));
      const error = h('p', { class: 'field-error', hidden: !d.error }, d.error || '');
      // A message already shown is updated as the supplier fixes it.
      const recheck = () => {
        if (!d.error) return;
        if (required && !d.provided) d.error = 'This document is required.';
        else if (d.provided && d.reference.trim().length < 2) d.error = 'Enter the document reference or number.';
        else if (d.link && !RX.url[0].test(d.link.trim())) d.error = RX.url[1];
        else d.error = '';
        error.textContent = d.error;
        error.hidden = !d.error;
      };
      detail.addEventListener('input', recheck);
      return h('div', { class: 'doc-card' },
        h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: d.provided, onchange: (e) => { d.provided = e.target.checked; detail.hidden = !d.provided; recheck(); } }),
          h('span', {}, h('strong', {}, name), required ? h('span', { class: 'req' }, ' *') : h('span', { class: 'secondary' }, ' (if applicable)'))),
        detail,
        error);
    });
  }

  function reviewPanel() {
    const rows = [];
    for (const step of STEPS) {
      if (!step.fields) continue;
      const items = step.fields.filter((f) => !['password', 'password2', 'declaration'].includes(f.key)).map((f) => {
        let v = data[f.key];
        if (Array.isArray(v)) v = v.join(', ');
        if (f.key === 'account_number' && v) v = `•••• ${String(v).slice(-4)}`;
        if (f.type === 'select' && v) { const o = f.options.find((x) => (Array.isArray(x) ? x[0] : x) === v); v = Array.isArray(o) ? o[1] : v; }
        return h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, f.label), h('span', { class: 'kv-value' }, v === '' || v === undefined || v === null ? '—' : String(v)));
      });
      if (items.length) rows.push(h('div', { class: 'mb' }, h('p', { class: 'h3 mb' }, step.title), items));
    }
    rows.push(h('div', { class: 'mb' }, h('p', { class: 'h3 mb' }, 'Supporting documents'),
      DOCUMENTS.map(([name]) => h('div', { class: 'kv' }, h('span', { class: 'kv-key' }, name),
        h('span', { class: 'kv-value' }, docs[name].provided ? docs[name].reference : 'Not provided')))));
    return rows;
  }

  function drawPanel() {
    drawSteps();
    const step = STEPS[current];
    const back = h('button', { class: 'btn btn-secondary', type: 'button', disabled: current === 0, onclick: () => { current -= 1; drawPanel(); window.scrollTo(0, 0); } }, 'Back');
    const next = h('button', { class: 'btn btn-primary', type: 'submit' }, step.id === 'review' ? 'Submit registration' : 'Continue');
    let body;
    if (step.id === 'documents') body = documentsPanel();
    else if (step.id === 'review') body = [note('info', 'Check everything before you submit', 'Your registration starts as "awaiting verification". A procurement officer verifies it in TenderTrack. You can bid straight away, but a tender can only be awarded to a verified supplier.'), h('div', { class: 'mt' }, reviewPanel())];
    else body = h('div', { class: 'form-grid' }, step.fields.map(fieldBlock));

    fill(panel, h('form', {
      novalidate: true,
      onsubmit: async (e) => {
        e.preventDefault();
        if (step.id === 'review') { await submit(next); return; }
        if (!validateStep(current)) {
          drawPanel();
          const firstError = panel.querySelector('[aria-invalid="true"], .field-error:not([hidden])');
          if (firstError) firstError.scrollIntoView({ block: 'center' });
          toast('Please correct the highlighted fields.', 'warn');
          return;
        }
        if (step.id === 'contact' && !data.email) data.email = data.contact_email;
        current += 1;
        drawPanel();
        window.scrollTo(0, 0);
      },
    },
    h('p', { class: 'eyebrow' }, `Step ${current + 1} of ${STEPS.length}`),
    h('h2', { class: 'h2' }, step.title),
    step.intro ? h('p', { class: 'meta mt-sm mb' }, step.intro) : h('div', { class: 'mb' }),
    step.id === 'banking' ? h('div', { class: 'mb' }, note('warn', 'Demonstration system', 'Do not enter real banking details. Use made-up numbers in the right format, for example account 1234567890 and branch code 250655.')) : null,
    body,
    h('div', { class: 'wizard-actions' }, back, next)));
  }

  async function submit(button) {
    for (let i = 0; i < STEPS.length - 1; i += 1) {
      if (!validateStep(i)) { current = i; drawPanel(); toast('Some details need attention.', 'warn'); return; }
    }
    const progress = h('ul', { class: 'bullets' });
    const say = (text) => progress.appendChild(h('li', {}, text));
    await busy(button, async () => {
      fill(panel, h('h2', { class: 'h2' }, 'Submitting your registration…'), progress);
      try {
        say('Creating your account…');
        await api('POST', '/api/register', {
          email: data.email.trim(), password: data.password,
          company: {
            company_name: data.company_name, registration_number: data.registration_number, csd_number: data.csd_number,
            business_type: data.business_type, tax_number: data.tax_number, contact_person: data.contact_person,
            job_title: data.job_title, mobile_number: data.mobile_number, province: data.province, bbbee_level: data.bbbee_level,
          },
        });
        const { error } = await sb.auth.signInWithPassword({ email: data.email.trim(), password: data.password });
        if (error) throw new Error(messageOf(error));
        say('Saving the company profile…');
        await rpc('save_supplier_profile', {
          p_company_name: data.company_name, p_registration_number: data.registration_number, p_tax_number: data.tax_number,
          p_vat_number: data.vat_number || '', p_business_type: data.business_type,
          p_year_established: data.year_established ? Number(data.year_established) : null,
          p_contact_person: data.contact_person, p_job_title: data.job_title, p_contact_email: data.contact_email,
          p_phone_number: data.phone_number || '', p_mobile_number: data.mobile_number,
          p_physical_address: data.physical_address, p_postal_address: data.postal_address || data.physical_address,
          p_province: data.province, p_industry_licences: data.industry_licences || '',
          p_professional_registrations: data.professional_registrations || '',
          p_categories: data.categories.join(', '), p_industry: data.industry, p_expertise: data.expertise || '',
          p_geographic_areas: data.geographic_areas.join(', '), p_company_profile: data.company_profile || '',
          p_employees: data.employees !== undefined && data.employees !== '' ? Number(data.employees) : null,
        });
        say('Saving compliance details…');
        await rpc('save_supplier_compliance', {
          p_tax_clearance_status: data.tax_clearance_status, p_tax_clearance_expiry: data.tax_clearance_expiry || null,
          p_bbbee_level: data.bbbee_level ? Number(data.bbbee_level) : null, p_bbbee_expiry: data.bbbee_expiry || null,
        });
        say('Saving banking details (confidential)…');
        await rpc('save_supplier_banking', {
          p_bank_name: data.bank_name, p_account_name: data.account_name, p_account_number: data.account_number.trim(),
          p_branch_code: data.branch_code.trim(), p_proof_url: data.proof_url || null,
        });
        say('Recording supporting documents…');
        for (const [name] of DOCUMENTS) {
          const d = docs[name];
          if (!d.provided) continue;
          await rpc('save_supplier_document', { p_name: name, p_reference: d.reference.trim(), p_expires_at: d.expires || null, p_file_url: d.link.trim() || null });
        }
        done();
      } catch (e) {
        fill(panel,
          note('danger', 'The registration did not finish', e.message),
          h('p', { class: 'meta mt' }, 'If the message says the account was created, sign in and complete your details in the TenderTrack app under My company.'),
          h('div', { class: 'wizard-actions' },
            h('button', { class: 'btn btn-secondary', type: 'button', onclick: () => { current = STEPS.length - 1; drawPanel(); } }, 'Back to the review'),
            h('a', { class: 'btn btn-primary', href: '/signin' }, 'Sign in')));
      }
    });
  }

  function done() {
    stepsList.hidden = true;
    fill(panel,
      h('div', { class: 'note note-success' }, icon('success'), h('div', {},
        h('p', { class: 'note-title' }, 'Registration submitted'),
        h('p', {}, `${data.company_name} (${data.csd_number}) is registered and awaiting verification.`))),
      h('h2', { class: 'h2 mt' }, 'What happens next'),
      h('ol', { class: 'bullets' },
        h('li', {}, 'A procurement officer checks your details and documents in TenderTrack and verifies your registration.'),
        h('li', {}, `Register for TenderTrack too: in the app, open Supplier login → Register an account, and enter ${data.email.trim()}, your password, CSD number ${data.csd_number} and registration number ${data.registration_number}. A 6-digit code is emailed to ${data.contact_email} to confirm it. Both registrations are needed to claim an award.`),
        h('li', {}, 'Bid for tenders on this portal. A tender can only be awarded to a verified supplier.'),
        h('li', {}, `If you are awarded a tender, a 10-digit award code is emailed to ${data.contact_email}. Enter it in TenderTrack to claim the contract.`)),
      h('div', { class: 'row mt' }, h('a', { class: 'btn btn-primary', href: '/tenders' }, 'Find a tender'), h('a', { class: 'btn btn-secondary', href: '/account' }, 'My bids & awards')));
    window.scrollTo(0, 0);
  }

  fill(main,
    h('div', { class: 'page-head' }, h('div', {},
      h('p', { class: 'eyebrow' }, 'Suppliers'),
      h('h1', { class: 'h1' }, 'Supplier registration'),
      h('p', { class: 'meta' }, 'Register your company here to bid for tenders. Then register for the TenderTrack app with the same account: awards are claimed there.'))),
    h('div', { class: 'wizard' }, stepsList, panel));
  drawPanel();
}

render();
