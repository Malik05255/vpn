import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  parseConfigurableProvider,
  providerBoundSetupMaterial,
  providerCredentialId,
  providerEncryptionLabel,
  providerFreeOnlyMetadata,
  providerSpec,
  providerStateKey,
  type ConfigurableProvider,
} from "./provider-policy.ts";

const FUNCTION_NAME = "h-provider-config";
const SETUP_TTL_MS = 10 * 60 * 1000;
const CREDENTIAL_VERSION = 1;

type DbClient = any;

type ValidationResult = {
  ok: true;
  provider: ConfigurableProvider;
  detail: Record<string, unknown>;
};

Deno.serve(async (req: Request) => {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!supabaseUrl || !serviceRole) return json({ ok: false, error: "runtime credentials unavailable" }, 500);

  const db = createClient(supabaseUrl, serviceRole, { auth: { persistSession: false } });
  const url = new URL(req.url);
  const path = routePath(url.pathname);
  const publicBase = `${supabaseUrl}/functions/v1/${FUNCTION_NAME}`;

  try {
    if (req.method === "GET" && ["/", "/health"].includes(path)) {
      return json({
        ok: true,
        service: FUNCTION_NAME,
        providers: ["openrouteservice", "foursquare"],
        freeOnly: true,
        secretsInApk: false,
      });
    }

    if (req.method === "GET" && path === "/status") {
      const provider = requireProvider(url.searchParams.get("provider"));
      return json(await getStatus(db, provider));
    }

    if (req.method === "POST" && path === "/setup-link") {
      if (!await isRuntimeAdmin(req, db)) return json({ ok: false, error: "Unauthorized" }, 401);
      const body = await readJsonBody(req);
      const provider = requireProvider(body?.provider ?? url.searchParams.get("provider"));
      const rawToken = randomUrlSafe(32);
      const tokenHash = await sha256Base64Url(providerBoundSetupMaterial(provider, rawToken));
      const expiresAt = new Date(Date.now() + SETUP_TTL_MS).toISOString();
      const { error } = await db.from("h_runtime_ai_setup_links").insert({
        token_hash: tokenHash,
        expires_at: expiresAt,
      });
      if (error) throw error;
      const connectUrl = new URL(`${publicBase}/connect`);
      connectUrl.searchParams.set("provider", provider);
      connectUrl.searchParams.set("setup", rawToken);
      return json({ ok: true, provider, expiresAt, connectUrl: connectUrl.toString() });
    }

    if (req.method === "GET" && path === "/connect") {
      const provider = requireProvider(url.searchParams.get("provider"));
      const setup = url.searchParams.get("setup")?.trim() || "";
      const valid = await validateSetupToken(db, provider, setup);
      if (!valid.ok) return html(errorPage(valid.error || "Link invalid or expired"), 400);
      return html(connectPage(publicBase, provider, setup));
    }

    if (req.method === "POST" && path === "/save") {
      const provider = requireProvider(url.searchParams.get("provider"));
      const setup = url.searchParams.get("setup")?.trim() || "";
      const valid = await validateSetupToken(db, provider, setup);
      if (!valid.ok) return html(errorPage(valid.error || "Link invalid or expired"), 400);

      const form = await req.formData();
      const apiKey = String(form.get("api_key") || "").trim();
      if (apiKey.length < 8 || apiKey.length > 1000) return html(errorPage("API key format is invalid."), 400);

      const validation = await validateProviderKey(provider, apiKey);
      const validatedAt = new Date().toISOString();
      const encrypted = await encryptProviderSecret(provider, apiKey);
      const metadata = {
        ...providerFreeOnlyMetadata(provider, validatedAt),
        validation_detail: validation.detail,
      };
      const credentialId = providerCredentialId(provider);

      const { error: credentialError } = await db.from("h_runtime_ai_credentials").upsert({
        id: credentialId,
        provider,
        secret_ciphertext: encrypted.ciphertext,
        secret_iv: encrypted.iv,
        secret_version: CREDENTIAL_VERSION,
        selected_model: null,
        model_verified_at: null,
        oauth_metadata: metadata,
        connected_at: validatedAt,
        updated_at: validatedAt,
      }, { onConflict: "id" });
      if (credentialError) throw credentialError;

      const { error: tokenError } = await db.from("h_runtime_ai_setup_links")
        .update({ used_at: validatedAt })
        .eq("token_hash", valid.hash)
        .is("used_at", null);
      if (tokenError) throw tokenError;

      await db.from("h_runtime_state").upsert({
        key: providerStateKey(provider),
        value: {
          provider,
          connected: true,
          ready: true,
          free_only: true,
          allow_paid: false,
          validated_at: validatedAt,
          validation_endpoint: providerSpec(provider).validationEndpoint,
          api_version: providerSpec(provider).apiVersion,
        },
        updated_at: validatedAt,
      }, { onConflict: "key" });

      return html(successPage(provider));
    }

    if (req.method === "POST" && path === "/disconnect") {
      if (!await isRuntimeAdmin(req, db)) return json({ ok: false, error: "Unauthorized" }, 401);
      const body = await readJsonBody(req);
      const provider = requireProvider(body?.provider ?? url.searchParams.get("provider"));
      await db.from("h_runtime_ai_credentials")
        .delete()
        .eq("id", providerCredentialId(provider))
        .eq("provider", provider);
      const now = new Date().toISOString();
      await db.from("h_runtime_state").upsert({
        key: providerStateKey(provider),
        value: { provider, connected: false, ready: false, free_only: true, allow_paid: false },
        updated_at: now,
      }, { onConflict: "key" });
      return json({ ok: true, provider, connected: false });
    }

    return json({ ok: false, error: "Not found", path }, 404);
  } catch (error) {
    const message = errorMessage(error);
    console.error("h-provider-config failed", message);
    if (path === "/connect" || path === "/save") return html(errorPage(message), 500);
    return json({ ok: false, error: message }, 500);
  }
});

