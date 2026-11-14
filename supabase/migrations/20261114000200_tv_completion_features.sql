-- VELNIX TV completion: secure profile PINs, category locks, sports sync/predictions, share redemption and mobile avatars.

alter table public.tv_profiles
  add column if not exists pin_enabled boolean not null default false,
  add column if not exists avatar_url text;

update public.tv_profiles
set pin_enabled = (pin_hash is not null and btrim(pin_hash) <> '')
where pin_enabled is distinct from (pin_hash is not null and btrim(pin_hash) <> '');

revoke select, insert, update on public.tv_profiles from authenticated;
grant select (id, user_id, name, avatar_id, is_kids, pin_enabled, avatar_url, created_at, updated_at)
  on public.tv_profiles to authenticated;
grant delete on public.tv_profiles to authenticated;
grant update (avatar_url, updated_at) on public.tv_profiles to authenticated;

create or replace function public.create_tv_profile(p_name text,p_avatar_id text default 'anime1',p_is_kids boolean default false,p_pin text default null)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_user_id uuid := auth.uid(); v_profile_id uuid; v_pin text := nullif(btrim(coalesce(p_pin,'')),'');
begin
 if v_user_id is null then raise exception 'Authentication required'; end if;
 if char_length(btrim(coalesce(p_name,''))) not between 1 and 32 then raise exception 'Profile name must be 1 to 32 characters'; end if;
 if coalesce(p_avatar_id,'anime1') !~ '^anime[1-8]$' then raise exception 'Invalid avatar'; end if;
 if v_pin is not null and v_pin !~ '^\d{4,6}$' then raise exception 'PIN must contain 4 to 6 digits'; end if;
 insert into public.tv_profiles(user_id,name,avatar_id,is_kids,pin_hash,pin_enabled)
 values(v_user_id,btrim(p_name),coalesce(p_avatar_id,'anime1'),coalesce(p_is_kids,false),
 case when v_pin is null then null else extensions.crypt(v_pin,extensions.gen_salt('bf',12)) end,v_pin is not null)
 returning id into v_profile_id;
 return v_profile_id;
end; $$;

