import type { Evidence } from "./research-engine.ts";
import type {
  RoutePlaceCandidate,
  RoutePlacesResearchResult,
} from "./route-places-engine.ts";

const ORS_BASE_URL = "https://api.heigit.org";
const ORS_PROFILE = "driving-car";
const TAVILY_SEARCH_URL = "https://api.tavily.com/search";
const TAVILY_USAGE_URL = "https://api.tavily.com/usage";
const FREE_TAVILY_PLAN = "researcher";
const MAX_WEB_VERIFICATION_CANDIDATES = 3;

type DbClient = any;
type Provider = "openrouteservice" | "tavily";

type ProviderCredential = {
  key: string;
  metadata: Record<string, unknown>;
  source: "encrypted_db" | "environment";
};

type GeoPoint = {
  lat: number;
  lon: number;
  label: string;
};

export type RouteDetourFact = {
  candidateId: string;
  candidateName: string;
  detourDistanceMeters: number;
  detourDistanceKm: number;
  detourDurationSeconds: number;
  detourDurationMinutes: number;
  totalDistanceMeters: number;
  totalDistanceKm: number;
  totalDurationSeconds: number;
  totalDurationMinutes: number;
  navigationUrl: string;
};

export type RoutePlaceEnhancement = {
  active: true;
  status: "verified" | "partial" | "unavailable";
  detours: RouteDetourFact[];
  evidence: Evidence[];
  providerTrace: string[];
  context: string;
};

export async function enhanceRoutePlaceResearch(
  db: DbClient,
  routePlaces: RoutePlacesResearchResult,
): Promise<RoutePlaceEnhancement | null> {
  if (routePlaces.status !== "verified" || !routePlaces.candidates.length) return null;
  if (!routePlaces.parsed.origin || !routePlaces.parsed.destination) return null;

  const providerTrace: string[] = [];
  let detours: RouteDetourFact[] = [];
  let evidence: Evidence[] = [];

  try {
    const ors = await loadCredential(db, "openrouteservice");
    if (ors && providerFreeOnly(ors.metadata)) {
      const origin = await geocode(ors.key, routePlaces.parsed.origin);
      const destination = await geocode(ors.key, routePlaces.parsed.destination);
      if (origin && destination) {
        detours = await calculateDetours(
          ors.key,
          origin,
          destination,
          routePlaces.candidates,
          providerTrace,
        );
      } else {
        providerTrace.push("detour_geocode_unresolved");
      }
    } else {
      providerTrace.push(ors ? "detour_openrouteservice_free_only_guard" : "detour_openrouteservice_not_connected");
    }
  } catch (error) {
    providerTrace.push(`detour_matrix_error:${errorMessage(error).slice(0, 120)}`);
  }

  if (routePlaces.parsed.requestedNeed) {
    try {
      const tavily = await loadCredential(db, "tavily");
      if (tavily) {
        const quota = await tavilyQuota(tavily.key);
        if (quota.allowed && quota.remaining > 0) {
          const ordered = orderCandidatesForVerification(routePlaces.candidates, detours);
          const maxChecks = Math.min(MAX_WEB_VERIFICATION_CANDIDATES, quota.remaining, ordered.length);
          for (const candidate of ordered.slice(0, maxChecks)) {
            const found = await verifyCandidateNeed(
              tavily.key,
              candidate,
              routePlaces.parsed.requestedNeed,
            );
            evidence.push(...found);
            providerTrace.push(`tavily_exact_candidate:${candidate.id}:${found.length}`);
          }
        } else {
          providerTrace.push("tavily_candidate_quota_guard");
        }
      } else {
        providerTrace.push("tavily_not_connected_for_exact_candidate_verification");
      }
    } catch (error) {
      providerTrace.push(`tavily_candidate_verification_error:${errorMessage(error).slice(0, 120)}`);
    }
  }

  evidence = dedupeEvidence(evidence);
  const status: RoutePlaceEnhancement["status"] = detours.length
    ? "verified"
    : evidence.length
      ? "partial"
      : "unavailable";
  return {
    active: true,
    status,
    detours,
    evidence,
    providerTrace,
    context: buildContext(routePlaces, detours, evidence, providerTrace),
  };
}

