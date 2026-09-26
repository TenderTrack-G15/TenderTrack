-- ===========================================================================
-- TenderTrack — Administrator (web admin portal)
--
-- Run in the Supabase SQL Editor after schema.sql, seed.sql, public_access.sql,
-- public_budget.sql, auditor_records.sql and supplier_module.sql.
-- Safe to re-run.
--
-- The Administrator works only in the admin portal, a website that runs on
-- the administrator's own computer. It is not part of the mobile app.
--
-- What this migration does
--   1. Two-factor sign-in is enforced BY THE DATABASE. An administrator who
--      signed in with a password only has no powers at all; the 6-digit code
--      is what unlocks them. A stolen password is not enough.
--   2. The Administrator is read-only on tender data (Deliverable 3, 5.7:
--      "deliberately containing no tender actions"). Until now administrators
--      shared every procurement officer power, including editing tenders.
--   3. Accounts are managed through audited functions: create, change role,
--      suspend, reinstate, reset password, delete. Nothing is changed without
--      an entry in the audit trail (FR3).
--   4. A suspended account loses all access at once.
--   5. Portal access log, activity summary with automatic flagging of
--      irregular patterns, announcements shown in the app, and a way for the
--      Administrator to flag a tender for review.
--   6. Downloads of reports and backup snapshots are recorded as well.
--   7. Fixes the masked banking view from supplier_module.sql, which any
--      signed-in account could read: a supplier now sees only its own row.
--
-- It also removes everything left over from the earlier, in-app
-- administrator design (staff_invitations and its functions).
-- ===========================================================================

do $$
begin
  if to_regclass('public.tenders') is null then
    raise exception 'Run schema.sql first.';
  end if;
  if to_regclass('public.supplier_documents') is null then
    raise exception 'Run supplier_module.sql first: supplier sign-up is kept working by this file.';
  end if;
end $$;


-- ---------------------------------------------------------------------------
-- 0. Remove the earlier in-app administrator design, if any of it was run
-- ---------------------------------------------------------------------------

drop function if exists admin_list_accounts();
drop function if exists admin_set_role(uuid, text, text);
drop function if exists admin_set_suspended(uuid, boolean, text);
drop function if exists admin_save_invitation(uuid, text, text, text, text);
drop function if exists admin_revoke_invitation(uuid);
drop function if exists admin_invitation_json(uuid);
drop function if exists admin_account_json(uuid);
drop function if exists account_is_suspended();
drop function if exists require_administrator();
drop function if exists current_actor_name();
drop function if exists active_administrator_count();
drop function if exists role_needs_department(user_role);
drop table if exists staff_invitations;


-- ---------------------------------------------------------------------------
-- 1. Suspension columns
-- ---------------------------------------------------------------------------

alter table profiles add column if not exists suspended        boolean not null default false;
alter table profiles add column if not exists suspended_reason text;
alter table profiles add column if not exists suspended_at     timestamptz;


-- ---------------------------------------------------------------------------
-- 2. Who the caller is — the one function every security rule relies on
-- ---------------------------------------------------------------------------
-- Every policy and function in TenderTrack asks auth_role() what the caller
-- may do. Two rules are added here, so they apply everywhere at once:
--   * a suspended account has no role;
--   * an administrator has no role until they have completed two-factor
--     sign-in (their session is then "aal2", assurance level 2).

create or replace function auth_role()
returns user_role language sql stable security definer set search_path = public as $$
  select p.role
  from profiles p
  where p.id = auth.uid()
    and not p.suspended
    and (p.role <> 'administrator' or coalesce(auth.jwt() ->> 'aal', '') = 'aal2');
$$;

-- Procurement officers only. Administrators used to be included, which gave
-- them every officer power, including registering and editing tenders.
create or replace function is_officer()
returns boolean language sql stable as $$
  select auth_role() = 'procurement_officer';
$$;

-- Unchanged: auditors and administrators read everything, read-only.
create or replace function is_oversight()
returns boolean language sql stable as $$
  select auth_role() in ('auditor', 'administrator');
$$;


