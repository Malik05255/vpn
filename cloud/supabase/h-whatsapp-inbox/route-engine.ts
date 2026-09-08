import {
  classifyTaskPriority,
  detectExplicitPriority,
  type HTaskPriority,
} from "./task-manager.ts";

const ORS_BASE_URL = "https://api.heigit.org";
const ORS_CREDENTIAL_ID = "openrouteservice_default";
const ROUTE_STATE_KEY = "route_provider_openrouteservice";
const DEFAULT_PROFILE = "driving-car";
const MAX_ENDPOINT_CHARS = 120;

type DbClient = any;

type ProviderCredential = {
  key: string;
  metadata: Record<string, unknown>;
  source: "encrypted_db" | "environment";
};

export type RoutePoint = {
  lat: number;
  lon: number;
  label: string;
};

export type VerifiedRoute = {
  provider: "openrouteservice";
  profile: "driving-car";
  origin: RoutePoint;
  destination: RoutePoint;
  distanceMeters: number;
  durationSeconds: number;
  distanceKm: number;
  durationMinutes: number;
  verifiedAt: string;
  sourceUrl: string;
  navigationUrl: string;
};

export type RouteResearchResult = {
  handled: true;
  query: string;
  status: "verified" | "missing_endpoints" | "not_configured" | "quota_exhausted" | "provider_error";
  route: VerifiedRoute | null;
  priority: HTaskPriority;
  providerTrace: string[];
  hardConstraints: string[];
  context: string;
};

export type ParsedRouteEndpoints = {
  origin: string | null;
  destination: string | null;
};

/**
 * Route Engine v1 intentionally handles direct route / distance requests only.
 * Route-aware restaurant/hotel discovery is a separate policy layer because it
 * needs both route geometry and place candidates; treating it as a plain route
 * here would silently drop the user's place constraints.
 */
export function isDirectRouteRequest(text: string): boolean {
  const q = normalizeArabic(text || "");
  if (!q.trim()) return false;
  if (looksLikePlaceDiscovery(q)) return false;
  return /(اسرع\s*طريق|المسافه\s*بين|المسافة\s*بين|طريق\s+من|من\s+.+\s+(?:الى|إلى)\s+|كيف\s*(?:اروح|أروح|اوصل|أوصل)|كم\s*(?:تبعد|يبعد)|وقت\s*الوصول|route\s+from|directions?\s+from|distance\s+from|from\s+.+\s+to\s+)/iu.test(q);
}

export function parseRouteEndpoints(text: string): ParsedRouteEndpoints {
  const cleaned = cleanRouteText(text);

  let match = cleaned.match(/(?:^|\s)من\s+(.+?)\s+(?:الى|إلى)\s+(.+?)(?:[؟?!.,،]|$)/iu);
  if (match) return endpointPair(match[1], match[2]);

  match = cleaned.match(/(?:^|\s)(?:from|directions?\s+from|route\s+from|distance\s+from)\s+(.+?)\s+to\s+(.+?)(?:[?!.;,]|$)/iu);
  if (match) return endpointPair(match[1], match[2]);

  // Arabic natural form: "كم تبعد محايل عن أبها؟" => origin=أبها, destination=محايل.
  match = cleaned.match(/كم\s*(?:تبعد|يبعد)\s+(.+?)\s+عن\s+(.+?)(?:[؟?!.,،]|$)/iu);
  if (match) return endpointPair(match[2], match[1]);

  // "المسافة بين أبها ومحايل" / "المسافه بين أبها و محايل"
  match = cleaned.match(/المساف(?:ة|ه)\s+بين\s+(.+?)\s+و\s*(.+?)(?:[؟?!.,،]|$)/iu);
  if (match) return endpointPair(match[1], match[2]);

  return { origin: null, destination: null };
}

