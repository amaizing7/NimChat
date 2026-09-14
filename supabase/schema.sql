-- NimChat backend schema
create extension if not exists pgcrypto;

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

alter table public.conversations add column if not exists created_by uuid references auth.users(id) on delete cascade;
alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages enable row level security;

create or replace function public.is_conversation_member(target_conversation uuid, target_user uuid default auth.uid())
returns boolean language sql security definer set search_path = public stable
as $$ select exists (select 1 from public.conversation_members where conversation_id = target_conversation and user_id = target_user); $$;
revoke all on function public.is_conversation_member(uuid, uuid) from public;
grant execute on function public.is_conversation_member(uuid, uuid) to authenticated;

drop policy if exists "profiles readable by authenticated users" on public.profiles;
drop policy if exists "users create own profile" on public.profiles;
drop policy if exists "users update own profile" on public.profiles;
drop policy if exists "members can read conversations" on public.conversations;
drop policy if exists "authenticated users can create conversations" on public.conversations;
drop policy if exists "members can read membership" on public.conversation_members;
drop policy if exists "users can add themselves or another member after joining" on public.conversation_members;
drop policy if exists "members can read messages" on public.messages;
drop policy if exists "members can send messages" on public.messages;

create policy "profiles readable by authenticated users" on public.profiles for select to authenticated using (true);
create policy "users create own profile" on public.profiles for insert to authenticated with check (id = auth.uid());
create policy "users update own profile" on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
create policy "members can read conversations" on public.conversations for select to authenticated using (created_by = auth.uid() or public.is_conversation_member(id, auth.uid()));
create policy "authenticated users can create conversations" on public.conversations for insert to authenticated with check (created_by = auth.uid());
create policy "members can read membership" on public.conversation_members for select to authenticated using (user_id = auth.uid() or public.is_conversation_member(conversation_id, auth.uid()));
create policy "users can add themselves or another member after joining" on public.conversation_members for insert to authenticated with check (user_id = auth.uid() or public.is_conversation_member(conversation_id, auth.uid()));
create policy "members can read messages" on public.messages for select to authenticated using (public.is_conversation_member(conversation_id, auth.uid()));
create policy "members can send messages" on public.messages for insert to authenticated with check (sender_id = auth.uid() and public.is_conversation_member(conversation_id, auth.uid()));

alter publication supabase_realtime add table public.messages;
