-- ===========================================================================
-- TenderTrack — Estimated budget withheld until award (design option 2)
--
-- Run in the Supabase SQL Editor AFTER public_access.sql. Safe to re-run.
--
-- Decision
--   A department's estimated budget is kept from the public and from
--   suppliers while a tender is open for bids or under evaluation, so bids
--   reflect real cost rather than clustering just under the budget. From
--   'awarded' onwards it is published next to the awarded value, so anyone
--   can compare the estimate with what was actually paid.
--
-- Why this needs the database, not just the app
--   Row-level security decides WHICH ROWS a user may read, never which
--   columns. The anon key ships inside the app, so while the public could read
--   the tenders table, anyone could request estimated_budget directly and
--   bypass whatever the screens hide. Instead:
--     * the public (and later suppliers) read the view tenders_public, which
--       returns NULL for the estimate until the tender is awarded;
--     * the tenders table itself is readable only by staff roles.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. The public view
-- ---------------------------------------------------------------------------
-- Same columns as tenders (minus created_by), so the app can read it the same
-- way. Unpublished ('registered') tenders are excluded here as well.
--
-- security_invoker = off: the view reads the table with its owner's rights,
-- which is what lets the public see these rows once the table itself is
-- closed to them. Supabase's Security Advisor will flag this as a "security
-- definer view" — that is intentional here, and the view only ever exposes
-- the columns listed below.

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
    created_at
  from tenders
  where status <> 'registered';

grant select on tenders_public to anon, authenticated;


-- ---------------------------------------------------------------------------
-- 2. Close the tenders table to everyone except staff
-- ---------------------------------------------------------------------------
-- Replaces tenders_public_read from schema.sql, which let anyone read every
-- column of every published tender.
--
-- Staff who need the real estimate on open tenders keep direct access:
-- procurement officers and administrators (is_officer), auditors
-- (is_oversight), the finance officer and the evaluation committee.
--
-- Suppliers are the bidders the estimate is withheld from, so they read
-- tenders_public for browsing. The one exception is a tender AWARDED to their
-- own company: by then the estimate is public anyway, and the deliverables
-- policy in schema.sql checks this table when a supplier uploads evidence for
-- their contract (FR5) — without this clause that check would always fail.

drop policy if exists tenders_public_read   on tenders;
drop policy if exists tenders_internal_read on tenders;

create policy tenders_internal_read on tenders
  for select to authenticated
  using (
    is_officer()
    or is_oversight()
    or auth_role() in ('finance_officer', 'evaluation_committee')
    or exists (
      select 1 from suppliers s
      where s.id = tenders.awarded_supplier_id
        and s.owner_id = auth.uid()
    )
  );


-- ---------------------------------------------------------------------------
-- Done. Quick check: should return 'open tenders with a visible estimate: 0'.
-- ---------------------------------------------------------------------------
select 'open tenders with a visible estimate: ' || count(*) as check_result
from tenders_public
where status in ('published', 'under_evaluation')
  and estimated_budget is not null;
