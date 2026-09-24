-- ===========================================================================
-- TenderTrack — Supplier module
--
-- Run in the Supabase SQL Editor after schema.sql, seed.sql and
-- auditor_records.sql. Safe to re-run. It needs neither the administrator nor
-- the earlier supplier_accounts.sql migration.
--
-- What this adds
--   1. The full supplier profile: company, contact, compliance, capabilities.
--   2. supplier_banking — kept confidential: only the supplier and a finance
--      officer may read it, and the account number is masked for oversight.
--   3. supplier_documents — the supporting documents and their status.
--   4. Everything a tender detail page needs: scope of work, important dates,
--      eligibility, documents, submission information, evaluation weights,
--      query contact, and tender updates (clarifications, Q&A, amendments,
--      deadline extensions).
--   5. Supplier sign-up: the company profile travels with the new login, so
--      the registration exists whether or not email confirmation is on.
--   6. Demo records for the seeded tenders, so the screens show something.
-- ===========================================================================

do $$
begin
  if to_regclass('public.tenders') is null then
    raise exception 'Run schema.sql first.';
  end if;
  if to_regclass('public.tender_changes') is null then
    raise exception 'Run auditor_records.sql first: this migration builds on it.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 1. The supplier profile
-- ---------------------------------------------------------------------------

alter table suppliers add column if not exists tax_number               text not null default '';
alter table suppliers add column if not exists vat_number               text not null default '';
alter table suppliers add column if not exists year_established         int;
alter table suppliers add column if not exists contact_person           text not null default '';
alter table suppliers add column if not exists job_title                text not null default '';
alter table suppliers add column if not exists phone_number             text not null default '';
alter table suppliers add column if not exists mobile_number            text not null default '';
alter table suppliers add column if not exists postal_address           text not null default '';
alter table suppliers add column if not exists province                 text not null default '';
alter table suppliers add column if not exists tax_clearance_status     text not null default 'not_submitted';
alter table suppliers add column if not exists bbbee_expiry             date;
alter table suppliers add column if not exists industry_licences        text not null default '';
alter table suppliers add column if not exists professional_registrations text not null default '';
alter table suppliers add column if not exists categories              text not null default '';
alter table suppliers add column if not exists industry                text not null default '';
alter table suppliers add column if not exists expertise               text not null default '';
alter table suppliers add column if not exists geographic_areas        text not null default '';
alter table suppliers add column if not exists company_profile         text not null default '';
alter table suppliers add column if not exists employees               int;
alter table suppliers add column if not exists updated_at              timestamptz not null default now();


-- ---------------------------------------------------------------------------
-- 2. Banking details — confidential
-- ---------------------------------------------------------------------------
-- Used only after a contract is awarded. The supplier keeps its own details;
-- a finance officer may read them to pay; nobody else may, not even a
-- procurement officer or an auditor.

create table if not exists supplier_banking (
  supplier_id    uuid primary key references suppliers(id) on delete cascade,
  bank_name      text not null,
  account_name   text not null,
  account_number text not null,
  branch_code    text not null,
  proof_url      text,
  updated_at     timestamptz not null default now()
);

alter table supplier_banking enable row level security;
grant select on supplier_banking to authenticated;
revoke all on supplier_banking from anon;

drop policy if exists banking_owner_read on supplier_banking;
create policy banking_owner_read on supplier_banking
  for select to authenticated
  using (
    exists (select 1 from suppliers s where s.id = supplier_id and s.owner_id = auth.uid())
    or auth_role() = 'finance_officer'
  );

-- What oversight may see instead: everything except the account number.
create or replace view supplier_banking_masked as
select b.supplier_id,
       s.company_name,
       b.bank_name,
       b.account_name,
       'xxxx' || right(b.account_number, 4) as account_number_masked,
       b.branch_code,
       b.updated_at
from supplier_banking b
join suppliers s on s.id = b.supplier_id;

grant select on supplier_banking_masked to authenticated;
revoke all on supplier_banking_masked from anon;


-- ---------------------------------------------------------------------------
-- 3. Supporting documents
-- ---------------------------------------------------------------------------

do $$
begin
  if not exists (select 1 from pg_type where typname = 'document_status') then
    create type document_status as enum ('not_submitted', 'submitted', 'verified', 'expired');
  end if;
end $$;

