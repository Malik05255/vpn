# H Personal Assistant Lab

Isolated experimental build of lm_AI for **H / المساعد الشخصي H**.

- Baseline source: `Malik05255/VibeApp-Fs` main snapshot.
- Lab repository: `Malik05255/vpn`.
- Lab applicationId: `com.malik05255.lmai.hlab`.
- Production applicationId remains `com.malik05255.lmai` in the production repository.
- The lab is intended to install side-by-side with production lm_AI.
- H is one assistant identity across in-app chat, memory, reminders, location context and the WhatsApp gateway.
- Personal reminders are separate from programming/development reminders.
- WhatsApp, Maps and backend credentials must never be committed or embedded in the APK.

## H Supabase runtime deployment

The H Supabase Edge Functions are deployed by `.github/workflows/h-supabase-runtime-deploy.yml`.

Required GitHub Actions secrets:

- `SUPABASE_ACCESS_TOKEN`: Supabase personal access token used only by GitHub Actions deployment.
- `SUPABASE_PROJECT_REF`: target Supabase project reference.

The workflow can deploy all H functions on changes under `cloud/supabase/**`, or deploy one function manually with `workflow_dispatch`.

Current H Supabase functions:

- `h-openrouter-oauth`
- `h-provider-config`
- `h-tavily-config`
- `h-whatsapp-action`
- `h-whatsapp-inbox`
- `h-whatsapp-peach`

Secrets remain server-side. The workflow does not write Supabase credentials, provider API keys, WhatsApp credentials or Maps credentials into the repository or Android APK.

The functions use their existing application-level controls (runtime secret, setup token, OAuth state/callback validation, or provider-specific validation). The deployment therefore disables Supabase gateway JWT verification for these H functions so external callbacks and custom-authenticated runtime calls are not blocked before the function's own authorization logic runs.
