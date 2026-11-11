create table if not exists public.subscription_access_codes (
  id uuid primary key default gen_random_uuid(),
  code_hash text not null unique,
  code_hint text not null,
  plan_code text not null check (plan_code in ('basic','standard','premium','gold','platinum')),
  valid_days integer not null check (valid_days between 1 and 3650),
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  used_by uuid references auth.users(id) on delete set null,
  redeemed_at timestamptz,
  access_expires_at timestamptz,
  revoked_at timestamptz
);

alter table public.subscription_access_codes enable row level security;
revoke all on public.subscription_access_codes from anon, authenticated;
