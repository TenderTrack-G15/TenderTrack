-- ===========================================================================
-- TenderTrack — Auditor (record inspection)
--
-- Run in the Supabase SQL Editor after schema.sql and seed.sql. Safe to re-run.
--
-- The Auditor observes and verifies: tender records, bid submissions,
-- evaluation results, award decisions, audit logs, compliance and the history
-- of changes to a tender. The Auditor creates nothing, changes nothing and
-- deletes nothing, and that is enforced here rather than by hiding buttons.
--
-- What this adds
--   1. tenders.published_at, so a publication date exists.
--   2. bids — who submitted, when, and for how much.
--   3. evaluation_criteria, evaluation_scores, evaluation_results — the
--      criteria used, the scores given, the comments and the recommendation.
--   4. award_approvals — who approved an award, their position, when, and why.
--   5. tender_changes — every change to a tender, recorded automatically by a
--      trigger, which is what makes "tender history" trustworthy.
--   6. Demo records for the seeded tenders, so the screens have something real
--      to show.
--
-- Replaces the earlier auditor migration (flags and findings), whose objects
-- are dropped in section 0.
-- ===========================================================================

do $$
begin
  if to_regclass('public.tenders') is null then
    raise exception 'Run schema.sql first: tenders does not exist.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 0. Remove the earlier auditor migration, if it was run
-- ---------------------------------------------------------------------------
-- The flag-investigation version of the Auditor is not part of this design.
-- The flag-count fix it introduced is kept below, because it corrects a real
-- bug: resolving a flag never reduced tenders.open_flag_count.

drop function if exists record_audit_review(uuid, text, text);
drop function if exists raise_auditor_flag(uuid, text, text, text, text);
drop table if exists audit_reviews;
drop sequence if exists audit_review_seq;

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

update tenders t
   set open_flag_count = (
     select count(*) from compliance_flags f
      where f.tender_id = t.id and f.status <> 'resolved')
 where t.open_flag_count <> (
     select count(*) from compliance_flags f
      where f.tender_id = t.id and f.status <> 'resolved');


-- ---------------------------------------------------------------------------
-- 1. Publication date
-- ---------------------------------------------------------------------------

alter table tenders add column if not exists published_at timestamptz;

-- Anything that has reached publication already has a date: use the day it was
-- registered, which is the best record that exists for the seeded tenders.
update tenders
   set published_at = created_at
 where published_at is null and status <> 'registered';

create or replace function set_published_at()
returns trigger language plpgsql as $$
begin
  if new.status <> 'registered' and new.published_at is null then
    new.published_at := now();
  end if;
  return new;
end;
$$;

drop trigger if exists tenders_set_published_at on tenders;
create trigger tenders_set_published_at
before update on tenders
for each row execute function set_published_at();


-- ---------------------------------------------------------------------------
-- 2. Bid submissions
-- ---------------------------------------------------------------------------

do $$
begin
  if not exists (select 1 from pg_type where typname = 'bid_status') then
    create type bid_status as enum ('submitted', 'withdrawn', 'disqualified', 'shortlisted', 'awarded');
  end if;
end $$;

create table if not exists bids (
  id                  uuid primary key default gen_random_uuid(),
  reference           text not null unique,
  tender_id           uuid not null references tenders(id) on delete cascade,
  supplier_id         uuid references suppliers(id),
  supplier_name       text not null,
  bid_value           numeric(14,2) not null check (bid_value > 0),
  submitted_at        timestamptz not null,
  status              bid_status not null default 'submitted',
  documents_received  int not null default 0,
  documents_required  int not null default 4,
  disqualified_reason text
);

create index if not exists bids_tender_idx on bids (tender_id, submitted_at);


-- ---------------------------------------------------------------------------
-- 3. Evaluation: criteria, scores, result
-- ---------------------------------------------------------------------------

create table if not exists evaluation_criteria (
  id          uuid primary key default gen_random_uuid(),
  tender_id   uuid not null references tenders(id) on delete cascade,
  name        text not null,
  description text not null default '',
  weight      int not null check (weight between 1 and 100),
  sequence    int not null default 1
);

