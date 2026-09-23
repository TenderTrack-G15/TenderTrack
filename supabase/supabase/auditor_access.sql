-- ===========================================================================
-- TenderTrack — Auditor role
--
-- Run in the Supabase SQL Editor after schema.sql and seed.sql. Safe to re-run.
--
-- Deliverable 3, section 5.6: the Auditor gets an overview of open flags by
-- severity and age, value under review and delivery slippage; a filterable
-- list of every flag with the option to raise one manually; and a flag
-- investigation screen that records findings and an outcome of cleared,
-- corrective action or escalated. Read-only across every other record.
--
-- What this adds
--   1. audit_reviews — the AuditReview entity from the Deliverable 3 ERD
--      (review, contract, auditor, date, findings, status).
--   2. record_audit_review() — writes the finding, moves the flag on, adds a
--      note the officer sees, and records it in the audit trail.
--   3. raise_auditor_flag() — the manual flag D3 asks for.
--   4. A fix for open_flag_count, which until now only ever went up.
-- ===========================================================================

-- This migration stands on its own: it needs only schema.sql (and its seed).
-- It does not depend on the administrator or supplier migrations.
do $$
begin
  if to_regclass('public.compliance_flags') is null then
    raise exception 'Run schema.sql first: compliance_flags does not exist.';
  end if;
  if to_regclass('public.audit_trail') is null then
    raise exception 'Run schema.sql first: audit_trail does not exist.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 1. Keep tenders.open_flag_count honest
-- ---------------------------------------------------------------------------
-- Raising a flag adds one to the count, but resolving one never took it away,
-- so the number on the public dashboard and the officer's screens could only
-- grow. The Auditor closes flags all day, so this is fixed here: whenever a
-- flag's status changes or a flag is removed, the tender's count is worked out
-- again from the flags themselves.

create or replace function sync_open_flag_count()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_tender uuid := coalesce(new.tender_id, old.tender_id);
begin
  update tenders t
     set open_flag_count = (
       select count(*) from compliance_flags f
        where f.tender_id = t.id and f.status <> 'resolved')
   where t.id = v_tender;
  return null;
end;
$$;

drop trigger if exists compliance_flags_count on compliance_flags;
create trigger compliance_flags_count
after update of status or delete on compliance_flags
for each row execute function sync_open_flag_count();

-- One-off correction of any drift already recorded.
update tenders t
   set open_flag_count = (
     select count(*) from compliance_flags f
      where f.tender_id = t.id and f.status <> 'resolved')
 where t.open_flag_count <> (
     select count(*) from compliance_flags f
      where f.tender_id = t.id and f.status <> 'resolved');


-- ---------------------------------------------------------------------------
-- 2. AuditReview (Deliverable 3 ERD)
-- ---------------------------------------------------------------------------

create sequence if not exists audit_review_seq start 100;

create table if not exists audit_reviews (
  id           uuid primary key default gen_random_uuid(),
  reference    text not null unique,
  flag_id      uuid not null references compliance_flags(id) on delete cascade,
  tender_id    uuid not null references tenders(id) on delete cascade,
  auditor_id   uuid references auth.users on delete set null,
  auditor_name text not null,
  review_date  date not null default current_date,
  findings     text not null,
  outcome      flag_outcome not null,
  created_at   timestamptz not null default now()
);

create index if not exists audit_reviews_flag_idx on audit_reviews (flag_id, created_at desc);

alter table audit_reviews enable row level security;

-- Officers and oversight read reviews; nobody writes directly. Every review is
-- created by record_audit_review() below, so none can be written without the
-- matching flag update and audit entry.
grant select on audit_reviews to authenticated;
revoke all on audit_reviews from anon;

drop policy if exists audit_reviews_read on audit_reviews;
create policy audit_reviews_read on audit_reviews
  for select to authenticated
  using (is_officer() or is_oversight());


-- ---------------------------------------------------------------------------
-- 3. Helpers
-- ---------------------------------------------------------------------------

create or replace function require_auditor()
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if auth_role() is distinct from 'auditor' then
    raise exception 'Only an auditor can do that.';
  end if;
end;
$$;

create or replace function new_flag_reference()
returns text language sql volatile set search_path = public as $$
  select 'FLG-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('flag_seq')::text, 4, '0');
$$;


-- ---------------------------------------------------------------------------
-- 4. Raise a flag by hand (D3: "an option to raise a flag manually")
-- ---------------------------------------------------------------------------