async function getStatus(db: DbClient, provider: ConfigurableProvider) {
  const { data: credential, error } = await db.from("h_runtime_ai_credentials")
    .select("provider,secret_version,oauth_metadata,connected_at,updated_at")
    .eq("id", providerCredentialId(provider))
    .eq("provider", provider)
    .maybeSingle();
  if (error) throw error;
  if (!credential) return { ok: true, provider, connected: false, ready: false, freeOnly: true };
  const metadata = isRecord(credential.oauth_metadata) ? credential.oauth_metadata : {};
  const ready = Number(credential.secret_version || 1) === CREDENTIAL_VERSION && metadata.free_only === true && metadata.allow_paid !== true;
  return {
    ok: true,
    provider,
    connected: true,
    ready,
    freeOnly: true,
    allowPaid: false,
    validatedAt: metadata.validated_at ?? null,
    connectedAt: credential.connected_at ?? null,
    updatedAt: credential.updated_at ?? null,
  };
}

async function validateProviderKey(provider: ConfigurableProvider, apiKey: string): Promise<ValidationResult> {
  return provider === "openrouteservice"
    ? validateOpenRouteService(apiKey)
    : validateFoursquare(apiKey);
}

async function validateOpenRouteService(apiKey: string): Promise<ValidationResult> {
  const url = new URL("https://api.heigit.org/pelias/v1/search");
  url.searchParams.set("text", "Riyadh");
  url.searchParams.set("size", "1");
  url.searchParams.set("boundary.country", "SA");
  const response = await fetch(url, { headers: { Authorization: apiKey, Accept: "application/json" } });
  const text = await response.text();
  if (!response.ok) throw new Error(`OpenRouteService rejected the key (${response.status}): ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const feature = Array.isArray(body?.features) ? body.features[0] : null;
  const coordinates = feature?.geometry?.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2 || !Number.isFinite(Number(coordinates[0])) || !Number.isFinite(Number(coordinates[1]))) {
    throw new Error("OpenRouteService validation returned no usable geocode result");
  }
  return {
    ok: true,
    provider: "openrouteservice",
    detail: {
      probe: "geocode",
      result_label: String(feature?.properties?.label || feature?.properties?.name || "Riyadh").slice(0, 120),
    },
  };
}

async function validateFoursquare(apiKey: string): Promise<ValidationResult> {
  const url = new URL("https://places-api.foursquare.com/places/search");
  url.searchParams.set("query", "coffee");
  url.searchParams.set("ll", "24.7136,46.6753");
  url.searchParams.set("radius", "1000");
  url.searchParams.set("limit", "1");
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: "application/json",
      "X-Places-Api-Version": "2025-06-17",
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Foursquare rejected the key (${response.status}): ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const rows = Array.isArray(body?.results) ? body.results : Array.isArray(body) ? body : null;
  if (!rows) throw new Error("Foursquare validation returned an unexpected response");
  return {
    ok: true,
    provider: "foursquare",
    detail: { probe: "places_search", api_version: "2025-06-17", result_count: rows.length },
  };
}

async function validateSetupToken(
  db: DbClient,
  provider: ConfigurableProvider,
  rawToken: string,
): Promise<{ ok: boolean; hash: string; error?: string }> {
  if (!rawToken) return { ok: false, hash: "", error: "Link is incomplete." };
  const hash = await sha256Base64Url(providerBoundSetupMaterial(provider, rawToken));
  const { data, error } = await db.from("h_runtime_ai_setup_links")
    .select("expires_at,used_at")
    .eq("token_hash", hash)
    .maybeSingle();
  if (error) throw error;
  if (!data) return { ok: false, hash, error: "Link is invalid for this provider." };
  if (data.used_at) return { ok: false, hash, error: "This link was already used." };
  if (new Date(data.expires_at).getTime() <= Date.now()) return { ok: false, hash, error: "Link expired. Request a new link." };
  return { ok: true, hash };
}

async function isRuntimeAdmin(req: Request, db: DbClient): Promise<boolean> {
  const provided = req.headers.get("x-h-runtime-secret")?.trim() || "";
  if (!provided) return false;
  const { data, error } = await db.from("h_runtime_config")
    .select("secret_value")
    .eq("key", "poll_secret")
    .maybeSingle();
  if (error) return false;
  const expected = String(data?.secret_value || "");
  return expected.length > 0 && constantTimeEquals(expected, provided);
}

async function encryptProviderSecret(provider: ConfigurableProvider, value: string) {
  const root = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!root) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not configured");
  const material = `${providerEncryptionLabel(provider)}:${root}`;
  const digest = await crypto.subtle.digest("SHA-256", toArrayBuffer(new TextEncoder().encode(material)));
  const key = await crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: toArrayBuffer(iv) },
    key,
    toArrayBuffer(new TextEncoder().encode(value)),
  );
  return { ciphertext: encodeBase64Url(new Uint8Array(encrypted)), iv: encodeBase64Url(iv) };
}

function requireProvider(value: unknown): ConfigurableProvider {
  const provider = parseConfigurableProvider(value);
  if (!provider) throw new Error("Unsupported provider. Use openrouteservice or foursquare.");
  return provider;
}

async function readJsonBody(req: Request): Promise<Record<string, unknown>> {
  const contentType = req.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) return {};
  const body = await req.json().catch(() => ({}));
  return isRecord(body) ? body : {};
}

function connectPage(base: string, provider: ConfigurableProvider, setup: string): string {
  const spec = providerSpec(provider);
  const action = new URL(`${base}/save`);
  action.searchParams.set("provider", provider);
  action.searchParams.set("setup", setup);
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Connect ${escapeHtml(spec.displayName)}</title><style>${css()}</style></head><body><main><h1>Connect ${escapeHtml(spec.displayName)} to H</h1><p>Enter the provider API key. H stores it encrypted on the server and marks this credential free-only.</p><form method="post" action="${escapeHtml(action.toString())}"><label>API Key</label><input name="api_key" type="password" autocomplete="off" required><button type="submit">Connect provider</button></form><p class="small">The key is never stored in GitHub or the Android APK. H will not opt into paid fallback automatically.</p></main></body></html>`;
}

function successPage(provider: ConfigurableProvider): string {
  const spec = providerSpec(provider);
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Connected</title><style>${css()}</style></head><body><main><h1>${escapeHtml(spec.displayName)} connected ✅</h1><p>The key was validated live and stored encrypted for H.</p><p>Paid fallback remains disabled.</p></main></body></html>`;
}

function errorPage(message: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Error</title><style>${css()}</style></head><body><main><h1>Could not connect provider</h1><p>${escapeHtml(message)}</p></main></body></html>`;
}

function css(): string {
  return `body{font-family:system-ui,sans-serif;background:#f6f7f9;color:#15171a;margin:0;padding:24px}main{max-width:560px;margin:8vh auto;background:#fff;padding:28px;border-radius:18px;box-shadow:0 8px 30px #00000012}h1{font-size:24px}p{line-height:1.65}label{display:block;margin:20px 0 8px;font-weight:700}input{box-sizing:border-box;width:100%;padding:14px;border:1px solid #cfd4da;border-radius:12px;font-size:16px}button{width:100%;margin-top:14px;padding:14px;border:0;border-radius:12px;background:#111;color:#fff;font-size:16px;font-weight:700}.small{font-size:13px;color:#626a73}`;
}

function routePath(pathname: string): string {
  const markers = [`/functions/v1/${FUNCTION_NAME}`, `/${FUNCTION_NAME}`];
  for (const marker of markers) {
    const index = pathname.indexOf(marker);
    if (index >= 0) {
      const rest = pathname.slice(index + marker.length);
      return rest ? (rest.startsWith("/") ? rest : `/${rest}`) : "/";
    }
  }
  for (const route of ["health", "status", "setup-link", "connect", "save", "disconnect"]) {
    if (pathname === `/${route}` || pathname.endsWith(`/${route}`)) return `/${route}`;
  }
  return pathname || "/";
}

function constantTimeEquals(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  const length = Math.max(left.length, right.length);
  let diff = left.length ^ right.length;
  for (let i = 0; i < length; i += 1) diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return diff === 0;
}

function randomUrlSafe(bytes: number): string {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return encodeBase64Url(value);
}

async function sha256Base64Url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", toArrayBuffer(new TextEncoder().encode(value)));
  return encodeBase64Url(new Uint8Array(digest));
}

function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function escapeHtml(value: string): string {
  return String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char] || char));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

function html(value: string, status = 200): Response {
  return new Response(new TextEncoder().encode(value), { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
}
