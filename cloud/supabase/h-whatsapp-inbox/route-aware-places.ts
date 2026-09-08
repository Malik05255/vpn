import {
  classifyTaskPriority,
  detectExplicitPriority,
  type HTaskPriority,
} from "./task-manager.ts";

const ORS_BASE_URL = "https://api.heigit.org";
const FOURSQUARE_SEARCH_URL = "https://places-api.foursquare.com/places/search";
const TAVILY_SEARCH_URL = "https://api.tavily.com/search";
const TAVILY_USAGE_URL = "https://api.tavily.com/usage";
const FOURSQUARE_API_VERSION = "2025-06-17";
const FREE_TAVILY_PLAN = "researcher";
const ROUTE_PROFILE = "driving-car";
const MAX_ROUTE_CANDIDATES = 6;
const MAX_WEB_VERIFICATION_CANDIDATES = 3;
const ROUTE_CORRIDOR_METERS = 8_000;
const MAX_SEARCH_RADIUS_METERS = 50_000;

type DbClient = any;
type Provider = "openrouteservice" | "foursquare" | "tavily";

type ProviderCredential = {
  key: string;
  metadata: Record<string, unknown>;
  source: "encrypted_db" | "environment";
};

export type RouteAwarePlaceEvidence = {
  title: string;
  url: string;
  snippet: string;
  publishedAt: string | null;
  provider: "tavily" | "foursquare";
  kind: "web" | "place";
};

export type RouteAwareRequest = {
  origin: string | null;
  destination: string | null;
  placeType: string;
  mandatoryNeed: string | null;
  wantsHighRating: boolean;
};

export type RouteAwarePoint = {
  lat: number;
  lon: number;
  label: string;
};

export type RouteAwareCandidate = {
  fsqPlaceId: string;
  name: string;
  lat: number;
  lon: number;
  address: string | null;
  categories: string[];
  tastes: string[];
  rating: number | null;
  popularity: number | null;
  website: string | null;
  sourceUrl: string;
  corridorDistanceMeters: number;
  detourDistanceMeters: number;
  detourDurationSeconds: number;
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  navigationUrl: string;
};

export type RouteAwarePlaceResult = {
  handled: true;
  query: string;
  status:
    | "verified"
    | "missing_endpoints"
    | "route_not_configured"
    | "places_not_configured"
    | "quota_exhausted"
    | "no_candidates"
    | "provider_error";
  priority: HTaskPriority;
  request: RouteAwareRequest;
  origin: RouteAwarePoint | null;
  destination: RouteAwarePoint | null;
  baselineDistanceMeters: number | null;
  baselineDurationSeconds: number | null;
  candidates: RouteAwareCandidate[];
  evidence: RouteAwarePlaceEvidence[];
  providerTrace: string[];
  hardConstraints: string[];
  context: string;
};

type RouteGeometry = {
  origin: RouteAwarePoint;
  destination: RouteAwarePoint;
  coordinates: Array<[number, number]>;
  distanceMeters: number;
  durationSeconds: number;
};

type RawPlaceCandidate = {
  fsqPlaceId: string;
  name: string;
  lat: number;
  lon: number;
  address: string | null;
  categories: string[];
  tastes: string[];
  rating: number | null;
  popularity: number | null;
  website: string | null;
  sourceUrl: string;
  corridorDistanceMeters: number;
};

export function isRouteAwarePlaceRequest(text: string): boolean {
  const q = normalizeArabic(String(text || ""));
  if (!looksLikePlaceDiscovery(q)) return false;
  return /(?:طريق|مسار|في\s+طريقي|على\s+طريقي|في\s+الطريق|على\s+الطريق|من\s+.+\s+(?:الى|إلى)\s+|on\s+the\s+(?:way|route)|along\s+the\s+route|route\s+from|from\s+.+\s+to\s+)/iu.test(q);
}

export function parseRouteAwareRequest(text: string): RouteAwareRequest {
  const cleaned = String(text || "").replace(/\s+/g, " ").trim().slice(0, 1200);
  const endpoints = parseRouteAwareEndpoints(cleaned);
  return {
    ...endpoints,
    placeType: extractPlaceType(cleaned),
    mandatoryNeed: extractMandatoryNeed(cleaned),
    wantsHighRating: /(?:تقييم(?:ه|ها|اتهم|اته)?\s*(?:عالي|عالية|مرتفع|مرتفعة)|عالي\s*التقييم|high(?:ly)?\s*rated|top\s*rated|rating\s*(?:above|over|>=?))/iu.test(cleaned),
  };
}