-- ---------------------------------------------------------------------------
-- 3. Take the remaining officer powers away from administrators
-- ---------------------------------------------------------------------------

-- This policy let administrators edit and even delete profile rows directly,
-- with no audit record. Account changes now go through the functions below.
drop policy if exists profiles_admin_manage on profiles;

drop policy if exists payments_write on payments;
create policy payments_write on payments
  for insert to authenticated
  with check (auth_role() in ('procurement_officer', 'finance_officer'));

drop policy if exists budgets_write on department_budgets;
create policy budgets_write on department_budgets
  for update to authenticated
  using (auth_role() = 'finance_officer')
  with check (auth_role() = 'finance_officer');

-- Fix: the masked banking view from supplier_module.sql could be read by any
-- signed-in account, including other suppliers. It now shows a supplier only
-- its own row, and shows the masked details to the roles that review
-- suppliers and payments. Administrators do not need banking details.
create or replace view supplier_banking_masked as
select b.supplier_id,
       s.company_name,
       b.bank_name,
       b.account_name,
       'xxxx' || right(b.account_number, 4) as account_number_masked,
       b.branch_code,
       b.updated_at
from supplier_banking b
join suppliers s on s.id = b.supplier_id
where s.owner_id = auth.uid()
   or auth_role() in ('procurement_officer', 'auditor', 'finance_officer');

grant select on supplier_banking_masked to authenticated;
revoke all on supplier_banking_masked from anon;


-- ---------------------------------------------------------------------------
-- 4. Helpers
-- ---------------------------------------------------------------------------

create or replace function require_admin()
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if auth_role() is distinct from 'administrator' then
    raise exception 'Only an administrator signed in with two-factor authentication can do that.';
  end if;
end;
$$;

create or replace function admin_actor_name()
returns text language sql stable security definer set search_path = public as $$
  select coalesce((select full_name from profiles where id = auth.uid()), 'Administrator');
$$;

-- Roles that act for one department and therefore must have one.
create or replace function role_needs_department(p_role user_role)
returns boolean language sql immutable as $$
  select p_role in ('procurement_officer', 'finance_officer', 'evaluation_committee');
$$;

create or replace function active_admin_count()
returns int language sql stable security definer set search_path = public as $$
  select count(*)::int from profiles where role = 'administrator' and not suspended;
$$;

-- Checks a role and department the way every account function needs to.
create or replace function validate_staff_role(p_role text, p_department text)
returns user_role language plpgsql immutable as $$
declare
  v_role user_role;
begin
  begin
    v_role := p_role::user_role;
  exception when invalid_text_representation then
    raise exception 'Choose a valid role.';
  end;
  if v_role = 'public' then
    raise exception 'Public is not an account role: the public use the app without signing in.';
  end if;
  if v_role = 'supplier' then
    raise exception 'Suppliers register themselves in the app, so their company details come with the account.';
  end if;
  if role_needs_department(v_role) and nullif(trim(coalesce(p_department, '')), '') is null then
    raise exception 'A % must be assigned to a department.', replace(v_role::text, '_', ' ');
  end if;
  return v_role;
end;
$$;

-- One account, as the portal shows it, including sign-in details that live in
-- Supabase's own auth.users table.
create or replace function admin_account_json(p_user_id uuid)
returns jsonb language sql stable security definer set search_path = public, auth as $$
  select jsonb_build_object(
    'id', p.id,
    'email', p.email,
    'full_name', p.full_name,
    'role', p.role,
    'department', p.department,
    'suspended', p.suspended,
    'suspended_reason', p.suspended_reason,
    'suspended_at', p.suspended_at,
    'created_at', p.created_at,
    'last_sign_in_at', u.last_sign_in_at,
    'owns_supplier', exists (select 1 from suppliers s where s.owner_id = p.id)
  )
  from profiles p
  left join auth.users u on u.id = p.id
  where p.id = p_user_id;
$$;


-- ---------------------------------------------------------------------------
-- 5. Portal access log
-- ---------------------------------------------------------------------------
-- The portal records each step of every sign-in. The account and time come
-- from the database, never from the browser, so an entry cannot be forged
-- for someone else. Wrong passwords are recorded by Supabase Auth itself
-- (Supabase dashboard -> Logs -> Auth).

