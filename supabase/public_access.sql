-- ===========================================================================
-- TenderTrack — Public / citizen access
--
-- Run this in the Supabase SQL Editor AFTER schema.sql and seed.sql.
-- It does not drop or recreate anything you already have. Safe to re-run.
--
-- What it adds
--   FR13  Public search             — nothing new needed: anonymous users can
--                                      already read every tender that is not
--                                      'registered' (policy tenders_public_read).
--   FR14  Public dashboard          — reads tenders, deliverables and the
--                                      payments_public view, all already public.
--   FR15  Flag a tender for review  — NEW: citizen_reports table plus four
--                                      functions a signed-out user can call.
--   POPIA                           — citizen text is kept away from anonymous
--                                      readers, contact details are optional, and
--                                      a citizen can erase them by withdrawing.
--
-- Why functions instead of letting the app insert rows directly
--   A citizen has no account, so row-level security cannot tell one member of
--   the public from another. Every write therefore goes through a SECURITY
--   DEFINER function that validates the input, rate-limits it, and writes to
--   several tables in one transaction (report, compliance flag, notification,
--   audit trail). The anonymous role is never given INSERT or UPDATE on any
--   table.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. Stop anonymous users reading compliance flag text
-- ---------------------------------------------------------------------------
-- schema.sql let anyone read every column of compliance_flags. Once citizens
-- can submit free text, that text lands in compliance_flags.description, and a
-- citizen may well include a name or phone number in it. The public app only
-- needs the COUNT of open flags per tender, which it already gets from
-- tenders.open_flag_count, so the full rows are restricted to the roles that
-- review them (FR15: "the flag can be reviewed by authorised users").

drop policy if exists flags_read on compliance_flags;
drop policy if exists flags_read_internal on compliance_flags;

create policy flags_read_internal on compliance_flags
  for select to authenticated
  using (is_officer() or is_oversight());


-- ---------------------------------------------------------------------------
-- 2. Vocabulary
-- ---------------------------------------------------------------------------

do $$ begin
  create type citizen_report_category as enum (
    'irregular_award',       -- the wrong company won, or the process looked rigged
    'non_delivery',          -- paid for but not delivered
    'overpricing',           -- the price looks far above market
    'conflict_of_interest',  -- an official is linked to the winning company
    'other'
  );
exception when duplicate_object then null; end $$;


-- ---------------------------------------------------------------------------
-- 3. Citizen reports
-- ---------------------------------------------------------------------------
-- One row per report. The matching compliance flag is what officers actually
-- work on; this table holds what only the citizen and the reviewers should see.
--
-- The reference is the citizen's only key to their report (there is no
-- account), so it is random rather than sequential and cannot be guessed.

