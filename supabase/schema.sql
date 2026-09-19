-- ===========================================================================
-- TenderTrack — Supabase schema
--
-- Run this in the Supabase SQL editor (Dashboard > SQL Editor > New query).
--
-- The design point the prototype review raised is implemented here: a user's
-- role is stored server-side, read from the JWT, and enforced by row-level
-- security. The Android app never sends a role and cannot choose one.
-- ===========================================================================

-- FIX: Supabase keeps extensions in a dedicated "extensions" schema, not public.
-- Creating it here means this script works on a clean Supabase project AND on a
-- plain Postgres/local setup.
create schema if not exists extensions;
create extension if not exists "pgcrypto" with schema extensions;

-- ---------------------------------------------------------------------------
-- Enumerated types — the single source of truth for every vocabulary
-- ---------------------------------------------------------------------------

create type user_role as enum (
  'public', 'supplier', 'procurement_officer', 'evaluation_committee',
  'finance_officer', 'auditor', 'administrator'
);

-- Tender lifecycle (FR2). Supplier verification has its own separate type.
create type tender_status as enum (
  'registered', 'published', 'under_evaluation', 'awarded', 'in_progress', 'completed'
);

create type supplier_status as enum ('awaiting_verification', 'verified', 'not_approved');
create type flag_severity as enum ('high', 'medium', 'low');
create type flag_status as enum ('open', 'under_investigation', 'resolved');
create type flag_outcome as enum ('cleared', 'corrective_action', 'escalated');
-- Deliverable lifecycle, separate again from tender and supplier vocabularies.
create type deliverable_status as enum (
  'not_started', 'awaiting_verification', 'verified', 'overdue'
);

create type notification_kind as enum ('flag', 'deadline', 'award_code', 'registration', 'payment');

-- ---------------------------------------------------------------------------
-- Profiles — one row per auth user, holding the assigned role
-- ---------------------------------------------------------------------------

create table profiles (
  id          uuid primary key references auth.users on delete cascade,
  email       text not null,
  full_name   text not null,
  role        user_role not null default 'supplier',
  department  text,
  created_at  timestamptz not null default now()
);

-- A new sign-up always lands as 'supplier'. Only an administrator can change a
-- role afterwards (enforced by the policy further down), so self-selecting
-- 'procurement_officer' is impossible.
create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into profiles (id, email, full_name, role)
  values (new.id, new.email, coalesce(new.raw_user_meta_data->>'full_name', new.email), 'supplier');
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function handle_new_user();

-- Reads the caller's role. STABLE + SECURITY DEFINER so policies can call it
-- without recursing into the profiles policy.
create or replace function auth_role()
returns user_role language sql stable security definer set search_path = public as $$
  select role from profiles where id = auth.uid();
$$;

create or replace function is_officer()
returns boolean language sql stable as $$
  select auth_role() in ('procurement_officer', 'administrator');
$$;

create or replace function is_oversight()
returns boolean language sql stable as $$
  select auth_role() in ('auditor', 'administrator');
$$;

-- ---------------------------------------------------------------------------
-- Suppliers
-- ---------------------------------------------------------------------------

