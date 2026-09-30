-- ===========================================================================
-- TenderTrack — Bidding on the demo eTender portal, and claiming an award
--
-- Run in the Supabase SQL Editor after the earlier files (schema, seed,
-- public_access, public_budget, auditor_records, supplier_module and
-- admin_portal). Safe to re-run.
--
-- The demo eTender portal stands in for the government's own procurement
-- website and uses this same database. TenderTrack itself takes no part in
-- bidding: suppliers find tenders in the app and bid on the portal.
--
-- What this adds
--   1. Bids: a registered supplier submits, resubmits or withdraws a bid on
--      the portal before the closing date, and sees how it went.
--   2. Awards from a bid: the department awards one of the bids received.
--   3. The award code: a random 10-digit one-time code, created by the
--      database at the moment of award. Only a scrambled copy (bcrypt hash) is
--      stored. The plain code goes into an email outbox that only the portal's
--      server can read; the server emails it and then wipes it.
--   4. Claiming: the supplier types the code in TenderTrack. The database
--      checks it (5 attempts, 14 days). Until it matches, the contract is not
--      theirs: they cannot start work or update deliverables.
--   5. Deliverables: after claiming, the supplier submits evidence for each
--      phase; an officer verifies it.
--   6. Closing-date extensions, closing bidding (early for a demonstration),
--      and public reading of tender packs so the portal shows tender details
--      without signing in.
--   7. The officer app's "next status" button, and direct edits of a tender
--      row, can no longer mark a tender awarded without a supplier, or start
--      a contract before the supplier has claimed it with the code.
--   8. An officer can verify or reject a registration but not change the
--      company's details (such as the email the code is sent to), and nobody
--      can insert a supplier that is already "verified".
--   9. Registering twice, on purpose: a company registers on the eTender
--      portal (the government's record) AND registers for TenderTrack in the
--      app. The app registration must match the portal registration (CSD and
--      company registration numbers, same account) and is confirmed with a
--      6-digit code emailed to the company's registered contact address. Until
--      both are done, the company cannot claim an award or update deliverables.
--
-- Dates written into emails and notices are in South African time.
-- It also closes three gaps in schema.sql: suppliers could mark their own
-- award as claimed without the code, could read the stored code hash, and
-- could mark their own deliverables as verified. The old award notification,
-- which carried the plain code inside the app, no longer does.
-- ===========================================================================

do $$
begin
  if to_regclass('public.supplier_documents') is null then
    raise exception 'Run supplier_module.sql first.';
  end if;
  if to_regclass('public.bids') is null then
    raise exception 'Run auditor_records.sql first.';
  end if;
  if not exists (select 1 from information_schema.columns
                  where table_schema = 'public' and table_name = 'profiles' and column_name = 'suspended') then
    raise exception 'Run admin_portal.sql first.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 1. Award codes: nobody reads or changes the table directly any more
-- ---------------------------------------------------------------------------

drop policy if exists award_codes_supplier_read  on award_codes;
drop policy if exists award_codes_supplier_claim on award_codes;
revoke all on award_codes from anon, authenticated;

alter table award_codes add column if not exists attempts     int not null default 0;
alter table award_codes add column if not exists locked_at    timestamptz;
alter table award_codes add column if not exists claimed_by   uuid;
alter table award_codes add column if not exists sent_to      text not null default '';
alter table award_codes add column if not exists issued_count int not null default 1;
alter table award_codes add column if not exists bid_id       uuid references bids(id) on delete set null;

-- Registered on the eTender portal / registered for TenderTrack in the app.
-- Companies that already exist when this runs (seed data, earlier sign-ups)
-- count as registered on both, once.
do $$
begin
  if not exists (select 1 from information_schema.columns
                  where table_schema = 'public' and table_name = 'suppliers' and column_name = 'app_registered_at') then
    alter table suppliers add column portal_registered_at timestamptz;
    alter table suppliers add column app_registered_at timestamptz;
    update suppliers set portal_registered_at = submitted_at, app_registered_at = submitted_at;
  end if;
end $$;

-- Award notifications written by the old award_tender contained the plain code.
update notifications
   set body = 'An award code was sent to your company email address. Enter it in TenderTrack to claim this award.'
 where kind = 'award_code' and body like 'Your award code is %';

-- Deliverables: a supplier no longer updates the table directly (it could set
-- its own work to "verified"). Evidence goes through submit_deliverable_evidence.
alter table deliverables add column if not exists evidence_note text not null default '';

drop policy if exists deliverables_officer_update on deliverables;
create policy deliverables_officer_update on deliverables for update to authenticated
  using (is_officer()) with check (is_officer());


-- ---------------------------------------------------------------------------
-- 2. Email outbox — read only by the portal's server (secret key)
-- ---------------------------------------------------------------------------

create table if not exists email_outbox (
  id              uuid primary key default gen_random_uuid(),
  to_email        text not null,
  to_name         text not null default '',
  subject         text not null,
  body            text not null,          -- may contain an award code until it is sent
  body_after_send text not null,          -- the same message with the code removed
  kind            text not null default 'general',
  tender_id       uuid references tenders(id) on delete set null,
  to_user         uuid,                   -- the account it is for (the demo mailbox shows it only to them)
  created_at      timestamptz not null default now(),
  sent_at         timestamptz,
  delivered_via   text,
  attempts        int not null default 0,
  last_error      text
);

alter table email_outbox add column if not exists to_user uuid;
alter table email_outbox drop constraint if exists email_outbox_kind_check;
alter table email_outbox add constraint email_outbox_kind_check
  check (kind in ('award_code', 'bid_receipt', 'verification', 'general'));

create index if not exists email_outbox_pending_idx on email_outbox (created_at) where sent_at is null;

alter table email_outbox enable row level security;
revoke all on email_outbox from anon, authenticated;
-- No policies on purpose: the app and the browser can never read it.


-- ---------------------------------------------------------------------------
-- 3. Bids
-- ---------------------------------------------------------------------------

alter table bids add column if not exists submitted_documents text[] not null default '{}';
alter table bids add column if not exists validity_days       int;
alter table bids add column if not exists preference_claim    text not null default '';
alter table bids add column if not exists declaration_at      timestamptz;
alter table bids add column if not exists notes               text not null default '';
alter table bids add column if not exists updated_at          timestamptz;

create sequence if not exists bid_seq start 1000;

-- One live bid per supplier per tender (a resubmission replaces it).
create unique index if not exists bids_one_live_per_supplier
  on bids (tender_id, supplier_id) where status <> 'withdrawn';

-- A supplier may read its own bids; officers and oversight read all of them
-- (policy bids_read from auditor_records.sql). Nobody writes directly.
drop policy if exists bids_supplier_read_own on bids;
create policy bids_supplier_read_own on bids for select to authenticated
  using (supplier_id in (select s.id from suppliers s where s.owner_id = auth.uid()));


-- ---------------------------------------------------------------------------
-- 4. The public may read a published tender's pack (for the portal)
-- ---------------------------------------------------------------------------

grant execute on function tender_is_open_to_bidders(uuid) to anon;

do $$
declare
  t text;
begin
  foreach t in array array['tender_details', 'tender_eligibility', 'tender_documents', 'tender_updates']
  loop
    execute format('grant select on %I to anon', t);
    execute format('drop policy if exists %I_public_read on %I', t, t);
    execute format(
      'create policy %I_public_read on %I for select to anon using (tender_is_open_to_bidders(tender_id))', t, t);
  end loop;
end $$;


-- The public tender list gains its publication date (added at the end, so the
-- app's existing reads of tenders_public are unaffected).
create or replace view tenders_public
  with (security_invoker = off) as
  select
    id,
    reference_number,
    title,
    description,
    department,
    category,
    case when status in ('awarded', 'in_progress', 'completed')
         then estimated_budget
    end                         as estimated_budget,   -- NULL until award
    closing_date,
    contract_period_months,
    status,
    awarded_supplier_id,
    awarded_supplier_name,
    awarded_value,
    awarded_at,
    paid_to_date,
    open_flag_count,
    created_at,
    published_at
  from tenders
  where status <> 'registered';

grant select on tenders_public to anon, authenticated;


-- ---------------------------------------------------------------------------
-- 5. Small helpers
-- ---------------------------------------------------------------------------

/** The caller's supplier registration, or null. */
create or replace function my_supplier_id()
returns uuid language sql stable security definer set search_path = public as $$
  select id from suppliers where owner_id = auth.uid() limit 1;
$$;

/** o•••s@infratech.co.za */
create or replace function mask_email(p_email text)
returns text language sql immutable as $$
  select case
    when p_email is null or position('@' in p_email) < 2 then coalesce(p_email, '')
    else left(p_email, 1) || '•••' || substr(p_email, position('@' in p_email) - 1)
  end;
$$;

-- Dates in emails and notices in South African time (Supabase runs in UTC).
create or replace function sa_time(p timestamptz) returns timestamp
language sql stable set search_path = public as $$ select p at time zone 'Africa/Johannesburg' $$;

/** R 17 950 000 */
create or replace function money_text(p_amount numeric)
returns text language sql immutable as $$
  select 'R ' || replace(to_char(round(coalesce(p_amount, 0)), 'FM999,999,999,999,990'), ',', ' ');
$$;

/** A random 10-digit code. Each digit comes from a fresh random byte, with no bias. */
create or replace function new_award_code()
returns text language plpgsql volatile set search_path = public, extensions as $$
declare
  v_code  text := '';
  v_bytes bytea;
  i       int;
begin
  while char_length(v_code) < 10 loop
    v_bytes := gen_random_bytes(16);
    for i in 0..15 loop
      if get_byte(v_bytes, i) < 250 and char_length(v_code) < 10 then
        v_code := v_code || (get_byte(v_bytes, i) % 10)::text;
      end if;
    end loop;
  end loop;
  return v_code;
end;
$$;

create or replace function actor_name()
returns text language sql stable security definer set search_path = public as $$
  select coalesce((select full_name from profiles where id = auth.uid()), 'System');
$$;

/**
 * The contract's phases, taken from the "Deliverables required" lines of the
 * tender pack and spread over the contract period. Only when the tender has
 * none yet, so it never duplicates.
 */
create or replace function create_contract_deliverables(p_tender_id uuid)
returns int language plpgsql security definer set search_path = public as $$
declare
  v_tender tenders%rowtype;
  v_lines  text[];
  v_n      int;
  v_each   numeric;
  v_start  date;
  i        int;
begin
  if exists (select 1 from deliverables where tender_id = p_tender_id) then
    return 0;
  end if;
  select * into v_tender from tenders where id = p_tender_id;
  if v_tender.awarded_value is null then
    return 0;
  end if;

  select array_agg(left(trim(line), 120) order by ord) into v_lines
  from unnest(string_to_array(coalesce((select deliverables from tender_details where tender_id = p_tender_id), ''), E'\n'))
       with ordinality as l(line, ord)
  where trim(line) <> '';

  if v_lines is null or array_length(v_lines, 1) is null then
    v_lines := array['Contract delivery'];
  end if;

  v_n := array_length(v_lines, 1);
  v_each := round(v_tender.awarded_value / v_n, 2);
  v_start := coalesce(v_tender.awarded_at, now())::date;

  for i in 1..v_n loop
    insert into deliverables (tender_id, phase_name, target_date, phase_value, status)
    values (p_tender_id, v_lines[i],
            v_start + make_interval(days => (v_tender.contract_period_months * 30 * i) / v_n),
            case when i = v_n then v_tender.awarded_value - v_each * (v_n - 1) else v_each end,
            'not_started')
    on conflict (tender_id, phase_name) do nothing;
  end loop;
  return v_n;
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. Issuing the award code
-- ---------------------------------------------------------------------------

/**
 * Creates (or replaces) the tender's award code and queues the email. Returns
 * the address it was sent to. Never returns the code itself.
 */
create or replace function issue_award_code(p_tender_id uuid, p_bid_id uuid default null)
returns text language plpgsql security definer set search_path = public, extensions, auth as $$
declare
  v_tender      tenders%rowtype;
  v_supplier    suppliers%rowtype;
  v_owner_email text;
  v_to          text;
  v_code        text := new_award_code();
  v_pretty      text;
  v_expires     timestamptz := now() + interval '14 days';
  v_subject     text;
  v_body        text;
  v_template    text;
begin
  select * into v_tender from tenders where id = p_tender_id;
  select * into v_supplier from suppliers where id = v_tender.awarded_supplier_id;
  if v_supplier.id is null then
    raise exception 'This tender has not been awarded to a supplier.';
  end if;

  select email into v_owner_email from auth.users where id = v_supplier.owner_id;
  v_to := lower(coalesce(nullif(trim(v_supplier.contact_email), ''), v_owner_email, ''));
  if v_to = '' then
    raise exception 'The supplier has no email address to send the award code to.';
  end if;

  insert into award_codes (tender_id, supplier_id, code_hash, expires_at, sent_to, bid_id)
  values (p_tender_id, v_supplier.id, crypt(v_code, gen_salt('bf', 8)), v_expires, v_to, p_bid_id)
  on conflict (tender_id) do update
     set supplier_id = excluded.supplier_id,
         code_hash = excluded.code_hash,
         expires_at = excluded.expires_at,
         sent_to = excluded.sent_to,
         bid_id = coalesce(excluded.bid_id, award_codes.bid_id),
         attempts = 0, locked_at = null, claimed_at = null, claimed_by = null,
         issued_count = award_codes.issued_count + 1,
         created_at = now();

  v_pretty := substr(v_code, 1, 5) || ' ' || substr(v_code, 6, 5);
  v_subject := 'Tender ' || v_tender.reference_number || ' awarded to ' || v_supplier.company_name
               || ' – your award code';
  v_template :=
    'Dear ' || coalesce(nullif(trim(v_supplier.contact_person), ''), v_supplier.company_name) || ',' || E'\n\n' ||
    v_tender.department || ' has awarded tender ' || v_tender.reference_number || ' – ' || v_tender.title ||
    ' to ' || v_supplier.company_name || '.' || E'\n\n' ||
    'Awarded value: ' || money_text(v_tender.awarded_value) || E'\n' ||
    'Your award code: {CODE}' || E'\n\n' ||
    'To accept the award, sign in to the TenderTrack app with your supplier account, open Awards, ' ||
    'choose this tender and enter the code. (Not registered for TenderTrack yet? On the app''s Supplier ' ||
    'login choose Register an account first.) It expires on ' || to_char(sa_time(v_expires), 'DD Mon YYYY') ||
    ' and may be tried 5 times.' || E'\n\n' ||
    'Until the award is claimed with this code you cannot start work on the contract or update its deliverables.' ||
    E'\n\n' ||
    'Do not share this code. Nobody from the department or TenderTrack will ever ask you for it.' || E'\n\n' ||
    '— eTender Demo Portal (a demonstration system for the TenderTrack student project, not a government website)';

  insert into email_outbox (to_email, to_name, subject, body, body_after_send, kind, tender_id, to_user)
  values (v_to, coalesce(nullif(trim(v_supplier.contact_person), ''), v_supplier.company_name), v_subject,
          replace(v_template, '{CODE}', v_pretty),
          replace(v_template, '{CODE}', '••••• ••••• (removed after sending)'),
          'award_code', p_tender_id, v_supplier.owner_id);

  -- Also to the account's sign-in address, if it is a different one.
  if coalesce(v_owner_email, '') <> '' and lower(v_owner_email) <> v_to then
    insert into email_outbox (to_email, to_name, subject, body, body_after_send, kind, tender_id, to_user)
    values (lower(v_owner_email), coalesce(nullif(trim(v_supplier.contact_person), ''), v_supplier.company_name),
            v_subject, replace(v_template, '{CODE}', v_pretty),
            replace(v_template, '{CODE}', '••••• ••••• (removed after sending)'),
            'award_code', p_tender_id, v_supplier.owner_id);
  end if;

  if v_supplier.owner_id is not null then
    insert into notifications (user_id, kind, title, body)
    values (v_supplier.owner_id, 'award_code', 'You have been awarded ' || v_tender.reference_number,
            'A 10-digit award code has been emailed to ' || mask_email(v_to) ||
            '. Enter it in TenderTrack under Awards by ' || to_char(sa_time(v_expires), 'DD Mon YYYY') ||
            ' to claim this contract.');
  end if;

  return v_to;
end;
$$;

/** Everything an award does, whichever screen it was made from. */
create or replace function finalize_award(
  p_tender_id uuid, p_supplier_id uuid, p_value numeric, p_awarded_at timestamptz,
  p_bid_id uuid, p_comment text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender   tenders%rowtype;
  v_supplier suppliers%rowtype;
  v_sent_to  text;
begin
  select * into v_tender from tenders where id = p_tender_id;
  select * into v_supplier from suppliers where id = p_supplier_id;

  update tenders set
    status = 'awarded',
    awarded_supplier_id = p_supplier_id,
    awarded_supplier_name = v_supplier.company_name,
    awarded_value = p_value,
    awarded_at = coalesce(p_awarded_at, now())
  where id = p_tender_id;

  v_sent_to := issue_award_code(p_tender_id, p_bid_id);
  perform create_contract_deliverables(p_tender_id);

  insert into award_approvals (tender_id, approver_name, approver_position, approved_at, approved_value, comments)
  values (p_tender_id, actor_name(), 'Procurement Officer', now(), p_value, coalesce(p_comment, ''))
  on conflict (tender_id) do nothing;

  -- FR6: flag a material difference between the estimate and the award.
  if abs(p_value - v_tender.estimated_budget) / v_tender.estimated_budget > 0.10 then
    insert into compliance_flags (reference, tender_id, tender_reference, tender_title,
                                  title, description, rule_triggered, severity)
    values ('FLG-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('flag_seq')::text, 4, '0'),
            p_tender_id, v_tender.reference_number, v_tender.title,
            'Award value variance',
            'The awarded value differs from the published estimate by more than 10%.',
            'Award variance above threshold', 'high');
    update tenders set open_flag_count = open_flag_count + 1 where id = p_tender_id;
  end if;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Tender awarded',
          v_supplier.company_name || ' · ' || money_text(p_value) || ' · award code emailed to ' || mask_email(v_sent_to),
          actor_name());

  return jsonb_build_object('tender_id', p_tender_id, 'reference', v_tender.reference_number,
                            'supplier', v_supplier.company_name, 'sent_to', mask_email(v_sent_to));
end;
$$;

/**
 * Award from the TenderTrack app (Award Tender screen). Same signature as
 * before, so the app needs no change. Returns nothing: the code is never
 * seen by the officer's device.
 */
create or replace function award_tender(
  p_tender_id uuid, p_supplier_id uuid, p_awarded_value numeric, p_awarded_at timestamptz
) returns void language plpgsql security definer set search_path = public as $$
declare
  v_tender   tenders%rowtype;
  v_supplier suppliers%rowtype;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may award a tender';
  end if;
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.status is distinct from 'under_evaluation' then
    raise exception 'Only a tender under evaluation can be awarded';
  end if;
  select * into v_supplier from suppliers where id = p_supplier_id;
  if v_supplier.status is distinct from 'verified' then
    raise exception 'Only a verified supplier can be awarded a tender';
  end if;
  if p_awarded_value is null or p_awarded_value <= 0 then
    raise exception 'Enter the awarded value.';
  end if;

  perform finalize_award(p_tender_id, p_supplier_id, p_awarded_value, p_awarded_at,
                         (select id from bids where tender_id = p_tender_id and supplier_id = p_supplier_id
                            and status <> 'withdrawn' limit 1),
                         '');
  update bids set status = 'awarded', updated_at = now()
   where tender_id = p_tender_id and supplier_id = p_supplier_id and status in ('submitted', 'shortlisted');
end;
$$;

/** Award from the portal's department console: one of the bids received. */
create or replace function award_bid(p_bid_id uuid, p_comment text default '')
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_bid      bids%rowtype;
  v_tender   tenders%rowtype;
  v_supplier suppliers%rowtype;
  v_result   jsonb;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may award a tender.';
  end if;
  select * into v_bid from bids where id = p_bid_id for update;
  if v_bid.id is null then
    raise exception 'That bid no longer exists.';
  end if;
  select * into v_tender from tenders where id = v_bid.tender_id for update;
  if v_tender.status is distinct from 'under_evaluation' then
    raise exception 'Close bidding first: only a tender under evaluation can be awarded.';
  end if;
  if v_bid.status not in ('submitted', 'shortlisted') then
    raise exception 'This bid was % and cannot be awarded.', v_bid.status;
  end if;
  select * into v_supplier from suppliers where id = v_bid.supplier_id;
  if v_supplier.id is null then
    raise exception 'This bid is not linked to a registered supplier.';
  end if;
  if v_supplier.status is distinct from 'verified' then
    raise exception '% is not verified yet. Verify the registration in TenderTrack first.', v_supplier.company_name;
  end if;

  update bids set status = 'awarded', updated_at = now() where id = p_bid_id;
  v_result := finalize_award(v_tender.id, v_supplier.id, v_bid.bid_value, now(), p_bid_id, p_comment);
  return v_result;
end;
$$;

/** Sends a new code (the old one stops working): expired, locked, lost, or never sent. */
create or replace function send_award_code(p_tender_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender tenders%rowtype;
  v_code   award_codes%rowtype;
  v_to     text;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may send an award code.';
  end if;
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.awarded_supplier_id is null then
    raise exception 'This tender has not been awarded.';
  end if;
  select * into v_code from award_codes where tender_id = p_tender_id;
  if v_code.claimed_at is not null then
    raise exception 'The supplier has already claimed this award.';
  end if;
  if v_tender.status <> 'awarded' then
    raise exception 'Award codes can only be sent while the tender is awarded and not yet claimed.';
  end if;

  v_to := issue_award_code(p_tender_id, null);
  perform create_contract_deliverables(p_tender_id);

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id,
          case when v_code.id is null then 'Award code sent' else 'Award code re-sent' end,
          v_tender.reference_number || ' · emailed to ' || mask_email(v_to), actor_name());

  return jsonb_build_object('sent_to', mask_email(v_to));
end;
$$;


/** For the department console: where the award code stands. Never the code. */
create or replace function award_code_status(p_tender_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_code award_codes%rowtype;
begin
  if not (is_officer() or is_oversight()) then
    raise exception 'Only officers and oversight can see award code status.';
  end if;
  select * into v_code from award_codes where tender_id = p_tender_id;
  if v_code.id is null then
    return jsonb_build_object('status', 'no_code');
  end if;
  return jsonb_build_object(
    'status', case when v_code.claimed_at is not null then 'claimed'
                   when v_code.locked_at is not null then 'locked'
                   when v_code.expires_at < now() then 'expired'
                   else 'pending' end,
    'sent_to', mask_email(v_code.sent_to),
    'expires_at', v_code.expires_at,
    'claimed_at', v_code.claimed_at,
    'attempts_left', greatest(0, 5 - v_code.attempts),
    'issued_count', v_code.issued_count,
    'issued_at', v_code.created_at);
end;
$$;


-- ---------------------------------------------------------------------------
-- 7. Claiming the award in TenderTrack
-- ---------------------------------------------------------------------------

/**
 * Checks the code. Returns { ok, message, attempts_left, locked, expired }.
 * A wrong code is not an error (an error would undo the attempt count).
 */
create or replace function claim_award(p_tender_id uuid, p_code text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  v_supplier uuid := my_supplier_id();
  v_tender   tenders%rowtype;
  v_code     award_codes%rowtype;
  v_clean    text := regexp_replace(coalesce(p_code, ''), '[^0-9]', '', 'g');
  v_left     int;
begin
  if v_supplier is null then
    raise exception 'Only a supplier account can claim an award.';
  end if;
  perform require_tendertrack_registration();
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.id is null or v_tender.awarded_supplier_id is distinct from v_supplier then
    raise exception 'This tender was not awarded to your company.';
  end if;
  select * into v_code from award_codes where tender_id = p_tender_id for update;
  if v_code.id is null then
    raise exception 'No award code has been sent for this tender yet. Ask the department to send it.';
  end if;

  if v_code.claimed_at is not null then
    return jsonb_build_object('ok', true, 'message', 'You have already claimed this award.', 'attempts_left', 0);
  end if;
  if v_code.locked_at is not null then
    return jsonb_build_object('ok', false, 'locked', true, 'attempts_left', 0,
      'message', 'This code is locked after 5 wrong attempts. Ask the department to send a new code.');
  end if;
  if v_code.expires_at < now() then
    return jsonb_build_object('ok', false, 'expired', true, 'attempts_left', 0,
      'message', 'This code expired on ' || to_char(sa_time(v_code.expires_at), 'DD Mon YYYY') ||
                 '. Ask the department to send a new code.');
  end if;
  if char_length(v_clean) <> 10 then
    return jsonb_build_object('ok', false, 'attempts_left', 5 - v_code.attempts,
      'message', 'Enter all 10 digits of the code from the email.');
  end if;

  if crypt(v_clean, v_code.code_hash) <> v_code.code_hash then
    update award_codes set attempts = attempts + 1,
           locked_at = case when attempts + 1 >= 5 then now() end
     where id = v_code.id;
    v_left := greatest(0, 5 - (v_code.attempts + 1));
    if v_left = 0 then
      insert into audit_trail (entity_type, entity_id, action, detail, actor)
      values ('tender', p_tender_id, 'Award code locked',
              v_tender.reference_number || ' · 5 wrong codes entered', actor_name());
      insert into notifications (user_id, kind, title, body)
      values (null, 'award_code', 'Award code locked: ' || v_tender.reference_number,
              v_tender.awarded_supplier_name || ' entered 5 wrong codes. Send a new code from the eTender portal if the request is genuine.');
      return jsonb_build_object('ok', false, 'locked', true, 'attempts_left', 0,
        'message', 'That code is not right, and the code is now locked. Ask the department to send a new code.');
    end if;
    return jsonb_build_object('ok', false, 'attempts_left', v_left,
      'message', 'That code is not right. ' || v_left || case when v_left = 1 then ' attempt' else ' attempts' end || ' left.');
  end if;

  update award_codes set claimed_at = now(), claimed_by = auth.uid() where id = v_code.id;
  if v_tender.status = 'awarded' then
    update tenders set status = 'in_progress' where id = p_tender_id;
  end if;
  perform create_contract_deliverables(p_tender_id);

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Award claimed',
          v_tender.reference_number || ' · ' || v_tender.awarded_supplier_name || ' accepted the award with its code',
          actor_name());
  insert into notifications (user_id, kind, title, body)
  values (null, 'award_code', 'Award claimed: ' || v_tender.reference_number,
          v_tender.awarded_supplier_name || ' has claimed the award. The contract is now in progress.');

  return jsonb_build_object('ok', true, 'attempts_left', 0,
    'message', 'Award claimed. ' || v_tender.reference_number || ' is now your contract.');
end;
$$;

/** The caller's awards, for the Awards screen in TenderTrack and the portal. */
create or replace function supplier_awards()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  -- Read-only, so the portal can show it too. Claiming needs the TenderTrack registration.
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'tender_id', t.id,
      'reference_number', t.reference_number,
      'title', t.title,
      'department', t.department,
      'status', t.status,
      'awarded_value', t.awarded_value,
      'awarded_at', t.awarded_at,
      'claim_status', case
          when c.id is null then 'no_code'
          when c.claimed_at is not null then 'claimed'
          when c.locked_at is not null then 'locked'
          when c.expires_at < now() then 'expired'
          else 'pending' end,
      'expires_at', c.expires_at,
      'claimed_at', c.claimed_at,
      'attempts_left', case when c.id is null then 0 else greatest(0, 5 - c.attempts) end,
      'sent_to', mask_email(c.sent_to),
      'deliverables_total', (select count(*) from deliverables d where d.tender_id = t.id),
      'deliverables_verified', (select count(*) from deliverables d where d.tender_id = t.id and d.status = 'verified'),
      'deliverables_awaiting', (select count(*) from deliverables d where d.tender_id = t.id and d.status = 'awaiting_verification')
    ) order by t.awarded_at desc), '[]'::jsonb)
  from tenders t
  left join award_codes c on c.tender_id = t.id
  where t.awarded_supplier_id is not null
    and t.awarded_supplier_id = my_supplier_id());
