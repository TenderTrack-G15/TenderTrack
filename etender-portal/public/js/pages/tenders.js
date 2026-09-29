/*
 * Tender opportunities: advertised, closed and awarded tenders, with a search
 * and filters. Click a row to see its key details, as on the official portals.
 */

import { chrome, showError } from '../layout.js';
import { sb } from '../client.js';
import { h, clear, icon, date, dateTime, money, daysUntil, empty, select, isOpen, fill, put } from '../ui.js';
import { listTenders, tabOf, statusBadge } from '../tenders-data.js';

const TABS = [['advertised', 'Advertised'], ['closed', 'Closed'], ['awarded', 'Awarded']];

async function render() {
  const { who, main } = await chrome(new URLSearchParams(location.search).get('tab') === 'awarded' ? 'awarded' : 'tenders');
  let tenders;
  try {
    tenders = await listTenders();
  } catch (e) {
    showError(main, e.message, render);
    return;
  }

  const params = new URLSearchParams(location.search);
  const state = {
    tab: TABS.some(([id]) => id === params.get('tab')) ? params.get('tab') : 'advertised',
    query: params.get('q') || '',
    department: 'all',
    category: 'all',
    pageSize: 10,
    page: 0,
    open: new Set(),
  };
  const isSupplier = who && who.profile && who.profile.role === 'supplier';

  const tabsEl = h('div', { class: 'tabs', role: 'tablist' });
  const tableHost = h('div');
  const countLine = h('p', { class: 'meta mb' });
  const departments = [...new Set(tenders.map((t) => t.department))].sort();
  const categories = [...new Set(tenders.map((t) => t.category).filter(Boolean))].sort();

  function filtered() {
    const q = state.query.trim().toLowerCase();
    return tenders.filter((t) => tabOf(t) === state.tab
      && (state.department === 'all' || t.department === state.department)
      && (state.category === 'all' || t.category === state.category)
      && (!q || `${t.reference_number} ${t.title} ${t.description} ${t.department} ${t.category}`.toLowerCase().includes(q)))
      .sort((a, b) => state.tab === 'awarded'
        ? String(b.awarded_at).localeCompare(String(a.awarded_at))
        : String(a.closing_date).localeCompare(String(b.closing_date)));
  }

  function drawTabs() {
    clear(tabsEl);
    for (const [id, label] of TABS) {
      const n = tenders.filter((t) => tabOf(t) === id).length;
      tabsEl.appendChild(h('button', {
        type: 'button', role: 'tab', class: state.tab === id ? 'active' : null, 'aria-selected': state.tab === id ? 'true' : 'false',
        onclick: () => {
          state.tab = id; state.page = 0; state.open.clear();
          const url = new URL(location.href); url.searchParams.set('tab', id); history.replaceState(null, '', url);
          drawTabs(); drawTable();
        },
      }, label, h('span', { class: 'count' }, `(${n})`)));
    }
  }

  function drawTable() {
    const list = filtered();
    const pages = Math.max(1, Math.ceil(list.length / state.pageSize));
    state.page = Math.min(state.page, pages - 1);
    const shown = list.slice(state.page * state.pageSize, (state.page + 1) * state.pageSize);
    countLine.textContent = `Showing ${list.length ? state.page * state.pageSize + 1 : 0}–${state.page * state.pageSize + shown.length} of ${list.length} tenders`;
    clear(tableHost);
    if (!list.length) {
      tableHost.appendChild(h('div', { class: 'card' }, empty('No tenders match', 'Change the search or filters, or look at another tab.', 'search')));
      return;
    }
    const awarded = state.tab === 'awarded';
    const head = awarded
      ? ['', 'Category', 'Description', 'Department', 'Awarded to', 'Value', 'Award date']
      : ['', 'Category', 'Description', 'Department', 'Advertised', 'Closing'];
    const tbody = h('tbody');
    for (const t of shown) {
      const detail = h('tr', { class: 'detail-row', hidden: !state.open.has(t.id) }, h('td', { colspan: String(head.length) }));
      const row = h('tr', {
        class: `main-row${state.open.has(t.id) ? ' open' : ''}`, tabindex: '0',
        onclick: () => toggle(t, row, detail),
        onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(t, row, detail); } },
      },
      h('td', { class: 'expander' }, h('span', { class: 'expander-icon', 'aria-hidden': 'true' }, state.open.has(t.id) ? '−' : '+')),
      h('td', {}, t.category || '—'),
      h('td', {}, h('div', { class: 'primary' }, t.title), h('div', { class: 'secondary' }, t.reference_number)),
      h('td', {}, t.department),
      awarded
        ? [h('td', {}, t.awarded_supplier_name || '—'), h('td', { class: 'num' }, money(t.awarded_value)), h('td', { class: 'nowrap' }, date(t.awarded_at))]
        : [h('td', { class: 'nowrap' }, date(t.published_at || t.created_at)),
          h('td', { class: 'nowrap' }, dateTime(t.closing_date),
            state.tab === 'advertised' ? h('div', { class: 'secondary' }, `${daysUntil(t.closing_date)} days left`) : null)]);
      if (state.open.has(t.id)) fillDetail(t, detail);
      put(tbody, row, detail);
    }
    put(tableHost, 
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, head.map((c, i) => h('th', { class: (awarded && i === 5) ? 'num' : null }, c)))),
        tbody)),
      h('div', { class: 'pager' },
        h('span', { class: 'meta' }, `Page ${state.page + 1} of ${pages}`),
        h('div', { class: 'row' },
          h('button', { class: 'btn btn-secondary btn-sm', type: 'button', disabled: state.page === 0, onclick: () => { state.page -= 1; drawTable(); } }, 'Previous'),
          h('button', { class: 'btn btn-secondary btn-sm', type: 'button', disabled: state.page >= pages - 1, onclick: () => { state.page += 1; drawTable(); } }, 'Next'))));
  }

  function toggle(t, row, detail) {
    const opening = !state.open.has(t.id);
    if (opening) state.open.add(t.id); else state.open.delete(t.id);
    row.classList.toggle('open', opening);
    row.querySelector('.expander-icon').textContent = opening ? '−' : '+';
    detail.hidden = !opening;
    if (opening) fillDetail(t, detail);
  }

  async function fillDetail(t, detail) {
    const cell = clear(detail.firstChild);
    cell.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spinner' }), 'Loading details…'));
    const { data } = await sb.from('tender_details').select('*').eq('tender_id', t.id).maybeSingle();
    const d = data || {};
    const kv = (k, v) => h('div', {}, h('div', { class: 'kv-key' }, k), h('div', { class: 'kv-value' }, v || '—'));
    fill(cell,
      h('div', { class: 'detail-grid' },
        kv('Tender number', t.reference_number),
        kv('Department', t.department),
        kv('Category', t.category),
        kv('Date published', date(t.published_at || t.created_at)),
        kv('Closing date', dateTime(t.closing_date)),
        kv('Contract period', t.contract_period_months ? `${t.contract_period_months} months` : '—'),
        kv('Briefing session', d.briefing_at ? `${dateTime(d.briefing_at)}${d.briefing_compulsory ? ' · compulsory' : ' · optional'}` : 'None'),
        kv('Briefing venue', d.briefing_venue),
        kv('Status', h('span', {}, statusBadge(t))),
        kv('Contact person', d.query_contact_name),
        kv('Email', d.query_contact_email),
        kv('Telephone', d.query_contact_phone),
        h('div', { class: 'full-span' }, h('div', { class: 'kv-key' }, 'Description'), h('div', { class: 'kv-value pre' }, t.description || '—'))),
      h('div', { class: 'detail-actions' },
        h('a', { class: 'btn btn-secondary btn-sm', href: `/tender?id=${t.id}` }, icon('document'), 'Tender details & documents'),
        isOpen(t) ? h('a', { class: 'btn btn-primary btn-sm', href: isSupplier ? `/bid?tender=${t.id}` : `/signin?next=${encodeURIComponent(`/bid?tender=${t.id}`)}` },
          icon('send'), isSupplier ? 'Submit a bid' : 'Sign in to bid') : null));
  }

  const searchInput = h('input', { class: 'input', type: 'search', value: state.query, placeholder: 'Search tender number, description, department or category', 'aria-label': 'Search tenders',
    oninput: (e) => { state.query = e.target.value; state.page = 0; drawTable(); } });

  fill(main,
    h('div', { class: 'page-head' },
      h('div', {}, h('p', { class: 'eyebrow' }, 'eTender Demo Portal'), h('h1', { class: 'h1' }, 'Tender opportunities'),
        h('p', { class: 'meta' }, 'Advertised tenders are open for bids until the closing date. Click a tender for its key details.'))),
    tabsEl,
    h('div', { class: 'toolbar' },
      h('div', { class: 'search grow' }, icon('search'), searchInput),
      select([['all', 'All departments'], ...departments.map((d) => [d, d])], 'all', (e) => { state.department = e.target.value; state.page = 0; drawTable(); }, 'Department'),
      select([['all', 'All categories'], ...categories.map((c) => [c, c])], 'all', (e) => { state.category = e.target.value; state.page = 0; drawTable(); }, 'Category'),
      select([['10', 'Show 10'], ['25', 'Show 25'], ['50', 'Show 50']], '10', (e) => { state.pageSize = Number(e.target.value); state.page = 0; drawTable(); }, 'Rows per page')),
    countLine,
    tableHost);
  drawTabs();
  drawTable();
}

render();
