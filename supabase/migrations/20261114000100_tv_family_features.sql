-- VELNIX TV family profiles, parental locks, sports reminders and channel sharing.
create table if not exists public.tv_profiles (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 name text not null check (char_length(btrim(name)) between 1 and 32),
 avatar_id text not null default 'anime1' check (avatar_id ~ '^anime[1-8]$'),
 is_kids boolean not null default false,
 pin_hash text,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create index if not exists tv_profiles_user_idx on public.tv_profiles(user_id, created_at);
create table if not exists public.tv_profile_settings (
 profile_id uuid primary key references public.tv_profiles(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 subtitle_language text not null default 'off',
 audio_language text not null default 'default',
 max_content_rating integer not null default 18 check(max_content_rating between 0 and 18),
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create table if not exists public.tv_profile_channel_locks (
 profile_id uuid not null references public.tv_profiles(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 channel_id uuid not null references public.channels(id) on delete cascade,
 created_at timestamptz not null default now(),
 primary key(profile_id, channel_id)
);
create table if not exists public.tv_profile_favorites (
 profile_id uuid not null references public.tv_profiles(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 channel_id uuid not null references public.channels(id) on delete cascade,
 created_at timestamptz not null default now(),
 primary key(profile_id, channel_id)
);
create table if not exists public.tv_profile_history (
 profile_id uuid not null references public.tv_profiles(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 channel_id uuid not null references public.channels(id) on delete cascade,
 watched_at timestamptz not null default now(),
 primary key(profile_id, channel_id)
);
create table if not exists public.tv_match_fixtures (
 id uuid primary key default gen_random_uuid(),
 home_team text not null,
 away_team text not null,
 competition text not null default '',
 starts_at timestamptz not null,
 channel_id uuid references public.channels(id) on delete set null,
 status text not null default 'scheduled' check(status in ('scheduled','live','finished','postponed')),
 home_score integer, away_score integer,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create table if not exists public.tv_match_reminders (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 profile_id uuid references public.tv_profiles(id) on delete cascade,
 fixture_id uuid not null references public.tv_match_fixtures(id) on delete cascade,
 remind_before_minutes integer not null default 15 check(remind_before_minutes in (5,10,15,30,60)),
 created_at timestamptz not null default now(),
 unique(user_id, profile_id, fixture_id)
);
create table if not exists public.tv_channel_shares (
 id uuid primary key default gen_random_uuid(),
 owner_id uuid not null references auth.users(id) on delete cascade,
 channel_id uuid not null references public.channels(id) on delete cascade,
 share_code text not null unique,
 expires_at timestamptz not null,
 max_uses integer not null default 1 check(max_uses between 1 and 20),
 uses integer not null default 0 check(uses >= 0),
 created_at timestamptz not null default now()
);
alter table public.tv_profiles enable row level security;
alter table public.tv_profile_settings enable row level security;
alter table public.tv_profile_channel_locks enable row level security;
alter table public.tv_profile_favorites enable row level security;
alter table public.tv_profile_history enable row level security;
alter table public.tv_match_fixtures enable row level security;
alter table public.tv_match_reminders enable row level security;
alter table public.tv_channel_shares enable row level security;
do $$
declare t text;
begin
 foreach t in array array['tv_profiles','tv_profile_settings','tv_profile_channel_locks','tv_profile_favorites','tv_profile_history','tv_match_reminders'] loop
  execute format('drop policy if exists %I on public.%I', t||'_owner_all',t);
  execute format('create policy %I on public.%I for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))',t||'_owner_all',t);
 end loop;
end $$;
drop policy if exists tv_match_fixtures_read_authenticated on public.tv_match_fixtures;
create policy tv_match_fixtures_read_authenticated on public.tv_match_fixtures for select to authenticated using (true);
drop policy if exists tv_channel_shares_owner_read on public.tv_channel_shares;
create policy tv_channel_shares_owner_read on public.tv_channel_shares for select to authenticated using (owner_id = (select auth.uid()));
drop policy if exists tv_channel_shares_owner_write on public.tv_channel_shares;
create policy tv_channel_shares_owner_write on public.tv_channel_shares for insert to authenticated with check (owner_id = (select auth.uid()));
grant select,insert,update,delete on public.tv_profiles,public.tv_profile_settings,public.tv_profile_channel_locks,public.tv_profile_favorites,public.tv_profile_history,public.tv_match_reminders,public.tv_channel_shares to authenticated;
grant select on public.tv_match_fixtures to authenticated;