end;
$$;


-- ---------------------------------------------------------------------------
-- 8. Deliverables: evidence from the supplier, once the award is claimed
-- ---------------------------------------------------------------------------

create or replace function submit_deliverable_evidence(p_deliverable_id uuid, p_evidence_url text, p_note text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_row    deliverables%rowtype;
  v_tender tenders%rowtype;
  v_url    text := nullif(trim(coalesce(p_evidence_url, '')), '');
  v_note   text := trim(coalesce(p_note, ''));
begin
  select * into v_row from deliverables where id = p_deliverable_id for update;
  if v_row.id is null then
    raise exception 'That deliverable no longer exists.';
  end if;
  select * into v_tender from tenders where id = v_row.tender_id;
  if v_tender.awarded_supplier_id is distinct from my_supplier_id() or my_supplier_id() is null then
    raise exception 'Only the company awarded this contract can update its deliverables.';
  end if;
  perform require_tendertrack_registration();
  if not exists (select 1 from award_codes where tender_id = v_tender.id and claimed_at is not null) then
    raise exception 'Claim this award with the code from your email before updating its deliverables.';
  end if;
  if v_tender.status = 'completed' then
    raise exception 'This contract is completed.';
  end if;
  if v_row.status = 'verified' then
    raise exception 'This deliverable has already been verified.';
  end if;
  if v_url is null and v_note = '' then
    raise exception 'Add a link to the evidence or describe what was delivered.';
  end if;
  if v_url is not null and v_url !~* '^https?://' then
    raise exception 'The evidence link must start with http:// or https://';
  end if;
  if char_length(v_note) > 1000 then
    raise exception 'Keep the description under 1000 characters.';
  end if;

  update deliverables
     set evidence_url = v_url, evidence_note = v_note, evidence_at = now(), status = 'awaiting_verification'
   where id = p_deliverable_id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', v_tender.id, 'Deliverable evidence submitted',
          v_tender.reference_number || ' · ' || v_row.phase_name, actor_name());
  insert into notifications (user_id, kind, title, body)
  values (null, 'deadline', 'Evidence to verify: ' || v_tender.reference_number,
          v_tender.awarded_supplier_name || ' submitted evidence for "' || v_row.phase_name || '".');

  return (select to_jsonb(d) from deliverables d where d.id = p_deliverable_id);
