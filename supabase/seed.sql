-- ===========================================================================
-- TenderTrack — demo seed data
--
-- Run this AFTER schema.sql, in the Supabase SQL editor.
--
-- It mirrors app/src/main/java/za/ac/tendertrack/data/sample/SampleData.kt,
-- so the app looks the same connected to Supabase as it does offline. Without
-- this your demo starts with empty lists and zeroed dashboard tiles.
--
-- Safe to re-run: every insert is guarded by "on conflict do nothing", and the
-- deletes at the top clear only these demo rows.
-- ===========================================================================

-- Clear previous demo rows (leaves profiles/auth users alone).
delete from payments;
delete from flag_notes;
delete from compliance_flags;
delete from deliverables;
delete from award_codes;
delete from tenders;
delete from suppliers;
delete from department_budgets;

-- ---------------------------------------------------------------------------
-- Department budgets — drives the Fund Utilisation screen (FR11)
-- ---------------------------------------------------------------------------
insert into department_budgets (department, financial_year, allocated, committed, disbursed) values
  ('Gauteng Dept of e-Government',     '2026/27', 248000000, 214700000, 162400000),
  ('Western Cape Provincial Treasury', '2026/27',  64000000,   6900000,          0),
  ('KZN Department of Health',         '2026/27', 120000000,  31200000,          0),
  ('Eastern Cape CoGTA',               '2026/27',  18000000,   2480000,    2480000),
  ('National Treasury',                '2026/27',  42000000,   4750000,          0)
on conflict (department, financial_year) do nothing;

-- ---------------------------------------------------------------------------
-- Suppliers — drives Supplier Registrations / Review screens
-- ---------------------------------------------------------------------------
insert into suppliers (
  id, company_name, registration_number, csd_number, tax_clearance_expiry,
  bbbee_level, business_type, contact_email, physical_address,
  documents_received, documents_required, status, submitted_at, decision_reason
) values
  ('a0000000-0000-4000-8000-000000000001', 'Infratech Solutions (Pty) Ltd', '2019/451236/07', 'MAAA0451236', '2027-06-30',
   2, 'Pty Ltd', 'ops@infratech.co.za', '14 Anderson Street, Marshalltown, Johannesburg, 2001',
   4, 4, 'verified', '2026-08-31T08:30:00Z', null),

  ('a0000000-0000-4000-8000-000000000002', 'Vuka IT Consulting', '2017/338914/07', 'MAAA0338914', null,
   1, 'Pty Ltd', 'admin@vukait.co.za', '8 Loop Street, Cape Town, 8001',
   3, 4, 'awaiting_verification', '2026-09-02T10:15:00Z', null),

  ('a0000000-0000-4000-8000-000000000003', 'Siyakhula Technologies', '2021/512077/07', 'MAAA0512077', '2026-06-30',
   4, 'Pty Ltd', 'ops@siyakhula.co.za', '22 Umgeni Road, Durban, 4001',
   4, 4, 'awaiting_verification', '2026-09-04T07:45:00Z', null),

  ('a0000000-0000-4000-8000-000000000004', 'Lethabo Digital CC', '2015/118844/23', 'MAAA0118844', '2027-02-28',
   1, 'Close Corporation', 'hello@lethabodigital.co.za', '5 Church Street, Polokwane, 0700',
   4, 4, 'awaiting_verification', '2026-09-05T11:20:00Z', null),

  ('a0000000-0000-4000-8000-000000000005', 'Northern Cape Networks', '2013/771122/07', 'MAAA0771122', '2026-03-31',
   6, 'Pty Ltd', 'info@ncnetworks.co.za', '3 Du Toitspan Road, Kimberley, 8301',
   4, 4, 'not_approved', '2026-08-11T09:00:00Z', 'Tax Clearance Certificate expired on 31 March 2026.')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Tenders — one in every lifecycle state, so the Dashboard tiles all populate
