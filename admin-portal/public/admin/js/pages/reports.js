/*
 * Reports and backups (Deliverable 3, section 5.7; FR17).
 * Tender, award, payment, deliverable, supplier, flag and audit-trail
 * reports by date range and department, as CSV (opens in Excel) or printed
 * to PDF. Plus a backup snapshot of every table the administrator can read.
 * Every download is recorded in the audit trail.
 */

import { sb, rpc, fetchAll, messageOf } from '../client.js';
import {
  h, clear, icon, pageHead, select, note, money, date, dateTime, STATUS_LABELS,
  downloadCsv, downloadFile, stamp, toast, busy,
} from '../ui.js';

function isoStart(day) { return new Date(`${day}T00:00:00`).toISOString(); }
function isoEnd(day) { return new Date(`${day}T23:59:59.999`).toISOString(); }
const byDept = (f, dept) => f.department === 'all' || dept === f.department;

/** Each report: where its rows come from, and its columns. */
const REPORTS = [
  {
    id: 'tenders', title: 'Tenders', text: 'Every tender registered in the period, with its status, budget, award and payments.',
    departmental: true,
    async rows(f) {
      const rows = await fetchAll(() => sb.from('tenders').select('*').gte('created_at', isoStart(f.from)).lte('created_at', isoEnd(f.to)).order('created_at'));
      return rows.filter((t) => byDept(f, t.department));
    },
    columns: [
      ['reference_number', 'Reference'], ['title', 'Title'], ['department', 'Department'], ['category', 'Category'],
      [(t) => STATUS_LABELS[t.status] || t.status, 'Status'], [(t) => money(t.estimated_budget), 'Estimated budget'],
      [(t) => date(t.closing_date), 'Closing date'], ['awarded_supplier_name', 'Awarded supplier'],
      [(t) => money(t.awarded_value), 'Awarded value'], [(t) => money(t.paid_to_date), 'Paid to date'], ['open_flag_count', 'Open flags'],
    ],
  },
  {
    id: 'awards', title: 'Awards', text: 'Contracts awarded in the period, compared with the estimated budget.',
    departmental: true,
    async rows(f) {
      const rows = await fetchAll(() => sb.from('tenders').select('*').not('awarded_at', 'is', null)
        .gte('awarded_at', isoStart(f.from)).lte('awarded_at', isoEnd(f.to)).order('awarded_at'));
      return rows.filter((t) => byDept(f, t.department));
    },
    columns: [
      ['reference_number', 'Reference'], ['title', 'Title'], ['department', 'Department'], ['awarded_supplier_name', 'Supplier'],
      [(t) => money(t.estimated_budget), 'Estimated budget'], [(t) => money(t.awarded_value), 'Awarded value'],
      [(t) => `${Math.round(((t.awarded_value - t.estimated_budget) / t.estimated_budget) * 100)}%`, 'Difference'],
      [(t) => date(t.awarded_at), 'Awarded on'],
    ],
  },
  {
    id: 'payments', title: 'Payments', text: 'Payments made in the period, per milestone and invoice (FR12).',
    departmental: true,
    async rows(f) {
      const rows = await fetchAll(() => sb.from('payments').select('*, tenders(title, department)').gte('paid_on', f.from).lte('paid_on', f.to).order('paid_on'));
      return rows.filter((p) => byDept(f, p.tenders && p.tenders.department));
    },
    columns: [
      ['tender_reference', 'Tender'], [(p) => p.tenders && p.tenders.title, 'Tender title'], [(p) => p.tenders && p.tenders.department, 'Department'],
      ['milestone', 'Milestone'], ['invoice_number', 'Invoice'], [(p) => money(p.amount), 'Amount'], [(p) => date(p.paid_on), 'Paid on'], ['recorded_by', 'Recorded by'],
    ],
  },
  {
    id: 'deliverables', title: 'Deliverable completion', text: 'Project phases due in the period and whether they were verified.',
    departmental: true,
    async rows(f) {
      const rows = await fetchAll(() => sb.from('deliverables').select('*, tenders(reference_number, title, department)').gte('target_date', f.from).lte('target_date', f.to).order('target_date'));
      return rows.filter((d) => byDept(f, d.tenders && d.tenders.department));
    },
    columns: [
      [(d) => d.tenders && d.tenders.reference_number, 'Tender'], [(d) => d.tenders && d.tenders.department, 'Department'],
      ['phase_name', 'Phase'], [(d) => date(d.target_date), 'Target date'], [(d) => money(d.phase_value), 'Value'],
      [(d) => String(d.status).replace(/_/g, ' '), 'Status'], [(d) => date(d.verified_at), 'Verified on'],
    ],
  },
  {
    id: 'suppliers', title: 'Suppliers', text: 'Supplier registrations submitted in the period and their verification status. No banking details.',
    departmental: false,
    async rows(f) {
      return fetchAll(() => sb.from('suppliers')
        .select('company_name, registration_number, csd_number, bbbee_level, tax_clearance_expiry, status, decision_reason, documents_received, documents_required, submitted_at')
        .gte('submitted_at', isoStart(f.from)).lte('submitted_at', isoEnd(f.to)).order('submitted_at'));
    },
    columns: [
      ['company_name', 'Company'], ['registration_number', 'Registration number'], ['csd_number', 'CSD number'],
      ['bbbee_level', 'B-BBEE level'], [(s) => date(s.tax_clearance_expiry), 'Tax clearance expiry'],
      [(s) => String(s.status).replace(/_/g, ' '), 'Status'], ['decision_reason', 'Reason'],
      [(s) => `${s.documents_received} of ${s.documents_required}`, 'Documents'], [(s) => date(s.submitted_at), 'Submitted'],
    ],
  },
  {
    id: 'flags', title: 'Compliance flags', text: 'Flags raised in the period, by the system or by an administrator.',
    departmental: true,
    async rows(f) {
      const rows = await fetchAll(() => sb.from('compliance_flags').select('*, tenders(department)').gte('raised_at', isoStart(f.from)).lte('raised_at', isoEnd(f.to)).order('raised_at'));
      return rows.filter((x) => byDept(f, x.tenders && x.tenders.department));
    },
    columns: [
      ['reference', 'Flag'], ['tender_reference', 'Tender'], [(x) => x.tenders && x.tenders.department, 'Department'], ['title', 'Title'],
      ['severity', 'Severity'], [(x) => String(x.status).replace(/_/g, ' '), 'Status'], ['outcome', 'Outcome'],
      ['rule_triggered', 'Raised because'], [(x) => dateTime(x.raised_at), 'Raised'],
    ],
  },
  {
    id: 'audit', title: 'Audit trail', text: 'Every recorded action in the period (FR3). Not filtered by department.',
    departmental: false,
    async rows(f) {
      return fetchAll(() => sb.from('audit_trail').select('*').gte('created_at', isoStart(f.from)).lte('created_at', isoEnd(f.to)).order('created_at'));
    },
    columns: [
      [(a) => dateTime(a.created_at), 'When'], ['entity_type', 'Record type'], ['action', 'Action'], ['detail', 'Detail'], ['actor', 'By'],
    ],
  },
];

