/*
 * Audit logs (FR3; Deliverable 3, section 5.7): searchable, filterable access
 * to the append-only record of every status change, verification, payment and
 * permission change, with export. Nobody can edit or delete these entries
 * through the API — not even an administrator.
 */

import { sb, rpc, fetchAll } from '../client.js';
import { h, clear, icon, pageHead, searchBox, select, badge, note, empty, dateTime, downloadCsv, stamp, toast } from '../ui.js';

const PAGE_SIZE = 50;
const ENTITY_LABELS = {
  tender: 'Tender', supplier: 'Supplier', account: 'Account', flag: 'Flag', payment: 'Payment',
  deliverable: 'Deliverable', announcement: 'Announcement', export: 'Export', bid: 'Bid',
};

function daysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export async function render(view) {
  const state = { from: daysAgo(90), to: new Date().toISOString().slice(0, 10), entity: 'all', actor: 'all', query: '', page: 0, rows: [] };

  const countLine = h('p', { class: 'meta count-line' });
  const tableHost = h('div');
  const actorHost = h('span');
  const entityHost = h('span');
  const fromInput = h('input', { class: 'input', type: 'date', value: state.from, 'aria-label': 'From date' });
  const toInput = h('input', { class: 'input', type: 'date', value: state.to, 'aria-label': 'To date' });

  async function load() {
    const start = new Date(`${state.from}T00:00:00`).toISOString();
    const end = new Date(`${state.to}T23:59:59.999`).toISOString();
    state.rows = await fetchAll(() => sb.from('audit_trail')
      .select('id, entity_type, entity_id, action, detail, actor, created_at')
      .gte('created_at', start).lte('created_at', end)
      .order('created_at', { ascending: false }));
    state.page = 0;
    buildFilters();
    draw();
  }

  function buildFilters() {
    const entities = [...new Set(state.rows.map((r) => r.entity_type))].sort();
    const actors = [...new Set(state.rows.map((r) => r.actor))].sort();
    if (!entities.includes(state.entity)) state.entity = 'all';
    if (!actors.includes(state.actor)) state.actor = 'all';
    clear(entityHost).appendChild(select([['all', 'All record types'], ...entities.map((e) => [e, ENTITY_LABELS[e] || e])], state.entity,
      (e) => { state.entity = e.target.value; state.page = 0; draw(); }, 'Record type'));
    clear(actorHost).appendChild(select([['all', 'Everyone'], ...actors.map((a) => [a, a])], state.actor,
      (e) => { state.actor = e.target.value; state.page = 0; draw(); }, 'Who'));
  }

  function filtered() {
    const q = state.query.trim().toLowerCase();
    return state.rows.filter((r) => (state.entity === 'all' || r.entity_type === state.entity)
      && (state.actor === 'all' || r.actor === state.actor)
      && (!q || `${r.action} ${r.detail} ${r.actor}`.toLowerCase().includes(q)));
  }

  function draw() {
    const rows = filtered();
    const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    state.page = Math.min(state.page, pages - 1);
    const shown = rows.slice(state.page * PAGE_SIZE, (state.page + 1) * PAGE_SIZE);
    countLine.textContent = `${rows.length} ${rows.length === 1 ? 'entry' : 'entries'} between ${state.from} and ${state.to}`;
    clear(tableHost);
    if (!rows.length) {
      tableHost.appendChild(h('div', { class: 'table-wrap' }, empty('No entries match', 'Widen the dates or clear the filters.', 'history')));
      return;
    }
    tableHost.append(
      h('div', { class: 'table-wrap' },
        h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Record'), h('th', {}, 'Action'), h('th', {}, 'Detail'), h('th', {}, 'By'))),
          h('tbody', {}, shown.map((r) => h('tr', {},
            h('td', { class: 'num' }, dateTime(r.created_at)),
            h('td', {}, badge(ENTITY_LABELS[r.entity_type] || r.entity_type, 'neutral', false)),
            h('td', {}, h('span', { class: 'primary' }, r.action)),
            h('td', { class: 'detail-text' }, r.detail || '—'),
            h('td', {}, r.actor)))))),
      h('div', { class: 'pager' },
        h('span', { class: 'meta' }, `Page ${state.page + 1} of ${pages}`),
        h('div', { class: 'row-actions' },
          h('button', { class: 'btn btn-secondary btn-xs', type: 'button', disabled: state.page === 0, onclick: () => { state.page -= 1; draw(); } }, 'Previous'),
          h('button', { class: 'btn btn-secondary btn-xs', type: 'button', disabled: state.page >= pages - 1, onclick: () => { state.page += 1; draw(); } }, 'Next'))));
  }

  async function exportCsv() {
    const rows = filtered();
    if (!rows.length) { toast('There is nothing to export.', 'warn'); return; }
    downloadCsv(`tendertrack-audit-log-${stamp()}.csv`, [
      [(r) => dateTime(r.created_at), 'When'],
      ['entity_type', 'Record type'],
      ['entity_id', 'Record id'],
      ['action', 'Action'],
      ['detail', 'Detail'],
      ['actor', 'By'],
    ], rows);
    await rpc('admin_record_export', { p_kind: 'audit_export', p_detail: `${rows.length} entries, ${state.from} to ${state.to}` }).catch(() => {});
    toast(`Exported ${rows.length} entries. The export itself is now in the audit trail.`);
  }

  const apply = h('button', {
    class: 'btn btn-secondary btn-sm', type: 'button',
    onclick: async () => {
      if (!fromInput.value || !toInput.value || fromInput.value > toInput.value) { toast('Choose a start date before the end date.', 'warn'); return; }
      state.from = fromInput.value;
      state.to = toInput.value;
      clear(tableHost).appendChild(h('div', { class: 'loading' }, h('span', { class: 'spinner' }), 'Loading…'));
      try { await load(); } catch (e) { toast(e.message, 'error'); }
    },
  }, 'Apply dates');

  await load();

  clear(view).append(
    pageHead('Records', 'Audit logs', 'Every status change, verification, payment, permission change and export. Entries cannot be edited or deleted.',
      h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: () => window.print() }, 'Print / PDF'),
      h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: exportCsv }, icon('download'), 'Export CSV')),
    h('div', { class: 'toolbar' },
      h('label', { class: 'inline' }, h('span', { class: 'label' }, 'From'), fromInput),
      h('label', { class: 'inline' }, h('span', { class: 'label' }, 'To'), toInput),
      apply),
    h('div', { class: 'toolbar' },
      searchBox('Search action, detail or name', (e) => { state.query = e.target.value; state.page = 0; draw(); }),
      entityHost, actorHost),
    countLine,
    tableHost,
    h('div', { class: 'mt-lg' }, note('neutral', 'Append-only (FR3)', 'The database allows reading these entries but not changing them. Records are written by the database itself when something happens, so the portal cannot leave anything out.')),
  );
}
