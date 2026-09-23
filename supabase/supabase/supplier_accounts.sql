-- ===========================================================================
-- TenderTrack — supplier sign-up
--
-- Run in the Supabase SQL Editor AFTER admin_access.sql. Safe to re-run.
--
-- Deliverable 3, section 5.3: a supplier creates an account (email, password,
-- mobile number) and a company profile (company name, registration number, CSD
-- number, tax compliance PIN, B-BBEE level, business type, province,
-- authorised representative). The Supplier role is applied automatically.
--
-- How it works
--   The app sends the company profile along with the sign-up. When Supabase
--   creates the login, the handle_new_user() trigger creates the profile AND
--   the supplier record, status "awaiting_verification". That lands in the
--   officers' existing Supplier Registrations queue, with a notification.
--
--   Doing it in the trigger means it works whether or not "Confirm email" is
--   switched on in Supabase: with confirmation on, there is no signed-in
--   session straight after sign-up, so the app could not insert the record
--   itself.
--
-- Also adds
--   * supplier_registration_problem(): lets the sign-up form check for a
--     duplicate registration or CSD number before submitting.
--   * resubmit_supplier_registration(): a supplier whose registration was not
--     approved corrects it and sends it back for review (Deliverable 3,
--     "Registration not approved").
-- ===========================================================================

do $$
begin
  if to_regclass('public.staff_invitations') is null then
    raise exception 'Run admin_access.sql first: this migration keeps its staff-invitation behaviour.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 1. The four company-profile fields the suppliers table did not have
-- ---------------------------------------------------------------------------

alter table suppliers add column if not exists mobile_number  text not null default '';
alter table suppliers add column if not exists tax_pin        text not null default '';
alter table suppliers add column if not exists province       text not null default '';
alter table suppliers add column if not exists representative text not null default '';


-- ---------------------------------------------------------------------------
-- 2. Validation — the same rules as the app's sign-up form
-- ---------------------------------------------------------------------------

create or replace function validate_supplier_profile(
  p_company_name text, p_registration_number text, p_csd_number text, p_tax_pin text,
  p_bbbee_level int, p_business_type text, p_province text, p_representative text,
  p_mobile_number text
) returns void language plpgsql immutable as $$
begin
  if char_length(trim(coalesce(p_company_name, ''))) < 2 then
    raise exception 'Enter the company name.';
  end if;
  if coalesce(p_registration_number, '') !~ '^[0-9]{4}/[0-9]{6}/[0-9]{2}$' then
    raise exception 'Company registration number must look like 2019/451236/07.';
  end if;
  if upper(coalesce(p_csd_number, '')) !~ '^MAAA[0-9]{7}$' then
    raise exception 'CSD number must look like MAAA0451236.';
  end if;
  if coalesce(p_tax_pin, '') !~ '^[A-Za-z0-9]{6,20}$' then
    raise exception 'Tax compliance PIN must be 6 to 20 letters or digits.';
  end if;
  if p_bbbee_level is not null and p_bbbee_level not between 1 and 8 then
    raise exception 'B-BBEE level must be between 1 and 8.';
  end if;
  if char_length(trim(coalesce(p_business_type, ''))) = 0 then
    raise exception 'Choose a business type.';
  end if;
  if coalesce(p_province, '') not in ('Eastern Cape', 'Free State', 'Gauteng', 'KwaZulu-Natal',
      'Limpopo', 'Mpumalanga', 'Northern Cape', 'North West', 'Western Cape') then
    raise exception 'Choose a province.';
  end if;
  if char_length(trim(coalesce(p_representative, ''))) < 2 then
    raise exception 'Enter the authorised representative''s name.';
  end if;
  if coalesce(p_mobile_number, '') !~ '^(\+27|0)[6-8][0-9]{8}$' then
    raise exception 'Mobile number must be a South African mobile, e.g. 0821234567.';
  end if;
end;
$$;

-- Returns a message if the registration or CSD number is already registered,
-- or null if both are free. p_exclude_id skips the supplier's own record when
-- resubmitting.
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


-- ---------------------------------------------------------------------------
-- 3. New logins: staff invitation, supplier registration, or plain account
-- ---------------------------------------------------------------------------

create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_invite   staff_invitations%rowtype;
  v_meta     jsonb := coalesce(new.raw_user_meta_data, '{}'::jsonb);
  v_level    int;
  v_problem  text;
  v_supplier uuid;