end;
$$;


/** An officer accepts or returns the supplier's evidence for one phase. */
create or replace function review_deliverable(p_deliverable_id uuid, p_accept boolean, p_comment text default '')
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_row      deliverables%rowtype;
  v_tender   tenders%rowtype;
  v_owner    uuid;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may review deliverables.';
  end if;
  select * into v_row from deliverables where id = p_deliverable_id for update;
  if v_row.id is null then
    raise exception 'That deliverable no longer exists.';
  end if;
  if v_row.status <> 'awaiting_verification' then
    raise exception 'Only a deliverable awaiting verification can be reviewed.';
  end if;
  if not coalesce(p_accept, false) and char_length(trim(coalesce(p_comment, ''))) < 5 then
    raise exception 'Say what is missing (at least 5 characters) when returning evidence.';
  end if;
  select * into v_tender from tenders where id = v_row.tender_id;

  if p_accept then
    update deliverables set status = 'verified', verified_by = auth.uid(), verified_at = now() where id = p_deliverable_id;
  else
    update deliverables set status = 'not_started',
           evidence_note = left('Returned: ' || trim(p_comment) || E'\n' || evidence_note, 1000)
     where id = p_deliverable_id;
  end if;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', v_tender.id, case when p_accept then 'Deliverable verified' else 'Deliverable evidence returned' end,
          v_tender.reference_number || ' · ' || v_row.phase_name ||
          case when p_accept then '' else ' · ' || trim(p_comment) end, actor_name());

  select owner_id into v_owner from suppliers where id = v_tender.awarded_supplier_id;
  if v_owner is not null then
    insert into notifications (user_id, kind, title, body)
    values (v_owner, 'deadline',
            case when p_accept then 'Deliverable verified: ' else 'Evidence returned: ' end || v_tender.reference_number,
            v_row.phase_name || case when p_accept then ' has been verified.' else ' — ' || trim(p_comment) end);
  end if;

  return (select to_jsonb(d) from deliverables d where d.id = p_deliverable_id);