create table if not exists supplier_documents (
  id           uuid primary key default gen_random_uuid(),
  supplier_id  uuid not null references suppliers(id) on delete cascade,
  name         text not null,
  status       document_status not null default 'not_submitted',
  reference    text not null default '',
  expires_at   date,
  file_url     text,
  updated_at   timestamptz not null default now(),
  unique (supplier_id, name)
);

alter table supplier_documents enable row level security;
grant select on supplier_documents to authenticated;
revoke all on supplier_documents from anon;

drop policy if exists supplier_documents_read on supplier_documents;
create policy supplier_documents_read on supplier_documents
  for select to authenticated
  using (
    exists (select 1 from suppliers s where s.id = supplier_id and s.owner_id = auth.uid())
    or is_officer() or is_oversight()
  );

/** The six documents every registration needs. */
create or replace function default_supplier_documents(p_supplier uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into supplier_documents (supplier_id, name)
  values (p_supplier, 'Company registration certificate (CIPC)'),
         (p_supplier, 'Tax clearance certificate / PIN'),
         (p_supplier, 'B-BBEE certificate or affidavit'),
         (p_supplier, 'Proof of business address'),
         (p_supplier, 'Company profile'),
         (p_supplier, 'Proof of banking details')
  on conflict (supplier_id, name) do nothing;
end;
$$;


-- ---------------------------------------------------------------------------
-- 4. Tender detail, as a bidder needs to see it
-- ---------------------------------------------------------------------------

create table if not exists tender_details (
  tender_id             uuid primary key references tenders(id) on delete cascade,
  scope_overview        text not null default '',
  deliverables          text not null default '',
  technical_specs       text not null default '',
  quantity_requirements text not null default '',
  expected_outcomes     text not null default '',
  briefing_at           timestamptz,
  briefing_venue        text not null default '',
  briefing_compulsory   boolean not null default false,
  site_visit_at         timestamptz,
  expected_award_date   date,
  submission_method     text not null default '',
  submission_format     text not null default '',
  required_documents    text not null default '',
  technical_weight      int,
  financial_weight      int,
  preference_points     text not null default '',
  query_contact_name    text not null default '',
  query_contact_email   text not null default '',
  query_contact_phone   text not null default ''
);

create table if not exists tender_eligibility (
  id          uuid primary key default gen_random_uuid(),
  tender_id   uuid not null references tenders(id) on delete cascade,
  requirement text not null,
  mandatory   boolean not null default true,
  sequence    int not null default 1
);

create table if not exists tender_documents (
  id         uuid primary key default gen_random_uuid(),
  tender_id  uuid not null references tenders(id) on delete cascade,
  name       text not null,
  doc_type   text not null default '',
  file_url   text,
  updated_at timestamptz not null default now()
);

do $$
begin
  if not exists (select 1 from pg_type where typname = 'tender_update_kind') then
    create type tender_update_kind as enum ('clarification', 'question', 'amendment', 'extension');
  end if;
end $$;

create table if not exists tender_updates (
  id           uuid primary key default gen_random_uuid(),
  tender_id    uuid not null references tenders(id) on delete cascade,
  kind         tender_update_kind not null,
  title        text not null,
  body         text not null default '',
  published_at timestamptz not null default now()
);

create index if not exists tender_updates_idx on tender_updates (tender_id, published_at desc);

-- What a bidder browses. A view runs with its owner's rights, so a supplier
-- can read published tenders through it without being given access to the
-- tenders table itself. The estimate stays hidden until award, exactly as
-- public_budget.sql already does for the public.
create or replace view tender_board as
select t.id,
       t.reference_number,
       t.title,
       t.description,
       t.department,
       t.category,
       t.status,
       case when t.status in ('awarded', 'in_progress', 'completed') then t.estimated_budget end
         as estimated_budget,
       t.published_at,
       t.closing_date,
       t.contract_period_months,
       t.awarded_supplier_name,
       t.awarded_value,
       t.awarded_at,
       t.open_flag_count
from tenders t
where t.status <> 'registered';

grant select on tender_board to authenticated;
revoke all on tender_board from anon;

-- Used by the policies below. A plain subquery on tenders would be filtered by
-- that table's own row security, which a supplier does not pass, so the pack
-- would look empty to the very people it is meant for.
create or replace function tender_is_open_to_bidders(p_tender uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from tenders t where t.id = p_tender and t.status <> 'registered');
$$;

revoke all on function tender_is_open_to_bidders(uuid) from public, anon;
grant execute on function tender_is_open_to_bidders(uuid) to authenticated;

-- Bidders and the public may read all of this for a tender that has been
-- published; officers and oversight may read it at any stage. Only officers
-- write, and nobody deletes.
do $$
declare
  t text;
begin
  foreach t in array array['tender_details', 'tender_eligibility', 'tender_documents', 'tender_updates']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('grant select on %I to authenticated', t);
    execute format('revoke all on %I from anon', t);

    execute format('drop policy if exists %I_read on %I', t, t);
    execute format($f$
      create policy %I_read on %I for select to authenticated
      using (is_officer() or is_oversight() or tender_is_open_to_bidders(tender_id))$f$, t, t);

    execute format('drop policy if exists %I_officer_write on %I', t, t);
    execute format(
      'create policy %I_officer_write on %I for insert to authenticated with check (is_officer())', t, t);
    execute format('drop policy if exists %I_officer_update on %I', t, t);
    execute format(
      'create policy %I_officer_update on %I for update to authenticated using (is_officer()) with check (is_officer())', t, t);
  end loop;
end $$;


-- ---------------------------------------------------------------------------
-- 5. A supplier maintains its own profile
-- ---------------------------------------------------------------------------
-- Through functions rather than a table policy, so a supplier can change its
-- own details but never its verification status, its CSD number or its owner.

create or replace function require_supplier()
returns uuid language plpgsql stable security definer set search_path = public as $$
declare
  v_supplier uuid;
begin
  select id into v_supplier from suppliers where owner_id = auth.uid();
  if v_supplier is null then
    raise exception 'No supplier registration belongs to this account.';
  end if;
  return v_supplier;
end;
$$;

create or replace function save_supplier_profile(
  p_company_name text, p_registration_number text, p_tax_number text, p_vat_number text,
  p_business_type text, p_year_established int,
  p_contact_person text, p_job_title text, p_contact_email text, p_phone_number text,
  p_mobile_number text, p_physical_address text, p_postal_address text, p_province text,
  p_industry_licences text, p_professional_registrations text,
  p_categories text, p_industry text, p_expertise text, p_geographic_areas text,
  p_company_profile text, p_employees int
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier uuid := require_supplier();
begin
  if char_length(trim(coalesce(p_company_name, ''))) < 2 then
    raise exception 'Enter the company name.';
  end if;
  if coalesce(p_registration_number, '') !~ '^[0-9]{4}/[0-9]{6}/[0-9]{2}$' then
    raise exception 'Company registration number must look like 2019/451236/07.';
  end if;
  if coalesce(p_contact_email, '') !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
    raise exception 'Enter a valid contact email address.';
  end if;
  if coalesce(p_mobile_number, '') !~ '^(\+27|0)[6-8][0-9]{8}$' then
    raise exception 'Mobile number must be a South African mobile, e.g. 0821234567.';
  end if;
  if p_year_established is not null and p_year_established not between 1800 and extract(year from now())::int then
    raise exception 'Check the year the company was established.';
  end if;
  if p_employees is not null and p_employees < 0 then
    raise exception 'Number of employees cannot be negative.';
  end if;

  update suppliers
     set company_name = trim(p_company_name),
         registration_number = trim(p_registration_number),
         tax_number = trim(coalesce(p_tax_number, '')),
         vat_number = trim(coalesce(p_vat_number, '')),
         business_type = trim(coalesce(p_business_type, '')),
         year_established = p_year_established,
         contact_person = trim(coalesce(p_contact_person, '')),
         job_title = trim(coalesce(p_job_title, '')),
         contact_email = lower(trim(p_contact_email)),
         phone_number = trim(coalesce(p_phone_number, '')),
         mobile_number = trim(coalesce(p_mobile_number, '')),
         physical_address = trim(coalesce(p_physical_address, '')),
         postal_address = trim(coalesce(p_postal_address, '')),
         province = coalesce(p_province, ''),
         industry_licences = trim(coalesce(p_industry_licences, '')),
         professional_registrations = trim(coalesce(p_professional_registrations, '')),
         categories = trim(coalesce(p_categories, '')),
         industry = trim(coalesce(p_industry, '')),
         expertise = trim(coalesce(p_expertise, '')),
         geographic_areas = trim(coalesce(p_geographic_areas, '')),
         company_profile = trim(coalesce(p_company_profile, '')),
         employees = p_employees,
         updated_at = now()
   where id = v_supplier;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier, 'Company profile updated', trim(p_company_name),
          coalesce((select full_name from profiles where id = auth.uid()), 'Supplier'));

  return (select to_jsonb(s) from suppliers s where s.id = v_supplier);
end;
$$;

create or replace function save_supplier_banking(
  p_bank_name text, p_account_name text, p_account_number text, p_branch_code text, p_proof_url text default null
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier uuid := require_supplier();
begin
  if char_length(trim(coalesce(p_bank_name, ''))) < 2 then
    raise exception 'Enter the bank name.';
  end if;
  if char_length(trim(coalesce(p_account_name, ''))) < 2 then
    raise exception 'Enter the name the account is held in.';
  end if;
  if coalesce(p_account_number, '') !~ '^[0-9]{6,17}$' then
    raise exception 'Account number must be 6 to 17 digits.';
  end if;
  if coalesce(p_branch_code, '') !~ '^[0-9]{6}$' then
    raise exception 'Branch code must be 6 digits.';
  end if;

  insert into supplier_banking (supplier_id, bank_name, account_name, account_number, branch_code, proof_url)
  values (v_supplier, trim(p_bank_name), trim(p_account_name), trim(p_account_number),
          trim(p_branch_code), nullif(trim(coalesce(p_proof_url, '')), ''))
  on conflict (supplier_id) do update
    set bank_name = excluded.bank_name, account_name = excluded.account_name,
        account_number = excluded.account_number, branch_code = excluded.branch_code,
        proof_url = excluded.proof_url, updated_at = now();

  -- The audit trail records that banking details changed, never what they are.
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier, 'Banking details updated', trim(p_bank_name),
          coalesce((select full_name from profiles where id = auth.uid()), 'Supplier'));

  return jsonb_build_object('saved', true);
end;
$$;

/** Records that a supporting document has been provided, and where it is. */
create or replace function save_supplier_document(
  p_name text, p_reference text, p_expires_at date, p_file_url text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier uuid := require_supplier();
  v_status   document_status;
begin
  if char_length(trim(coalesce(p_name, ''))) < 2 then
    raise exception 'Choose which document this is.';
  end if;

  v_status := case
    when p_expires_at is not null and p_expires_at < current_date then 'expired'
    else 'submitted'
  end;

  insert into supplier_documents (supplier_id, name, status, reference, expires_at, file_url, updated_at)
  values (v_supplier, trim(p_name), v_status, trim(coalesce(p_reference, '')), p_expires_at,
          nullif(trim(coalesce(p_file_url, '')), ''), now())
  on conflict (supplier_id, name) do update
    set status = excluded.status, reference = excluded.reference, expires_at = excluded.expires_at,
        file_url = excluded.file_url, updated_at = now();

  update suppliers
     set documents_received = (
       select count(*) from supplier_documents d
        where d.supplier_id = v_supplier and d.status in ('submitted', 'verified'))
   where id = v_supplier;

  return (select to_jsonb(d) from supplier_documents d
           where d.supplier_id = v_supplier and d.name = trim(p_name));
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. Supplier sign-up
-- ---------------------------------------------------------------------------
-- The company profile travels with the sign-up, so the registration exists
-- even when Supabase is set to confirm email addresses first (in which case
-- there is no signed-in session straight afterwards).

create or replace function supplier_registration_problem(
  p_registration_number text, p_csd_number text, p_exclude_id uuid default null
) returns text language sql stable security definer set search_path = public as $$
  select case
    when exists (select 1 from suppliers
                 where upper(csd_number) = upper(trim(p_csd_number))
                   and id is distinct from p_exclude_id)
      then 'This CSD number is already registered on TenderTrack.'
    when exists (select 1 from suppliers
                 where registration_number = trim(p_registration_number)
                   and id is distinct from p_exclude_id)
      then 'This company registration number is already registered on TenderTrack.'
  end;
$$;

create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_meta     jsonb := coalesce(new.raw_user_meta_data, '{}'::jsonb);
  v_invite   record;
  v_supplier uuid;
  v_problem  text;
begin
  -- A staff invitation wins, if the administrator module is installed.
  if to_regclass('public.staff_invitations') is not null then
    execute 'select id, full_name, role, department from staff_invitations
              where lower(email) = lower($1) and accepted_at is null'
      into v_invite using new.email;

    if v_invite.id is not null then
      insert into profiles (id, email, full_name, role, department)
      values (new.id, new.email, v_invite.full_name, v_invite.role, v_invite.department);
      execute 'update staff_invitations set accepted_at = now(), accepted_user = $1, updated_at = now()
                where id = $2' using new.id, v_invite.id;
      insert into audit_trail (entity_type, entity_id, action, detail, actor)
      values ('account', new.id, 'Invitation accepted',
              new.email || ' joined as ' || v_invite.role::text, 'System');
      return new;
    end if;
  end if;

  -- A supplier signing up sends its company details with the sign-up.
  if v_meta->>'account_type' = 'supplier' then
    if upper(coalesce(v_meta->>'csd_number', '')) !~ '^MAAA[0-9]{7}$' then
      raise exception 'CSD number must look like MAAA0451236.';
    end if;
    if coalesce(v_meta->>'registration_number', '') !~ '^[0-9]{4}/[0-9]{6}/[0-9]{2}$' then
      raise exception 'Company registration number must look like 2019/451236/07.';
    end if;

    v_problem := supplier_registration_problem(v_meta->>'registration_number', v_meta->>'csd_number');
    if v_problem is not null then
      raise exception '%', v_problem;
    end if;

    insert into profiles (id, email, full_name, role)
    -- The sign-up screen may send either key name; accept both.
    values (new.id, new.email,
            trim(coalesce(nullif(v_meta->>'contact_person', ''), nullif(v_meta->>'representative', ''), new.email)),
            'supplier');

    insert into suppliers (owner_id, company_name, registration_number, csd_number, business_type,
                           contact_email, contact_person, job_title, mobile_number, province,
                           bbbee_level, tax_number)
    values (new.id, trim(v_meta->>'company_name'), trim(v_meta->>'registration_number'),
            upper(trim(v_meta->>'csd_number')), trim(coalesce(v_meta->>'business_type', '')),
            new.email, trim(coalesce(nullif(v_meta->>'contact_person', ''), v_meta->>'representative', '')),
            trim(coalesce(v_meta->>'job_title', '')), trim(coalesce(v_meta->>'mobile_number', '')),
            coalesce(v_meta->>'province', ''), nullif(v_meta->>'bbbee_level', '')::int,
            trim(coalesce(nullif(v_meta->>'tax_number', ''), v_meta->>'tax_pin', '')))
    returning id into v_supplier;

    perform default_supplier_documents(v_supplier);

    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('supplier', v_supplier, 'Supplier registered',
            trim(v_meta->>'company_name') || ' (' || upper(trim(v_meta->>'csd_number')) || ')',
            trim(coalesce(nullif(v_meta->>'contact_person', ''), v_meta->>'representative', 'Supplier')));

    insert into notifications (user_id, kind, title, body)
    values (null, 'registration', 'New supplier registration',
            trim(v_meta->>'company_name') || ' is awaiting verification.');
    return new;
  end if;

  insert into profiles (id, email, full_name, role)
  values (new.id, new.email, coalesce(v_meta->>'full_name', new.email), 'supplier');
  return new;
end;
$$;


/**
 * Sends a corrected registration back for review (Deliverable 3, section 5.3:
 * "Registration not approved" offers a route to correct and resubmit).
 */
create or replace function resubmit_supplier_registration()
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier uuid := require_supplier();
  v_status   supplier_status;
  v_name     text;
begin
  select status, company_name into v_status, v_name from suppliers where id = v_supplier;
  if v_status <> 'not_approved' then
    raise exception 'Only a registration that was not approved can be resubmitted.';
  end if;

  update suppliers
     set status = 'awaiting_verification', submitted_at = now(), updated_at = now()
   where id = v_supplier;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier, 'Registration resubmitted', v_name,
          coalesce((select full_name from profiles where id = auth.uid()), 'Supplier'));

  insert into notifications (user_id, kind, title, body)
  values (null, 'registration', 'Supplier registration resubmitted',
          v_name || ' corrected its registration and is awaiting verification.');

  return (select to_jsonb(s) from suppliers s where s.id = v_supplier);
end;
$$;


-- ---------------------------------------------------------------------------
-- 7. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function require_supplier()                     from public, anon, authenticated;
revoke all on function default_supplier_documents(uuid)       from public, anon, authenticated;

grant execute on function supplier_registration_problem(text, text, uuid) to anon, authenticated;

revoke all on function save_supplier_profile(text, text, text, text, text, int, text, text, text, text,
  text, text, text, text, text, text, text, text, text, text, text, int) from public, anon;
grant execute on function save_supplier_profile(text, text, text, text, text, int, text, text, text, text,
  text, text, text, text, text, text, text, text, text, text, text, int) to authenticated;

revoke all on function save_supplier_banking(text, text, text, text, text) from public, anon;
grant execute on function save_supplier_banking(text, text, text, text, text) to authenticated;

revoke all on function save_supplier_document(text, text, date, text) from public, anon;
grant execute on function save_supplier_document(text, text, date, text) to authenticated;

revoke all on function resubmit_supplier_registration() from public, anon;
grant execute on function resubmit_supplier_registration() to authenticated;


-- ---------------------------------------------------------------------------
-- 8. Demo records, so the screens have something to show
-- ---------------------------------------------------------------------------

do $$
declare
  v_kzn uuid := (select id from tenders where reference_number = 'KZN/HW/9923');
  v_wc  uuid := (select id from tenders where reference_number = 'WC/SF/1042');
  v_sup uuid;
begin
  if exists (select 1 from tender_details) then return; end if;

  insert into tender_details (tender_id, scope_overview, deliverables, technical_specs,
      quantity_requirements, expected_outcomes, briefing_at, briefing_venue, briefing_compulsory,
      site_visit_at, expected_award_date, submission_method, submission_format, required_documents,
      technical_weight, financial_weight, preference_points,
      query_contact_name, query_contact_email, query_contact_phone)
  values (
    v_kzn,
    'Supply, delivery and installation of networking hardware for 42 district health facilities in KwaZulu-Natal.',
    E'Switches, routers and wireless access points delivered to each site\nInstallation and commissioning at all 42 facilities\nTwelve months of on-site support\nAs-built documentation and asset register',
    E'Layer-3 managed switches with PoE+\nWi-Fi 6 access points\nFive-year hardware warranty\nSANS 10142 compliant installation',
    '42 sites · approximately 620 switch ports and 180 access points',
    'Every facility connected to the provincial health network with 99.5% availability.',
    '2026-09-18 10:00:00+02', 'KZN Department of Health, Natalia Building, Pietermaritzburg', true,
    '2026-09-25 09:00:00+02', '2026-11-15',
    'Sealed bid, delivered by hand to the tender box at the address in the bid document.',
    'One original and two copies, each in a separate sealed envelope, clearly marked.',
    E'Completed pricing schedule (SBD 3.1)\nTax compliance PIN\nB-BBEE certificate or sworn affidavit\nCSD registration summary\nCompany registration certificate',
    20, 80, '80/20 preference point system (PPPFA)',
    'N. Zulu', 'tenders@kznhealth.gov.za', '033 395 2000'),
   (v_wc,
    'Development and support of a citizen-facing service request portal for the Western Cape.',
    E'Discovery and design phase\nPortal build with accessibility compliance\nIntegration with the existing case management system\nTwo years of maintenance and support',
    E'WCAG 2.1 AA accessibility\nPOPIA-compliant data handling\nHosting within South Africa\nSource code handed over on completion',
    'One portal, approximately 40 000 users a month',
    'Citizens able to log and track service requests without visiting an office.',
    null, '', false,
    null, '2026-11-20',
    'Electronic submission through the provincial eTender portal.',
    'Single PDF, maximum 20 MB, with the pricing schedule in a separate file.',
    E'Completed pricing schedule\nTax compliance PIN\nB-BBEE certificate or sworn affidavit\nTwo reference letters',
    30, 70, '80/20 preference point system (PPPFA)',
    'A. Petersen', 'supplychain@westerncape.gov.za', '021 483 0000');

  insert into tender_eligibility (tender_id, requirement, mandatory, sequence) values
    (v_kzn, 'Registered on the Central Supplier Database (CSD)', true, 1),
    (v_kzn, 'Valid tax compliance status with SARS', true, 2),
    (v_kzn, 'B-BBEE certificate or sworn affidavit', false, 3),
    (v_kzn, 'At least three comparable installations in the past five years', true, 4),
    (v_kzn, 'Manufacturer certification for the equipment offered', true, 5),
    (v_wc, 'Registered on the Central Supplier Database (CSD)', true, 1),
    (v_wc, 'Valid tax compliance status with SARS', true, 2),
    (v_wc, 'At least two government portal projects delivered', true, 3),
    (v_wc, 'B-BBEE certificate or sworn affidavit', false, 4);

  insert into tender_documents (tender_id, name, doc_type) values
    (v_kzn, 'Request for Proposal (RFP)', 'RFP'),
    (v_kzn, 'Terms of Reference', 'TOR'),
    (v_kzn, 'Technical specifications', 'Specification'),
    (v_kzn, 'Pricing schedule (SBD 3.1)', 'Pricing'),
    (v_kzn, 'Standard bidding forms (SBD 1, 4, 6.1)', 'Forms'),
    (v_kzn, 'Draft contract (SLA)', 'Contract'),
    (v_wc, 'Request for Proposal (RFP)', 'RFP'),
    (v_wc, 'Terms of Reference', 'TOR'),
    (v_wc, 'Pricing schedule', 'Pricing'),
    (v_wc, 'Draft contract', 'Contract');

  insert into tender_updates (tender_id, kind, title, body, published_at) values
    (v_kzn, 'clarification', 'Briefing session venue confirmed',
     'The compulsory briefing will be held in the Natalia Building auditorium. Bring proof of CSD registration.',
     '2026-09-10 08:00:00+02'),
    (v_kzn, 'question', 'Q: May access points be supplied by a different manufacturer to the switches?',
     'A: Yes, provided both carry current manufacturer certification and are covered by a single support agreement.',
     '2026-09-15 14:20:00+02'),
    (v_kzn, 'extension', 'Closing date extended by one week',
     'Following requests at the briefing session, the closing date moved from 27 September to 4 October 2026.',
     '2026-09-19 16:00:00+02'),
    (v_wc, 'amendment', 'Pricing schedule reissued',
     'The pricing schedule has been reissued with the maintenance years split out. Use version 2 only.',
     '2026-08-28 11:30:00+02');

  -- Fill in the demo supplier's profile, banking and documents.
  select id into v_sup from suppliers where company_name = 'Infratech Solutions (Pty) Ltd';
  if v_sup is not null then
    update suppliers
       set tax_number = '9123456789', vat_number = '4123456789', year_established = 2011,
           contact_person = 'N. Pillay', job_title = 'Business Development Manager',
           phone_number = '011 234 5600', mobile_number = '0825551234',
           postal_address = 'PO Box 4412, Rivonia, 2128', province = 'Gauteng',
           tax_clearance_status = 'valid', industry = 'Information and communications technology',
           categories = 'Network infrastructure; End-user computing; Managed services',
           expertise = 'Enterprise networking, structured cabling, service desk operations',
           geographic_areas = 'Gauteng, Limpopo, Mpumalanga, North West',
           company_profile = 'Infratech Solutions has delivered network infrastructure to provincial ' ||
             'departments since 2011, with a permanent technical team of 48 and offices in Johannesburg and Polokwane.',
           employees = 48, industry_licences = 'ECSA-registered project engineer on staff',
           professional_registrations = 'IITPSA corporate member', updated_at = now()
     where id = v_sup;

    perform default_supplier_documents(v_sup);
    update supplier_documents set status = 'verified', reference = 'CIPC 2011/004512/07',
           updated_at = now()
     where supplier_id = v_sup and name = 'Company registration certificate (CIPC)';
    update supplier_documents set status = 'verified', reference = 'TCS PIN 7X2K9QL4', expires_at = '2027-03-31'
     where supplier_id = v_sup and name = 'Tax clearance certificate / PIN';
    update supplier_documents set status = 'submitted', reference = 'Level 2 affidavit', expires_at = '2027-01-31'
     where supplier_id = v_sup and name = 'B-BBEE certificate or affidavit';

    insert into supplier_banking (supplier_id, bank_name, account_name, account_number, branch_code)
    values (v_sup, 'First National Bank', 'Infratech Solutions (Pty) Ltd', '62345678901', '250655')
    on conflict (supplier_id) do nothing;

    update suppliers
       set documents_received = (select count(*) from supplier_documents d
                                  where d.supplier_id = v_sup and d.status in ('submitted', 'verified'))
     where id = v_sup;
  end if;
end $$;


-- Done.
select 'tender_details' as record_type, count(*) from tender_details
union all select 'tender_eligibility', count(*) from tender_eligibility
union all select 'tender_documents', count(*) from tender_documents
union all select 'tender_updates', count(*) from tender_updates
union all select 'supplier_documents', count(*) from supplier_documents
union all select 'supplier_banking', count(*) from supplier_banking
order by 1;