create table if not exists admin_access_log (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid,
  email      text not null default '',
  event      text not null check (event in (
               'password_ok', 'mfa_enrolled', 'mfa_verified', 'mfa_failed',
               'not_admin', 'signed_out', 'idle_timeout')),
  detail     text not null default '',
  user_agent text not null default '',
  created_at timestamptz not null default now()
);

create index if not exists admin_access_log_idx on admin_access_log (created_at desc);

alter table admin_access_log enable row level security;
grant select on admin_access_log to authenticated;
revoke all on admin_access_log from anon;

drop policy if exists admin_access_log_read on admin_access_log;
create policy admin_access_log_read on admin_access_log
  for select to authenticated
  using (auth_role() = 'administrator');

-- Any signed-in user may record a step of their own sign-in (including a
-- non-administrator who tried the portal). Nobody can write directly.
create or replace function log_portal_event(p_event text, p_detail text default '', p_user_agent text default '')
returns void language plpgsql security definer set search_path = public, auth as $$
declare
  v_email text;
begin
  if auth.uid() is null then
    raise exception 'Sign in first.';
  end if;
  -- A simple guard against flooding the log.
  if (select count(*) from admin_access_log
       where user_id = auth.uid() and created_at > now() - interval '10 minutes') >= 60 then
    return;
  end if;
  select email into v_email from auth.users where id = auth.uid();
  insert into admin_access_log (user_id, email, event, detail, user_agent)
  values (auth.uid(), coalesce(v_email, ''), p_event,
          left(coalesce(p_detail, ''), 300), left(coalesce(p_user_agent, ''), 300));
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. Accounts
-- ---------------------------------------------------------------------------

/** Who is signed in to the portal. Fails unless they are an active
    administrator who has completed two-factor sign-in. */
create or replace function admin_whoami()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform require_admin();
  return (select jsonb_build_object(
            'id', p.id, 'email', p.email, 'full_name', p.full_name,
            'role', p.role, 'aal', auth.jwt() ->> 'aal')
          from profiles p where p.id = auth.uid());
end;
$$;

/** READ — every account, newest first within each role. */
create or replace function admin_list_accounts()
returns jsonb language plpgsql stable security definer set search_path = public, auth as $$
begin
  perform require_admin();
  return coalesce(
    (select jsonb_agg(admin_account_json(p.id) order by p.suspended, p.role, p.full_name)
     from profiles p),
    '[]'::jsonb);
end;
$$;

/**
 * CREATE, step 1 — checks a new account before the portal's server creates
 * the login. Returns the cleaned-up values. The login itself is created by the
 * server with Supabase's secret key, which never reaches the browser.
 */
create or replace function admin_check_new_account(
  p_email text, p_full_name text, p_role text, p_department text default null
) returns jsonb language plpgsql stable security definer set search_path = public, auth as $$
declare
  v_email text := lower(trim(coalesce(p_email, '')));
  v_name  text := trim(coalesce(p_full_name, ''));
  v_role  user_role;
begin
  perform require_admin();
  if v_email !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
    raise exception 'Enter a valid email address.';
  end if;
  if char_length(v_name) < 2 then
    raise exception 'Enter the person''s full name.';
  end if;
  v_role := validate_staff_role(p_role, p_department);
  if exists (select 1 from auth.users where lower(email) = v_email) then
    raise exception 'An account with this email already exists.';
  end if;
  return jsonb_build_object(
    'email', v_email,
    'full_name', v_name,
    'role', v_role,
    'department', nullif(trim(coalesce(p_department, '')), ''),
    'created_by', admin_actor_name());
end;
$$;

/**
 * CREATE, step 2 — runs inside Supabase Auth's own transaction when the
 * server creates the login. The server attaches the role as "app_metadata",
 * which only the secret key can set: a member of the public signing up in the
 * app cannot give themselves a staff role this way.
 */