end;
$$;


-- ---------------------------------------------------------------------------
-- 9. Bidding on the portal
-- ---------------------------------------------------------------------------

create or replace function submit_bid(
  p_tender_id uuid, p_bid_value numeric, p_validity_days int, p_documents text[],
  p_preference_claim text, p_notes text, p_declaration boolean
) returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_supplier suppliers%rowtype;
  v_tender   tenders%rowtype;
  v_required text[];
  v_missing  text[];
  v_existing bids%rowtype;
  v_bid      uuid;
  v_ref      text;
  v_email    text;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid() limit 1;
  if v_supplier.id is null then
    raise exception 'Sign in with a registered supplier account to bid.';
  end if;
  if v_supplier.status = 'not_approved' then
    raise exception 'Your registration was not approved. Correct it and resubmit before bidding.';
  end if;
  if v_supplier.portal_registered_at is null then
    raise exception 'Finish your company''s registration on the eTender portal before bidding: sign in and open My bids & awards.';
  end if;

  select * into v_tender from tenders where id = p_tender_id;
  if v_tender.id is null or v_tender.status = 'registered' then
    raise exception 'That tender is not open for bids.';
  end if;
  if v_tender.status <> 'published' or v_tender.closing_date <= now() then
    raise exception 'Bidding for % has closed.', v_tender.reference_number;
  end if;

  if coalesce(p_declaration, false) is not true then
    raise exception 'Accept the declaration to submit your bid.';
  end if;
  if p_bid_value is null or p_bid_value <= 0 or p_bid_value >= 1000000000000 then
    raise exception 'Enter your total bid price in rand.';
  end if;
  if p_validity_days is null or p_validity_days not between 30 and 365 then
    raise exception 'The bid validity period must be between 30 and 365 days.';
  end if;

  select coalesce(array_agg(trim(line)), '{}') into v_required
  from unnest(string_to_array(coalesce((select required_documents from tender_details where tender_id = p_tender_id), ''), E'\n')) as line
  where trim(line) <> '';

  select coalesce(array_agg(r), '{}') into v_missing
  from unnest(v_required) r
  where not (r = any (coalesce(p_documents, '{}')));
  if array_length(v_missing, 1) > 0 then
    raise exception 'Confirm every required document. Still missing: %', array_to_string(v_missing, '; ');
  end if;

  select * into v_existing from bids
   where tender_id = p_tender_id and supplier_id = v_supplier.id and status <> 'withdrawn'
   limit 1 for update;

  if v_existing.id is not null then
    if v_existing.status <> 'submitted' then
      raise exception 'Your bid is already %, so it can no longer be changed.', v_existing.status;
    end if;
    update bids set bid_value = round(p_bid_value, 2), validity_days = p_validity_days,
           submitted_documents = coalesce(p_documents, '{}'),
           documents_received = coalesce(array_length(p_documents, 1), 0),
           documents_required = coalesce(array_length(v_required, 1), 0),
           preference_claim = trim(coalesce(p_preference_claim, '')),
           notes = left(trim(coalesce(p_notes, '')), 1000),
           declaration_at = now(), submitted_at = now(), updated_at = now(),
           supplier_name = v_supplier.company_name
     where id = v_existing.id;
    v_bid := v_existing.id;
    v_ref := v_existing.reference;
  else
    v_ref := 'BID-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('bid_seq')::text, 5, '0');
    insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                      documents_received, documents_required, submitted_documents, validity_days,
                      preference_claim, declaration_at, notes)
    values (v_ref, p_tender_id, v_supplier.id, v_supplier.company_name, round(p_bid_value, 2), now(), 'submitted',
            coalesce(array_length(p_documents, 1), 0), coalesce(array_length(v_required, 1), 0),
            coalesce(p_documents, '{}'), p_validity_days, trim(coalesce(p_preference_claim, '')), now(),
            left(trim(coalesce(p_notes, '')), 1000))
    returning id into v_bid;
  end if;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('bid', v_bid, case when v_existing.id is null then 'Bid submitted' else 'Bid resubmitted' end,
          v_tender.reference_number || ' · ' || v_supplier.company_name || ' · ' || money_text(p_bid_value),
          actor_name());

  select email into v_email from auth.users where id = auth.uid();
  v_email := lower(coalesce(nullif(trim(v_supplier.contact_email), ''), v_email, ''));
  if v_email <> '' then
    insert into email_outbox (to_email, to_name, subject, body, body_after_send, kind, tender_id, to_user)
    select v_email, v_supplier.company_name,
           'Bid received: ' || v_tender.reference_number || ' (' || v_ref || ')',
           msg, msg, 'bid_receipt', p_tender_id, auth.uid()
    from (select
      'Your bid for ' || v_tender.reference_number || ' – ' || v_tender.title || ' was received on ' ||
      to_char(sa_time(now()), 'DD Mon YYYY HH24:MI') || '.' || E'\n\n' ||
      'Bid reference: ' || v_ref || E'\n' ||
      'Total bid price: ' || money_text(p_bid_value) || E'\n' ||
      'Valid for: ' || p_validity_days || ' days' || E'\n' ||
      'Bidding closes: ' || to_char(sa_time(v_tender.closing_date), 'DD Mon YYYY HH24:MI') || E'\n\n' ||
      'You may change or withdraw the bid on the portal until the closing date.' || E'\n\n' ||
      '— eTender Demo Portal (a demonstration system, not a government website)' as msg) m;
  end if;

  return (select to_jsonb(b) from bids b where b.id = v_bid);