async function calculateDetours(
  apiKey: string,
  origin: GeoPoint,
  destination: GeoPoint,
  candidates: RoutePlaceCandidate[],
  trace: string[],
): Promise<RouteDetourFact[]> {
  const locations = [
    [origin.lon, origin.lat],
    [destination.lon, destination.lat],
    ...candidates.map((candidate) => [candidate.lon, candidate.lat]),
  ];
  const response = await fetch(`${ORS_BASE_URL}/openrouteservice/v2/matrix/${ORS_PROFILE}`, {
    method: "POST",
    headers: {
      Authorization: apiKey,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      locations,
      metrics: ["distance", "duration"],
      units: "m",
    }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`ORS matrix ${response.status}: ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const distances = body?.distances;
  const durations = body?.durations;
  if (!Array.isArray(distances) || !Array.isArray(durations)) throw new Error("ORS matrix returned no usable matrix");

  const baselineDistance = matrixNumber(distances, 0, 1);
  const baselineDuration = matrixNumber(durations, 0, 1);
  if (baselineDistance == null || baselineDuration == null) throw new Error("ORS matrix returned no baseline route");

  const result: RouteDetourFact[] = [];
  candidates.forEach((candidate, index) => {
    const matrixIndex = index + 2;
    const originDistance = matrixNumber(distances, 0, matrixIndex);
    const candidateDistance = matrixNumber(distances, matrixIndex, 1);
    const originDuration = matrixNumber(durations, 0, matrixIndex);
    const candidateDuration = matrixNumber(durations, matrixIndex, 1);
    if (originDistance == null || candidateDistance == null || originDuration == null || candidateDuration == null) return;

    const detour = computeDetourMetrics(
      baselineDistance,
      baselineDuration,
      originDistance,
      candidateDistance,
      originDuration,
      candidateDuration,
    );
    result.push({
      candidateId: candidate.id,
      candidateName: candidate.name,
      detourDistanceMeters: Math.round(detour.detourDistanceMeters),
      detourDistanceKm: round(detour.detourDistanceMeters / 1000, 1),
      detourDurationSeconds: Math.round(detour.detourDurationSeconds),
      detourDurationMinutes: Math.max(0, Math.round(detour.detourDurationSeconds / 60)),
      totalDistanceMeters: Math.round(detour.totalDistanceMeters),
      totalDistanceKm: round(detour.totalDistanceMeters / 1000, 1),
      totalDurationSeconds: Math.round(detour.totalDurationSeconds),
      totalDurationMinutes: Math.max(1, Math.round(detour.totalDurationSeconds / 60)),
      navigationUrl: buildNavigationUrl(origin, destination, candidate),
    });
  });
  result.sort((a, b) => a.detourDurationSeconds - b.detourDurationSeconds || a.detourDistanceMeters - b.detourDistanceMeters);
  trace.push(`openrouteservice_matrix_detours:${result.length}`);
  return result;
}

export function computeDetourMetrics(
  baselineDistanceMeters: number,
  baselineDurationSeconds: number,
  originToCandidateDistanceMeters: number,
  candidateToDestinationDistanceMeters: number,
  originToCandidateDurationSeconds: number,
  candidateToDestinationDurationSeconds: number,
) {
  const totalDistanceMeters = originToCandidateDistanceMeters + candidateToDestinationDistanceMeters;
  const totalDurationSeconds = originToCandidateDurationSeconds + candidateToDestinationDurationSeconds;
  return {
    totalDistanceMeters,
    totalDurationSeconds,
    detourDistanceMeters: Math.max(0, totalDistanceMeters - baselineDistanceMeters),
    detourDurationSeconds: Math.max(0, totalDurationSeconds - baselineDurationSeconds),
  };
}

async function verifyCandidateNeed(
  apiKey: string,
  candidate: RoutePlaceCandidate,
  mandatoryNeed: string,
): Promise<Evidence[]> {
  const query = `"${candidate.name}" ${mandatoryNeed} menu ${candidate.address || "Saudi Arabia"}`;
  const response = await fetch(TAVILY_SEARCH_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      query,
      search_depth: "basic",
      max_results: 4,
      include_answer: false,
      include_raw_content: false,
      include_images: false,
      include_usage: true,
      auto_parameters: false,
      topic: "general",
      country: "saudi arabia",
    }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Tavily candidate verification ${response.status}: ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const rows = Array.isArray(body?.results) ? body.results : [];
  return rows.map((item: any): Evidence => ({
    title: String(item?.title || item?.url || candidate.name),
    url: String(item?.url || ""),
    snippet: [
      `candidate_name=${candidate.name}`,
      `candidate_address=${candidate.address || "unknown"}`,
      `required_need=${mandatoryNeed}`,
      `source_text=${String(item?.content || "").slice(0, 1300)}`,
    ].join("; "),
    publishedAt: String(item?.published_date || item?.publishedAt || "").trim() || null,
    provider: "tavily",
    kind: "web",
  })).filter((item: Evidence) => Boolean(item.url));
}

async function tavilyQuota(apiKey: string): Promise<{ allowed: boolean; remaining: number }> {
  const response = await fetch(TAVILY_USAGE_URL, {
    headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" },
  });
  const text = await response.text();
  if (!response.ok) return { allowed: false, remaining: 0 };
  const usage = JSON.parse(text);
  const plan = String(usage?.account?.current_plan || "").trim().toLowerCase();
  const used = nonNegative(usage?.account?.plan_usage, 0);
  const limit = positive(usage?.account?.plan_limit, plan === FREE_TAVILY_PLAN ? 1000 : 0);
  return {
    allowed: plan === FREE_TAVILY_PLAN && limit > 0 && used < limit,
    remaining: Math.max(0, limit - used),
  };
}

async function geocode(apiKey: string, text: string): Promise<GeoPoint | null> {
  const url = new URL(`${ORS_BASE_URL}/pelias/v1/search`);
  url.searchParams.set("text", text);
  url.searchParams.set("size", "1");
  if (looksSaudi(text)) url.searchParams.set("boundary.country", "SA");
  const response = await fetch(url, {
    headers: { Authorization: apiKey, Accept: "application/json" },
  });
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
  return {
    lat,
    lon,
    label: String(properties.label || properties.name || text).trim().slice(0, 180) || text,
  };
}

async function loadCredential(db: DbClient, provider: Provider): Promise<ProviderCredential | null> {
  const id = provider === "openrouteservice" ? "openrouteservice_default" : "tavily_default";
  const { data: row } = await db.from("h_runtime_ai_credentials")
    .select("secret_ciphertext,secret_iv,secret_version,oauth_metadata")
    .eq("id", id)
    .eq("provider", provider)
    .maybeSingle();
  if (row) {
    if (Number(row.secret_version || 1) !== 1) return null;
    const key = (await decryptProviderSecret(provider, String(row.secret_ciphertext), String(row.secret_iv))).trim();
    return key ? {
      key,
      metadata: isRecord(row.oauth_metadata) ? row.oauth_metadata : {},
      source: "encrypted_db",
    } : null;
  }

  if (provider === "openrouteservice") {
    const key = String(Deno.env.get("OPENROUTESERVICE_API_KEY") || "").trim();
    return key ? { key, metadata: { free_only: true, allow_paid: false }, source: "environment" } : null;
  }
  const key = String(Deno.env.get("TAVILY_API_KEY") || "").trim();
  return key ? { key, metadata: { free_only: true, allow_paid: false }, source: "environment" } : null;
}

async function decryptProviderSecret(provider: Provider, ciphertext: string, iv: string): Promise<string> {
  const root = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!root) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not configured");
  const label = provider === "tavily" ? "h-tavily-aes-v1" : `h-provider-aes-v1:${provider}`;
  const digest = await crypto.subtle.digest("SHA-256", toArrayBuffer(new TextEncoder().encode(`${label}:${root}`)));
  const key = await crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["decrypt"]);
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: toArrayBuffer(decodeBase64Url(iv)) },
    key,
    toArrayBuffer(decodeBase64Url(ciphertext)),
  );
  return new TextDecoder().decode(decrypted);
}

function providerFreeOnly(metadata: Record<string, unknown>): boolean {
  return metadata.free_only === true && metadata.allow_paid !== true;
}

function orderCandidatesForVerification(
  candidates: RoutePlaceCandidate[],
  detours: RouteDetourFact[],
): RoutePlaceCandidate[] {
  const detourIndex = new Map(detours.map((fact, index) => [fact.candidateId, index]));
  return [...candidates].sort((a, b) => {
    if (a.explicitNeedMatch !== b.explicitNeedMatch) return a.explicitNeedMatch ? -1 : 1;
    const aDetour = detourIndex.get(a.id);
    const bDetour = detourIndex.get(b.id);
    if (aDetour != null || bDetour != null) return (aDetour ?? Number.MAX_SAFE_INTEGER) - (bDetour ?? Number.MAX_SAFE_INTEGER);
    return a.corridorDistanceMeters - b.corridorDistanceMeters || (b.rating ?? -1) - (a.rating ?? -1);
  });
}

function buildContext(
  routePlaces: RoutePlacesResearchResult,
  detours: RouteDetourFact[],
  evidence: Evidence[],
  trace: string[],
): string {
  return [
    "H_ROUTE_PLACE_ENHANCEMENT_CONTEXT",
    `Request: ${routePlaces.query}`,
    `Mandatory need: ${routePlaces.parsed.requestedNeed || "none"}`,
    `High rating requested: ${wantsHighRating(routePlaces.query)}`,
    `Detour facts: ${detours.length}`,
    `Exact candidate web evidence: ${evidence.length}`,
    `Provider trace: ${trace.join(" | ") || "none"}`,
    "",
    "VERIFIED ORS MATRIX DETOURS:",
    ...detours.map((fact, index) => [
      `[D${index + 1}] ${fact.candidateName}`,
      `candidate_id=${fact.candidateId}`,
      `detour_distance_m=${fact.detourDistanceMeters}`,
      `detour_distance_km=${fact.detourDistanceKm}`,
      `detour_duration_s=${fact.detourDurationSeconds}`,
      `detour_duration_min=${fact.detourDurationMinutes}`,
      `total_distance_km=${fact.totalDistanceKm}`,
      `total_duration_min=${fact.totalDurationMinutes}`,
      `navigation_url=${fact.navigationUrl}`,
    ].join("; ")),
    "",
    "RULES:",
    "- ORS Matrix detour facts above are route estimates for origin -> candidate -> destination relative to origin -> destination.",
    "- Detour duration is not live traffic. Never invent traffic, closures, tolls or incidents.",
    "- Prefer candidates with the smallest verified detour only after mandatory dish/service constraints are satisfied.",
    "- When high rating was requested, use only an explicit verified rating and never call an unknown rating high.",
    "- Tavily candidate evidence is supporting web/menu evidence; it must still clearly refer to the exact candidate before proving the mandatory need.",
  ].join("\n");
}

function buildNavigationUrl(origin: GeoPoint, destination: GeoPoint, candidate: RoutePlaceCandidate): string {
  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", `${origin.lat},${origin.lon}`);
  url.searchParams.set("destination", `${destination.lat},${destination.lon}`);
  url.searchParams.set("waypoints", `${candidate.lat},${candidate.lon}`);
  url.searchParams.set("travelmode", "driving");
  return url.toString();
}

function wantsHighRating(text: string): boolean {
  return /(?:تقييم(?:ه|ها|اتهم|اته)?\s*(?:عالي|عالية|مرتفع|مرتفعة)|عالي\s*التقييم|high(?:ly)?\s*rated|top\s*rated|rating\s*(?:above|over|>=?))/iu.test(text);
}

function matrixNumber(matrix: any, row: number, column: number): number | null {
  const value = Number(matrix?.[row]?.[column]);
  return Number.isFinite(value) && value >= 0 ? value : null;
}

function dedupeEvidence(items: Evidence[]): Evidence[] {
  const seen = new Set<string>();
  const result: Evidence[] = [];
  for (const item of items) {
    const key = `${item.provider}:${item.url || item.title}`.toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    result.push(item);
  }
  return result;
}

function looksSaudi(text: string): boolean {
  return /(السعود|الرياض|جدة|مكة|المدينة|أبها|ابها|محايل|عسير|جازان|الخبر|الدمام|الطائف|خميس\s*مشيط|الباحة|نجران)/iu.test(text);
}

function validCoordinate(lat: number, lon: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

function nonNegative(...values: unknown[]): number {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number) && number >= 0) return number;
  }
  return 0;
}

function positive(...values: unknown[]): number {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number) && number > 0) return number;
  }
  return 0;
}

function round(value: number, digits: number): number {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
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
