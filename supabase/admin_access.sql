-- ===========================================================================
-- TenderTrack — Administrator role
--
-- Run in the Supabase SQL Editor AFTER schema.sql, seed.sql, public_access.sql
-- and public_budget.sql. Safe to re-run.
--
-- Deliverable 3, section 5.7: the Administrator manages accounts (create,
-- suspend, reinstate), assigns roles and department scope, and reads the audit
-- log. The Administrator performs no tender actions.
--
-- What this adds
--   1. Suspension. A suspended account keeps its row but loses every
--      permission, because auth_role() returns nothing for it.
--   2. Staff invitations. An administrator invites someone by email with a
--      role; when that person's login is created, the role is applied
--      automatically. This replaces promoting officers by hand in SQL.
--   3. Functions for every administrative change, so each one is validated
--      and written to the audit trail (FR3). Administrators can no longer
--      edit or delete profile rows directly.
--
-- Why accounts are not created from inside the app
--   Creating a login needs Supabase's service_role key, which bypasses every
--   security rule. It must never be shipped inside an app. So the login itself
--   is created in the Supabase dashboard (or by the person signing up), and the
--   invitation decides which role it gets.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. Suspension
-- ---------------------------------------------------------------------------

alter table profiles add column if not exists suspended        boolean not null default false;
alter table profiles add column if not exists suspended_reason text;

-- A suspended account has no role as far as every security rule is concerned.
-- Every policy that checks a role goes through this function, so this one
-- change blocks a suspended user everywhere at once.
create or replace function auth_role()
returns user_role language sql stable security definer set search_path = public as $$
  select role from profiles where id = auth.uid() and not suspended;
$$;


-- ---------------------------------------------------------------------------
-- 2. Profiles: administrators read, but change things only through functions
-- ---------------------------------------------------------------------------
-- The old profiles_admin_manage policy was "for all", which included DELETE:
-- an administrator could delete a profile row (leaving that person unable to
-- sign in), or demote the last administrator and lock everyone out. And none
-- of it was recorded in the audit trail.

drop policy if exists profiles_admin_manage on profiles;
drop policy if exists profiles_admin_read   on profiles;

create policy profiles_admin_read on profiles
  for select to authenticated
  using (auth_role() = 'administrator');


-- ---------------------------------------------------------------------------
-- 3. Staff invitations
-- ---------------------------------------------------------------------------