end;
$$;

create or replace function withdraw_bid(p_bid_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_bid    bids%rowtype;
  v_tender tenders%rowtype;
begin
  select * into v_bid from bids where id = p_bid_id for update;
  if v_bid.id is null or v_bid.supplier_id is distinct from my_supplier_id() or my_supplier_id() is null then
    raise exception 'That bid does not belong to your company.';
  end if;
  select * into v_tender from tenders where id = v_bid.tender_id;
  if v_bid.status <> 'submitted' then
    raise exception 'Only a submitted bid can be withdrawn.';
  end if;
  if v_tender.status <> 'published' or v_tender.closing_date <= now() then
    raise exception 'Bidding has closed, so the bid can no longer be withdrawn.';
  end if;
  update bids set status = 'withdrawn', updated_at = now() where id = p_bid_id;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('bid', p_bid_id, 'Bid withdrawn', v_tender.reference_number || ' · ' || v_bid.supplier_name, actor_name());
  return (select to_jsonb(b) from bids b where b.id = p_bid_id);
end;
$$;

/** The caller's bids, with the tender and how the bid went. */
create or replace function supplier_bids()
returns jsonb language sql stable security definer set search_path = public as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'id', b.id,
      'reference', b.reference,
      'tender_id', t.id,
      'tender_reference', t.reference_number,
      'tender_title', t.title,
      'department', t.department,
      'closing_date', t.closing_date,
      'tender_status', t.status,
      'bid_value', b.bid_value,
      'validity_days', b.validity_days,
      'submitted_at', b.submitted_at,
      'submitted_documents', b.submitted_documents,
      'preference_claim', b.preference_claim,
      'notes', b.notes,
      'status', b.status,
      'outcome', case
          when b.status = 'withdrawn' then 'withdrawn'
          when b.status = 'disqualified' then 'disqualified'
          when b.status = 'awarded' then 'awarded'
          when t.status in ('awarded', 'in_progress', 'completed') then 'unsuccessful'
          when t.status = 'under_evaluation' or t.closing_date <= now() then 'under_evaluation'
          else 'open' end
    ) order by b.submitted_at desc), '[]'::jsonb)
  from bids b
  join tenders t on t.id = b.tender_id
  where b.supplier_id = my_supplier_id();
