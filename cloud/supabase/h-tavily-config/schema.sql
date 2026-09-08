-- H server-side research credential support. Service-role only; no client policies.

alter table public.h_runtime_ai_setup_links
  add column if not exists purpose text not null default 'openrouter';

alter table public.h_runtime_ai_credentials
  drop constraint if exists h_runtime_ai_credentials_provider_check;

alter table public.h_runtime_ai_credentials
  add constraint h_runtime_ai_credentials_provider_check
  check (provider in ('openrouter', 'tavily', 'exa', 'foursquare', 'openrouteservice'));

create index if not exists h_runtime_ai_setup_links_purpose_expires_idx
  on public.h_runtime_ai_setup_links(purpose, expires_at);