create table if not exists citizen_reports (
  id             uuid primary key default gen_random_uuid(),
  reference      text not null unique,
  tender_id      uuid not null references tenders(id) on delete cascade,
  flag_id        uuid references compliance_flags(id) on delete set null,
  category       citizen_report_category not null,
  details        text not null check (char_length(details) between 20 and 1000),
  -- Optional. POPIA: collect only what is necessary.
  contact_email  text check (contact_email is null
                            or contact_email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
  additions      int not null default 0,
  submitted_at   timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  withdrawn_at   timestamptz
);

create index if not exists citizen_reports_tender_idx on citizen_reports (tender_id, submitted_at desc);

alter table citizen_reports enable row level security;

-- Reviewers can read reports. There is deliberately NO policy for anon:
-- signed-out users reach this table only through the functions below.
drop policy if exists citizen_reports_internal_read on citizen_reports;
create policy citizen_reports_internal_read on citizen_reports
  for select to authenticated
  using (is_officer() or is_oversight());


-- ---------------------------------------------------------------------------
-- 4. Helpers
-- ---------------------------------------------------------------------------

-- CR-XXXXX-XXXXX from an alphabet without look-alike characters (no 0/O, 1/I),
-- using cryptographically random bytes. 32^10 ≈ 10^15 possible references.
create or replace function new_citizen_reference()
returns text language plpgsql volatile
  -- "extensions" is required: gen_random_bytes lives there on Supabase.
  set search_path = public, extensions as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  bytes bytea;
  ref   text;
begin
  loop
    bytes := gen_random_bytes(10);
    ref := 'CR-';
    for i in 0..9 loop
      ref := ref || substr(alphabet, 1 + (get_byte(bytes, i) % 32), 1);
      if i = 4 then ref := ref || '-'; end if;
    end loop;
    exit when not exists (select 1 from citizen_reports where reference = ref);
  end loop;
  return ref;
end;
$$;

-- Plain-language label used in flag titles and notifications.
create or replace function citizen_category_label(p citizen_report_category)
returns text language sql immutable as $$
  select case p
    when 'irregular_award'      then 'Irregular award'
    when 'non_delivery'         then 'Paid but not delivered'
    when 'overpricing'          then 'Overpricing'
    when 'conflict_of_interest' then 'Conflict of interest'
    else 'Other concern'
  end;
$$;

-- What a citizen is allowed to see about their own report. Never returns the
-- contact email itself, only whether one is held.
create or replace function citizen_report_view(p_reference text)
returns jsonb language sql stable security definer
  set search_path = public as $$
  select coalesce((
    select jsonb_build_object(
      'found',            true,
      'reference',        r.reference,
      'tender_id',        t.id,
      'tender_reference', t.reference_number,
      'tender_title',     t.title,
      'category',         r.category,
      'details',          r.details,
      'has_contact',      r.contact_email is not null,
      'additions',        r.additions,
      'status', case
                  when r.withdrawn_at is not null            then 'withdrawn'
                  when f.status = 'resolved'                 then 'closed'
                  when f.status = 'under_investigation'      then 'under_review'
                  else 'received'
                end,
      'outcome',          case when f.status = 'resolved' then f.outcome::text end,
      'submitted_at',     r.submitted_at,
      'updated_at',       r.updated_at
    )
    from citizen_reports r
    join tenders t on t.id = r.tender_id
    left join compliance_flags f on f.id = r.flag_id
    where r.reference = upper(trim(p_reference))
  ), jsonb_build_object('found', false));
$$;


-- ---------------------------------------------------------------------------
-- 5. CREATE — submit a report (FR15)
-- ---------------------------------------------------------------------------

create or replace function submit_citizen_report(
  p_tender_id     uuid,
  p_category      text,
  p_details       text,
  p_contact_email text default null
) returns jsonb language plpgsql security definer
  set search_path = public, extensions as $$
declare
  v_tender    tenders%rowtype;
  v_category  citizen_report_category;
  v_details   text := trim(coalesce(p_details, ''));
  v_contact   text := nullif(lower(trim(coalesce(p_contact_email, ''))), '');
  v_reference text;
  v_flag_id   uuid;
  v_flag_ref  text;
  v_severity  flag_severity;
begin
  -- The tender must exist and be public. A 'registered' tender has not been
  -- published, so the public has no business reporting on it.
  select * into v_tender from tenders where id = p_tender_id;
  if not found or v_tender.status = 'registered' then
    raise exception 'This tender is not open to public review.';
  end if;

  begin
    v_category := p_category::citizen_report_category;
  exception when invalid_text_representation then
    raise exception 'Choose what kind of concern this is.';
  end;

  if char_length(v_details) < 20 then
    raise exception 'Describe the concern in at least 20 characters.';
  end if;
  if char_length(v_details) > 1000 then
    raise exception 'Keep the description under 1000 characters.';
  end if;

  if v_contact is not null
     and v_contact !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
    raise exception 'Enter a valid email address, or leave it blank.';
  end if;

  -- Basic abuse protection. Without accounts there is no per-person limit,
  -- so limit per tender and refuse exact repeats.
  if (select count(*) from citizen_reports
      where tender_id = p_tender_id and submitted_at > now() - interval '1 hour') >= 20 then
    raise exception 'This tender has received many reports in the last hour. Please try again later.';
  end if;
  if exists (select 1 from citizen_reports
             where tender_id = p_tender_id
               and lower(details) = lower(v_details)
               and submitted_at > now() - interval '24 hours') then
    raise exception 'An identical report on this tender was already received today.';
  end if;

  -- Unverified public reports start lower than automatic rule breaches,
  -- except the two categories that point at corruption directly.
  v_severity := case v_category
                  when 'irregular_award'      then 'medium'
                  when 'conflict_of_interest' then 'medium'
                  else 'low'
                end;

  v_flag_ref := 'FLG-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('flag_seq')::text, 4, '0');

  -- The compliance flag is what appears on the officer's Flags screen.
  insert into compliance_flags (reference, tender_id, tender_reference, tender_title,
                                title, description, rule_triggered, severity,
                                status, raised_automatically)
  values (v_flag_ref, v_tender.id, v_tender.reference_number, v_tender.title,
          'Citizen report: ' || citizen_category_label(v_category),
          v_details,
          'Public report (FR15)',
          v_severity, 'open', false)
  returning id into v_flag_id;

  update tenders set open_flag_count = open_flag_count + 1 where id = v_tender.id;

  v_reference := new_citizen_reference();

  insert into citizen_reports (reference, tender_id, flag_id, category, details, contact_email)
  values (v_reference, v_tender.id, v_flag_id, v_category, v_details, v_contact);

  -- user_id null = shown to every procurement officer (see notifications_own).
  insert into notifications (user_id, kind, title, body)
  values (null, 'flag',
          'Citizen report on ' || v_tender.reference_number,
          citizen_category_label(v_category) || ' reported by a member of the public. See ' || v_flag_ref || '.');

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', v_tender.id, 'Citizen report received',
          v_flag_ref || ' · ' || citizen_category_label(v_category), 'Member of the public');

  return citizen_report_view(v_reference);
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. READ — look a report up by its reference
-- ---------------------------------------------------------------------------

