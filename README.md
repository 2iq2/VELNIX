# VELNIX

Premium bilingual live-entertainment web app.

## Current implementation
- Responsive Arabic/English interface with RTL/LTR switching
- Supabase email/password sign-up, sign-in, sign-out and password recovery
- Authenticated channel catalogue with paginated loading
- Dynamic channel categories and administrator-only Xtream import control
- HLS playback and MPEG-TS playback loaded on demand
- Web app manifest and network-first HTML service-worker strategy

## Deployment
Hosted with GitHub Pages from the `main` branch.

## Security and content
- Supabase publishable key is intended for browser use; privileged service-role keys must remain server-side.
- Stream credentials are stored as server-side Supabase secrets and must not be committed to this repository.
- Only channels and streams VELNIX is authorized to distribute should be published.