export async function prepareVerifiedRouteResearch(
  db: DbClient,
  messages: Array<Record<string, string>>,
): Promise<RouteResearchResult | null> {
  const query = latestUserMessage(messages);
  if (!isDirectRouteRequest(query)) return null;

  const priority = detectExplicitPriority(query) ?? classifyTaskPriority(query, "route");
  const parsed = parseRouteEndpoints(query);
  const providerTrace: string[] = [];
  const hardConstraints = ["route_facts_must_be_provider_verified=true"];

  if (!parsed.origin || !parsed.destination) {
    providerTrace.push("route_missing_endpoints");
    return {
      handled: true,
      query,
      status: "missing_endpoints",
      route: null,
      priority,
      providerTrace,
      hardConstraints,
      context: buildMissingEndpointContext(query, parsed, priority),
    };
  }

  hardConstraints.push(`route_origin=${parsed.origin}`, `route_destination=${parsed.destination}`);

  const credential = await loadCredential(db).catch((error) => {
    providerTrace.push(`openrouteservice_credential_error:${errorMessage(error).slice(0, 100)}`);
    return null;
  });
  if (!credential) {
    providerTrace.push("openrouteservice_not_connected");
    return {
      handled: true,
      query,
      status: "not_configured",
      route: null,
      priority,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, "OpenRouteService API key is not configured."),
    };
  }

  if (!providerFreeOnly(credential.metadata)) {
    providerTrace.push("openrouteservice_free_only_guard");
    return {
      handled: true,
      query,
      status: "not_configured",
      route: null,
      priority,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, "The route credential is not enrolled as free-only."),
    };
  }

  const quota = await loadQuotaState(db).catch(() => null);
  if (quota && quota.remaining <= 0 && quota.resetAt && quota.resetAt.getTime() > Date.now()) {
    providerTrace.push("openrouteservice_quota_guard");
    return {
      handled: true,
      query,
      status: "quota_exhausted",
      route: null,
      priority,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, "The verified free route quota is exhausted until its provider reset."),
    };
  }

  try {
    const origin = await geocode(credential.key, parsed.origin, providerTrace, db);
    const destination = await geocode(credential.key, parsed.destination, providerTrace, db);
    if (!origin || !destination) {
      providerTrace.push("openrouteservice_geocode_no_match");
      return {
        handled: true,
        query,
        status: "provider_error",
        route: null,
        priority,
        providerTrace,
        hardConstraints,
        context: buildUnavailableContext(query, priority, "One or both route endpoints could not be geocoded reliably."),
      };
    }

    const route = await directions(credential.key, origin, destination, providerTrace, db);
    providerTrace.push(`openrouteservice:${credential.source}:verified`);
    return {
      handled: true,
      query,
      status: "verified",
      route,
      priority,
      providerTrace,
      hardConstraints,
      context: buildVerifiedRouteContext(query, priority, route, providerTrace),
    };
  } catch (error) {
    const message = errorMessage(error);
    providerTrace.push(`openrouteservice_error:${message.slice(0, 120)}`);
    return {
      handled: true,
      query,
      status: /429|quota|rate/i.test(message) ? "quota_exhausted" : "provider_error",
      route: null,
      priority,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, `Verified route lookup failed: ${message.slice(0, 160)}`),
    };
  }
}

async function geocode(
  apiKey: string,
  text: string,
  trace: string[],
  db: DbClient,
): Promise<RoutePoint | null> {
  const url = new URL(`${ORS_BASE_URL}/pelias/v1/search`);
  url.searchParams.set("text", text);
  url.searchParams.set("size", "1");
  if (looksSaudi(text)) url.searchParams.set("boundary.country", "SA");

  const response = await fetch(url, {
    headers: { Authorization: apiKey, Accept: "application/json" },
  });
  await recordRateLimitHeaders(db, response, "geocode").catch(() => undefined);
  const bodyText = await response.text();
  if (!response.ok) throw new Error(`ORS geocode ${response.status}: ${bodyText.slice(0, 180)}`);

  const body = JSON.parse(bodyText);
  const feature = Array.isArray(body?.features) ? body.features[0] : null;
  const coordinates = feature?.geometry?.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) return null;
  const lon = Number(coordinates[0]);
  const lat = Number(coordinates[1]);
  if (!validCoordinate(lat, lon)) return null;

  const properties = feature?.properties || {};
  const label = String(properties.label || properties.name || text).trim().slice(0, 180) || text;
  trace.push(`openrouteservice_geocode:${label.slice(0, 50)}`);
  return { lat, lon, label };
}