create or replace function citizen_report_status(p_reference text)
returns jsonb language sql stable security definer
  set search_path = public as $$
  select citizen_report_view(p_reference);
$$;


-- ---------------------------------------------------------------------------
-- 7. UPDATE — add more information to an open report
-- ---------------------------------------------------------------------------
-- The original description is never overwritten (FR3: nothing changes without
-- trace). New information is added as a note on the compliance flag, which the
-- officer already sees on the Flag Detail screen.

create or replace function add_citizen_report_information(
  p_reference   text,
  p_information text
) returns jsonb language plpgsql security definer
  set search_path = public as $$
declare
  v_report citizen_reports%rowtype;
  v_status text;
  v_info   text := trim(coalesce(p_information, ''));
begin
  select * into v_report from citizen_reports where reference = upper(trim(p_reference));
  if not found then
    raise exception 'No report was found with that reference.';
  end if;

  v_status := citizen_report_view(v_report.reference) ->> 'status';
  if v_status = 'withdrawn' then
    raise exception 'This report was withdrawn and can no longer be changed.';
  end if;
  if v_status = 'closed' then
    raise exception 'This report has been closed by the reviewers and can no longer be changed.';
  end if;

  if char_length(v_info) < 10 then
    raise exception 'Add at least 10 characters of new information.';
  end if;
  if char_length(v_info) > 1000 then
    raise exception 'Keep the new information under 1000 characters.';
  end if;
  if v_report.additions >= 5 then
    raise exception 'This report already has the maximum of 5 additions.';
  end if;

  if v_report.flag_id is not null then
    insert into flag_notes (flag_id, author, text)
    values (v_report.flag_id, 'Member of the public', 'Additional information: ' || v_info);
  end if;

  update citizen_reports
     set additions = additions + 1, updated_at = now()
   where id = v_report.id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('tender', v_report.tender_id, 'Citizen report updated',
          'More information added to ' || v_report.reference, 'Member of the public');

  return citizen_report_view(v_report.reference);
end;
$$;


-- ---------------------------------------------------------------------------
-- 8. DELETE — withdraw a report and erase personal information (POPIA)
-- ---------------------------------------------------------------------------
-- The flag itself is NOT deleted: FR3 forbids removing records without trace,
-- and the officer decides whether a withdrawn concern still needs review. What
-- IS removed is the only personal information held — the contact email.

create or replace function withdraw_citizen_report(p_reference text)
returns jsonb language plpgsql security definer
  set search_path = public as $$
declare
  v_report citizen_reports%rowtype;
begin
  select * into v_report from citizen_reports where reference = upper(trim(p_reference));
  if not found then
    raise exception 'No report was found with that reference.';
  end if;

  if v_report.withdrawn_at is null then
    update citizen_reports
       set withdrawn_at = now(), updated_at = now(), contact_email = null
     where id = v_report.id;

    if v_report.flag_id is not null then
      insert into flag_notes (flag_id, author, text)
      values (v_report.flag_id, 'Member of the public',
              'The member of the public withdrew this report. Any contact details they gave have been erased.');
    end if;

    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('tender', v_report.tender_id, 'Citizen report withdrawn',
            v_report.reference || ' withdrawn; contact details erased', 'Member of the public');
  end if;

  return citizen_report_view(v_report.reference);
end;
$$;


-- ---------------------------------------------------------------------------
-- 9. Who may call what
-- ---------------------------------------------------------------------------
-- The four citizen functions are open to signed-out users. The helpers are not.

revoke all on function new_citizen_reference()                       from public, anon, authenticated;
revoke all on function citizen_report_view(text)                      from public, anon, authenticated;

grant execute on function submit_citizen_report(uuid, text, text, text) to anon, authenticated;
grant execute on function citizen_report_status(text)                   to anon, authenticated;
grant execute on function add_citizen_report_information(text, text)    to anon, authenticated;
grant execute on function withdraw_citizen_report(text)                 to anon, authenticated;


-- ---------------------------------------------------------------------------
-- Done. Quick check: should list the four citizen functions.
-- ---------------------------------------------------------------------------
select proname as citizen_function
from pg_proc
where proname in ('submit_citizen_report', 'citizen_report_status',
                  'add_citizen_report_information', 'withdraw_citizen_report')
order by proname;