create table if not exists staff_invitations (
  id            uuid primary key default gen_random_uuid(),
  email         text not null check (email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
  full_name     text not null check (char_length(trim(full_name)) >= 2),
  role          user_role not null check (role not in ('public', 'supplier')),
  department    text,
  invited_by    text not null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  accepted_at   timestamptz,
  accepted_user uuid references auth.users on delete set null
);

create unique index if not exists staff_invitations_email_idx on staff_invitations (lower(email));

alter table staff_invitations enable row level security;

-- Explicit, rather than relying on Supabase's default grants: signed-in users
-- may query the table (the policy below limits it to administrators);
-- signed-out users may not touch it at all.
grant select on staff_invitations to authenticated;
revoke all on staff_invitations from anon;

drop policy if exists invitations_admin_read on staff_invitations;
create policy invitations_admin_read on staff_invitations
  for select to authenticated
  using (auth_role() = 'administrator');

-- When a login is created, apply a matching invitation; otherwise the new
-- account lands as 'supplier' exactly as before.
create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_invite staff_invitations%rowtype;
begin
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
  else
    insert into profiles (id, email, full_name, role)
    values (new.id, new.email,
            coalesce(new.raw_user_meta_data->>'full_name', new.email), 'supplier');
  end if;
  return new;
end;
$$;


-- ---------------------------------------------------------------------------
-- 4. Helpers
-- ---------------------------------------------------------------------------

create or replace function require_administrator()
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if auth_role() is distinct from 'administrator' then
    raise exception 'Only an administrator can do that.';
  end if;
end;
$$;

-- The name written into the audit trail for the person making a change.
create or replace function current_actor_name()
returns text language sql stable security definer set search_path = public as $$
  select coalesce((select full_name from profiles where id = auth.uid()), 'Unknown');
$$;

-- Roles that act for one department must have one.
create or replace function role_needs_department(p_role user_role)
returns boolean language sql immutable as $$
  select p_role in ('procurement_officer', 'finance_officer', 'evaluation_committee');
$$;

create or replace function active_administrator_count()
returns int language sql stable security definer set search_path = public as $$
  select count(*)::int from profiles where role = 'administrator' and not suspended;
$$;

-- One account as the admin screens see it, including last sign-in time,
-- which lives in Supabase's own auth.users table.
create or replace function admin_account_json(p_user_id uuid)
returns jsonb language sql stable security definer set search_path = public, auth as $$
  select jsonb_build_object(
    'id', p.id, 'email', p.email, 'full_name', p.full_name, 'role', p.role,
    'department', p.department, 'suspended', p.suspended,
    'suspended_reason', p.suspended_reason, 'created_at', p.created_at,
    'last_sign_in_at', u.last_sign_in_at
  )
  from profiles p left join auth.users u on u.id = p.id
  where p.id = p_user_id;
$$;


-- ---------------------------------------------------------------------------
-- 5. READ — every account (User Accounts screen)
-- ---------------------------------------------------------------------------

-- Returns one JSON array, which the app decodes directly as a list.
drop function if exists admin_list_accounts();
create function admin_list_accounts()
returns jsonb language plpgsql stable security definer set search_path = public, auth as $$
begin
  perform require_administrator();
  return coalesce(
    (select jsonb_agg(admin_account_json(p.id) order by p.suspended, p.role, p.full_name)
     from profiles p),
    '[]'::jsonb);
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. UPDATE — assign a role and department scope (FR16)
-- ---------------------------------------------------------------------------

create or replace function admin_set_role(p_user_id uuid, p_role text, p_department text default null)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target     profiles%rowtype;
  v_role       user_role;
  v_department text := nullif(trim(coalesce(p_department, '')), '');
begin
  perform require_administrator();

  select * into v_target from profiles where id = p_user_id;
  if not found then raise exception 'That account no longer exists.'; end if;

  begin
    v_role := p_role::user_role;
  exception when invalid_text_representation then
    raise exception 'Choose a valid role.';
  end;

  if v_role = 'public' then
    raise exception 'Public is not an account role: the public use the app without signing in.';
  end if;
  if p_user_id = auth.uid() then
    raise exception 'You cannot change your own role. Ask another administrator.';
  end if;
  if role_needs_department(v_role) and v_department is null then
    raise exception 'A % must be assigned to a department.', replace(v_role::text, '_', ' ');
  end if;
  if v_target.role = 'administrator' and v_role <> 'administrator'
     and not v_target.suspended and active_administrator_count() <= 1 then
    raise exception 'This is the only active administrator. Make someone else an administrator first.';
  end if;

  update profiles set role = v_role, department = v_department where id = p_user_id;

  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('account', p_user_id, 'Role changed',
          v_target.email || ': ' || v_target.role::text || ' → ' || v_role::text ||
            coalesce(' (' || v_department || ')', ''),
          current_actor_name());

  return admin_account_json(p_user_id);
end;
$$;


-- ---------------------------------------------------------------------------
-- 7. UPDATE / soft DELETE — suspend or reinstate
-- ---------------------------------------------------------------------------
-- Suspending is how an account is removed. The row is kept, because FR3 forbids
-- removing records without trace and the audit trail refers to it.

create or replace function admin_set_suspended(p_user_id uuid, p_suspended boolean, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = public, auth as $$
declare
  v_target profiles%rowtype;
  v_reason text := nullif(trim(coalesce(p_reason, '')), '');
begin
  perform require_administrator();

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
    if v_target.role = 'administrator' and active_administrator_count() <= 1 then
      raise exception 'This is the only active administrator and cannot be suspended.';
    end if;
    update profiles set suspended = true, suspended_reason = v_reason where id = p_user_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('account', p_user_id, 'Account suspended', v_target.email || ': ' || v_reason, current_actor_name());
  else
    if not v_target.suspended then return admin_account_json(p_user_id); end if;
    update profiles set suspended = false, suspended_reason = null where id = p_user_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('account', p_user_id, 'Account reinstated', v_target.email, current_actor_name());
  end if;

  return admin_account_json(p_user_id);
end;
$$;

-- Lets the sign-in screen tell a suspended user why they cannot get in.
create or replace function account_is_suspended()
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce((select suspended from profiles where id = auth.uid()), false);
$$;


-- ---------------------------------------------------------------------------
-- 8. CREATE / UPDATE — invite a staff member, or change a pending invitation
-- ---------------------------------------------------------------------------

create or replace function admin_invitation_json(p_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select to_jsonb(i) from staff_invitations i where i.id = p_id;
$$;

create or replace function admin_save_invitation(
  p_id uuid, p_email text, p_full_name text, p_role text, p_department text default null
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_email      text := lower(trim(coalesce(p_email, '')));
  v_name       text := trim(coalesce(p_full_name, ''));
  v_department text := nullif(trim(coalesce(p_department, '')), '');
  v_role       user_role;
  v_existing   staff_invitations%rowtype;
  v_id         uuid;
begin
  perform require_administrator();

  if v_email !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
    raise exception 'Enter a valid email address.';
  end if;
  if char_length(v_name) < 2 then
    raise exception 'Enter the person''s full name.';
  end if;
  begin
    v_role := p_role::user_role;
  exception when invalid_text_representation then
    raise exception 'Choose a valid role.';
  end;
  if v_role in ('public', 'supplier') then
    raise exception 'Invitations are for staff roles. Suppliers register themselves.';
  end if;
  if role_needs_department(v_role) and v_department is null then
    raise exception 'A % must be assigned to a department.', replace(v_role::text, '_', ' ');
  end if;
  if exists (select 1 from profiles where lower(email) = v_email) then
    raise exception 'An account with this email already exists. Change its role under User accounts instead.';
  end if;

  if p_id is null then
    if exists (select 1 from staff_invitations where lower(email) = v_email) then
      raise exception 'This email has already been invited.';
    end if;
    insert into staff_invitations (email, full_name, role, department, invited_by)
    values (v_email, v_name, v_role, v_department, current_actor_name())
    returning id into v_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('invitation', v_id, 'Invitation created', v_email || ' as ' || v_role::text, current_actor_name());
  else
    select * into v_existing from staff_invitations where id = p_id;
    if not found then raise exception 'That invitation no longer exists.'; end if;
    if v_existing.accepted_at is not null then
      raise exception 'This invitation has been accepted. Change the role under User accounts instead.';
    end if;
    if exists (select 1 from staff_invitations where lower(email) = v_email and id <> p_id) then
      raise exception 'Another invitation already uses this email.';
    end if;
    update staff_invitations
       set email = v_email, full_name = v_name, role = v_role,
           department = v_department, updated_at = now()
     where id = p_id;
    v_id := p_id;
    insert into audit_trail (entity_type, entity_id, action, detail, actor)
    values ('invitation', v_id, 'Invitation updated', v_email || ' as ' || v_role::text, current_actor_name());
  end if;

  return admin_invitation_json(v_id);
end;
$$;


-- ---------------------------------------------------------------------------
-- 9. DELETE — revoke a pending invitation
-- ---------------------------------------------------------------------------
-- Invitations are the one thing an administrator may truly delete: a pending
-- invitation has granted nothing yet. The audit trail keeps a record of it.

create or replace function admin_revoke_invitation(p_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_invite staff_invitations%rowtype;
begin
  perform require_administrator();

  select * into v_invite from staff_invitations where id = p_id;
  if not found then raise exception 'That invitation no longer exists.'; end if;
  if v_invite.accepted_at is not null then
    raise exception 'This invitation has been accepted. Suspend the account instead.';
  end if;

  delete from staff_invitations where id = p_id;
  insert into audit_trail (entity_type, entity_id, action, detail, actor)
  values ('invitation', p_id, 'Invitation revoked', v_invite.email, current_actor_name());

  return jsonb_build_object('revoked', true);
end;
$$;


-- ---------------------------------------------------------------------------
-- 10. Who may call what
-- ---------------------------------------------------------------------------

revoke all on function require_administrator()          from public, anon, authenticated;
revoke all on function current_actor_name()             from public, anon, authenticated;
revoke all on function active_administrator_count()     from public, anon, authenticated;
revoke all on function admin_account_json(uuid)         from public, anon, authenticated;
revoke all on function admin_invitation_json(uuid)      from public, anon, authenticated;

revoke all on function admin_list_accounts()                              from public, anon;
revoke all on function admin_set_role(uuid, text, text)                   from public, anon;
revoke all on function admin_set_suspended(uuid, boolean, text)           from public, anon;
revoke all on function admin_save_invitation(uuid, text, text, text, text) from public, anon;
revoke all on function admin_revoke_invitation(uuid)                      from public, anon;
revoke all on function account_is_suspended()                             from public, anon;

grant execute on function admin_list_accounts()                              to authenticated;
grant execute on function admin_set_role(uuid, text, text)                   to authenticated;
grant execute on function admin_set_suspended(uuid, boolean, text)           to authenticated;
grant execute on function admin_save_invitation(uuid, text, text, text, text) to authenticated;
grant execute on function admin_revoke_invitation(uuid)                      to authenticated;
grant execute on function account_is_suspended()                             to authenticated;


-- ---------------------------------------------------------------------------
-- Done. Should list the six administrator functions.
-- ---------------------------------------------------------------------------
select proname as admin_function
from pg_proc
where proname in ('admin_list_accounts', 'admin_set_role', 'admin_set_suspended',
                  'admin_save_invitation', 'admin_revoke_invitation', 'account_is_suspended')
order by proname;