create or replace function apply_admin_created_role()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_meta jsonb := new.raw_app_meta_data;
  v_role user_role;
  v_dept text := nullif(trim(coalesce(new.raw_app_meta_data ->> 'tt_department', '')), '');
  v_name text := nullif(trim(coalesce(new.raw_app_meta_data ->> 'tt_full_name', '')), '');
begin
  v_role := validate_staff_role(v_meta ->> 'tt_role', v_dept);

  update profiles
     set role = v_role,
         department = v_dept,
         full_name = coalesce(v_name, full_name)
   where id = new.id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', new.id, 'Account created',
          new.email || ' as ' || v_role::text || coalesce(' (' || v_dept || ')', ''),
          coalesce(nullif(v_meta ->> 'tt_created_by', ''), 'Administrator'));
  return new;
end;
$$;

drop trigger if exists on_admin_created_role on auth.users;
create trigger on_admin_created_role
  after update of raw_app_meta_data on auth.users
  for each row
  when (new.raw_app_meta_data ->> 'tt_role' is not null
        and new.raw_app_meta_data ->> 'tt_role' is distinct from old.raw_app_meta_data ->> 'tt_role')
  execute function apply_admin_created_role();

/** UPDATE — full name, role and department. */
create or replace function admin_update_account(
  p_user_id uuid, p_full_name text, p_role text, p_department text default null
) returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target profiles%rowtype;
  v_role   user_role;
  v_name   text := trim(coalesce(p_full_name, ''));
  v_dept   text := nullif(trim(coalesce(p_department, '')), '');
  v_changes text[] := '{}';
begin
  perform require_admin();

  select * into v_target from profiles where id = p_user_id;
  if not found then raise exception 'That account no longer exists.'; end if;
  if char_length(v_name) < 2 then raise exception 'Enter the person''s full name.'; end if;

  if v_target.role = 'supplier' then
    raise exception 'Supplier accounts belong to a company registration; their role cannot be changed here.';
  end if;
  v_role := validate_staff_role(p_role, v_dept);

  if p_user_id = auth.uid() and v_role <> v_target.role then
    raise exception 'You cannot change your own role. Ask another administrator.';
  end if;
  if v_target.role = 'administrator' and v_role <> 'administrator'
     and not v_target.suspended and active_admin_count() <= 1 then
    raise exception 'This is the only active administrator. Make someone else an administrator first.';
  end if;

  if v_name is distinct from v_target.full_name then
    v_changes := v_changes || ('name: ' || v_target.full_name || ' → ' || v_name);
  end if;
  if v_role is distinct from v_target.role then
    v_changes := v_changes || ('role: ' || v_target.role::text || ' → ' || v_role::text);
  end if;
  if v_dept is distinct from v_target.department then
    v_changes := v_changes || ('department: ' || coalesce(v_target.department, '—') || ' → ' || coalesce(v_dept, '—'));
  end if;

  if array_length(v_changes, 1) is null then
    return admin_account_json(p_user_id);
  end if;

  update profiles set full_name = v_name, role = v_role, department = v_dept where id = p_user_id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', p_user_id,
          case when v_role is distinct from v_target.role then 'Role changed' else 'Account updated' end,
          v_target.email || ': ' || array_to_string(v_changes, '; '),
          admin_actor_name());

  return admin_account_json(p_user_id);
end;
$$;

/**
 * UPDATE (soft DELETE) — suspend or reinstate. The database side takes effect
 * immediately: a suspended account's role is ignored by every security rule.
 * The portal's server also blocks the login in Supabase Auth, so the person
 * cannot sign in to the app at all.
 */