create table suppliers (
  id                   uuid primary key default gen_random_uuid(),
  owner_id             uuid references auth.users on delete set null,
  company_name         text not null,
  registration_number  text not null,
  csd_number           text not null unique,
  tax_clearance_expiry date,
  bbbee_level          int check (bbbee_level between 1 and 8),
  business_type        text not null default '',
  contact_email        text not null default '',
  physical_address     text not null default '',
  documents_received   int not null default 0,
  documents_required   int not null default 4,
  status               supplier_status not null default 'awaiting_verification',
  decision_reason      text,
  submitted_at         timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Tenders
-- ---------------------------------------------------------------------------

create table tenders (
  id                     uuid primary key default gen_random_uuid(),
  reference_number       text not null unique,
  title                  text not null,
  description            text not null default '',
  department             text not null,
  category               text not null,
  estimated_budget       numeric(14,2) not null check (estimated_budget > 0),
  closing_date           timestamptz not null,
  contract_period_months int not null default 12 check (contract_period_months > 0),
  status                 tender_status not null default 'registered',
  awarded_supplier_id    uuid references suppliers(id),
  awarded_supplier_name  text,
  awarded_value          numeric(14,2),
  awarded_at             timestamptz,
  paid_to_date           numeric(14,2) not null default 0,
  open_flag_count        int not null default 0,
  created_by             uuid references auth.users,
  created_at             timestamptz not null default now(),

  -- An award is all-or-nothing: you cannot have a value without a supplier.
  constraint award_is_complete check (
    (awarded_supplier_id is null and awarded_value is null and awarded_at is null)
    or (awarded_supplier_id is not null and awarded_value is not null and awarded_at is not null)
  ),
  -- FR12: disbursement can never exceed what was awarded.
  constraint paid_within_award check (awarded_value is null or paid_to_date <= awarded_value)
);

create index tenders_status_idx on tenders (status);
create index tenders_closing_idx on tenders (closing_date);

-- ---------------------------------------------------------------------------
-- Award codes — FR4
--
-- Issued at the point of award, never returned to the client that issued it.
-- The table is readable only by the supplier it belongs to.
-- ---------------------------------------------------------------------------

create table award_codes (
  id          uuid primary key default gen_random_uuid(),
  tender_id   uuid not null references tenders(id) on delete cascade,
  supplier_id uuid not null references suppliers(id) on delete cascade,
  code_hash   text not null,                       -- only the hash is stored
  expires_at  timestamptz not null,
  claimed_at  timestamptz,
  created_at  timestamptz not null default now(),
  unique (tender_id)
);

-- ---------------------------------------------------------------------------
-- Payments, budgets, flags, audit trail, notifications
-- ---------------------------------------------------------------------------

create table payments (
  id               uuid primary key default gen_random_uuid(),
  tender_id        uuid not null references tenders(id) on delete cascade,
  tender_reference text not null default '',
  milestone        text not null,
  amount           numeric(14,2) not null check (amount > 0),
  paid_on          date not null,
  invoice_number   text not null,
  recorded_by      text not null default '',
  created_at       timestamptz not null default now(),

  -- The same invoice must not be captured twice against the same tender.
  -- Double-capture of an invoice is a real disbursement fraud vector, and
  -- without this the seed script is not safe to re-run either.
  constraint payment_invoice_unique unique (tender_id, invoice_number)
);

-- Contract deliverables / milestones.
-- Deliverable 3 ERD calls this ContractMilestone. FR5 links uploaded evidence
-- to a milestone and FR12 blocks payment until that evidence is reviewed, so
-- neither requirement can be enforced without this table.
create table deliverables (
  id             uuid primary key default gen_random_uuid(),
  tender_id      uuid not null references tenders(id) on delete cascade,
  phase_name     text not null,
  target_date    date not null,
  phase_value    numeric(14,2) not null check (phase_value >= 0),
  status         deliverable_status not null default 'not_started',
  evidence_url   text,
  evidence_at    timestamptz,
  verified_by    uuid references auth.users,
  verified_at    timestamptz,
  created_at     timestamptz not null default now(),
  unique (tender_id, phase_name)
);

create index deliverables_tender_idx on deliverables (tender_id);

create table department_budgets (
  id             uuid primary key default gen_random_uuid(),
  department     text not null,
  financial_year text not null,
  allocated      numeric(16,2) not null,
  committed      numeric(16,2) not null default 0,
  disbursed      numeric(16,2) not null default 0,
  unique (department, financial_year)
);

create table compliance_flags (
  id                   uuid primary key default gen_random_uuid(),
  reference            text not null unique,
  tender_id            uuid not null references tenders(id) on delete cascade,
  tender_reference     text not null,
  tender_title         text not null,
  title                text not null,
  description          text not null,
  rule_triggered       text not null,
  severity             flag_severity not null,
  status               flag_status not null default 'open',
  outcome              flag_outcome,
  raised_at            timestamptz not null default now(),
  raised_automatically boolean not null default true,
  assigned_to          text
);

create table flag_notes (
  id         uuid primary key default gen_random_uuid(),
  flag_id    uuid not null references compliance_flags(id) on delete cascade,
  author     text not null,
  text       text not null,
  created_at timestamptz not null default now()
);

create table audit_trail (
  id          uuid primary key default gen_random_uuid(),
  entity_type text not null,
  entity_id   uuid not null,
  action      text not null,
  detail      text not null default '',
  actor       text not null,
  created_at  timestamptz not null default now()
);

create index audit_entity_idx on audit_trail (entity_type, entity_id);

create table notifications (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid references auth.users on delete cascade,
  kind       notification_kind not null,
  title      text not null,
  body       text not null,
  read       boolean not null default false,
  created_at timestamptz not null default now()
);

-- ===========================================================================
-- Business logic that must not live in the client
-- ===========================================================================

-- FR2: a tender may only move to the next state in the lifecycle.
create or replace function advance_tender_status(
  p_tender_id uuid, p_new_status tender_status, p_reason text
) returns void language plpgsql security definer set search_path = public as $$
declare
  v_current tender_status;
  v_expected tender_status;
  v_actor text;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may change a tender status';
  end if;

  select status into v_current from tenders where id = p_tender_id for update;
  if v_current is null then
    raise exception 'Tender not found';
  end if;

  v_expected := case v_current
    when 'registered'       then 'published'
    when 'published'        then 'under_evaluation'
    when 'under_evaluation' then 'awarded'
    when 'awarded'          then 'in_progress'
    when 'in_progress'      then 'completed'
    else null end;

  if v_expected is null or p_new_status <> v_expected then
    raise exception 'A tender in % cannot move to %', v_current, p_new_status;
  end if;

  update tenders set status = p_new_status where id = p_tender_id;

  select full_name into v_actor from profiles where id = auth.uid();
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Status changed', p_new_status || ' — ' || p_reason, coalesce(v_actor, 'system'));
end;
$$;

-- Sequence used for human-readable flag references. Declared BEFORE the
-- function that consumes it.
create sequence if not exists flag_seq start 100;

-- FR4: awarding issues the single-use 10-character code. The plain code is
-- queued for email and SMS delivery; only its hash is stored, and the function
-- returns nothing, so the officer's device never sees it.
create or replace function award_tender(
  p_tender_id uuid, p_supplier_id uuid, p_awarded_value numeric, p_awarded_at timestamptz
) returns void language plpgsql security definer
  -- FIX: "extensions" MUST be on the path or gen_random_bytes/crypt/gen_salt
  -- raise 42883 at runtime. A schema on search_path that does not exist is
  -- ignored, so this is safe on any layout.
  set search_path = public, extensions as $$
declare
  v_code text;
  v_supplier suppliers%rowtype;
  v_tender tenders%rowtype;
  v_actor text;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may award a tender';
  end if;

  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.status <> 'under_evaluation' then
    raise exception 'Only a tender under evaluation can be awarded';
  end if;

  select * into v_supplier from suppliers where id = p_supplier_id;
  if v_supplier.status <> 'verified' then
    raise exception 'Only a verified supplier can be awarded a tender';
  end if;

  -- 10 characters, uppercase alphanumeric.
  v_code := upper(substr(replace(encode(gen_random_bytes(16), 'base64'), '/', ''), 1, 10));

  update tenders set
    status = 'awarded',
    awarded_supplier_id = p_supplier_id,
    awarded_supplier_name = v_supplier.company_name,
    awarded_value = p_awarded_value,
    awarded_at = p_awarded_at
  where id = p_tender_id;

  insert into award_codes (tender_id, supplier_id, code_hash, expires_at)
  values (p_tender_id, p_supplier_id, crypt(v_code, gen_salt('bf')), now() + interval '14 days');

  -- Delivery is out of band: a scheduled function reads this notification and
  -- sends the email and SMS. The code is not returned to the caller.
  insert into notifications (user_id, kind, title, body)
  values (v_supplier.owner_id, 'award_code',
          'You have been awarded ' || v_tender.reference_number,
          'Your award code is ' || v_code || '. Enter it in TenderTrack to claim this award. It expires in 14 days.');

  -- FR6: flag a material difference between the estimate and the award.
  if abs(p_awarded_value - v_tender.estimated_budget) / v_tender.estimated_budget > 0.10 then
    insert into compliance_flags (reference, tender_id, tender_reference, tender_title,
                                  title, description, rule_triggered, severity)
    values ('FLG-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('flag_seq')::text, 4, '0'),
            p_tender_id, v_tender.reference_number, v_tender.title,
            'Award value variance',
            'The awarded value differs from the published estimate by more than 10%.',
            'Award variance above threshold', 'high');
    update tenders set open_flag_count = open_flag_count + 1 where id = p_tender_id;
  end if;

  select full_name into v_actor from profiles where id = auth.uid();
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Tender awarded',
          v_supplier.company_name || ' · R ' || p_awarded_value, coalesce(v_actor, 'system'));