create table if not exists evaluation_scores (
  id           uuid primary key default gen_random_uuid(),
  bid_id       uuid not null references bids(id) on delete cascade,
  criterion_id uuid not null references evaluation_criteria(id) on delete cascade,
  score        numeric(5,2) not null check (score >= 0 and score <= 100),
  comment      text not null default '',
  unique (bid_id, criterion_id)
);

create table if not exists evaluation_results (
  id                     uuid primary key default gen_random_uuid(),
  tender_id              uuid not null unique references tenders(id) on delete cascade,
  committee              text not null,
  completed_at           timestamptz not null,
  recommended_bid_id     uuid references bids(id),
  recommendation_comment text not null default ''
);


-- ---------------------------------------------------------------------------
-- 4. Award decisions
-- ---------------------------------------------------------------------------

create table if not exists award_approvals (
  id                uuid primary key default gen_random_uuid(),
  tender_id         uuid not null unique references tenders(id) on delete cascade,
  approver_name     text not null,
  approver_position text not null,
  approved_at       timestamptz not null,
  approved_value    numeric(14,2),
  comments          text not null default ''
);


-- ---------------------------------------------------------------------------
-- 5. Tender history, recorded automatically
-- ---------------------------------------------------------------------------

create table if not exists tender_changes (
  id          uuid primary key default gen_random_uuid(),
  tender_id   uuid not null references tenders(id) on delete cascade,
  field_label text not null,
  old_value   text,
  new_value   text,
  changed_by  text not null,
  changed_at  timestamptz not null default now()
);

create index if not exists tender_changes_idx on tender_changes (tender_id, changed_at desc);

-- Writes one row per field that actually changed. Because it is a trigger, a
-- change cannot be made without the history recording it.
create or replace function record_tender_change()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_actor text := coalesce((select full_name from profiles where id = auth.uid()), 'System');
begin
  if new.closing_date is distinct from old.closing_date then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Closing date', to_char(old.closing_date, 'DD Mon YYYY'),
            to_char(new.closing_date, 'DD Mon YYYY'), v_actor);
  end if;
  if new.status is distinct from old.status then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Status', replace(old.status::text, '_', ' '), replace(new.status::text, '_', ' '), v_actor);
  end if;
  if new.estimated_budget is distinct from old.estimated_budget then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Estimated budget', to_char(old.estimated_budget, 'FM999G999G999D00'),
            to_char(new.estimated_budget, 'FM999G999G999D00'), v_actor);
  end if;
  if new.title is distinct from old.title then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Title', old.title, new.title, v_actor);
  end if;
  if new.awarded_supplier_name is distinct from old.awarded_supplier_name then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Awarded supplier', coalesce(old.awarded_supplier_name, '—'),
            coalesce(new.awarded_supplier_name, '—'), v_actor);
  end if;
  if new.awarded_value is distinct from old.awarded_value then
    insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by)
    values (new.id, 'Awarded value', to_char(old.awarded_value, 'FM999G999G999D00'),
            to_char(new.awarded_value, 'FM999G999G999D00'), v_actor);
  end if;
  return null;
end;
$$;

drop trigger if exists tenders_record_change on tenders;
create trigger tenders_record_change
after update on tenders
for each row execute function record_tender_change();


-- ---------------------------------------------------------------------------
-- 6. Who may read and write
-- ---------------------------------------------------------------------------
-- Auditors read everything here and write nothing: no policy grants them
-- insert, update or delete. Nobody may delete, which keeps FR3 true.

alter table bids                enable row level security;
alter table evaluation_criteria enable row level security;
alter table evaluation_scores   enable row level security;
alter table evaluation_results  enable row level security;
alter table award_approvals     enable row level security;
alter table tender_changes      enable row level security;

grant select on bids, evaluation_criteria, evaluation_scores, evaluation_results,
                award_approvals, tender_changes to authenticated;