/** Tables included in a backup snapshot. Supplier banking is not readable by administrators. */
const SNAPSHOT_TABLES = [
  'tenders', 'suppliers', 'payments', 'deliverables', 'department_budgets', 'compliance_flags', 'flag_notes',
  'audit_trail', 'announcements', 'citizen_reports', 'bids', 'evaluation_criteria', 'evaluation_scores',
  'evaluation_results', 'award_approvals', 'tender_changes', 'tender_details', 'tender_eligibility',
  'tender_documents', 'tender_updates', 'supplier_documents', 'admin_access_log',
];

export async function render(view) {
  const [budgets, tenders, lastBackup] = await Promise.all([
    sb.from('department_budgets').select('department'),
    sb.from('tenders').select('department'),
    sb.from('audit_trail').select('actor, detail, created_at').eq('action', 'Backup snapshot downloaded').order('created_at', { ascending: false }).limit(1),
  ]);
  const departments = [...new Set([...(budgets.data || []), ...(tenders.data || [])].map((r) => r.department))].sort();

  const today = new Date();
  const yearAgo = new Date(today);
  yearAgo.setFullYear(today.getFullYear() - 1);
  const filters = { from: yearAgo.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10), department: 'all' };

  const fromInput = h('input', { class: 'input', type: 'date', value: filters.from, 'aria-label': 'From date', onchange: (e) => { filters.from = e.target.value; } });
  const toInput = h('input', { class: 'input', type: 'date', value: filters.to, 'aria-label': 'To date', onchange: (e) => { filters.to = e.target.value; } });
  const deptSelect = select([['all', 'All departments'], ...departments.map((d) => [d, d])], 'all', (e) => { filters.department = e.target.value; }, 'Department');
  const previewHost = h('div', { class: 'report-preview' });
  const backupLine = h('p', { class: 'meta' });

  function showLastBackup(entry) {
    backupLine.textContent = entry
      ? `Last snapshot: ${dateTime(entry.created_at)} by ${entry.actor} (${entry.detail}).`
      : 'No snapshot has been downloaded from the portal yet.';
  }
  showLastBackup(lastBackup.data && lastBackup.data[0]);

  function filtersOk() {
    if (!filters.from || !filters.to || filters.from > filters.to) {
      toast('Choose a start date before the end date.', 'warn');
      return false;
    }
    return true;
  }

  function describe(report) {
    const dept = report.departmental ? (filters.department === 'all' ? 'all departments' : filters.department) : 'all departments';
    return `${report.title} · ${filters.from} to ${filters.to} · ${dept}`;
  }

  async function downloadReport(report, button) {
    if (!filtersOk()) return;
    await busy(button, async () => {
      try {
        const rows = await report.rows(filters);
        if (!rows.length) { toast('No rows for these dates and department.', 'warn'); return; }
        const suffix = report.departmental && filters.department !== 'all' ? `-${filters.department.toLowerCase().replace(/[^a-z0-9]+/g, '-')}` : '';
        downloadCsv(`tendertrack-${report.id}${suffix}-${filters.from}-to-${filters.to}.csv`, report.columns, rows);
        await rpc('admin_record_export', { p_kind: report.id === 'audit' ? 'audit_export' : 'report', p_detail: `${describe(report)} · ${rows.length} rows` }).catch(() => {});
        toast(`Downloaded ${rows.length} rows. Open the file in Excel.`);
      } catch (e) {
        toast(messageOf(e), 'error');
      }
    });
  }

  async function previewReport(report, button) {
    if (!filtersOk()) return;
    await busy(button, async () => {
      try {
        const rows = await report.rows(filters);
        clear(previewHost).append(
          h('div', { class: 'spread mb-lg' },
            h('div', {}, h('p', { class: 'eyebrow' }, 'TenderTrack report'), h('h2', { class: 'h2' }, describe(report)),
              h('p', { class: 'meta' }, `${rows.length} rows · generated ${dateTime(new Date().toISOString())}`)),
            h('div', { class: 'row-actions' },
              h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: () => window.print() }, 'Print / Save as PDF'),
              h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: () => clear(previewHost) }, 'Close'))),
          rows.length
            ? h('div', { class: 'table-wrap' }, h('table', {},
              h('thead', {}, h('tr', {}, report.columns.map(([, header]) => h('th', {}, header)))),
              h('tbody', {}, rows.slice(0, 500).map((r) => h('tr', {}, report.columns.map(([key]) => {
                const value = typeof key === 'function' ? key(r) : r[key];
                return h('td', {}, value === null || value === undefined || value === '' ? '—' : String(value));
              }))))))
            : h('p', { class: 'meta' }, 'No rows for these dates and department.'),
          rows.length > 500 ? h('p', { class: 'hint mt-lg' }, 'Showing the first 500 rows. Download the CSV for all of them.') : null);
        await rpc('admin_record_export', { p_kind: report.id === 'audit' ? 'audit_export' : 'report', p_detail: `Viewed ${describe(report)} · ${rows.length} rows` }).catch(() => {});
        previewHost.scrollIntoView({ behavior: 'smooth', block: 'start' });
      } catch (e) {
        toast(messageOf(e), 'error');
      }
    });
  }

  async function downloadSnapshot(button) {
    await busy(button, async () => {
      const snapshot = { app: 'TenderTrack', kind: 'backup snapshot', created_at: new Date().toISOString(), tables: {}, skipped: {} };
      let total = 0;
      try {
        snapshot.tables.accounts = await rpc('admin_list_accounts');
        total += snapshot.tables.accounts.length;
      } catch (e) {
        snapshot.skipped.accounts = e.message;
      }
      for (const table of SNAPSHOT_TABLES) {
        try {
          const rows = await fetchAll(() => sb.from(table).select('*'), 100000);
          snapshot.tables[table] = rows;
          total += rows.length;
        } catch (e) {
          snapshot.skipped[table] = e.message;     // a table this project does not have, or cannot read
        }
      }
      const tableCount = Object.keys(snapshot.tables).length;
      downloadFile(`tendertrack-backup-${stamp()}.json`, JSON.stringify(snapshot, null, 2), 'application/json');
      const detail = `${tableCount} tables, ${total} rows`;
      await rpc('admin_record_export', { p_kind: 'backup', p_detail: detail }).catch(() => {});
      showLastBackup({ created_at: snapshot.created_at, actor: 'you', detail });
      toast(`Snapshot downloaded: ${detail}. Keep it somewhere safe; it contains personal information (POPIA).`);
    });
  }

  clear(view).append(
    pageHead('Records', 'Reports & backups', 'Export tender, award, payment, deliverable, supplier, flag and audit reports (FR17). Every download is recorded.'),
    h('div', { class: 'card mb-lg no-print' },
      h('p', { class: 'card-title mb-lg' }, 'Period and department'),
      h('div', { class: 'toolbar' },
        h('label', { class: 'inline' }, h('span', { class: 'label' }, 'From'), fromInput),
        h('label', { class: 'inline' }, h('span', { class: 'label' }, 'To'), toInput),
        deptSelect),
      h('p', { class: 'hint' }, 'CSV files open directly in Excel. For a PDF, open the preview and choose "Save as PDF" in the print window.')),
    h('div', { class: 'cards-2 no-print' }, REPORTS.map((report) => {
      const csvButton = h('button', { class: 'btn btn-primary btn-sm', type: 'button' }, icon('download'), 'CSV / Excel');
      const previewButton = h('button', { class: 'btn btn-secondary btn-sm', type: 'button' }, 'Preview / PDF');
      csvButton.addEventListener('click', () => downloadReport(report, csvButton));
      previewButton.addEventListener('click', () => previewReport(report, previewButton));
      return h('div', { class: 'card' },
        h('p', { class: 'card-title' }, report.title),
        h('p', { class: 'meta mb-lg' }, report.text),
        h('div', { class: 'row-actions' }, csvButton, previewButton));
    })),
    h('div', { class: 'mt-lg' }, previewHost),

    h('h2', { class: 'h2 section-title no-print' }, 'Backups'),
    h('div', { class: 'grid-2 no-print' },
      h('div', { class: 'card' },
        h('p', { class: 'card-title' }, 'Backup snapshot'),
        h('p', { class: 'meta mb-lg' }, 'Downloads every table an administrator can read as one JSON file, including accounts and the audit trail. Supplier banking details are not included.'),
        backupLine,
        h('div', { class: 'row-actions mt-lg' }, (() => {
          const b = h('button', { class: 'btn btn-primary btn-sm', type: 'button' }, icon('backup'), 'Download snapshot');
          b.addEventListener('click', () => downloadSnapshot(b));
          return b;
        })())),
      h('div', { class: 'card' },
        h('p', { class: 'card-title' }, 'Restoring'),
        h('p', { class: 'meta' }, 'Restoring replaces the whole database, so it is done by the project owner in the Supabase dashboard (Database → Backups, available on paid plans), never from this portal.'),
        h('div', { class: 'mt-lg' }, note('neutral', 'Planned, not built', 'Restoring from the portal with a second administrator\'s approval (Deliverable 3) needs a server-side restore job. The snapshot above is the fallback for a free Supabase project.')))),
  );
}