create or replace function public.set_tv_profile_pin(p_profile_id uuid,p_pin text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_user_id uuid := auth.uid(); v_pin text := nullif(btrim(coalesce(p_pin,'')),''); v_rows integer;
begin
 if v_user_id is null then raise exception 'Authentication required'; end if;
 if v_pin is not null and v_pin !~ '^\d{4,6}$' then raise exception 'PIN must contain 4 to 6 digits'; end if;
 update public.tv_profiles set pin_hash=case when v_pin is null then null else extensions.crypt(v_pin,extensions.gen_salt('bf',12)) end,
 pin_enabled=(v_pin is not null),updated_at=now() where id=p_profile_id and user_id=v_user_id;
 get diagnostics v_rows = row_count; return v_rows=1;
end; $$;

create or replace function public.verify_tv_profile_pin(p_profile_id uuid,p_pin text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_user_id uuid := auth.uid(); v_hash text; v_matches boolean := false;
begin
 if v_user_id is null or coalesce(p_pin,'') !~ '^\d{4,6}$' then return false; end if;
 select pin_hash into v_hash from public.tv_profiles where id=p_profile_id and user_id=v_user_id;
 if not found then return false; end if;
 if v_hash is null or btrim(v_hash)='' then return true; end if;
 if v_hash like '$2%' then v_matches := extensions.crypt(p_pin,v_hash)=v_hash;
 else
  v_matches := encode(extensions.digest(p_pin,'sha256'),'hex')=v_hash;
  if v_matches then update public.tv_profiles set pin_hash=extensions.crypt(p_pin,extensions.gen_salt('bf',12)),pin_enabled=true,updated_at=now()
    where id=p_profile_id and user_id=v_user_id; end if;
 end if;
 return v_matches;
end; $$;

revoke all on function public.create_tv_profile(text,text,boolean,text) from public,anon;
revoke all on function public.set_tv_profile_pin(uuid,text) from public,anon;
revoke all on function public.verify_tv_profile_pin(uuid,text) from public,anon;
grant execute on function public.create_tv_profile(text,text,boolean,text) to authenticated;
grant execute on function public.set_tv_profile_pin(uuid,text) to authenticated;
grant execute on function public.verify_tv_profile_pin(uuid,text) to authenticated;

create table if not exists public.tv_profile_category_locks (
 profile_id uuid not null references public.tv_profiles(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 category_key text not null check(category_key ~ '^[a-z0-9_-]{1,60}$'),
 created_at timestamptz not null default now(),
 primary key(profile_id,category_key)
);
alter table public.tv_profile_category_locks enable row level security;
drop policy if exists tv_profile_category_locks_owner_all on public.tv_profile_category_locks;
create policy tv_profile_category_locks_owner_all on public.tv_profile_category_locks for all to authenticated
 using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
grant select,insert,update,delete on public.tv_profile_category_locks to authenticated;

alter table public.tv_match_fixtures add column if not exists source_provider text,add column if not exists source_event_id text;
create unique index if not exists tv_match_fixtures_provider_event_unique on public.tv_match_fixtures(source_provider,source_event_id)
 where source_provider is not null and source_event_id is not null;

create table if not exists public.tv_match_predictions (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 fixture_id uuid not null references public.tv_match_fixtures(id) on delete cascade,
 profile_id uuid references public.tv_profiles(id) on delete cascade,
 predicted_home_score integer not null check(predicted_home_score between 0 and 30),
 predicted_away_score integer not null check(predicted_away_score between 0 and 30),
 created_at timestamptz not null default now(),updated_at timestamptz not null default now(),
 unique(user_id,fixture_id)
);
alter table public.tv_match_predictions enable row level security;
drop policy if exists tv_match_predictions_owner_all on public.tv_match_predictions;
create policy tv_match_predictions_owner_all on public.tv_match_predictions for all to authenticated
 using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
grant select,insert,update,delete on public.tv_match_predictions to authenticated;

create table if not exists public.tv_sports_feed_state (
 id boolean primary key default true check(id),last_synced_at timestamptz,last_error text,updated_at timestamptz not null default now()
);
alter table public.tv_sports_feed_state enable row level security;

create or replace function public.redeem_tv_channel_share(p_share_code text,p_profile_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_user_id uuid := auth.uid(); v_channel_id uuid;
begin
 if v_user_id is null then raise exception 'Authentication required'; end if;
 if not exists(select 1 from public.tv_profiles p where p.id=p_profile_id and p.user_id=v_user_id) then raise exception 'Profile not found'; end if;
 update public.tv_channel_shares s set uses=uses+1
 where s.share_code=upper(btrim(coalesce(p_share_code,''))) and s.expires_at>now() and s.uses<s.max_uses
 returning s.channel_id into v_channel_id;
 if v_channel_id is null then raise exception 'Share code is invalid, expired or already used'; end if;
 insert into public.tv_profile_favorites(user_id,profile_id,channel_id) values(v_user_id,p_profile_id,v_channel_id)
 on conflict(profile_id,channel_id) do nothing;
 return v_channel_id;
end; $$;
revoke all on function public.redeem_tv_channel_share(text,uuid) from public,anon;
grant execute on function public.redeem_tv_channel_share(text,uuid) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('tv-avatars','tv-avatars',true,3145728,array['image/jpeg','image/png','image/webp'])
on conflict(id) do update set public=true,file_size_limit=3145728,allowed_mime_types=array['image/jpeg','image/png','image/webp'];

drop policy if exists tv_avatars_public_read on storage.objects;
create policy tv_avatars_public_read on storage.objects for select to public using(bucket_id='tv-avatars');
drop policy if exists tv_avatars_owner_insert on storage.objects;
create policy tv_avatars_owner_insert on storage.objects for insert to authenticated
 with check(bucket_id='tv-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text);
drop policy if exists tv_avatars_owner_update on storage.objects;
create policy tv_avatars_owner_update on storage.objects for update to authenticated
 using(bucket_id='tv-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text)
 with check(bucket_id='tv-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text);
grant select,insert,update on storage.objects to authenticated;