revoke all on bids, evaluation_criteria, evaluation_scores, evaluation_results,
              award_approvals, tender_changes from anon;

do $$
declare
  t text;
begin
  foreach t in array array['bids', 'evaluation_criteria', 'evaluation_scores',
                           'evaluation_results', 'award_approvals', 'tender_changes']
  loop
    execute format('drop policy if exists %I_read on %I', t, t);
    execute format(
      'create policy %I_read on %I for select to authenticated using (is_officer() or is_oversight())', t, t);

    -- Officers may add records (the future bidding and evaluation screens);
    -- auditors may not, and nobody may delete.
    execute format('drop policy if exists %I_officer_write on %I', t, t);
    execute format(
      'create policy %I_officer_write on %I for insert to authenticated with check (is_officer())', t, t);
    execute format('drop policy if exists %I_officer_update on %I', t, t);
    execute format(
      'create policy %I_officer_update on %I for update to authenticated using (is_officer()) with check (is_officer())', t, t);
  end loop;
end $$;

-- The history is written by the trigger alone: not even an officer may edit it.
drop policy if exists tender_changes_officer_write on tender_changes;
drop policy if exists tender_changes_officer_update on tender_changes;


-- ---------------------------------------------------------------------------
-- 7. Demo records for the seeded tenders
-- ---------------------------------------------------------------------------
-- Only runs when there are no bids yet, so re-running this file never
-- duplicates anything.

do $$
declare
  v_gp_it   uuid := (select id from tenders where reference_number = 'GP/IT/2290');
  v_nat     uuid := (select id from tenders where reference_number = 'NAT/SEC/492');
  v_cld     uuid := (select id from tenders where reference_number = 'GP/CLD/1187');
  v_infra   uuid := (select id from suppliers where company_name = 'Infratech Solutions (Pty) Ltd');
  v_vuka    uuid := (select id from suppliers where company_name = 'Vuka IT Consulting');
  v_siya    uuid := (select id from suppliers where company_name = 'Siyakhula Technologies');
  v_letha   uuid := (select id from suppliers where company_name = 'Lethabo Digital CC');
  v_bid_a   uuid;
  v_bid_b   uuid;
  v_bid_c   uuid;
  v_price   uuid;
  v_tech    uuid;
  v_bbbee   uuid;