export function parseRouteAwareEndpoints(text: string): { origin: string | null; destination: string | null } {
  const cleaned = String(text || "").replace(/\s+/g, " ").trim();
  const tail = "(?=\\s+(?:يقدم|يبيع|عنده|فيه|بشرط|شرط|بتقييم|تقييم|تقييماته|ويكون|ويكون|بسعر|سعر|رخيص|ارخص|أرخص|serves?|with|rating|rated|cheap|under)\\b|[؟?!.,،;]|$)";

  let match = cleaned.match(new RegExp(`(?:طريق|مسار|طريقي|الطريق)?\\s*(?:من)\\s+(.+?)\\s+(?:الى|إلى)\\s+(.+?)${tail}`, "iu"));
  if (match) return endpointPair(match[1], match[2]);

  match = cleaned.match(new RegExp(`(?:route\\s+from|from)\\s+(.+?)\\s+to\\s+(.+?)${tail}`, "iu"));
  if (match) return endpointPair(match[1], match[2]);

  match = cleaned.match(new RegExp(`(?:في|على)\\s+(?:طريقي|الطريق)\\s+(?:الى|إلى)\\s+(.+?)${tail}`, "iu"));
  if (match) return { origin: null, destination: cleanEndpoint(match[1]) || null };

  // "مطعم على طريق محايل" identifies a destination/road anchor, but it does not
  // establish the user's origin. Fail safely and ask only for the missing origin.
  match = cleaned.match(new RegExp(`(?:على|في)\\s+طريق\\s+(?!من\\b)(.+?)${tail}`, "iu"));
  if (match) return { origin: null, destination: cleanEndpoint(match[1]) || null };

  return { origin: null, destination: null };
}

