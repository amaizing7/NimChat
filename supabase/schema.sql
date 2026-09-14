-- NimChat backend schema
create extension if not exists pgcrypto;
create schema if not exists private;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique,
  display_name text,
  created_at timestamptz not null default now()
);

create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.conversation_members (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(trim(body)) between 1 and 4000),
  created_at timestamptz not null default now()
);

create index if not exists messages_conversation_created_idx on public.messages(conversation_id, created_at);
create index if not exists conversation_members_user_idx on public.conversation_members(user_id);

alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages enable row level security;

grant select, insert, update on public.profiles to authenticated;
grant select on public.conversations to authenticated;
grant select on public.conversation_members to authenticated;
grant select, insert, update, delete on public.messages to authenticated;
revoke insert, update, delete on public.conversations from authenticated;
revoke insert, update, delete on public.conversation_members from authenticated;

create or replace function private.is_conversation_member(target_conversation uuid, target_user uuid default auth.uid())
returns boolean
language sql
security definer
stable
set search_path = public
as $$
  select exists (select 1 from public.conversation_members where conversation_id = target_conversation and user_id = target_user);
$$;
revoke all on function private.is_conversation_member(uuid, uuid) from public, anon;
grant execute on function private.is_conversation_member(uuid, uuid) to authenticated;

drop policy if exists "profiles readable by authenticated users" on public.profiles;
drop policy if exists "users create own profile" on public.profiles;
drop policy if exists "users update own profile" on public.profiles;
drop policy if exists "members can read conversations" on public.conversations;
drop policy if exists "authenticated users can create conversations" on public.conversations;
drop policy if exists "members can read membership" on public.conversation_members;
drop policy if exists "users can add themselves or another member after joining" on public.conversation_members;
drop policy if exists "members can read messages" on public.messages;
drop policy if exists "members can send messages" on public.messages;
drop policy if exists "senders can update own messages" on public.messages;
drop policy if exists "senders can delete own messages" on public.messages;

create policy "profiles readable by authenticated users" on public.profiles for select to authenticated using (true);
create policy "users create own profile" on public.profiles for insert to authenticated with check (id = auth.uid());
create policy "users update own profile" on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
create policy "members can read conversations" on public.conversations for select to authenticated using (created_by = auth.uid() or private.is_conversation_member(id, auth.uid()));
create policy "members can read membership" on public.conversation_members for select to authenticated using (user_id = auth.uid() or private.is_conversation_member(conversation_id, auth.uid()));
create policy "members can read messages" on public.messages for select to authenticated using (private.is_conversation_member(conversation_id, auth.uid()));
create policy "members can send messages" on public.messages for insert to authenticated with check (sender_id = auth.uid() and private.is_conversation_member(conversation_id, auth.uid()));
create policy "senders can update own messages" on public.messages for update to authenticated using (sender_id = auth.uid()) with check (sender_id = auth.uid() and private.is_conversation_member(conversation_id, auth.uid()));
create policy "senders can delete own messages" on public.messages for delete to authenticated using (sender_id = auth.uid() and private.is_conversation_member(conversation_id, auth.uid()));

create or replace function public.create_direct_conversation(target_user uuid)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  existing_id uuid;
  new_id uuid;
begin
  if me is null then raise exception 'not_authenticated'; end if;
  if target_user is null or target_user = me then raise exception 'invalid_target'; end if;
  if not exists (select 1 from public.profiles where id = target_user) then raise exception 'user_not_found'; end if;
  perform pg_advisory_xact_lock(hashtextextended(least(me::text, target_user::text) || ':' || greatest(me::text, target_user::text), 0));
  select c.id into existing_id
  from public.conversations c
  where exists (select 1 from public.conversation_members cm where cm.conversation_id = c.id and cm.user_id = me)
    and exists (select 1 from public.conversation_members cm where cm.conversation_id = c.id and cm.user_id = target_user)
    and 2 = (select count(*) from public.conversation_members cm where cm.conversation_id = c.id)
  order by c.created_at asc limit 1;
  if existing_id is not null then return existing_id; end if;
  insert into public.conversations(created_by) values (me) returning id into new_id;
  insert into public.conversation_members(conversation_id, user_id) values (new_id, me), (new_id, target_user);
  return new_id;
end;
$$;
revoke all on function public.create_direct_conversation(uuid) from public;
grant execute on function public.create_direct_conversation(uuid) to authenticated;

drop function if exists public.list_my_conversations();
create function public.list_my_conversations()
returns table(
  conversation_id uuid,
  other_user_id uuid,
  other_username text,
  other_display_name text,
  last_message text,
  last_message_at timestamptz
)
language sql
security definer
stable
set search_path = public
as $$
  select c.id, other.user_id, p.username, p.display_name, last_msg.body, last_msg.created_at
  from public.conversations c
  join public.conversation_members mine on mine.conversation_id = c.id and mine.user_id = auth.uid()
  join public.conversation_members other on other.conversation_id = c.id and other.user_id <> auth.uid()
  join public.profiles p on p.id = other.user_id
  left join lateral (select m.body, m.created_at from public.messages m where m.conversation_id = c.id order by m.created_at desc limit 1) last_msg on true
  where auth.uid() is not null
  order by coalesce(last_msg.created_at, c.created_at) desc;
$$;
revoke all on function public.list_my_conversations() from public;
grant execute on function public.list_my_conversations() to authenticated;

do $$
begin
  if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'messages') then
    alter publication supabase_realtime add table public.messages;
  end if;
exception when undefined_object then null;
end $$;