create or replace function admin_set_suspended(p_user_id uuid, p_suspended boolean, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target profiles%rowtype;
  v_reason text := nullif(trim(coalesce(p_reason, '')), '');
begin
  perform require_admin();

  select * into v_target from profiles where id = p_user_id;
  if not found then raise exception 'That account no longer exists.'; end if;
  if p_user_id = auth.uid() then
    raise exception 'You cannot suspend or reinstate your own account.';
  end if;

  if p_suspended then
    if v_target.suspended then return admin_account_json(p_user_id); end if;
    if v_reason is null or char_length(v_reason) < 5 then
      raise exception 'Give a reason for the suspension (at least 5 characters).';
    end if;
    if v_target.role = 'administrator' and active_admin_count() <= 1 then
      raise exception 'This is the only active administrator and cannot be suspended.';
    end if;
    update profiles set suspended = true, suspended_reason = v_reason, suspended_at = now()
     where id = p_user_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('account', p_user_id, 'Account suspended', v_target.email || ': ' || v_reason, admin_actor_name());
  else
    if not v_target.suspended then return admin_account_json(p_user_id); end if;
    update profiles set suspended = false, suspended_reason = null, suspended_at = null
     where id = p_user_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('account', p_user_id, 'Account reinstated', v_target.email, admin_actor_name());
  end if;

  return admin_account_json(p_user_id);
end;
$$;

/** Checks and records a password reset before the server sets the new password. */
create or replace function admin_prepare_password_reset(p_user_id uuid)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target profiles%rowtype;
begin
  perform require_admin();
  select * into v_target from profiles where id = p_user_id;
  if not found then raise exception 'That account no longer exists.'; end if;
  if p_user_id = auth.uid() then
    raise exception 'Change your own password in the Supabase dashboard, not here.';
  end if;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', p_user_id, 'Password reset', v_target.email || ': temporary password issued',
          admin_actor_name());
  return admin_account_json(p_user_id);
end;
$$;

/**
 * DELETE — only for accounts that have not created any records. An account
 * that registered tenders, verified deliverables or owns a supplier
 * registration can only be suspended: deleting it would break the audit
 * trail (FR3).
 */
create or replace function admin_prepare_delete(p_user_id uuid)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target profiles%rowtype;
begin
  perform require_admin();
  select * into v_target from profiles where id = p_user_id;
  if not found then raise exception 'That account no longer exists.'; end if;
  if p_user_id = auth.uid() then
    raise exception 'You cannot delete your own account.';
  end if;
  if v_target.role = 'administrator' and not v_target.suspended and active_admin_count() <= 1 then
    raise exception 'This is the only active administrator and cannot be deleted.';
  end if;
  if exists (select 1 from tenders where created_by = p_user_id)
     or exists (select 1 from deliverables where verified_by = p_user_id) then
    raise exception 'This account has created records, so it can only be suspended. Deleting it would break the audit trail (FR3).';
  end if;
  if exists (select 1 from suppliers where owner_id = p_user_id) then
    raise exception 'This account owns a supplier registration, so it can only be suspended.';
  end if;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', p_user_id, 'Account deleted',
          v_target.email || ' (' || v_target.role::text || ')', admin_actor_name());
  return admin_account_json(p_user_id);
end;
$$;

/** Records that a step the server tried in Supabase Auth did not go through. */
create or replace function admin_report_auth_failure(p_user_id uuid, p_action text, p_message text)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform require_admin();
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', p_user_id, left(coalesce(p_action, 'Action') || ' not completed', 80),
          left(coalesce(p_message, ''), 300), admin_actor_name());
end;
$$;


-- ---------------------------------------------------------------------------
-- 7. New logins: supplier sign-up, or a plain account
-- ---------------------------------------------------------------------------
-- Same as supplier_module.sql, minus the staff-invitation branch from the old
-- administrator design. Accounts made in the admin portal get their role from
-- the apply_admin_created_role trigger above.

create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_meta     jsonb := coalesce(new.raw_user_meta_data, '{}'::jsonb);
  v_supplier uuid;
  v_problem  text;
begin
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

  -- Anything else starts as a supplier account with no company. Accounts made
  -- in the admin portal are given their staff role a moment later, in the same
  -- transaction, by apply_admin_created_role.
  insert into profiles (id, email, full_name, role)
  values (new.id, new.email, coalesce(nullif(v_meta->>'full_name', ''), new.email), 'supplier');
  return new;
end;
$$;


-- ---------------------------------------------------------------------------
-- 8. Announcements — written in the portal, shown in the app
-- ---------------------------------------------------------------------------

