/*
 * Tender oversight — read only (Deliverable 3: the administrator "monitors
 * all tenders ... flags anomalies", with no tender actions). The only action
 * is "Flag for review", which lands on the procurement officer's Flags screen
 * in the app with a notification.
 */

import { sb, rpc, messageOf } from '../client.js';
import {
  h, clear, icon, tile, pageHead, searchBox, select, badge, note, empty, money, moneyCompact, date,
  STATUS_LABELS, modal, field, busy, toast,
} from '../ui.js';
import { tenderConcerns, SEVERITY_TONE } from '../rules.js';

const STATUS_TONE = {
  registered: 'neutral', published: 'info', under_evaluation: 'warn',
  awarded: 'success', in_progress: 'success', completed: 'neutral',
};
const FLAG_STATUS = { open: ['Open', 'danger'], under_investigation: ['Under investigation', 'warn'], resolved: ['Resolved', 'success'] };

export async function render(view) {
  const state = { tenders: [], flags: [], query: '', status: 'all', department: 'all', onlyConcerns: false };

  async function load() {
    const [t, f] = await Promise.all([
      sb.from('tenders').select('id, reference_number, title, department, category, status, estimated_budget, closing_date, awarded_supplier_name, awarded_value, awarded_at, paid_to_date, open_flag_count')
        .order('created_at', { ascending: false }),
      sb.from('compliance_flags').select('reference, tender_reference, title, severity, status, raised_at, raised_automatically, rule_triggered')
        .order('raised_at', { ascending: false }).limit(12),
    ]);
    if (t.error) throw new Error(messageOf(t.error));
    if (f.error) throw new Error(messageOf(f.error));
    const now = Date.now();
    state.tenders = t.data.map((x) => ({ ...x, concerns: tenderConcerns(x, now) }));
    state.flags = f.data;
  }
  await load();

  const tilesHost = h('div');
  const tableHost = h('div');
  const flagsHost = h('div');
  const countLine = h('p', { class: 'meta count-line' });

  function visible() {
    const q = state.query.trim().toLowerCase();
    return state.tenders.filter((t) => (state.status === 'all' || t.status === state.status)
      && (state.department === 'all' || t.department === state.department)
      && (!state.onlyConcerns || t.concerns.length > 0)
      && (!q || `${t.reference_number} ${t.title} ${t.awarded_supplier_name || ''}`.toLowerCase().includes(q)));
  }

  function drawTiles() {
    const all = state.tenders;
    const awarded = all.reduce((sum, t) => sum + Number(t.awarded_value || 0), 0);
    const paid = all.reduce((sum, t) => sum + Number(t.paid_to_date || 0), 0);
    const concerned = all.filter((t) => t.concerns.length).length;
    clear(tilesHost).appendChild(h('div', { class: 'tiles mb-lg' },
      tile({ label: 'Tenders', value: all.length, foot: `${all.filter((t) => t.status === 'published').length} open for bids` }),
      tile({ label: 'Awarded value', value: moneyCompact(awarded), money: true, foot: `${all.filter((t) => t.awarded_value).length} awards` }),
      tile({ label: 'Paid to date', value: moneyCompact(paid), money: true, foot: awarded ? `${Math.round((paid / awarded) * 100)}% of awarded value` : 'No awards yet' }),
      tile({ label: 'Tenders to look at', value: concerned, foot: 'Highlighted by the checks below', alert: all.some((t) => t.concerns.some((c) => c.severity === 'high')) })));
  }

  function draw() {
    const rows = visible();
    countLine.textContent = `${rows.length} of ${state.tenders.length} tenders`;
    clear(tableHost);
    if (!rows.length) {
      tableHost.appendChild(h('div', { class: 'table-wrap' }, empty('No tenders match', 'Change the search or filters.', 'gavel')));
      return;
    }
    tableHost.appendChild(h('div', { class: 'table-wrap' },
      h('table', {},
        h('thead', {}, h('tr', {},
          h('th', {}, 'Tender'), h('th', {}, 'Status'), h('th', { class: 'num' }, 'Estimate'),
          h('th', {}, 'Award'), h('th', { class: 'num' }, 'Paid'), h('th', {}, 'Closing'), h('th', {}, 'Checks'), h('th', {}, ''))),
        h('tbody', {}, rows.map((t) => h('tr', {},
          h('td', { class: 'wide' }, h('div', { class: 'primary' }, t.title), h('div', { class: 'secondary' }, `${t.reference_number} · ${t.department}`)),
          h('td', {}, badge(STATUS_LABELS[t.status] || t.status, STATUS_TONE[t.status] || 'neutral')),
          h('td', { class: 'num' }, money(t.estimated_budget)),
          h('td', {}, t.awarded_value
            ? [h('div', { class: 'nowrap' }, money(t.awarded_value)), h('div', { class: 'secondary' }, t.awarded_supplier_name || '—')]
            : h('span', { class: 'secondary' }, '—')),
          h('td', { class: 'num' }, money(t.paid_to_date)),
          h('td', { class: 'nowrap' }, date(t.closing_date)),
          h('td', {}, t.concerns.length
            ? h('div', { class: 'stack' }, t.concerns.map((c) => h('div', {}, badge(c.text, SEVERITY_TONE[c.severity]))))
            : h('span', { class: 'secondary' }, 'Nothing stands out')),
          h('td', {}, h('button', { class: 'btn btn-secondary btn-xs', type: 'button', onclick: () => openFlag(t) }, icon('flag'), 'Flag for review'))))))));
  }

  function drawFlags() {
    clear(flagsHost).append(
      h('h2', { class: 'h2 section-title' }, 'Latest compliance flags'),
      state.flags.length
        ? h('div', { class: 'table-wrap' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'Flag'), h('th', {}, 'Tender'), h('th', {}, 'Severity'), h('th', {}, 'Status'), h('th', {}, 'Raised'))),
          h('tbody', {}, state.flags.map((f) => {
            const [label, tone] = FLAG_STATUS[f.status] || [f.status, 'neutral'];
            return h('tr', {},
              h('td', {}, h('div', { class: 'primary' }, f.title), h('div', { class: 'secondary' }, `${f.reference} · ${f.raised_automatically ? 'Raised by the system' : f.rule_triggered}`)),
              h('td', {}, f.tender_reference),
              h('td', {}, badge(f.severity, SEVERITY_TONE[f.severity] || 'neutral')),
              h('td', {}, badge(label, tone)),
              h('td', {}, date(f.raised_at)));
          }))))
        : h('p', { class: 'meta' }, 'No compliance flags yet.'));
  }

  function openFlag(tender) {
    const title = field({ label: 'Title', input: h('input', { class: 'input', maxlength: '120', placeholder: 'e.g. Award far above the estimate' }) });
    const description = field({
      label: 'What should the officer look at?',
      input: h('textarea', { class: 'textarea', maxlength: '1000', placeholder: 'Describe the concern and what you noticed.' }),
    });
    const severity = field({ label: 'Severity', input: select([['high', 'High'], ['medium', 'Medium'], ['low', 'Low']], tender.concerns[0] ? tender.concerns[0].severity : 'medium', null, 'Severity') });
    if (tender.concerns[0]) title.input.value = tender.concerns[0].text.slice(0, 120);
    const errorBox = h('div', { class: 'mb-lg', hidden: true });

    modal('Flag for review', `${tender.reference_number} · ${tender.title}`, (close) => {
      const submit = h('button', { class: 'btn btn-primary btn-sm', type: 'submit' }, icon('flag'), 'Raise flag');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          errorBox.hidden = true;
          let ok = true;
          if (title.input.value.trim().length < 5) { title.setError('At least 5 characters.'); ok = false; } else title.setError('');
          if (description.input.value.trim().length < 15) { description.setError('At least 15 characters.'); ok = false; } else description.setError('');
          if (!ok) return;
          await busy(submit, async () => {
            try {
              const flag = await rpc('admin_raise_flag', {
                p_tender_id: tender.id,
                p_title: title.input.value.trim(),
                p_description: description.input.value.trim(),
                p_severity: severity.input.value,
              });
              close();
              toast(`${flag.reference} sent to procurement officers. It is on their Flags screen in the app.`);
              await load();
              drawTiles(); draw(); drawFlags();
            } catch (e) {
              clear(errorBox).appendChild(note('danger', null, e.message));
              errorBox.hidden = false;
            }
          });
        },
      },
      note('neutral', null, 'The flag goes to the procurement officers with a notification, and to the audit trail. You cannot change the tender itself.'),
      h('div', { class: 'mt-lg' }),
      errorBox, title.wrap, description.wrap, severity.wrap,
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  const departments = [...new Set(state.tenders.map((t) => t.department))].sort();

  clear(view).append(
    pageHead('Oversight', 'Tender oversight', 'Read only. Tenders are registered, awarded and paid by officers in the app.'),
    tilesHost,
    h('div', { class: 'toolbar' },
      searchBox('Search reference, title or supplier', (e) => { state.query = e.target.value; draw(); }),
      select([['all', 'All statuses'], ...Object.entries(STATUS_LABELS)], 'all', (e) => { state.status = e.target.value; draw(); }, 'Status'),
      select([['all', 'All departments'], ...departments.map((d) => [d, d])], 'all', (e) => { state.department = e.target.value; draw(); }, 'Department'),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', onchange: (e) => { state.onlyConcerns = e.target.checked; draw(); } }), 'Only tenders to look at')),
    countLine,
    tableHost,
    flagsHost,
  );
  drawTiles();
  draw();
  drawFlags();
}