async function directions(
  apiKey: string,
  origin: RoutePoint,
  destination: RoutePoint,
  trace: string[],
  db: DbClient,
): Promise<VerifiedRoute> {
  const url = `${ORS_BASE_URL}/openrouteservice/v2/directions/${DEFAULT_PROFILE}/json`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: apiKey,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      coordinates: [[origin.lon, origin.lat], [destination.lon, destination.lat]],
      instructions: false,
    }),
  });
  await recordRateLimitHeaders(db, response, "directions").catch(() => undefined);
  const bodyText = await response.text();
  if (!response.ok) throw new Error(`ORS directions ${response.status}: ${bodyText.slice(0, 180)}`);

  const body = JSON.parse(bodyText);
  const summary = Array.isArray(body?.routes) ? body.routes[0]?.summary : null;
  const distanceMeters = Number(summary?.distance);
  const durationSeconds = Number(summary?.duration);
  if (!Number.isFinite(distanceMeters) || distanceMeters < 0 || !Number.isFinite(durationSeconds) || durationSeconds < 0) {
    throw new Error("ORS directions returned no verified route summary");
  }

  const verifiedAt = new Date().toISOString();
  trace.push(`openrouteservice_directions:${Math.round(distanceMeters)}m:${Math.round(durationSeconds)}s`);
  return {
    provider: "openrouteservice",
    profile: DEFAULT_PROFILE,
    origin,
    destination,
    distanceMeters,
    durationSeconds,
    distanceKm: round(distanceMeters / 1000, 1),
    durationMinutes: Math.max(1, Math.round(durationSeconds / 60)),
    verifiedAt,
    sourceUrl: "https://openrouteservice.org/",
    navigationUrl: buildNavigationUrl(origin, destination),
  };
}

async function loadCredential(db: DbClient): Promise<ProviderCredential | null> {
  const { data: row } = await db.from("h_runtime_ai_credentials")
    .select("secret_ciphertext,secret_iv,secret_version,oauth_metadata")
    .eq("id", ORS_CREDENTIAL_ID)
    .eq("provider", "openrouteservice")
    .maybeSingle();

  if (row) {
    if (Number(row.secret_version || 1) !== 1) return null;
    const metadata = isRecord(row.oauth_metadata) ? row.oauth_metadata : {};
    const key = (await decryptProviderSecret(String(row.secret_ciphertext), String(row.secret_iv))).trim();
    return key ? { key, metadata, source: "encrypted_db" } : null;
  }

  const env = String(Deno.env.get("OPENROUTESERVICE_API_KEY") || "").trim();
  if (!env) return null;
  return {
    key: env,
    metadata: { free_only: true, allow_paid: false },
    source: "environment",
  };
}

function providerFreeOnly(metadata: Record<string, unknown>): boolean {
  return metadata.free_only === true && metadata.allow_paid !== true;
}

async function decryptProviderSecret(ciphertext: string, iv: string): Promise<string> {
  const root = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!root) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not configured");
  const digest = await crypto.subtle.digest(
    "SHA-256",
    toArrayBuffer(new TextEncoder().encode(`h-provider-aes-v1:openrouteservice:${root}`)),
  );
  const key = await crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["decrypt"]);
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: toArrayBuffer(decodeBase64Url(iv)) },
    key,
    toArrayBuffer(decodeBase64Url(ciphertext)),
  );
  return new TextDecoder().decode(decrypted);
}

async function recordRateLimitHeaders(db: DbClient, response: Response, endpoint: string): Promise<void> {
  const limit = headerNumber(response.headers.get("x-ratelimit-limit"));
  const remaining = headerNumber(response.headers.get("x-ratelimit-remaining"));
  const resetEpoch = headerNumber(response.headers.get("x-ratelimit-reset"));
  if (limit == null && remaining == null && resetEpoch == null) return;

  await db.from("h_runtime_state").upsert({
    key: ROUTE_STATE_KEY,
    value: {
      provider: "openrouteservice",
      free_only: true,
      endpoint,
      limit,
      remaining,
      reset_epoch_seconds: resetEpoch,
      observed_at: new Date().toISOString(),
    },
    updated_at: new Date().toISOString(),
  }, { onConflict: "key" });
}

async function loadQuotaState(db: DbClient): Promise<{ remaining: number; resetAt: Date | null } | null> {
  const { data } = await db.from("h_runtime_state").select("value").eq("key", ROUTE_STATE_KEY).maybeSingle();
  const value = data?.value;
  if (!value || typeof value !== "object") return null;
  const remaining = Number(value.remaining);
  if (!Number.isFinite(remaining)) return null;
  const reset = Number(value.reset_epoch_seconds);
  const resetAt = Number.isFinite(reset) && reset > 0 ? new Date(reset * 1000) : null;
  return { remaining, resetAt };
}