$$;


-- ---------------------------------------------------------------------------
-- 10. Department console on the portal
-- ---------------------------------------------------------------------------

/**
 * The officer app's "next status" button, tightened for award codes:
 *   - awarding goes through award_tender / award_bid, which issue the code;
 *   - a contract starts (in progress) only when the supplier claims the award
 *     with the code; the officer cannot start it for them.
 * Same signature as in schema.sql, so the app needs no change.
 */
create or replace function advance_tender_status(
  p_tender_id uuid, p_new_status tender_status, p_reason text
) returns void language plpgsql security definer set search_path = public as $$
declare
  v_tender   tenders%rowtype;
  v_expected tender_status;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may change a tender status';
  end if;
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.id is null then
    raise exception 'Tender not found';
  end if;

  v_expected := case v_tender.status
    when 'registered'       then 'published'
    when 'published'        then 'under_evaluation'
    when 'under_evaluation' then 'awarded'
    when 'awarded'          then 'in_progress'
    when 'in_progress'      then 'completed'
    else null end;
  if v_expected is null or p_new_status <> v_expected then
    raise exception 'A tender in % cannot move to %', v_tender.status, p_new_status;
  end if;

  if p_new_status = 'awarded' then
    raise exception 'Use Award tender to choose the supplier. It issues the award code.';
  end if;
  if p_new_status = 'in_progress'
     and not exists (select 1 from award_codes where tender_id = p_tender_id and claimed_at is not null) then
    raise exception 'The supplier has not claimed this award yet. It moves to In progress by itself when the supplier enters the award code in TenderTrack.';
  end if;

  update tenders
     set status = p_new_status,
         -- Closing bidding early moves the closing date to now.
         closing_date = case when p_new_status = 'under_evaluation' then least(closing_date, now()) else closing_date end
   where id = p_tender_id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Status changed', p_new_status || ' — ' || coalesce(p_reason, ''), actor_name());
end;
$$;

/** Closes bidding (early, for a demonstration, or after the closing date) and opens the bids. */
create or replace function close_bidding(p_tender_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender tenders%rowtype;
  v_early  boolean;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may close bidding.';
  end if;
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.id is null or v_tender.status <> 'published' then
    raise exception 'Only a tender that is open for bids can be closed.';
  end if;
  if char_length(trim(coalesce(p_reason, ''))) < 5 then
    raise exception 'Give the reason (at least 5 characters).';
  end if;
  v_early := v_tender.closing_date > now();

  -- Closing early moves the closing date to now, so no page still shows it as open.
  update tenders set status = 'under_evaluation', closing_date = least(closing_date, now())
   where id = p_tender_id;
  if v_early then
    insert into tender_updates (tender_id, kind, title, body)
    values (p_tender_id, 'amendment', 'Bidding closed early on ' || to_char(sa_time(now()), 'DD Mon YYYY HH24:MI'),
            trim(p_reason));
  end if;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, case when v_early then 'Bidding closed early' else 'Bidding closed' end,
          v_tender.reference_number || ' · ' ||
          (select count(*) from bids where tender_id = p_tender_id and status <> 'withdrawn') || ' bids · ' || trim(p_reason),
          actor_name());
  return jsonb_build_object('early', v_early,
    'bids', (select count(*) from bids where tender_id = p_tender_id and status <> 'withdrawn'));
end;
$$;

create or replace function extend_tender_closing(p_tender_id uuid, p_new_closing timestamptz, p_reason text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender tenders%rowtype;
begin
  if not is_officer() then
    raise exception 'Only a procurement officer may extend a closing date.';
  end if;
  select * into v_tender from tenders where id = p_tender_id for update;
  if v_tender.status <> 'published' then
    raise exception 'Only a tender that is open for bids can be extended.';
  end if;
  if p_new_closing is null or p_new_closing <= v_tender.closing_date or p_new_closing <= now() + interval '1 hour' then
    raise exception 'The new closing date must be later than the current one and in the future.';
  end if;
  if char_length(trim(coalesce(p_reason, ''))) < 5 then
    raise exception 'Give the reason for the extension (at least 5 characters).';
  end if;

  update tenders set closing_date = p_new_closing where id = p_tender_id;
  insert into tender_updates (tender_id, kind, title, body)
  values (p_tender_id, 'extension',
          'Closing date extended to ' || to_char(sa_time(p_new_closing), 'DD Mon YYYY HH24:MI'), trim(p_reason));
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', p_tender_id, 'Closing date extended',
          v_tender.reference_number || ' · ' || to_char(sa_time(v_tender.closing_date), 'DD Mon YYYY') || ' → ' ||
          to_char(sa_time(p_new_closing), 'DD Mon YYYY') || ' · ' || trim(p_reason), actor_name());
  return (select to_jsonb(t) from tenders t where t.id = p_tender_id);
end;
$$;