begin
  if exists (select 1 from bids) then return; end if;

  -- GP/IT/2290 — awarded, so it has bids, an evaluation and an approval.
  insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                    documents_received, documents_required)
  values ('BID-2290-01', v_gp_it, v_infra, 'Infratech Solutions (Pty) Ltd', 17950000.00,
          '2026-06-28 10:14:00+02', 'awarded', 4, 4)
  returning id into v_bid_a;

  insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                    documents_received, documents_required)
  values ('BID-2290-02', v_gp_it, v_vuka, 'Vuka IT Consulting', 19200000.00,
          '2026-06-29 15:42:00+02', 'shortlisted', 4, 4)
  returning id into v_bid_b;

  insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                    documents_received, documents_required, disqualified_reason)
  values ('BID-2290-03', v_gp_it, v_letha, 'Lethabo Digital CC', 15100000.00,
          '2026-06-30 16:55:00+02', 'disqualified', 2, 4,
          'Tax clearance certificate and B-BBEE affidavit not submitted.')
  returning id into v_bid_c;

  insert into evaluation_criteria (tender_id, name, description, weight, sequence)
  values (v_gp_it, 'Price', 'Scored against the lowest acceptable bid (PPPFA 80/20).', 80, 1)
  returning id into v_price;
  insert into evaluation_criteria (tender_id, name, description, weight, sequence)
  values (v_gp_it, 'Technical capability', 'Team, method and comparable past projects.', 15, 2)
  returning id into v_tech;
  insert into evaluation_criteria (tender_id, name, description, weight, sequence)
  values (v_gp_it, 'B-BBEE', 'Specific goals claimed and verified.', 5, 3)
  returning id into v_bbbee;

  insert into evaluation_scores (bid_id, criterion_id, score, comment) values
    (v_bid_a, v_price, 92.00, 'Within 2% of the lowest compliant bid.'),
    (v_bid_a, v_tech,  88.00, 'Three comparable provincial rollouts evidenced.'),
    (v_bid_a, v_bbbee, 100.00, 'Level 2 contributor, verified certificate.'),
    (v_bid_b, v_price, 78.00, 'Higher than the lowest compliant bid.'),
    (v_bid_b, v_tech,  84.00, 'Strong method, fewer comparable projects.'),
    (v_bid_b, v_bbbee, 80.00, 'Level 4 contributor.');

  insert into evaluation_results (tender_id, committee, completed_at, recommended_bid_id, recommendation_comment)
  values (v_gp_it, 'Bid Evaluation Committee — Gauteng Dept of e-Government',
          '2026-08-05 14:30:00+02', v_bid_a,
          'Infratech scored highest overall after the disqualification of Lethabo Digital for incomplete documents.');

  insert into award_approvals (tender_id, approver_name, approver_position, approved_at, approved_value, comments)
  values (v_gp_it, 'M. Dlamini', 'Chief Financial Officer', '2026-08-12 08:15:00+02', 17950000.00,
          'Recommendation accepted. Award within the approved budget and the delegated authority.');

  -- NAT/SEC/492 — under evaluation, so bids and criteria but no result yet.
  insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                    documents_received, documents_required)
  values ('BID-0492-01', v_nat, v_siya, 'Siyakhula Technologies', 4610000.00,
          '2026-08-01 11:05:00+02', 'submitted', 4, 4),
         ('BID-0492-02', v_nat, v_vuka, 'Vuka IT Consulting', 4880000.00,
          '2026-08-02 09:48:00+02', 'submitted', 3, 4);

  insert into evaluation_criteria (tender_id, name, description, weight, sequence) values
    (v_nat, 'Price', 'Scored against the lowest acceptable bid (PPPFA 80/20).', 80, 1),
    (v_nat, 'Security accreditation', 'Valid accreditation for classified environments.', 20, 2);

  -- GP/CLD/1187 — awarded and in progress: one bid, an approval, and a change
  -- to its closing date, which is what the history screen shows.
  insert into bids (reference, tender_id, supplier_id, supplier_name, bid_value, submitted_at, status,
                    documents_received, documents_required)
  values ('BID-1187-01', v_cld, v_siya, 'Siyakhula Technologies', 9450000.00,
          '2026-05-14 13:20:00+02', 'awarded', 4, 4);

  insert into award_approvals (tender_id, approver_name, approver_position, approved_at, approved_value, comments)
  values (v_cld, 'P. Naicker', 'Head of Supply Chain Management', '2026-06-30 10:40:00+02', 9450000.00,
          'Single compliant bid received. Market price tested against the transversal contract.');

  insert into tender_changes (tender_id, field_label, old_value, new_value, changed_by, changed_at) values
    (v_cld, 'Closing date', '08 May 2026', '15 May 2026', 'T. Mokoena', '2026-05-02 09:15:00+02'),
    (v_cld, 'Status', 'published', 'under evaluation', 'T. Mokoena', '2026-05-16 08:05:00+02'),
    (v_cld, 'Status', 'under evaluation', 'awarded', 'T. Mokoena', '2026-06-30 10:45:00+02'),
    (v_cld, 'Status', 'awarded', 'in progress', 'T. Mokoena', '2026-07-02 07:50:00+02');
end $$;


-- Done. Should list the six new tables with their demo rows.
select 'bids' as record_type, count(*) from bids
union all select 'evaluation_criteria', count(*) from evaluation_criteria
union all select 'evaluation_scores', count(*) from evaluation_scores
union all select 'evaluation_results', count(*) from evaluation_results
union all select 'award_approvals', count(*) from award_approvals
union all select 'tender_changes', count(*) from tender_changes
order by 1;