create or replace function raise_auditor_flag(
  p_tender_id uuid, p_title text, p_description text, p_rule text, p_severity text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender   tenders%rowtype;
  v_severity flag_severity;
  v_actor    text;
  v_flag     uuid;
begin
  perform require_auditor();

  select * into v_tender from tenders where id = p_tender_id;
  if not found then raise exception 'That tender no longer exists.'; end if;

  if char_length(trim(coalesce(p_title, ''))) < 5 then
    raise exception 'Give the flag a title of at least 5 characters.';
  end if;
  if char_length(trim(coalesce(p_description, ''))) < 15 then
    raise exception 'Describe the concern in at least 15 characters.';
  end if;
  if char_length(trim(coalesce(p_rule, ''))) < 3 then
    raise exception 'Name the rule or standard the concern relates to.';
  end if;

  begin
    v_severity := p_severity::flag_severity;
  exception when invalid_text_representation then
    raise exception 'Severity must be high, medium or low.';
  end;

  select full_name into v_actor from profiles where id = auth.uid();

  insert into compliance_flags (reference, tender_id, tender_reference, tender_title,
                                title, description, rule_triggered, severity,
                                status, raised_automatically, assigned_to)
  values (new_flag_reference(), v_tender.id, v_tender.reference_number, v_tender.title,
          trim(p_title), trim(p_description), trim(p_rule), v_severity,
          'open', false, coalesce(v_actor, 'Auditor'))
  returning id into v_flag;

  update tenders set open_flag_count = open_flag_count + 1 where id = p_tender_id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('flag', v_flag, 'Flag raised by auditor',
          v_tender.reference_number || ' · ' || trim(p_title), coalesce(v_actor, 'Auditor'));

  -- user_id null = shown to every procurement officer (notifications_own).
  insert into notifications (user_id, kind, title, body)
  values (null, 'flag', 'Auditor raised a flag on ' || v_tender.reference_number, trim(p_title));

  return (select to_jsonb(f) from compliance_flags f where f.id = v_flag);
end;
$$;


-- ---------------------------------------------------------------------------
-- 5. Record findings and an outcome (D3 "Flag Investigation")
-- ---------------------------------------------------------------------------
-- cleared           → the flag is closed
-- corrective_action → stays open for investigation; the officer must act
-- escalated         → stays open for investigation, raised to high severity

create or replace function record_audit_review(
  p_flag_id uuid, p_findings text, p_outcome text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_flag    compliance_flags%rowtype;
  v_outcome flag_outcome;
  v_actor   text;
  v_review  uuid;
  v_ref     text;
begin
  perform require_auditor();

  select * into v_flag from compliance_flags where id = p_flag_id;
  if not found then raise exception 'That flag no longer exists.'; end if;
  if v_flag.status = 'resolved' then
    raise exception 'This flag has already been closed.';
  end if;
  if char_length(trim(coalesce(p_findings, ''))) < 15 then
    raise exception 'Record your findings in at least 15 characters.';
  end if;

  begin
    v_outcome := p_outcome::flag_outcome;
  exception when invalid_text_representation then
    raise exception 'Outcome must be cleared, corrective_action or escalated.';
  end;

  select full_name into v_actor from profiles where id = auth.uid();
  v_ref := 'AR-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('audit_review_seq')::text, 4, '0');

  insert into audit_reviews (reference, flag_id, tender_id, auditor_id, auditor_name, findings, outcome)
  values (v_ref, v_flag.id, v_flag.tender_id, auth.uid(), coalesce(v_actor, 'Auditor'),
          trim(p_findings), v_outcome)
  returning id into v_review;

  update compliance_flags
     set outcome = v_outcome,
         -- the CASE result is text, so it must be cast to the enum types
         status = (case when v_outcome = 'cleared' then 'resolved'
                        else 'under_investigation' end)::flag_status,
         severity = (case when v_outcome = 'escalated' then 'high'
                          else severity::text end)::flag_severity,
         assigned_to = coalesce(assigned_to, v_actor)
   where id = v_flag.id;

  -- The officer's existing Flag Detail screen reads these notes.
  insert into flag_notes (flag_id, author, text)
  values (v_flag.id, coalesce(v_actor, 'Auditor'),
          'Audit review ' || v_ref || ' (' || replace(v_outcome::text, '_', ' ') || '): ' || trim(p_findings));

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('flag', v_flag.id, 'Audit review recorded',
          v_flag.reference || ' · ' || replace(v_outcome::text, '_', ' '), coalesce(v_actor, 'Auditor'));

  insert into notifications (user_id, kind, title, body)
  values (null, 'flag',
          'Audit review on ' || v_flag.tender_reference,
          v_flag.title || ' — ' || replace(v_outcome::text, '_', ' ') || '.');

  return (select to_jsonb(r) from audit_reviews r where r.id = v_review);
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function require_auditor()      from public, anon, authenticated;
revoke all on function new_flag_reference()   from public, anon, authenticated;
revoke all on function sync_open_flag_count() from public, anon, authenticated;

revoke all on function raise_auditor_flag(uuid, text, text, text, text) from public, anon;
revoke all on function record_audit_review(uuid, text, text)            from public, anon;

grant execute on function raise_auditor_flag(uuid, text, text, text, text) to authenticated;
grant execute on function record_audit_review(uuid, text, text)            to authenticated;


-- Done. Should list both auditor functions and the review table.
select proname as auditor_function
from pg_proc
where proname in ('raise_auditor_flag', 'record_audit_review')
order by proname;