export async function prepareRouteAwarePlaceResearch(
  db: DbClient,
  messages: Array<Record<string, string>>,
): Promise<RouteAwarePlaceResult | null> {
  const query = latestUserMessage(messages);
  if (!isRouteAwarePlaceRequest(query)) return null;

  const request = parseRouteAwareRequest(query);
  const priority = detectExplicitPriority(query) ?? classifyTaskPriority(query, "local_places");
  const providerTrace: string[] = [];
  const hardConstraints = [
    "route_aware_place_request=true",
    "on_route_claim_requires_verified_geometry=true",
    "route_facts_must_be_provider_verified=true",
    "place_location_must_be_provider_verified=true",
  ];
  if (request.mandatoryNeed) hardConstraints.push(`mandatory_place_need=${request.mandatoryNeed}`);
  if (request.wantsHighRating) hardConstraints.push("high_rating_requested=true");

  if (!request.origin || !request.destination) {
    providerTrace.push("route_aware_missing_endpoints");
    return result({
      query,
      status: "missing_endpoints",
      priority,
      request,
      providerTrace,
      hardConstraints,
      context: buildMissingEndpointContext(query, request, priority),
    });
  }

  hardConstraints.push(`route_origin=${request.origin}`, `route_destination=${request.destination}`);

  const ors = await loadProviderCredential(db, "openrouteservice").catch((error) => {
    providerTrace.push(`openrouteservice_credential_error:${errorMessage(error).slice(0, 100)}`);
    return null;
  });
  if (!ors || !providerFreeOnly(ors.metadata)) {
    providerTrace.push(ors ? "openrouteservice_free_only_guard" : "openrouteservice_not_connected");
    return result({
      query,
      status: "route_not_configured",
      priority,
      request,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, "Verified route provider is not configured on the free-only path."),
    });
  }

  const foursquare = await loadProviderCredential(db, "foursquare").catch((error) => {
    providerTrace.push(`foursquare_credential_error:${errorMessage(error).slice(0, 100)}`);
    return null;
  });
  if (!foursquare || !providerFreeOnly(foursquare.metadata)) {
    providerTrace.push(foursquare ? "foursquare_free_only_guard" : "foursquare_not_connected");
    return result({
      query,
      status: "places_not_configured",
      priority,
      request,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, "A free-only Places credential is required to verify businesses along the route."),
    });
  }

  try {
    const origin = await geocode(ors.key, request.origin, providerTrace);
    const destination = await geocode(ors.key, request.destination, providerTrace);
    if (!origin || !destination) {
      providerTrace.push("route_aware_geocode_no_match");
      return result({
        query,
        status: "provider_error",
        priority,
        request,
        providerTrace,
        hardConstraints,
        origin,
        destination,
        context: buildUnavailableContext(query, priority, "One or both route endpoints could not be geocoded reliably."),
      });
    }

    const route = await loadRouteGeometry(ors.key, origin, destination, providerTrace);
    const searchPoints = sampleRoutePoints(route.coordinates, sampleTargetForPriority(priority));
    const searchRadius = routeSearchRadiusMeters(route.distanceMeters, searchPoints.length);
    providerTrace.push(`route_samples:${searchPoints.length}:radius=${searchRadius}`);

    const rawCandidates = await searchPlacesAlongRoute(
      foursquare.key,
      request,
      route,
      searchPoints,
      searchRadius,
      providerTrace,
    );

    if (!rawCandidates.length) {
      return result({
        query,
        status: "no_candidates",
        priority,
        request,
        providerTrace,
        hardConstraints,
        origin,
        destination,
        baselineDistanceMeters: route.distanceMeters,
        baselineDurationSeconds: route.durationSeconds,
        context: buildNoCandidatesContext(query, priority, request, route),
      });
    }

    const matrixCandidates = rawCandidates
      .sort((a, b) => a.corridorDistanceMeters - b.corridorDistanceMeters || compareRatingDesc(a.rating, b.rating))
      .slice(0, MAX_ROUTE_CANDIDATES);
    const ranked = await rankCandidatesWithMatrix(ors.key, route, matrixCandidates, providerTrace);
    const candidates = ranked
      .sort((a, b) => a.detourDurationSeconds - b.detourDurationSeconds || a.detourDistanceMeters - b.detourDistanceMeters || compareRatingDesc(a.rating, b.rating))
      .slice(0, MAX_ROUTE_CANDIDATES);

    let evidence = candidates.map(placeEvidence);
    if (request.mandatoryNeed && candidates.length) {
      const tavily = await loadProviderCredential(db, "tavily").catch(() => null);
      if (tavily) {
        const quota = await tavilyQuota(tavily.key).catch(() => null);
        if (quota?.allowed && quota.remaining > 0) {
          const verificationTargets = candidates.slice(0, Math.min(MAX_WEB_VERIFICATION_CANDIDATES, quota.remaining));
          for (const candidate of verificationTargets) {
            const found = await verifyCandidateNeedWithWeb(tavily.key, candidate, request.mandatoryNeed, providerTrace).catch((error) => {
              providerTrace.push(`tavily_candidate_error:${candidate.fsqPlaceId}:${errorMessage(error).slice(0, 70)}`);
              return [] as RouteAwarePlaceEvidence[];
            });
            evidence.push(...found);
          }
        } else {
          providerTrace.push("tavily_free_quota_guard");
        }
      } else {
        providerTrace.push("tavily_not_connected_for_candidate_verification");
      }
    }

    evidence = dedupeEvidence(evidence);
    providerTrace.push(`route_aware_verified:${candidates.length}`);
    const context = buildVerifiedContext(query, priority, request, route, candidates, providerTrace, evidence);
    await recordState(db, {
      query: query.slice(0, 500),
      status: "verified",
      origin: origin.label,
      destination: destination.label,
      baseline_distance_m: route.distanceMeters,
      baseline_duration_s: route.durationSeconds,
      candidate_count: candidates.length,
      mandatory_need: request.mandatoryNeed,
      providers: providerTrace,
      verified_at: new Date().toISOString(),
    }).catch(() => undefined);

    return {
      handled: true,
      query,
      status: "verified",
      priority,
      request,
      origin,
      destination,
      baselineDistanceMeters: route.distanceMeters,
      baselineDurationSeconds: route.durationSeconds,
      candidates,
      evidence,
      providerTrace,
      hardConstraints,
      context,
    };
  } catch (error) {
    const message = errorMessage(error);
    const status = /429|quota|rate\s*limit/i.test(message) ? "quota_exhausted" : "provider_error";
    providerTrace.push(`route_aware_error:${message.slice(0, 140)}`);
    return result({
      query,
      status,
      priority,
      request,
      providerTrace,
      hardConstraints,
      context: buildUnavailableContext(query, priority, `Route-aware place lookup failed: ${message.slice(0, 180)}`),
    });
  }
}