-- ---------------------------------------------------------------------------
insert into tenders (
  id, reference_number, title, description, department, category,
  estimated_budget, closing_date, contract_period_months, status,
  awarded_supplier_id, awarded_supplier_name, awarded_value, awarded_at,
  paid_to_date, open_flag_count, created_at
) values
  ('b0000000-0000-4000-8000-000000000001', 'GP/IT/2290', 'Network Hardware Supply',
   'Supply, delivery and installation of core switching and routing hardware across 14 sites.',
   'Gauteng Dept of e-Government', 'IT Infrastructure',
   18400000, '2026-06-30T11:00:00Z', 24, 'awarded',
   'a0000000-0000-4000-8000-000000000001', 'Infratech Solutions (Pty) Ltd', 17950000, '2026-08-12T14:00:00Z',
   3231000, 1, '2026-06-02T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000002', 'WC/SF/1042', 'SaaS CRM Integration',
   'Integration of a citizen relationship management platform with existing provincial systems.',
   'Western Cape Provincial Treasury', 'IT Services',
   6900000, '2026-10-01T12:00:00Z', 18, 'published',
   null, null, null, null, 0, 0, '2026-07-14T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000003', 'KZN/HW/9923', 'End-User Device Leases',
   '36-month lease of 4 200 notebooks and docking stations for district health offices.',
   'KZN Department of Health', 'End-User Devices',
   31200000, '2026-10-04T12:00:00Z', 36, 'published',
   null, null, null, null, 0, 1, '2026-07-28T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000004', 'NAT/SEC/492', 'Cybersecurity Audit Services',
   'Independent penetration testing and control assurance across national departments.',
   'National Treasury', 'Cybersecurity',
   4750000, '2026-08-02T11:00:00Z', 12, 'under_evaluation',
   null, null, null, null, 0, 1, '2026-06-20T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000005', 'EC/WEB/004', 'Municipal Portal Development',
   'Design and build of a municipal services portal with payment integration.',
   'Eastern Cape CoGTA', 'IT Services',
   2600000, '2026-01-20T11:00:00Z', 12, 'completed',
   'a0000000-0000-4000-8000-000000000002', 'Vuka IT Consulting', 2480000, '2026-02-18T10:00:00Z',
   2480000, 0, '2025-12-05T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000006', 'GP/CLD/1187', 'Cloud Backup Hosting',
   'Offsite backup and disaster recovery hosting for provincial data centres.',
   'Gauteng Dept of e-Government', 'Cloud Hosting',
   9800000, '2026-05-15T11:00:00Z', 24, 'in_progress',
   'a0000000-0000-4000-8000-000000000003', 'Siyakhula Technologies', 9450000, '2026-06-30T10:00:00Z',
   4100000, 0, '2026-04-01T09:00:00Z'),

  ('b0000000-0000-4000-8000-000000000007', 'GP/IT/2291', 'Data Centre UPS Replacement',
   'Replacement of uninterruptible power supply units at the primary data centre.',
   'Gauteng Dept of e-Government', 'IT Infrastructure',
   5400000, '2026-11-12T11:00:00Z', 6, 'registered',
   null, null, null, null, 0, 0, '2026-09-01T09:00:00Z')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Payments — already-disbursed amounts must match tenders.paid_to_date above
-- ---------------------------------------------------------------------------
insert into payments (tender_id, tender_reference, milestone, amount, paid_on, invoice_number, recorded_by) values
  ('b0000000-0000-4000-8000-000000000001', 'GP/IT/2290',  'Deposit / mobilisation',    1795000, '2026-08-20', 'INV-2026-0388', 'T. Mokoena'),
  ('b0000000-0000-4000-8000-000000000001', 'GP/IT/2290',  'Delivery milestone 1',      1436000, '2026-09-01', 'INV-2026-0441', 'T. Mokoena'),
  ('b0000000-0000-4000-8000-000000000005', 'EC/WEB/004',  'Final payment / retention',  620000, '2026-08-30', 'INV-2026-0402', 'P. Naidoo'),
  ('b0000000-0000-4000-8000-000000000006', 'GP/CLD/1187', 'Deposit / mobilisation',    2400000, '2026-07-10', 'INV-2026-0301', 'P. Naidoo'),
  ('b0000000-0000-4000-8000-000000000006', 'GP/CLD/1187', 'Delivery milestone 1',      1700000, '2026-08-14', 'INV-2026-0356', 'P. Naidoo')
on conflict (tender_id, invoice_number) do nothing;