create table if not exists announcements (
  id         uuid primary key default gen_random_uuid(),
  title      text not null check (char_length(trim(title)) between 3 and 120),
  body       text not null default '' check (char_length(body) <= 1000),
  audience   text not null default 'everyone'
             check (audience in ('everyone', 'public', 'suppliers', 'staff')),
  severity   text not null default 'info' check (severity in ('info', 'warning', 'critical')),
  published  boolean not null default true,
  starts_at  timestamptz not null default now(),
  ends_at    timestamptz,
  created_by text not null default '',
  created_at timestamptz not null default now(),
  updated_by text not null default '',
  updated_at timestamptz not null default now(),
  constraint announcement_dates check (ends_at is null or ends_at > starts_at)
);

create index if not exists announcements_live_idx on announcements (published, starts_at, ends_at);

alter table announcements enable row level security;
grant select on announcements to anon, authenticated;
grant insert, update, delete on announcements to authenticated;

-- Who may read which notices. The app also filters by audience, but the
-- database decides: a staff-only notice never reaches a supplier's phone.
create or replace function announcement_visible(p_audience text, p_published boolean,
                                                p_starts timestamptz, p_ends timestamptz)
returns boolean language sql stable security definer set search_path = public as $$
  select auth_role() = 'administrator'
      or (p_published
          and p_starts <= now()
          and (p_ends is null or p_ends > now())
          and (p_audience in ('everyone', 'public')
               or (p_audience = 'suppliers' and auth_role() = 'supplier')
               or (p_audience = 'staff' and auth_role() in ('procurement_officer', 'evaluation_committee',
                                                            'finance_officer', 'auditor', 'administrator'))));
$$;

drop policy if exists announcements_read on announcements;
create policy announcements_read on announcements
  for select to anon, authenticated
  using (announcement_visible(audience, published, starts_at, ends_at));

drop policy if exists announcements_admin_insert on announcements;
create policy announcements_admin_insert on announcements
  for insert to authenticated with check (auth_role() = 'administrator');

drop policy if exists announcements_admin_update on announcements;
create policy announcements_admin_update on announcements
  for update to authenticated
  using (auth_role() = 'administrator') with check (auth_role() = 'administrator');

drop policy if exists announcements_admin_delete on announcements;
create policy announcements_admin_delete on announcements
  for delete to authenticated using (auth_role() = 'administrator');

-- Who wrote or changed a notice is set here, never taken from the browser,
-- and every change is written to the audit trail.
create or replace function announcements_stamp()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.created_by := admin_actor_name();
    new.created_at := now();
  end if;
  new.updated_by := admin_actor_name();
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists announcements_stamp on announcements;
create trigger announcements_stamp
  before insert or update on announcements
  for each row execute function announcements_stamp();

create or replace function announcements_audit()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_row announcements%rowtype := coalesce(new, old);
begin
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('announcement', v_row.id,
          case tg_op when 'INSERT' then 'Announcement published'
                     when 'UPDATE' then 'Announcement updated'
                     else 'Announcement deleted' end,
          v_row.title || ' (' || v_row.audience || ')',
          admin_actor_name());
  return null;
end;
$$;

drop trigger if exists announcements_audit on announcements;
create trigger announcements_audit
  after insert or update or delete on announcements
  for each row execute function announcements_audit();


-- ---------------------------------------------------------------------------
-- 9. Flag a tender for review (Deliverable 3: "Admin ... flags anomalies")
-- ---------------------------------------------------------------------------
-- The flag lands on the procurement officer's Flags screen in the app, with
-- a notification, exactly like one raised by the system.