async function geocode(apiKey: string, text: string, trace: string[]): Promise<RouteAwarePoint | null> {
  const url = new URL(`${ORS_BASE_URL}/pelias/v1/search`);
  url.searchParams.set("text", text);
  url.searchParams.set("size", "1");
  if (looksSaudi(text)) url.searchParams.set("boundary.country", "SA");
  const response = await fetch(url, { headers: { Authorization: apiKey, Accept: "application/json" } });
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

async function loadRouteGeometry(
  apiKey: string,
  origin: RouteAwarePoint,
  destination: RouteAwarePoint,
  trace: string[],
): Promise<RouteGeometry> {
  const response = await fetch(`${ORS_BASE_URL}/openrouteservice/v2/directions/${ROUTE_PROFILE}/geojson`, {
    method: "POST",
    headers: { Authorization: apiKey, "Content-Type": "application/json", Accept: "application/geo+json,application/json" },
    body: JSON.stringify({ coordinates: [[origin.lon, origin.lat], [destination.lon, destination.lat]], instructions: false }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`ORS route geometry ${response.status}: ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const feature = Array.isArray(body?.features) ? body.features[0] : null;
  const coordinates = feature?.geometry?.type === "LineString" && Array.isArray(feature?.geometry?.coordinates)
    ? feature.geometry.coordinates
        .map((point: unknown) => Array.isArray(point) && point.length >= 2 ? [Number(point[0]), Number(point[1])] as [number, number] : null)
        .filter((point: [number, number] | null): point is [number, number] => Boolean(point && validCoordinate(point[1], point[0])))
    : [];
  const summary = feature?.properties?.summary;
  const distanceMeters = Number(summary?.distance);
  const durationSeconds = Number(summary?.duration);
  if (coordinates.length < 2 || !Number.isFinite(distanceMeters) || distanceMeters <= 0 || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    throw new Error("ORS route geometry returned no usable verified route");
  }
  trace.push(`openrouteservice_geojson:${Math.round(distanceMeters)}m:${Math.round(durationSeconds)}s`);
  return { origin, destination, coordinates, distanceMeters, durationSeconds };
}

async function searchPlacesAlongRoute(
  apiKey: string,
  request: RouteAwareRequest,
  route: RouteGeometry,
  samples: Array<[number, number]>,
  radiusMeters: number,
  trace: string[],
): Promise<RawPlaceCandidate[]> {
  const byId = new Map<string, RawPlaceCandidate>();
  const searchQuery = request.mandatoryNeed ? `${request.placeType} ${request.mandatoryNeed}` : request.placeType;

  for (const [lon, lat] of samples) {
    const url = new URL(FOURSQUARE_SEARCH_URL);
    url.searchParams.set("query", searchQuery);
    url.searchParams.set("ll", `${lat},${lon}`);
    url.searchParams.set("radius", String(radiusMeters));
    url.searchParams.set("limit", "12");
    url.searchParams.set("sort", "RATING");
    url.searchParams.set("fields", "fsq_place_id,name,latitude,longitude,location,rating,popularity,tastes,categories,website,link,geocodes");

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${apiKey}`,
        Accept: "application/json",
        "X-Places-Api-Version": FOURSQUARE_API_VERSION,
      },
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`Foursquare search ${response.status}: ${text.slice(0, 180)}`);
    const body = JSON.parse(text);
    const rows = Array.isArray(body?.results) ? body.results : Array.isArray(body) ? body : [];

    for (const item of rows) {
      const parsed = parseFoursquareCandidate(item, route.coordinates);
      if (!parsed || parsed.corridorDistanceMeters > ROUTE_CORRIDOR_METERS) continue;
      const existing = byId.get(parsed.fsqPlaceId);
      if (!existing || parsed.corridorDistanceMeters < existing.corridorDistanceMeters) byId.set(parsed.fsqPlaceId, parsed);
    }
  }

  const candidates = [...byId.values()];
  trace.push(`foursquare_route_candidates:${candidates.length}`);
  return candidates;
}

function parseFoursquareCandidate(item: any, route: Array<[number, number]>): RawPlaceCandidate | null {
  const fsqPlaceId = String(item?.fsq_place_id || item?.fsq_id || item?.id || "").trim();
  const name = String(item?.name || "").trim();
  const lat = firstFinite(item?.latitude, item?.geocodes?.main?.latitude);
  const lon = firstFinite(item?.longitude, item?.geocodes?.main?.longitude);
  if (!fsqPlaceId || !name || lat == null || lon == null || !validCoordinate(lat, lon)) return null;
  const location = item?.location || {};
  const address = [location?.formatted_address, location?.locality, location?.region].filter(Boolean).join(" | ") || null;
  const categories = Array.isArray(item?.categories) ? item.categories.map((x: any) => String(x?.name || "").trim()).filter(Boolean) : [];
  const tastes = Array.isArray(item?.tastes) ? item.tastes.map((x: unknown) => String(x).trim()).filter(Boolean) : [];
  const rating = finiteOrNull(item?.rating);
  const popularity = finiteOrNull(item?.popularity);
  const website = String(item?.website || "").trim() || null;
  const sourceUrl = String(item?.link || "").trim() || `https://foursquare.com/v/${encodeURIComponent(fsqPlaceId)}`;
  return {
    fsqPlaceId,
    name,
    lat,
    lon,
    address,
    categories,
    tastes,
    rating,
    popularity,
    website,
    sourceUrl,
    corridorDistanceMeters: Math.round(distancePointToPolylineMeters(lat, lon, route)),
  };
}

async function rankCandidatesWithMatrix(
  apiKey: string,
  route: RouteGeometry,
  candidates: RawPlaceCandidate[],
  trace: string[],
): Promise<RouteAwareCandidate[]> {
  const locations = [
    [route.origin.lon, route.origin.lat],
    [route.destination.lon, route.destination.lat],
    ...candidates.map((candidate) => [candidate.lon, candidate.lat]),
  ];
  const response = await fetch(`${ORS_BASE_URL}/openrouteservice/v2/matrix/${ROUTE_PROFILE}`, {
    method: "POST",
    headers: { Authorization: apiKey, "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ locations, metrics: ["distance", "duration"], units: "m" }),
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

  const ranked: RouteAwareCandidate[] = [];
  candidates.forEach((candidate, index) => {
    const matrixIndex = index + 2;
    const originDistance = matrixNumber(distances, 0, matrixIndex);
    const destinationDistance = matrixNumber(distances, matrixIndex, 1);
    const originDuration = matrixNumber(durations, 0, matrixIndex);
    const destinationDuration = matrixNumber(durations, matrixIndex, 1);
    if (originDistance == null || destinationDistance == null || originDuration == null || destinationDuration == null) return;
    const detour = computeDetourMetrics(
      baselineDistance,
      baselineDuration,
      originDistance,
      destinationDistance,
      originDuration,
      destinationDuration,
    );
    ranked.push({
      ...candidate,
      detourDistanceMeters: Math.round(detour.detourDistanceMeters),
      detourDurationSeconds: Math.round(detour.detourDurationSeconds),
      totalDistanceMeters: Math.round(detour.totalDistanceMeters),
      totalDurationSeconds: Math.round(detour.totalDurationSeconds),
      navigationUrl: buildNavigationUrl(route.origin, route.destination, candidate),
    });
  });
  trace.push(`openrouteservice_matrix:${ranked.length}`);
  return ranked;
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

export function distancePointToPolylineMeters(
  lat: number,
  lon: number,
  polyline: Array<[number, number]>,
): number {
  if (polyline.length === 0) return Number.POSITIVE_INFINITY;
  if (polyline.length === 1) return haversineMeters(lat, lon, polyline[0][1], polyline[0][0]);
  let best = Number.POSITIVE_INFINITY;
  for (let index = 0; index < polyline.length - 1; index += 1) {
    const a = toLocalMeters(polyline[index][1], polyline[index][0], lat, lon);
    const b = toLocalMeters(polyline[index + 1][1], polyline[index + 1][0], lat, lon);
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const denominator = dx * dx + dy * dy;
    const t = denominator <= 0 ? 0 : clamp(-(a.x * dx + a.y * dy) / denominator, 0, 1);
    const x = a.x + t * dx;
    const y = a.y + t * dy;
    best = Math.min(best, Math.hypot(x, y));
  }
  return best;
}

async function verifyCandidateNeedWithWeb(
  apiKey: string,
  candidate: RouteAwareCandidate,
  mandatoryNeed: string,
  trace: string[],
): Promise<RouteAwarePlaceEvidence[]> {
  const query = `"${candidate.name}" ${mandatoryNeed} menu ${candidate.address || "Saudi Arabia"}`;
  const response = await fetch(TAVILY_SEARCH_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
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
  const evidence = rows.map((item: any): RouteAwarePlaceEvidence => ({
    title: String(item?.title || item?.url || candidate.name),
    url: String(item?.url || ""),
    snippet: String(item?.content || "").slice(0, 1500),
    publishedAt: String(item?.published_date || item?.publishedAt || "").trim() || null,
    provider: "tavily",
    kind: "web",
  })).filter((item: RouteAwarePlaceEvidence) => Boolean(item.url));
  trace.push(`tavily_candidate:${candidate.fsqPlaceId}:${evidence.length}`);
  return evidence;
}

async function tavilyQuota(apiKey: string): Promise<{ allowed: boolean; remaining: number }> {
  const response = await fetch(TAVILY_USAGE_URL, { headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" } });
  const text = await response.text();
  if (!response.ok) return { allowed: false, remaining: 0 };
  const usage = JSON.parse(text);
  const plan = String(usage?.account?.current_plan || "").trim().toLowerCase();
  const used = nonNegative(usage?.account?.plan_usage, 0);
  const limit = positive(usage?.account?.plan_limit, plan === FREE_TAVILY_PLAN ? 1000 : 0);
  return { allowed: plan === FREE_TAVILY_PLAN && limit > 0 && used < limit, remaining: Math.max(0, limit - used) };
}

async function loadProviderCredential(db: DbClient, provider: Provider): Promise<ProviderCredential | null> {
  const id = provider === "openrouteservice" ? "openrouteservice_default" : provider === "foursquare" ? "foursquare_default" : "tavily_default";
  const { data: row } = await db.from("h_runtime_ai_credentials")
    .select("secret_ciphertext,secret_iv,secret_version,oauth_metadata")
    .eq("id", id)
    .eq("provider", provider)
    .maybeSingle();
  if (row) {
    if (Number(row.secret_version || 1) !== 1) return null;
    const key = (await decryptProviderSecret(provider, String(row.secret_ciphertext), String(row.secret_iv))).trim();
    return key ? { key, metadata: isRecord(row.oauth_metadata) ? row.oauth_metadata : {}, source: "encrypted_db" } : null;
  }

  if (provider === "openrouteservice") {
    const key = String(Deno.env.get("OPENROUTESERVICE_API_KEY") || "").trim();
    return key ? { key, metadata: { free_only: true, allow_paid: false }, source: "environment" } : null;
  }
  if (provider === "tavily") {
    const key = String(Deno.env.get("TAVILY_API_KEY") || "").trim();
    return key ? { key, metadata: { free_only: true, allow_paid: false }, source: "environment" } : null;
  }
  return null;
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

function placeEvidence(candidate: RouteAwareCandidate): RouteAwarePlaceEvidence {
  return {
    title: candidate.name,
    url: candidate.sourceUrl,
    snippet: [
      `fsq_place_id=${candidate.fsqPlaceId}`,
      `address=${candidate.address || "unknown"}`,
      `coordinates=${candidate.lat},${candidate.lon}`,
      `categories=${candidate.categories.join(", ") || "unknown"}`,
      `tastes=${candidate.tastes.join(", ") || "unknown"}`,
      `rating=${candidate.rating ?? "unknown"}`,
      `popularity=${candidate.popularity ?? "unknown"}`,
      `corridor_distance_m=${candidate.corridorDistanceMeters}`,
      `detour_distance_m=${candidate.detourDistanceMeters}`,
      `detour_duration_s=${candidate.detourDurationSeconds}`,
    ].join("; "),
    publishedAt: null,
    provider: "foursquare",
    kind: "place",
  };
}

function buildVerifiedContext(
  query: string,
  priority: HTaskPriority,
  request: RouteAwareRequest,
  route: RouteGeometry,
  candidates: RouteAwareCandidate[],
  trace: string[],
  evidence: RouteAwarePlaceEvidence[],
): string {
  const candidateText = candidates.map((candidate, index) => [
    `[P${index + 1}] ${candidate.name}`,
    `fsq_place_id=${candidate.fsqPlaceId}`,
    `coordinates=${candidate.lat},${candidate.lon}`,
    `address=${candidate.address || "unknown"}`,
    `rating=${candidate.rating ?? "unknown"}`,
    `categories=${candidate.categories.join(", ") || "unknown"}`,
    `tastes=${candidate.tastes.join(", ") || "unknown"}`,
    `corridor_distance_m=${candidate.corridorDistanceMeters}`,
    `detour_distance_m=${candidate.detourDistanceMeters}`,
    `detour_duration_s=${candidate.detourDurationSeconds}`,
    `navigation_url=${candidate.navigationUrl}`,
  ].join("\n")).join("\n\n");

  return [
    "H_VERIFIED_ROUTE_AWARE_PLACES_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Origin: ${route.origin.label}`,
    `Destination: ${route.destination.label}`,
    `Baseline distance meters: ${route.distanceMeters}`,
    `Baseline duration seconds: ${route.durationSeconds}`,
    `Requested place type: ${request.placeType}`,
    `Mandatory need: ${request.mandatoryNeed || "none"}`,
    `High rating requested: ${request.wantsHighRating}`,
    `Provider trace: ${trace.join(" | ")}`,
    `Evidence count: ${evidence.length}`,
    "",
    "VERIFIED ROUTE CANDIDATES:",
    candidateText || "NONE",
    "",
    "RULES:",
    "- ORS route geometry/matrix is authoritative for corridor and detour facts. Never replace those facts with estimates.",
    "- Foursquare is authoritative only for the place fields explicitly supplied above (identity, coordinates, address, rating/categories/tastes when present).",
    "- A taste/category is NOT proof that a mandatory menu item or service is currently offered.",
    "- If a mandatory need was requested, recommend a candidate only when web evidence explicitly ties that exact place to that need.",
    "- If high rating was requested, do not call an unknown rating high. Prefer the highest verified rating among candidates that also satisfy every mandatory constraint.",
    "- Detour duration is a routing estimate, not live traffic. Do not invent traffic, closures, tolls or incidents.",
    "- If no candidate satisfies every mandatory constraint, say so and show only clearly-labeled partial matches if useful.",
  ].join("\n");
}

function buildMissingEndpointContext(query: string, request: RouteAwareRequest, priority: HTaskPriority): string {
  return [
    "H_ROUTE_AWARE_PLACE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `origin=${request.origin || "MISSING"}`,
    `destination=${request.destination || "MISSING"}`,
    "A route-aware place search requires both route endpoints.",
    "Ask only for the missing origin or destination. Do not infer current GPS/location from WhatsApp text.",
  ].join("\n");
}

function buildUnavailableContext(query: string, priority: HTaskPriority, reason: string): string {
  return [
    "H_ROUTE_AWARE_PLACE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Status: ${reason}`,
    "Do not claim a business is on the route or quote a detour without verified route geometry and place coordinates.",
    "Explain the limitation briefly and do not fill route/place facts from memory.",
  ].join("\n");
}

function buildNoCandidatesContext(query: string, priority: HTaskPriority, request: RouteAwareRequest, route: RouteGeometry): string {
  return [
    "H_ROUTE_AWARE_PLACE_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Origin: ${route.origin.label}`,
    `Destination: ${route.destination.label}`,
    `Place type: ${request.placeType}`,
    "No place candidate was verified inside the allowed route corridor.",
    "Do not invent a place or widen the route constraint silently.",
  ].join("\n");
}

function result(input: {
  query: string;
  status: RouteAwarePlaceResult["status"];
  priority: HTaskPriority;
  request: RouteAwareRequest;
  providerTrace: string[];
  hardConstraints: string[];
  context: string;
  origin?: RouteAwarePoint | null;
  destination?: RouteAwarePoint | null;
  baselineDistanceMeters?: number | null;
  baselineDurationSeconds?: number | null;
}): RouteAwarePlaceResult {
  return {
    handled: true,
    query: input.query,
    status: input.status,
    priority: input.priority,
    request: input.request,
    origin: input.origin ?? null,
    destination: input.destination ?? null,
    baselineDistanceMeters: input.baselineDistanceMeters ?? null,
    baselineDurationSeconds: input.baselineDurationSeconds ?? null,
    candidates: [],
    evidence: [],
    providerTrace: input.providerTrace,
    hardConstraints: input.hardConstraints,
    context: input.context,
  };
}

function parseRouteAwareEndpoints(text: string) {
  return parseRouteAwareRequest(text);
}

function endpointPair(origin: string, destination: string) {
  return { origin: cleanEndpoint(origin) || null, destination: cleanEndpoint(destination) || null };
}

function cleanEndpoint(value: string): string {
  return String(value || "")
    .replace(/^(?:وش|ما|هو|هي|ابي|أبي|ابغى|أبغى|اريد|أريد|اعطني|أعطني)\s+/iu, "")
    .replace(/[؟?!.,،;]+$/g, "")
    .trim()
    .slice(0, 120);
}

function extractPlaceType(text: string): string {
  const pairs: Array<[RegExp, string]> = [
    [/(?:مطعم|مطاعم|restaurant)/iu, "restaurant"],
    [/(?:فندق|فنادق|hotel)/iu, "hotel"],
    [/(?:مقهى|كوفي|كافيه|cafe|coffee)/iu, "cafe"],
    [/(?:صيدلية|pharmacy)/iu, "pharmacy"],
    [/(?:مستشفى|hospital)/iu, "hospital"],
    [/(?:محطة|gas\s*station|fuel\s*station)/iu, "gas station"],
    [/(?:سوبرماركت|بقالة|supermarket|grocery)/iu, "supermarket"],
  ];
  for (const [pattern, value] of pairs) if (pattern.test(text)) return value;
  return "place";
}

function extractMandatoryNeed(text: string): string | null {
  const stop = "(?=\\s+(?:بشرط|شرط|بتقييم|تقييم|تقييماته|ويكون|ويكون|رخيص|ارخص|أرخص|قريب|على\\s+الطريق|في\\s+الطريق|with\\s+rating|rating|rated|cheap)\\b|[؟?!.,،;]|$)";
  for (const prefix of ["يقدم", "يبيع", "عنده", "فيه", "serves", "serve", "has"]) {
    const match = text.match(new RegExp(`${prefix}\\s+(.+?)${stop}`, "iu"));
    const value = match?.[1]?.trim();
    if (value) return value.slice(0, 80);
  }
  return null;
}

function looksLikePlaceDiscovery(text: string): boolean {
  return /(مطعم|مطاعم|فندق|فنادق|مقهى|كوفي|كافيه|صيدلية|مستشفى|محطة|سوبرماركت|بقالة|restaurant|hotel|cafe|coffee|pharmacy|hospital|gas\s*station|supermarket|grocery|places?)/iu.test(text);
}

function sampleTargetForPriority(priority: HTaskPriority): number {
  return priority === "important" ? 8 : priority === "medium" ? 6 : 4;
}

export function sampleRoutePoints(polyline: Array<[number, number]>, target: number): Array<[number, number]> {
  if (polyline.length <= target) return dedupeCoordinates(polyline);
  const count = Math.max(2, Math.min(target, polyline.length));
  const points: Array<[number, number]> = [];
  for (let i = 0; i < count; i += 1) {
    const index = Math.round((i * (polyline.length - 1)) / (count - 1));
    points.push(polyline[index]);
  }
  return dedupeCoordinates(points);
}

function routeSearchRadiusMeters(distanceMeters: number, sampleCount: number): number {
  const halfGap = distanceMeters / Math.max(2, sampleCount * 2);
  return Math.round(clamp(halfGap + ROUTE_CORRIDOR_METERS, 10_000, MAX_SEARCH_RADIUS_METERS));
}

function buildNavigationUrl(origin: RouteAwarePoint, destination: RouteAwarePoint, candidate: RawPlaceCandidate): string {
  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", `${origin.lat},${origin.lon}`);
  url.searchParams.set("destination", `${destination.lat},${destination.lon}`);
  url.searchParams.set("waypoints", `${candidate.lat},${candidate.lon}`);
  url.searchParams.set("travelmode", "driving");
  return url.toString();
}

function matrixNumber(matrix: any, row: number, column: number): number | null {
  const value = Number(matrix?.[row]?.[column]);
  return Number.isFinite(value) && value >= 0 ? value : null;
}

function compareRatingDesc(a: number | null, b: number | null): number {
  return (b ?? -1) - (a ?? -1);
}

function toLocalMeters(lat: number, lon: number, originLat: number, originLon: number) {
  const avgLat = ((lat + originLat) / 2) * Math.PI / 180;
  return {
    x: (lon - originLon) * 111_320 * Math.cos(avgLat),
    y: (lat - originLat) * 110_540,
  };
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6_371_000;
  const p1 = lat1 * Math.PI / 180;
  const p2 = lat2 * Math.PI / 180;
  const dp = (lat2 - lat1) * Math.PI / 180;
  const dl = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function dedupeCoordinates(points: Array<[number, number]>): Array<[number, number]> {
  const seen = new Set<string>();
  return points.filter(([lon, lat]) => {
    const key = `${lon.toFixed(5)},${lat.toFixed(5)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function dedupeEvidence(items: RouteAwarePlaceEvidence[]): RouteAwarePlaceEvidence[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = `${item.provider}:${item.url || item.title}`.toLowerCase();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function latestUserMessage(messages: Array<Record<string, string>>): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (messages[index]?.role === "user" && typeof messages[index]?.content === "string") return messages[index].content.trim();
  }
  return "";
}

function normalizeArabic(value: string): string {
  return value.replace(/[إأآ]/g, "ا").replace(/ى/g, "ي").replace(/ؤ/g, "و").replace(/ئ/g, "ي").replace(/[ًٌٍَُِّْـ]/g, "");
}

function looksSaudi(text: string): boolean {
  return /(السعود|الرياض|جدة|مكة|المدينة|أبها|ابها|محايل|عسير|جازان|الخبر|الدمام|الطائف|خميس\s*مشيط|الباحة|نجران)/iu.test(text);
}

function validCoordinate(lat: number, lon: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

function firstFinite(...values: unknown[]): number | null {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

function finiteOrNull(value: unknown): number | null {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
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

async function recordState(db: DbClient, value: Record<string, unknown>) {
  await db.from("h_runtime_state").upsert({ key: "route_aware_places", value, updated_at: new Date().toISOString() }, { onConflict: "key" });
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