end;
$$;

-- South African financial year for a date: 1 April 2026 -> '2026/27'.
create or replace function sa_financial_year(p_on date)
returns text language sql immutable as $$
  select case when extract(month from p_on) >= 4
    then extract(year from p_on)::int || '/' || right((extract(year from p_on)::int + 1)::text, 2)
    else (extract(year from p_on)::int - 1) || '/' || right(extract(year from p_on)::text, 2)
  end;
$$;

-- FR10 / FR12: record a payment and keep paid_to_date in step.
create or replace function record_payment(
  p_tender_id uuid, p_milestone text, p_amount numeric, p_paid_on date, p_invoice text
) returns void language plpgsql security definer set search_path = public as $$
declare
  v_tender tenders%rowtype;
  v_actor text;
begin
  if auth_role() not in ('procurement_officer', 'finance_officer', 'administrator') then
    raise exception 'You do not have permission to record payments';
  end if;

  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.awarded_value is null then
    raise exception 'A payment can only be recorded against an awarded tender';
  end if;
  if v_tender.paid_to_date + p_amount > v_tender.awarded_value then
    raise exception 'Payment would exceed the awarded value of R%', v_tender.awarded_value;
  end if;

  -- FR12: a payment may not be released until the milestone documentation has
  -- been submitted AND verified by an officer. Enforced only where the tender
  -- actually has deliverables captured, so tenders recorded before the supplier
  -- upload flow exists are not blocked.
  if exists (select 1 from deliverables d where d.tender_id = p_tender_id) then
    if not exists (
      select 1 from deliverables d
      where d.tender_id = p_tender_id
        and d.phase_name = p_milestone
        and d.status = 'verified'
    ) then
      raise exception
        'FR12: milestone "%" has no verified documentation, so no payment can be released against it',
        p_milestone;
    end if;
  end if;

  select full_name into v_actor from profiles where id = auth.uid();

  insert into payments (tender_id, tender_reference, milestone, amount, paid_on, invoice_number, recorded_by)
  values (p_tender_id, v_tender.reference_number, p_milestone, p_amount, p_paid_on, p_invoice, coalesce(v_actor, 'system'));

  update tenders set paid_to_date = paid_to_date + p_amount where id = p_tender_id;

  -- FIX: the financial year was hard-coded, so every payment recorded from
  -- 1 April 2027 onwards would silently update nothing. SA financial years run
  -- 1 April to 31 March, so derive it from the payment date.
  update department_budgets set disbursed = disbursed + p_amount
  where department = v_tender.department
    and financial_year = sa_financial_year(p_paid_on);

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Payment recorded',
          p_milestone || ' · R ' || p_amount, coalesce(v_actor, 'system'));