function buildVerifiedRouteContext(
  query: string,
  priority: HTaskPriority,
  route: VerifiedRoute,
  trace: string[],
): string {
  return [
    "H_VERIFIED_ROUTE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Provider trace: ${trace.join(" | ")}`,
    "",
    "VERIFIED ROUTE FACTS:",
    `origin=${route.origin.label}`,
    `origin_coordinates=${route.origin.lat},${route.origin.lon}`,
    `destination=${route.destination.label}`,
    `destination_coordinates=${route.destination.lat},${route.destination.lon}`,
    `profile=${route.profile}`,
    `distance_meters=${route.distanceMeters}`,
    `distance_km=${route.distanceKm}`,
    `duration_seconds=${route.durationSeconds}`,
    `duration_minutes=${route.durationMinutes}`,
    `verified_at=${route.verifiedAt}`,
    `source=${route.sourceUrl}`,
    `navigation_url=${route.navigationUrl}`,
    "",
    "RULES:",
    "- Route distance and duration above are provider-verified. Do not replace them with model estimates.",
    "- Describe duration as an estimate from the route provider, not a live traffic guarantee.",
    "- Do not invent traffic, incidents, road closures, tolls, arrival time, or alternative-route claims.",
    "- You may give the navigation URL as an action link.",
  ].join("\n");
}

function buildMissingEndpointContext(
  query: string,
  parsed: ParsedRouteEndpoints,
  priority: HTaskPriority,
): string {
  return [
    "H_VERIFIED_ROUTE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    "Route lookup cannot run yet because an endpoint is missing.",
    `origin=${parsed.origin || "MISSING"}`,
    `destination=${parsed.destination || "MISSING"}`,
    "Ask only for the missing origin/destination. Do not estimate any route fact.",
  ].join("\n");
}

function buildUnavailableContext(query: string, priority: HTaskPriority, reason: string): string {
  return [
    "H_VERIFIED_ROUTE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Route provider status: ${reason}`,
    "Do not estimate distance, duration, fastest route, traffic, or detour from memory.",
    "Explain the limitation briefly and preserve any non-route help that is safe to provide.",
  ].join("\n");
}

function endpointPair(origin: string, destination: string): ParsedRouteEndpoints {
  const a = cleanEndpoint(origin);
  const b = cleanEndpoint(destination);
  return { origin: a || null, destination: b || null };
}

function cleanRouteText(value: string): string {
  return value.replace(/\s+/g, " ").trim().slice(0, 1000);
}

function cleanEndpoint(value: string): string {
  return value
    .replace(/^(?:وش|ما|هو|هي|ابي|أبي|ابغى|أبغى|اريد|أريد|اعطني|أعطني)\s+/iu, "")
    .replace(/\s+(?:كم\s+المسافة|المسافه|المسافة|وكم\s+ياخذ|وكم\s+يأخذ|وكم\s+الوقت|بالسيارة|بالسياره|car|driving).*$/iu, "")
    .replace(/[؟?!.,،;]+$/g, "")
    .trim()
    .slice(0, MAX_ENDPOINT_CHARS);
}

function looksLikePlaceDiscovery(text: string): boolean {
  return /(مطعم|مطاعم|فندق|فنادق|مقهى|كوفي|كافيه|صيدلية|مستشفى|محطة|سوبرماركت|بقالة|restaurant|hotel|cafe|pharmacy|hospital|places?)/iu.test(text);
}

function latestUserMessage(messages: Array<Record<string, string>>): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (messages[index]?.role === "user" && typeof messages[index]?.content === "string") {
      return messages[index].content.trim();
    }
  }
  return "";
}

function looksSaudi(text: string): boolean {
  return /(السعود|الرياض|جدة|مكة|المدينة|أبها|ابها|محايل|عسير|جازان|الخبر|الدمام|الطائف|خميس\s*مشيط)/iu.test(text);
}

function normalizeArabic(value: string): string {
  return value.replace(/[إأآ]/g, "ا").replace(/ى/g, "ي").replace(/ؤ/g, "و").replace(/ئ/g, "ي").replace(/[ًٌٍَُِّْـ]/g, "");
}

function validCoordinate(lat: number, lon: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

function buildNavigationUrl(origin: RoutePoint, destination: RoutePoint): string {
  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", `${origin.lat},${origin.lon}`);
  url.searchParams.set("destination", `${destination.lat},${destination.lon}`);
  url.searchParams.set("travelmode", "driving");
  return url.toString();
}

function round(value: number, digits: number): number {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function headerNumber(value: string | null): number | null {
  if (value == null || value.trim() === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