-- ---------------------------------------------------------------------------
-- Deliverables — the new FR5/FR12 table.
--
-- NOTE: record_payment() only enforces FR12 for tenders that HAVE deliverables.
-- The two rows below are marked 'verified' precisely so the payments already
-- recorded above remain valid and so you can demo a successful payment.
-- To demo FR12 blocking a payment, set one to 'awaiting_verification' and try
-- to pay against it from the Record Payment screen.
-- ---------------------------------------------------------------------------
insert into deliverables (tender_id, phase_name, target_date, phase_value, status, evidence_at, verified_at) values
  ('b0000000-0000-4000-8000-000000000006', 'Deposit / mobilisation', '2026-07-05', 2400000, 'verified',  '2026-07-04T10:00:00Z', '2026-07-08T09:00:00Z'),
  ('b0000000-0000-4000-8000-000000000006', 'Delivery milestone 1',   '2026-08-10', 1700000, 'verified',  '2026-08-09T10:00:00Z', '2026-08-13T09:00:00Z'),
  ('b0000000-0000-4000-8000-000000000006', 'Delivery milestone 2',   '2026-10-15', 2400000, 'awaiting_verification', '2026-10-14T10:00:00Z', null),
  ('b0000000-0000-4000-8000-000000000006', 'Acceptance testing',     '2026-12-01', 1900000, 'not_started', null, null),
  ('b0000000-0000-4000-8000-000000000006', 'Final payment / retention', '2027-01-20', 1050000, 'not_started', null, null)
on conflict (tender_id, phase_name) do nothing;

-- ---------------------------------------------------------------------------
-- Compliance flags — drives the Flags and Flag Detail screens (FR6-FR8)
-- ---------------------------------------------------------------------------
insert into compliance_flags (
  id, reference, tender_id, tender_reference, tender_title,
  title, description, rule_triggered, severity, status, raised_at, raised_automatically, assigned_to
) values
  ('c0000000-0000-4000-8000-000000000001', 'FLG-2026-0093',
   'b0000000-0000-4000-8000-000000000001', 'GP/IT/2290', 'Network Hardware Supply',
   'Award value variance',
   'The awarded value of R 17 950 000 differs from the published estimate of R 18 400 000. Combined with a single-supplier shortlist, the rule threshold was met.',
   'Award variance above threshold', 'high', 'open', '2026-08-13T08:00:00Z', true, 'T. Mokoena'),

  ('c0000000-0000-4000-8000-000000000002', 'FLG-2026-0094',
   'b0000000-0000-4000-8000-000000000003', 'KZN/HW/9923', 'End-User Device Leases',
   'Single bid received',
   'Only one responsive bid was received before the closing date. Departmental policy requires a minimum of three.',
   'Insufficient competition', 'medium', 'open', '2026-09-06T08:00:00Z', true, null),

  ('c0000000-0000-4000-8000-000000000003', 'FLG-2026-0095',
   'b0000000-0000-4000-8000-000000000004', 'NAT/SEC/492', 'Cybersecurity Audit Services',
   'Supplier tax clearance lapsed',
   'A shortlisted supplier has a tax clearance certificate that expired before the evaluation date.',
   'Supplier compliance document expired', 'high', 'under_investigation', '2026-09-10T08:00:00Z', true, 'S. van Wyk')
on conflict (id) do nothing;

insert into flag_notes (id, flag_id, author, text) values
  ('d0000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001', 'T. Mokoena', 'Requested the evaluation committee minutes to confirm the shortlist was correctly constituted.'),
  ('d0000000-0000-4000-8000-000000000002', 'c0000000-0000-4000-8000-000000000003', 'S. van Wyk', 'Supplier has been asked to provide a current tax clearance certificate within 7 days.')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Audit trail — FR3. Inserted directly here because the RPCs that normally
-- write it were not used to create this demo data.
-- ---------------------------------------------------------------------------
insert into audit_trail (id, entity_type, entity_id, action, detail, actor) values
  ('e0000000-0000-4000-8000-000000000001', 'tender', 'b0000000-0000-4000-8000-000000000001', 'Tender registered', 'GP/IT/2290 created', 'T. Mokoena'),
  ('e0000000-0000-4000-8000-000000000002', 'tender', 'b0000000-0000-4000-8000-000000000001', 'Status changed', 'awarded — evaluation complete', 'T. Mokoena'),
  ('e0000000-0000-4000-8000-000000000003', 'tender', 'b0000000-0000-4000-8000-000000000006', 'Payment recorded', 'Delivery milestone 1 · R 1700000', 'P. Naidoo')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Done. Check the counts.
-- ---------------------------------------------------------------------------
select
  (select count(*) from tenders)           as tenders,
  (select count(*) from suppliers)         as suppliers,
  (select count(*) from payments)          as payments,
  (select count(*) from deliverables)      as deliverables,
  (select count(*) from compliance_flags)  as flags,
  (select count(*) from department_budgets) as budgets;