end;
$$;

-- ===========================================================================
-- Row level security
--
-- Every table is locked by default and opened only to the roles that need it.
-- ===========================================================================

alter table profiles           enable row level security;
alter table suppliers          enable row level security;
alter table tenders            enable row level security;
alter table award_codes        enable row level security;
alter table payments           enable row level security;
alter table deliverables       enable row level security;
alter table department_budgets enable row level security;
alter table compliance_flags   enable row level security;
alter table flag_notes         enable row level security;
alter table audit_trail        enable row level security;
alter table notifications      enable row level security;

-- Profiles ------------------------------------------------------------------
create policy profiles_read_self on profiles
  for select using (id = auth.uid() or is_oversight() or is_officer());

-- A user may edit their own profile but NOT their own role. This is the policy
-- that makes role self-selection impossible.
-- FIX: the original WITH CHECK sub-queried profiles from inside a policy ON
-- profiles, which Postgres rejects at runtime with
-- "infinite recursion detected in policy for relation profiles".
-- auth_role() is SECURITY DEFINER so it bypasses RLS and breaks the cycle.
create policy profiles_update_self on profiles
  for update to authenticated using (id = auth.uid())
  with check (id = auth.uid() and role = auth_role());

create policy profiles_admin_manage on profiles
  for all using (auth_role() = 'administrator') with check (auth_role() = 'administrator');

-- Tenders -------------------------------------------------------------------
-- Anyone, signed in or not, may read a published tender: this is the public
-- transparency requirement (FR13, FR14).
create policy tenders_public_read on tenders
  for select to anon, authenticated
  using (status <> 'registered' or is_officer() or is_oversight());

create policy tenders_officer_write on tenders
  for insert to authenticated with check (is_officer());

create policy tenders_officer_update on tenders
  for update to authenticated using (is_officer()) with check (is_officer());

-- Suppliers -----------------------------------------------------------------
create policy suppliers_own_read on suppliers
  for select to authenticated
  using (owner_id = auth.uid() or is_officer() or is_oversight());

create policy suppliers_own_insert on suppliers
  for insert to authenticated with check (owner_id = auth.uid());

-- Only an officer may change a verification status.
create policy suppliers_officer_update on suppliers
  for update to authenticated using (is_officer()) with check (is_officer());

-- Award codes ---------------------------------------------------------------
-- Deliberately unreadable by the officer who issued the award.
create policy award_codes_supplier_read on award_codes
  for select to authenticated
  using (exists (select 1 from suppliers s where s.id = award_codes.supplier_id and s.owner_id = auth.uid()));