begin
  -- a) A staff invitation always wins (admin_access.sql behaviour, unchanged).
  select * into v_invite
  from staff_invitations
  where lower(email) = lower(new.email) and accepted_at is null;

  if found then
    insert into profiles (id, email, full_name, role, department)
    values (new.id, new.email, v_invite.full_name, v_invite.role, v_invite.department);

    update staff_invitations
       set accepted_at = now(), accepted_user = new.id, updated_at = now()
     where id = v_invite.id;

    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('account', new.id, 'Invitation accepted',
            new.email || ' joined as ' || v_invite.role::text, 'System');
    return new;
  end if;

  -- b) A supplier signing up in the app sends its company profile with it.
  if v_meta->>'account_type' = 'supplier' then
    v_level := nullif(v_meta->>'bbbee_level', '')::int;

    perform validate_supplier_profile(
      v_meta->>'company_name', v_meta->>'registration_number', v_meta->>'csd_number',
      v_meta->>'tax_pin', v_level, v_meta->>'business_type', v_meta->>'province',
      v_meta->>'representative', v_meta->>'mobile_number');

    v_problem := supplier_registration_problem(v_meta->>'registration_number', v_meta->>'csd_number');
    if v_problem is not null then
      raise exception '%', v_problem;
    end if;

    insert into profiles (id, email, full_name, role)
    values (new.id, new.email, trim(v_meta->>'representative'), 'supplier');

    insert into suppliers (owner_id, company_name, registration_number, csd_number, bbbee_level,
                           business_type, contact_email, mobile_number, tax_pin, province, representative)
    values (new.id, trim(v_meta->>'company_name'), trim(v_meta->>'registration_number'),
            upper(trim(v_meta->>'csd_number')), v_level, trim(v_meta->>'business_type'),
            new.email, trim(v_meta->>'mobile_number'), upper(trim(v_meta->>'tax_pin')),
            v_meta->>'province', trim(v_meta->>'representative'))
    returning id into v_supplier;

    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('supplier', v_supplier, 'Supplier registered',
            trim(v_meta->>'company_name') || ' (' || upper(trim(v_meta->>'csd_number')) || ')',
            trim(v_meta->>'representative'));

    -- user_id null = visible to every procurement officer (notifications_own policy).
    insert into notifications (user_id, kind, title, body)
    values (null, 'registration', 'New supplier registration',
            trim(v_meta->>'company_name') || ' is awaiting verification.');
    return new;
  end if;

  -- c) Anything else: a supplier account with no company profile yet.
  insert into profiles (id, email, full_name, role)
  values (new.id, new.email, coalesce(new.raw_user_meta_data->>'full_name', new.email), 'supplier');
  return new;
end;
$$;


-- ---------------------------------------------------------------------------
-- 4. Correct and resubmit a registration that was not approved
-- ---------------------------------------------------------------------------

create or replace function resubmit_supplier_registration(
  p_company_name text, p_registration_number text, p_csd_number text, p_tax_pin text,
  p_bbbee_level int, p_business_type text, p_province text, p_representative text,
  p_mobile_number text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_supplier suppliers%rowtype;
  v_problem  text;
begin
  select * into v_supplier from suppliers where owner_id = auth.uid();
  if not found then
    raise exception 'No supplier registration belongs to this account.';
  end if;
  if v_supplier.status <> 'not_approved' then
    raise exception 'Only a registration that was not approved can be resubmitted.';
  end if;

  perform validate_supplier_profile(p_company_name, p_registration_number, p_csd_number, p_tax_pin,
    p_bbbee_level, p_business_type, p_province, p_representative, p_mobile_number);

  v_problem := supplier_registration_problem(p_registration_number, p_csd_number, v_supplier.id);
  if v_problem is not null then
    raise exception '%', v_problem;
  end if;

  update suppliers
     set company_name = trim(p_company_name), registration_number = trim(p_registration_number),
         csd_number = upper(trim(p_csd_number)), tax_pin = upper(trim(p_tax_pin)),
         bbbee_level = p_bbbee_level, business_type = trim(p_business_type), province = p_province,
         representative = trim(p_representative), mobile_number = trim(p_mobile_number),
         status = 'awaiting_verification', submitted_at = now()
   where id = v_supplier.id;

  -- The earlier decision_reason is kept until an officer decides again, so the
  -- officer can see what was wrong last time. The audit trail keeps both.
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('supplier', v_supplier.id, 'Registration resubmitted', trim(p_company_name),
          trim(p_representative));

  insert into notifications (user_id, kind, title, body)
  values (null, 'registration', 'Supplier registration resubmitted',
          trim(p_company_name) || ' corrected its registration and is awaiting verification.');

  return (select to_jsonb(s) from suppliers s where s.id = v_supplier.id);
end;
$$;


-- ---------------------------------------------------------------------------
-- 5. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function validate_supplier_profile(text, text, text, text, int, text, text, text, text)
  from public, anon, authenticated;

-- The sign-up form runs before there is an account, so signed-out users may call this.
grant execute on function supplier_registration_problem(text, text, uuid) to anon, authenticated;

revoke all on function resubmit_supplier_registration(text, text, text, text, int, text, text, text, text)
  from public, anon;
grant execute on function resubmit_supplier_registration(text, text, text, text, int, text, text, text, text)
  to authenticated;


-- Done. Should list the four new columns.
select column_name as new_supplier_column
from information_schema.columns
where table_name = 'suppliers'
  and column_name in ('mobile_number', 'tax_pin', 'province', 'representative')
order by column_name;