/** Tax clearance and B-BBEE, captured at registration on the portal. */
create or replace function save_supplier_compliance(
  p_tax_clearance_status text, p_tax_clearance_expiry date, p_bbbee_level int, p_bbbee_expiry date
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier uuid := require_supplier();
begin
  if coalesce(p_tax_clearance_status, '') not in ('not_submitted', 'valid', 'pending', 'expired') then
    raise exception 'Choose the tax clearance status.';
  end if;
  if p_bbbee_level is not null and p_bbbee_level not between 1 and 8 then
    raise exception 'B-BBEE level must be between 1 and 8.';
  end if;
  update suppliers
     set tax_clearance_status = case
           when p_tax_clearance_status = 'valid' and p_tax_clearance_expiry < current_date then 'expired'
           else p_tax_clearance_status end,
         tax_clearance_expiry = p_tax_clearance_expiry,
         bbbee_level = p_bbbee_level,
         bbbee_expiry = p_bbbee_expiry,
         updated_at = now()
   where id = v_supplier;
  return (select to_jsonb(s) - 'owner_id' from suppliers s where s.id = v_supplier);
end;
$$;


-- ---------------------------------------------------------------------------
-- 11. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function mask_email(text)                                        from public, anon, authenticated;
revoke all on function money_text(numeric)                                      from public, anon, authenticated;
revoke all on function new_award_code()                                        from public, anon, authenticated;
revoke all on function actor_name()                                            from public, anon, authenticated;
revoke all on function create_contract_deliverables(uuid)                      from public, anon, authenticated;
revoke all on function issue_award_code(uuid, uuid)                            from public, anon, authenticated;
revoke all on function finalize_award(uuid, uuid, numeric, timestamptz, uuid, text) from public, anon, authenticated;
revoke all on function my_supplier_id()                                        from public, anon;

revoke all on function award_tender(uuid, uuid, numeric, timestamptz)          from public, anon;
revoke all on function award_bid(uuid, text)                                   from public, anon;
revoke all on function send_award_code(uuid)                                   from public, anon;
revoke all on function award_code_status(uuid)                                 from public, anon;
revoke all on function claim_award(uuid, text)                                 from public, anon;
revoke all on function supplier_awards()                                       from public, anon;
revoke all on function submit_deliverable_evidence(uuid, text, text)           from public, anon;
revoke all on function review_deliverable(uuid, boolean, text)                  from public, anon;
revoke all on function submit_bid(uuid, numeric, int, text[], text, text, boolean) from public, anon;
revoke all on function withdraw_bid(uuid)                                      from public, anon;
revoke all on function supplier_bids()                                         from public, anon;
revoke all on function extend_tender_closing(uuid, timestamptz, text)          from public, anon;
revoke all on function close_bidding(uuid, text)                               from public, anon;
revoke all on function save_supplier_compliance(text, date, int, date)         from public, anon;

grant execute on function my_supplier_id()                                     to authenticated;
grant execute on function award_tender(uuid, uuid, numeric, timestamptz)       to authenticated;
grant execute on function award_bid(uuid, text)                                to authenticated;
grant execute on function close_bidding(uuid, text)                            to authenticated;
grant execute on function send_award_code(uuid)                                to authenticated;
grant execute on function award_code_status(uuid)                              to authenticated;
grant execute on function claim_award(uuid, text)                              to authenticated;
grant execute on function supplier_awards()                                    to authenticated;
grant execute on function submit_deliverable_evidence(uuid, text, text)        to authenticated;
grant execute on function review_deliverable(uuid, boolean, text)               to authenticated;
grant execute on function submit_bid(uuid, numeric, int, text[], text, text, boolean) to authenticated;
grant execute on function withdraw_bid(uuid)                                   to authenticated;
grant execute on function supplier_bids()                                      to authenticated;
grant execute on function extend_tender_closing(uuid, timestamptz, text)       to authenticated;
grant execute on function save_supplier_compliance(text, date, int, date)      to authenticated;


-- ---------------------------------------------------------------------------
-- 12. Side doors: the same rules for direct table updates
-- ---------------------------------------------------------------------------

-- A supplier is created only through sign-up (handle_new_user), which always
-- starts it as "awaiting verification". Inserting one directly could create a
-- supplier that is already "verified", so that is no longer allowed.
drop policy if exists suppliers_own_insert on suppliers;

-- One company per account (my_supplier_id relies on it).
do $$
begin
  if exists (select 1 from suppliers where owner_id is not null group by owner_id having count(*) > 1) then
    raise notice 'Some accounts own more than one supplier, so the one-company-per-account rule was not added.';
  else
    create unique index if not exists suppliers_one_per_owner on suppliers (owner_id) where owner_id is not null;
  end if;
end $$;

-- An officer verifies or rejects a registration, and changes nothing else.
-- In particular, not the contact email the award code is sent to, and not
-- who owns the company.
create or replace function guard_supplier_officer_update()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_allowed text[] := array['status', 'decision_reason', 'documents_received', 'documents_required', 'updated_at'];
begin
  if auth.uid() is not null and old.owner_id is distinct from auth.uid() and is_officer()
     and (to_jsonb(new) - v_allowed) is distinct from (to_jsonb(old) - v_allowed) then
    raise exception 'A procurement officer can verify or reject a registration, but not change the company''s details.';
  end if;
  return new;
end;
$$;

drop trigger if exists suppliers_guard_officer_update on suppliers;
create trigger suppliers_guard_officer_update before update on suppliers
  for each row execute function guard_supplier_officer_update();

-- A tender is awarded to a supplier, and a contract starts only after the
-- supplier claims the award with its code: also when a tender row is edited
-- directly rather than through the functions above.
create or replace function guard_tender_award_status()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.status is distinct from old.status then
    if new.status = 'awarded' and new.awarded_supplier_id is null then
      raise exception 'A tender can only be awarded to a supplier. Use Award tender.';
    end if;
    if new.status = 'in_progress' and old.status = 'awarded'
       and not exists (select 1 from award_codes where tender_id = new.id and claimed_at is not null) then
      raise exception 'The supplier has not claimed this award yet. It moves to In progress by itself when the supplier enters the award code in TenderTrack.';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists tenders_guard_award_status on tenders;
create trigger tenders_guard_award_status before update of status on tenders
  for each row execute function guard_tender_award_status();

revoke all on function guard_supplier_officer_update() from public, anon, authenticated;
revoke all on function guard_tender_award_status()     from public, anon, authenticated;


-- ---------------------------------------------------------------------------
-- 13. Registering for TenderTrack as well as on the eTender portal
-- ---------------------------------------------------------------------------
-- The portal registration creates the company (the government's record); the
-- portal's server records it once the registration is complete (contact,
-- banking, required documents). A company created any other way (for example
-- the app's older sign-up) finishes its registration on the portal itself.
-- The company then registers for TenderTrack in the app with the same account,
-- proving it with its CSD and company registration numbers and a 6-digit code
-- emailed to the contact address held by the portal. portal_registered_at is
-- set only by the portal's server (secret key); app_registered_at only by
-- complete_tendertrack_registration(). Officers cannot change either (section 12).

create table if not exists tendertrack_registration_codes (
  supplier_id       uuid primary key references suppliers(id) on delete cascade,
  code_hash         text not null,
  expires_at        timestamptz not null,
  attempts          int not null default 0,
  sent_to           text not null default '',
  created_at        timestamptz not null default now(),
  sent_count        int not null default 1,
  window_started_at timestamptz not null default now()
);
alter table tendertrack_registration_codes enable row level security;
revoke all on tendertrack_registration_codes from anon, authenticated;
-- No policies on purpose: only the functions below read it.

/** Six random digits, with the same unbiased method as the award code. */
create or replace function new_verification_code()
returns text language plpgsql volatile set search_path = public, extensions as $$
declare
  v_code  text := '';
  v_bytes bytea;
  i       int;
begin
  while char_length(v_code) < 6 loop
    v_bytes := gen_random_bytes(12);
    for i in 0..11 loop
      if get_byte(v_bytes, i) < 250 and char_length(v_code) < 6 then
        v_code := v_code || (get_byte(v_bytes, i) % 10)::text;
      end if;
    end loop;
  end loop;
  return v_code;
end;
$$;

/** Stops TenderTrack actions (claiming, deliverables) until the company is registered on both. */
create or replace function require_tendertrack_registration()
returns void language plpgsql stable security definer set search_path = public as $$
declare
  v_supplier suppliers%rowtype;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid() limit 1;
  if v_supplier.id is null then
    raise exception 'Only a supplier account can do this.';
  end if;
  if v_supplier.portal_registered_at is null then
    raise exception 'Finish your company''s registration on the eTender portal first (sign in there with this email and password), then register for TenderTrack.';
  end if;
  if v_supplier.app_registered_at is null then
    raise exception 'Finish registering for TenderTrack first: Supplier login, then Register an account.';
  end if;
end;
$$;

/** Where the signed-in supplier stands. Safe to call before registering. */
create or replace function tendertrack_registration_status()
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_supplier suppliers%rowtype;
  v_code     tendertrack_registration_codes%rowtype;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid() limit 1;
  if v_supplier.id is null then
    return jsonb_build_object('has_company', false, 'portal_registered', false, 'app_registered', false);
  end if;
  select * into v_code from tendertrack_registration_codes where supplier_id = v_supplier.id;
  return jsonb_build_object(
    'has_company', true,
    'company_name', v_supplier.company_name,
    'csd_number', v_supplier.csd_number,
    'portal_registered', v_supplier.portal_registered_at is not null,
    'app_registered', v_supplier.app_registered_at is not null,
    'app_registered_at', v_supplier.app_registered_at,
    'code_pending', v_code.supplier_id is not null and v_code.expires_at > now() and v_code.attempts < 5,
    'sent_to', mask_email(coalesce(nullif(v_code.sent_to, ''), v_supplier.contact_email)));
end;
$$;

/**
 * Step 1 in the app: checks the details against the portal registration of
 * the signed-in account and emails a 6-digit code to its contact address.
 */
create or replace function start_tendertrack_registration(p_csd_number text, p_registration_number text)
returns jsonb language plpgsql security definer set search_path = public, extensions, auth as $$
declare
  v_supplier suppliers%rowtype;
  v_existing tendertrack_registration_codes%rowtype;
  v_code     text;
  v_to       text;
  v_expires  timestamptz := now() + interval '15 minutes';
  v_template text;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid() limit 1;
  if v_supplier.id is null then
    raise exception 'This account has no company. Register your company on the eTender portal first.';
  end if;
  if v_supplier.portal_registered_at is null then
    raise exception 'Your company''s registration on the eTender portal is not complete. Sign in on the portal with this email and password to finish it, then come back.';
  end if;
  if v_supplier.app_registered_at is not null then
    return jsonb_build_object('already', true, 'company_name', v_supplier.company_name);
  end if;
  if upper(trim(coalesce(p_csd_number, ''))) <> upper(v_supplier.csd_number)
     or trim(coalesce(p_registration_number, '')) <> v_supplier.registration_number then
    raise exception 'These numbers do not match the company registered on the eTender portal with this account.';
  end if;

  select * into v_existing from tendertrack_registration_codes where supplier_id = v_supplier.id for update;
  if v_existing.supplier_id is not null then
    if v_existing.created_at > now() - interval '60 seconds' then
      raise exception 'A code was sent less than a minute ago. Wait a minute before asking for another.';
    end if;
    if v_existing.window_started_at > now() - interval '24 hours' and v_existing.sent_count >= 5 then
      raise exception 'Too many codes today. Try again tomorrow, or contact the department.';
    end if;
  end if;

  select email into v_to from auth.users where id = v_supplier.owner_id;
  v_to := lower(coalesce(nullif(trim(v_supplier.contact_email), ''), v_to, ''));
  if v_to = '' then
    raise exception 'The company has no email address on the eTender portal.';
  end if;

  v_code := new_verification_code();
  insert into tendertrack_registration_codes (supplier_id, code_hash, expires_at, sent_to)
  values (v_supplier.id, crypt(v_code, gen_salt('bf', 8)), v_expires, v_to)
  on conflict (supplier_id) do update
     set code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0,
         sent_to = excluded.sent_to, created_at = now(),
         sent_count = case when tendertrack_registration_codes.window_started_at > now() - interval '24 hours'
                           then tendertrack_registration_codes.sent_count + 1 else 1 end,
         window_started_at = case when tendertrack_registration_codes.window_started_at > now() - interval '24 hours'
                                  then tendertrack_registration_codes.window_started_at else now() end;

  v_template :=
    'Someone is registering ' || v_supplier.company_name || ' (' || v_supplier.csd_number || ') for the ' ||
    'TenderTrack app.' || E'\n\n' ||
    'Your TenderTrack verification code: {CODE}' || E'\n\n' ||
    'Enter it in the app to finish registering. It expires at ' || to_char(sa_time(v_expires), 'HH24:MI') ||
    ' (15 minutes) and may be tried 5 times.' || E'\n\n' ||
    'If this was not you, ignore this email: nothing changes without the code. Do not share it.' || E'\n\n' ||
    '— eTender Demo Portal (a demonstration system for the TenderTrack student project, not a government website)';
  insert into email_outbox (to_email, to_name, subject, body, body_after_send, kind, to_user)
  values (v_to, coalesce(nullif(trim(v_supplier.contact_person), ''), v_supplier.company_name),
          'Your TenderTrack verification code',
          replace(v_template, '{CODE}', v_code),
          replace(v_template, '{CODE}', '•••••• (removed after sending)'),
          'verification', v_supplier.owner_id);

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier.id, 'TenderTrack registration started',
          v_supplier.company_name || ' · code sent to ' || mask_email(v_to), actor_name());

  return jsonb_build_object('already', false, 'sent_to', mask_email(v_to), 'expires_at', v_expires,
                            'company_name', v_supplier.company_name);
end;
$$;

/** Step 2 in the app: the code from the email finishes the registration. */
create or replace function complete_tendertrack_registration(p_code text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  v_supplier suppliers%rowtype;
  v_code     tendertrack_registration_codes%rowtype;
  v_clean    text := regexp_replace(coalesce(p_code, ''), '[^0-9]', '', 'g');
  v_left     int;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid() limit 1 for update;
  if v_supplier.id is null then
    raise exception 'This account has no company. Register your company on the eTender portal first.';
  end if;
  if v_supplier.app_registered_at is not null then
    return jsonb_build_object('ok', true, 'message', 'You are already registered for TenderTrack.', 'attempts_left', 0);
  end if;
  select * into v_code from tendertrack_registration_codes where supplier_id = v_supplier.id for update;
  if v_code.supplier_id is null or v_code.attempts >= 5 then
    return jsonb_build_object('ok', false, 'attempts_left', 0, 'need_new_code', true,
      'message', 'There is no valid code. Ask for a new one.');
  end if;
  if v_code.expires_at < now() then
    return jsonb_build_object('ok', false, 'attempts_left', 0, 'need_new_code', true,
      'message', 'That code has expired. Ask for a new one.');
  end if;
  if char_length(v_clean) <> 6 then
    return jsonb_build_object('ok', false, 'attempts_left', 5 - v_code.attempts,
      'message', 'Enter all 6 digits of the code from the email.');
  end if;
  if crypt(v_clean, v_code.code_hash) <> v_code.code_hash then
    update tendertrack_registration_codes set attempts = attempts + 1
     where supplier_id = v_supplier.id returning 5 - attempts into v_left;
    return jsonb_build_object('ok', false, 'attempts_left', v_left, 'need_new_code', v_left <= 0,
      'message', case when v_left <= 0 then 'That code is not right. Ask for a new code.'
                      else 'That code is not right. ' || v_left || case when v_left = 1 then ' attempt' else ' attempts' end || ' left.' end);
  end if;

  update suppliers set app_registered_at = now() where id = v_supplier.id;
  delete from tendertrack_registration_codes where supplier_id = v_supplier.id;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier.id, 'Registered for TenderTrack', v_supplier.company_name, actor_name());
  insert into notifications (user_id, kind, title, body)
  values (v_supplier.owner_id, 'registration', 'Registered for TenderTrack',
          v_supplier.company_name || ' is now registered on the eTender portal and in TenderTrack.');
  return jsonb_build_object('ok', true, 'attempts_left', 0,
    'message', v_supplier.company_name || ' is registered for TenderTrack.');
end;
$$;

revoke all on function new_verification_code()                     from public, anon, authenticated;
revoke all on function require_tendertrack_registration()           from public, anon, authenticated;
revoke all on function tendertrack_registration_status()            from public, anon;
revoke all on function start_tendertrack_registration(text, text)   from public, anon;
revoke all on function complete_tendertrack_registration(text)      from public, anon;
grant execute on function tendertrack_registration_status()         to authenticated;
grant execute on function start_tendertrack_registration(text, text) to authenticated;
grant execute on function complete_tendertrack_registration(text)   to authenticated;


-- ---------------------------------------------------------------------------
-- Done. Shows the tenders open for bids on the portal right now.
-- ---------------------------------------------------------------------------
select reference_number, title, to_char(sa_time(closing_date), 'DD Mon YYYY HH24:MI') as closes
from tenders
where status = 'published' and closing_date > now()
order by closing_date;