create or replace function admin_raise_flag(
  p_tender_id uuid, p_title text, p_description text, p_severity text default 'medium'
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_tender   tenders%rowtype;
  v_severity flag_severity;
  v_flag     uuid;
  v_ref      text;
begin
  perform require_admin();

  select * into v_tender from tenders where id = p_tender_id;
  if not found then raise exception 'That tender no longer exists.'; end if;
  if char_length(trim(coalesce(p_title, ''))) < 5 then
    raise exception 'Give the flag a title of at least 5 characters.';
  end if;
  if char_length(trim(coalesce(p_description, ''))) < 15 then
    raise exception 'Describe the concern in at least 15 characters.';
  end if;
  begin
    v_severity := p_severity::flag_severity;
  exception when invalid_text_representation then
    raise exception 'Severity must be high, medium or low.';
  end;

  v_ref := 'FLG-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('flag_seq')::text, 4, '0');

  insert into compliance_flags (reference, tender_id, tender_reference, tender_title,
                                title, description, rule_triggered, severity,
                                status, raised_automatically)
  values (v_ref, v_tender.id, v_tender.reference_number, v_tender.title,
          trim(p_title), trim(p_description), 'Raised by the administrator', v_severity,
          'open', false)
  returning id into v_flag;

  update tenders set open_flag_count = open_flag_count + 1 where id = v_tender.id;

  insert into notifications (user_id, kind, title, body)
  values (null, 'flag', 'Administrator flagged ' || v_tender.reference_number, trim(p_title));

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('flag', v_flag, 'Flag raised by administrator',
          v_tender.reference_number || ' · ' || trim(p_title), admin_actor_name());

  return (select to_jsonb(f) from compliance_flags f where f.id = v_flag);
end;
$$;


-- ---------------------------------------------------------------------------
-- 10. Application activity, with automatic flagging of irregular patterns
-- ---------------------------------------------------------------------------

create or replace function admin_activity_summary()
returns jsonb language plpgsql stable security definer set search_path = public, auth as $$
declare
  v_irregular jsonb;
begin
  perform require_admin();

  select coalesce(jsonb_agg(x order by x->>'at' desc), '[]'::jsonb) into v_irregular
  from (
    -- 1. Three or more wrong two-factor codes for one account within 15 minutes.
    select jsonb_build_object(
             'kind', 'Repeated wrong 2FA codes',
             'detail', l.email || ': ' || max(l.n) || ' wrong codes within 15 minutes',
             'at', max(l.created_at)) as x
    from (
      select a.email, a.created_at,
             (select count(*) from admin_access_log b
               where b.user_id = a.user_id and b.event = 'mfa_failed'
                 and b.created_at between a.created_at - interval '15 minutes' and a.created_at) as n
      from admin_access_log a
      where a.event = 'mfa_failed' and a.created_at > now() - interval '7 days'
    ) l
    where l.n >= 3
    group by l.email

    union all
    -- 2. Someone who is not an administrator signed in to the portal.
    select jsonb_build_object(
             'kind', 'Non-administrator at the portal',
             'detail', email || ' passed the password step but is not an administrator',
             'at', created_at)
    from admin_access_log
    where event = 'not_admin' and created_at > now() - interval '7 days'

    union all
    -- 3. A new administrator was made.
    select jsonb_build_object(
             'kind', 'New administrator',
             'detail', detail || ' — by ' || actor,
             'at', created_at)
    from audit_trail
    where entity_type = 'account' and created_at > now() - interval '7 days'
      and ((action = 'Account created' and detail like '% as administrator%')
           or (action = 'Role changed' and detail like '%→ administrator%'))

    union all
    -- 4. A burst of ten or more account changes by one person within 10 minutes.
    select jsonb_build_object(
             'kind', 'Burst of account changes',
             'detail', b.actor || ' made ' || b.n || ' account changes within 10 minutes',
             'at', b.last_at)
    from (
      select a.actor, max(a.created_at) as last_at,
             max((select count(*) from audit_trail c
                   where c.entity_type = 'account' and c.actor = a.actor
                     and c.created_at between a.created_at - interval '10 minutes' and a.created_at)) as n
      from audit_trail a
      where a.entity_type = 'account' and a.created_at > now() - interval '7 days'
      group by a.actor
    ) b
    where b.n >= 10
  ) flagged;

  return jsonb_build_object(
    'accounts_total',       (select count(*) from profiles),
    'accounts_suspended',   (select count(*) from profiles where suspended),
    'administrators',       (select count(*) from profiles where role = 'administrator' and not suspended),
    'signed_in_last_hour',  (select count(*) from auth.users where last_sign_in_at > now() - interval '1 hour'),
    'signed_in_today',      (select count(*) from auth.users where last_sign_in_at > date_trunc('day', now())),
    'events_today',         (select count(*) from audit_trail where created_at > date_trunc('day', now())),
    'events_7_days',        (select count(*) from audit_trail where created_at > now() - interval '7 days'),
    'mfa_failures_24h',     (select count(*) from admin_access_log
                              where event = 'mfa_failed' and created_at > now() - interval '24 hours'),
    'portal_sign_ins_24h',  (select count(*) from admin_access_log
                              where event = 'mfa_verified' and created_at > now() - interval '24 hours'),
    'irregular',            v_irregular
  );
end;
$$;


-- ---------------------------------------------------------------------------
-- 10b. Reports and backup snapshots are recorded as well (FR3, FR17)
-- ---------------------------------------------------------------------------
-- Downloading data is itself an action worth recording: the audit trail shows
-- who exported what, and the portal shows when the last snapshot was taken.

create or replace function admin_record_export(p_kind text, p_detail text)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform require_admin();
  if p_kind is null or p_kind not in ('report', 'backup', 'audit_export') then
    raise exception 'Unknown export type.';
  end if;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('export', auth.uid(),
          case p_kind when 'backup' then 'Backup snapshot downloaded'
                      when 'audit_export' then 'Audit log exported'
                      else 'Report exported' end,
          left(coalesce(p_detail, ''), 300), admin_actor_name());
end;
$$;


-- ---------------------------------------------------------------------------
-- 11. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function require_admin()                   from public, anon, authenticated;
revoke all on function admin_actor_name()                from public, anon, authenticated;
revoke all on function active_admin_count()              from public, anon, authenticated;
revoke all on function admin_account_json(uuid)          from public, anon, authenticated;
revoke all on function apply_admin_created_role()        from public, anon, authenticated;
revoke all on function announcements_stamp()             from public, anon, authenticated;
revoke all on function announcements_audit()             from public, anon, authenticated;

revoke all on function log_portal_event(text, text, text)                       from public, anon;
revoke all on function admin_whoami()                                           from public, anon;
revoke all on function admin_list_accounts()                                    from public, anon;
revoke all on function admin_check_new_account(text, text, text, text)          from public, anon;
revoke all on function admin_update_account(uuid, text, text, text)             from public, anon;
revoke all on function admin_set_suspended(uuid, boolean, text)                 from public, anon;
revoke all on function admin_prepare_password_reset(uuid)                       from public, anon;
revoke all on function admin_prepare_delete(uuid)                               from public, anon;
revoke all on function admin_report_auth_failure(uuid, text, text)              from public, anon;
revoke all on function admin_raise_flag(uuid, text, text, text)                 from public, anon;
revoke all on function admin_activity_summary()                                 from public, anon;
revoke all on function admin_record_export(text, text)                          from public, anon;

grant execute on function log_portal_event(text, text, text)                    to authenticated;
grant execute on function admin_whoami()                                        to authenticated;
grant execute on function admin_list_accounts()                                 to authenticated;
grant execute on function admin_check_new_account(text, text, text, text)       to authenticated;
grant execute on function admin_update_account(uuid, text, text, text)          to authenticated;
grant execute on function admin_set_suspended(uuid, boolean, text)              to authenticated;
grant execute on function admin_prepare_password_reset(uuid)                    to authenticated;
grant execute on function admin_prepare_delete(uuid)                            to authenticated;
grant execute on function admin_report_auth_failure(uuid, text, text)           to authenticated;
grant execute on function admin_raise_flag(uuid, text, text, text)              to authenticated;
grant execute on function admin_activity_summary()                              to authenticated;
grant execute on function admin_record_export(text, text)                       to authenticated;


-- ---------------------------------------------------------------------------
-- Done. Lists the administrators; empty until you create the first one
-- (see the setup guide, step 3).
-- ---------------------------------------------------------------------------
select email, full_name, suspended
from profiles
where role = 'administrator'
order by email;