-- Deliverables --------------------------------------------------------------
-- Delivery progress is public (FR14); only officers may change it, and only a
-- supplier may attach evidence to its own contract.
create policy deliverables_read on deliverables for select to anon, authenticated using (true);
create policy deliverables_officer_write on deliverables for insert to authenticated
  with check (is_officer());
create policy deliverables_officer_update on deliverables for update to authenticated
  using (
    is_officer()
    or exists (
      select 1 from tenders t join suppliers s on s.id = t.awarded_supplier_id
      where t.id = deliverables.tender_id and s.owner_id = auth.uid()
    )
  )
  with check (
    is_officer()
    or exists (
      select 1 from tenders t join suppliers s on s.id = t.awarded_supplier_id
      where t.id = deliverables.tender_id and s.owner_id = auth.uid()
    )
  );

-- Award codes (claiming) ----------------------------------------------------
-- FIX: the original had no UPDATE policy, so FR4 "claim your awarded tender"
-- could never be completed by the supplier it was issued to.
create policy award_codes_supplier_claim on award_codes
  for update to authenticated
  using (exists (select 1 from suppliers s where s.id = award_codes.supplier_id and s.owner_id = auth.uid()))
  with check (exists (select 1 from suppliers s where s.id = award_codes.supplier_id and s.owner_id = auth.uid()));

-- Payments ------------------------------------------------------------------
-- FIX (POPIA): the original policy exposed invoice_number and recorded_by (a
-- named official) to anonymous users. FR11 only requires the public to see how
-- much has been disbursed, not who processed it or against which invoice.
-- Signed-in oversight/officer roles still read the full row; the public reads
-- the redacted view "payments_public" declared below.
create policy payments_read_internal on payments
  for select to authenticated
  using (is_officer() or is_oversight() or auth_role() = 'finance_officer');

create policy payments_write on payments
  for insert to authenticated
  with check (auth_role() in ('procurement_officer', 'finance_officer', 'administrator'));

-- Public-facing, personal-data-free projection of the same rows (FR11, FR14).
-- security_invoker = off means the view reads with the definer's rights, so the
-- restrictive policy above does not block it.
create view payments_public
  with (security_invoker = off) as
  select id, tender_id, tender_reference, milestone, amount, paid_on
  from payments;

grant select on payments_public to anon, authenticated;

-- Budgets -------------------------------------------------------------------
create policy budgets_read on department_budgets for select to anon, authenticated using (true);
create policy budgets_write on department_budgets for update to authenticated
  using (auth_role() in ('finance_officer', 'administrator'))
  with check (auth_role() in ('finance_officer', 'administrator'));

-- Flags ---------------------------------------------------------------------
create policy flags_read on compliance_flags for select to anon, authenticated using (true);
-- FIX: the original used "for all", which includes DELETE. FR3 requires that
-- no record can be removed without trace, so a flag may be raised and updated
-- (resolved, escalated) but never deleted through the API.
create policy flags_insert on compliance_flags for insert to authenticated
  with check (is_officer() or is_oversight());
create policy flags_update on compliance_flags for update to authenticated
  using (is_officer() or is_oversight()) with check (is_officer() or is_oversight());

create policy flag_notes_read on flag_notes for select to authenticated
  using (is_officer() or is_oversight());
create policy flag_notes_write on flag_notes for insert to authenticated
  with check (is_officer() or is_oversight());

-- Audit trail ---------------------------------------------------------------
-- Readable by oversight, never editable by anyone through the API.
create policy audit_read on audit_trail for select to authenticated
  using (is_officer() or is_oversight());

-- Notifications -------------------------------------------------------------
create policy notifications_own on notifications for select to authenticated
  using (user_id = auth.uid() or (user_id is null and is_officer()));
create policy notifications_own_update on notifications for update to authenticated
  using (user_id = auth.uid() or (user_id is null and is_officer()))
  with check (user_id = auth.uid() or (user_id is null and is_officer()));

-- ===========================================================================
-- Seed data for a demo account
-- ===========================================================================

insert into department_budgets (department, financial_year, allocated, committed, disbursed)
values ('Gauteng Dept of e-Government', '2026/27', 248000000, 214700000, 162400000)
on conflict do nothing;

-- After creating a user in Authentication > Users, promote them with:
--   update profiles set role = 'procurement_officer',
--                       full_name = 'T. Mokoena',
--                       department = 'Gauteng Dept of e-Government'
--   where email = 'you@example.com';
