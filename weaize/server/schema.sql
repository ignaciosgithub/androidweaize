-- Supabase / Postgres schema for Weaize encrypted location history.
-- Run this in the Supabase SQL editor (or psql) once.
--
-- The payload column only ever contains AES-256-GCM ciphertext produced on the
-- phone; the server (and anyone with the anon API key) cannot read locations.
-- Only holders of the private key can decrypt.

create table if not exists public.locations (
    id bigint generated always as identity primary key,
    device_id uuid not null,
    recorded_at timestamptz not null,
    payload text not null,
    inserted_at timestamptz not null default now()
);

create index if not exists locations_device_recorded_idx
    on public.locations (device_id, recorded_at desc);

alter table public.locations enable row level security;

-- The app and viewer use the anon key: allow inserts and reads, but never
-- updates or deletes, so previous locations can never be removed via the API.
create policy "anon can insert locations"
    on public.locations for insert
    to anon
    with check (true);

create policy "anon can read locations"
    on public.locations for select
    to anon
    using (true);

-- No update/delete policies: history is append-only through the API.
