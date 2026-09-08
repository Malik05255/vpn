import {
  classifyTaskPriority,
  detectExplicitPriority,
  type HTaskPriority,
} from "./task-manager.ts";
import { parseRouteEndpoints, type ParsedRouteEndpoints } from "./route-engine.ts";

const ORS_BASE_URL = "https://api.heigit.org";
const ORS_CREDENTIAL_ID = "openrouteservice_default";
const FOURSQUARE_SEARCH_URL = "https://places-api.foursquare.com/places/search";
const FOURSQUARE_CREDENTIAL_ID = "foursquare_default";
const DEFAULT_PROFILE = "driving-car";
const ROUTE_RADIUS_METERS = 6_000;
const MAX_ROUTE_CANDIDATES = 8;

type DbClient = any;

type SecretProvider = "openrouteservice" | "foursquare";

type ProviderCredential = {
  key: string;
  metadata: Record<string, unknown>;
  source: "encrypted_db" | "environment";
};

export type RouteCoordinate = {
  lat: number;
  lon: number;
};

export type RoutePlaceCandidate = {
  id: string;
  name: string;
  address: string | null;
  lat: number;
  lon: number;
  categories: string[];
  tastes: string[];
  rating: number | null;
  website: string | null;
  sourceUrl: string;
  mapUrl: string;
  corridorDistanceMeters: number;
  corridorDistanceKm: number;
  explicitNeedMatch: boolean;
};

export type ParsedRoutePlaceRequest = {
  origin: string | null;
  destination: string | null;
  placeQuery: string;
  requestedNeed: string | null;
};

export type RoutePlacesResearchResult = {
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
  parsed: ParsedRoutePlaceRequest;
  candidates: RoutePlaceCandidate[];
  routeDistanceKm: number | null;
  routeDurationMinutes: number | null;
  routeOriginLabel: string | null;
  routeDestinationLabel: string | null;
  providerTrace: string[];
  hardConstraints: string[];
  context: string;
  placeSearchQuery: string;
};

type RouteGeometryResult = {
  coordinates: RouteCoordinate[];
  distanceMeters: number;
  durationSeconds: number;
  originLabel: string;
  destinationLabel: string;
};

type RawPlaceCandidate = Omit<RoutePlaceCandidate, "corridorDistanceMeters" | "corridorDistanceKm" | "explicitNeedMatch">;

export function isRouteAwarePlaceDiscovery(text: string): boolean {
  const q = String(text || "");
  const place = /(مطعم|مطاعم|فندق|فنادق|مقهى|كوفي|كافيه|صيدلية|مستشفى|محطة|سوبرماركت|بقالة|restaurant|hotel|cafe|pharmacy|hospital|places?)/iu.test(q);
  const route = /(طريق\s*(?:من|الى|إلى|[\p{L}])|مسار|في\s+طريقي|على\s+طريقي|في\s+الطريق|على\s+الطريق|on\s+the\s+(?:way|route)|along\s+the\s+route|route\s+from)/iu.test(q);
  return place && route;
}

export function parseRoutePlaceRequest(text: string): ParsedRoutePlaceRequest {
  const route = parseRouteEndpoints(text);
  let origin = route.origin;
  let destination = route.destination;

  if (!destination) {
    const destinationOnly = String(text || "").match(/(?:على|في)\s+طريق\s+([^،,.؟?!]{2,80})/iu)?.[1];
    if (destinationOnly) {
      destination = cleanRouteDestination(destinationOnly);
    }
  }

  const placeQuery = extractPlaceQuery(text);
  const requestedNeed = extractRequestedNeed(text);
  return { origin, destination, placeQuery, requestedNeed };
}

export function sampleRouteCoordinates(
  coordinates: RouteCoordinate[],
  maxPoints: number,
): RouteCoordinate[] {
  if (!coordinates.length || maxPoints <= 0) return [];
  if (coordinates.length <= maxPoints) return coordinates.map((point) => ({ ...point }));
  if (maxPoints === 1) return [{ ...coordinates[Math.floor(coordinates.length / 2)] }];

  const result: RouteCoordinate[] = [];
  for (let i = 0; i < maxPoints; i += 1) {
    const index = Math.round((i * (coordinates.length - 1)) / (maxPoints - 1));
    const point = coordinates[index];
    if (!result.some((item) => item.lat === point.lat && item.lon === point.lon)) result.push({ ...point });
  }
  return result;
}

export function distancePointToPolylineMeters(
  point: RouteCoordinate,
  line: RouteCoordinate[],
): number {
  if (!line.length) return Number.POSITIVE_INFINITY;
  if (line.length === 1) return haversineMeters(point, line[0]);

  let best = Number.POSITIVE_INFINITY;
  for (let i = 0; i < line.length - 1; i += 1) {
    const distance = distancePointToSegmentMeters(point, line[i], line[i + 1]);
    if (distance < best) best = distance;
  }
  return best;
}

export function rankCorridorCandidates(
  candidates: RawPlaceCandidate[],
  geometry: RouteCoordinate[],
  requestedNeed: string | null,
  maxCorridorMeters = ROUTE_RADIUS_METERS,
): RoutePlaceCandidate[] {
  const ranked = candidates
    .map((candidate): RoutePlaceCandidate => {
      const corridorDistanceMeters = distancePointToPolylineMeters(candidate, geometry);
      return {
        ...candidate,
        corridorDistanceMeters: Math.round(corridorDistanceMeters),
        corridorDistanceKm: round(corridorDistanceMeters / 1000, 1),
        explicitNeedMatch: requestedNeed ? candidateExplicitlyMatchesNeed(candidate, requestedNeed) : true,
      };
    })
    .filter((candidate) => Number.isFinite(candidate.corridorDistanceMeters) && candidate.corridorDistanceMeters <= maxCorridorMeters)
    .sort((a, b) => {
      if (a.explicitNeedMatch !== b.explicitNeedMatch) return a.explicitNeedMatch ? -1 : 1;
      if (a.corridorDistanceMeters !== b.corridorDistanceMeters) return a.corridorDistanceMeters - b.corridorDistanceMeters;
      return (b.rating ?? -1) - (a.rating ?? -1);
    });
  return ranked.slice(0, MAX_ROUTE_CANDIDATES);
}

export async function prepareRoutePlacesResearch(
  db: DbClient,
  query: string,
): Promise<RoutePlacesResearchResult | null> {
  if (!isRouteAwarePlaceDiscovery(query)) return null;

  const priority = detectExplicitPriority(query) ?? classifyTaskPriority(query, "local_places");
  const parsed = parseRoutePlaceRequest(query);
  const providerTrace: string[] = [];
  const hardConstraints = [
    "route_aware_place_request=true",
    "on_route_claim_requires_verified_geometry=true",
    "corridor_distance_is_geometry_distance_not_driving_detour=true",
  ];

  if (parsed.requestedNeed) hardConstraints.push(`mandatory_place_need=${parsed.requestedNeed}`);
  if (parsed.origin) hardConstraints.push(`route_origin=${parsed.origin}`);
  if (parsed.destination) hardConstraints.push(`route_destination=${parsed.destination}`);

  if (!parsed.origin || !parsed.destination) {
    providerTrace.push("route_places_missing_endpoints");
    return makeResult({
      query,
      status: "missing_endpoints",
      priority,
      parsed,
      candidates: [],
      providerTrace,
      hardConstraints,
      placeSearchQuery: buildPlaceSearchQuery(parsed),
      context: buildMissingEndpointContext(query, parsed, priority),
    });
  }

  try {
    const routeCredential = await loadCredential(db, "openrouteservice");
    if (!routeCredential || !providerFreeOnly(routeCredential.metadata)) {
      providerTrace.push(routeCredential ? "openrouteservice_free_only_guard" : "openrouteservice_not_connected");
      return makeResult({
        query,
        status: "route_not_configured",
        priority,
        parsed,
        candidates: [],
        providerTrace,
        hardConstraints,
        placeSearchQuery: buildPlaceSearchQuery(parsed),
        context: buildUnavailableContext(query, priority, "Verified route provider is not configured on a free-only credential."),
      });
    }

    const route = await loadVerifiedRouteGeometry(
      db,
      routeCredential.key,
      parsed.origin,
      parsed.destination,
      providerTrace,
    );

    const foursquare = await loadCredential(db, "foursquare");
    if (!foursquare || !providerFreeOnly(foursquare.metadata)) {
      providerTrace.push(foursquare ? "foursquare_free_only_guard" : "foursquare_not_connected");
      return makeResult({
        query,
        status: "places_not_configured",
        priority,
        parsed,
        candidates: [],
        route,
        providerTrace,
        hardConstraints,
        placeSearchQuery: buildPlaceSearchQuery(parsed),
        context: buildUnavailableContext(query, priority, "Verified place provider is not configured on a free-only credential."),
      });
    }

    const sampleCount = priority === "important" ? 6 : priority === "medium" ? 5 : 4;
    const samplePoints = sampleRouteCoordinates(route.coordinates, sampleCount);
    const rawCandidates: RawPlaceCandidate[] = [];
    for (const point of samplePoints) {
      const found = await searchFoursquareAtPoint(
        foursquare.key,
        point,
        parsed.placeQuery,
        ROUTE_RADIUS_METERS,
      );
      rawCandidates.push(...found);
      providerTrace.push(`foursquare_route_sample:${round(point.lat, 4)},${round(point.lon, 4)}:${found.length}`);
    }

    const unique = dedupeCandidates(rawCandidates);
    const candidates = rankCorridorCandidates(unique, route.coordinates, parsed.requestedNeed);
    providerTrace.push(`route_corridor_candidates:${candidates.length}`);

    if (!candidates.length) {
      return makeResult({
        query,
        status: "no_candidates",
        priority,
        parsed,
        candidates: [],
        route,
        providerTrace,
        hardConstraints,
        placeSearchQuery: buildPlaceSearchQuery(parsed),
        context: buildNoCandidatesContext(query, parsed, priority, route),
      });
    }

    return makeResult({
      query,
      status: "verified",
      priority,
      parsed,
      candidates,
      route,
      providerTrace,
      hardConstraints,
      placeSearchQuery: buildPlaceSearchQuery(parsed),
      context: buildVerifiedContext(query, parsed, priority, route, candidates, providerTrace),
    });
  } catch (error) {
    const message = errorMessage(error);
    providerTrace.push(`route_places_error:${message.slice(0, 140)}`);
    const quota = /429|quota|rate/i.test(message);
    return makeResult({
      query,
      status: quota ? "quota_exhausted" : "provider_error",
      priority,
      parsed,
      candidates: [],
      providerTrace,
      hardConstraints,
      placeSearchQuery: buildPlaceSearchQuery(parsed),
      context: buildUnavailableContext(query, priority, `Route+places lookup failed: ${message.slice(0, 180)}`),
    });
  }
}

async function loadVerifiedRouteGeometry(
  db: DbClient,
  apiKey: string,
  originText: string,
  destinationText: string,
  trace: string[],
): Promise<RouteGeometryResult> {
  const origin = await geocode(apiKey, originText);
  const destination = await geocode(apiKey, destinationText);
  if (!origin || !destination) throw new Error("ORS geocoding could not resolve both route endpoints");
  trace.push(`openrouteservice_geocode_origin:${origin.label}`);
  trace.push(`openrouteservice_geocode_destination:${destination.label}`);

  const url = `${ORS_BASE_URL}/openrouteservice/v2/directions/${DEFAULT_PROFILE}/geojson`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: apiKey,
      "Content-Type": "application/json",
      Accept: "application/geo+json, application/json",
    },
    body: JSON.stringify({
      coordinates: [[origin.lon, origin.lat], [destination.lon, destination.lat]],
      instructions: false,
    }),
  });
  await recordRateLimitHeaders(db, response, "route_places_geojson").catch(() => undefined);
  const text = await response.text();
  if (!response.ok) throw new Error(`ORS route geometry ${response.status}: ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const feature = Array.isArray(body?.features) ? body.features[0] : null;
  const rawCoordinates = feature?.geometry?.coordinates;
  const summary = feature?.properties?.summary;
  if (!Array.isArray(rawCoordinates) || rawCoordinates.length < 2) throw new Error("ORS route geometry returned no usable line");

  const coordinates = rawCoordinates
    .map((value: any): RouteCoordinate | null => {
      if (!Array.isArray(value) || value.length < 2) return null;
      const lon = Number(value[0]);
      const lat = Number(value[1]);
      return validCoordinate(lat, lon) ? { lat, lon } : null;
    })
    .filter((value: RouteCoordinate | null): value is RouteCoordinate => Boolean(value));
  if (coordinates.length < 2) throw new Error("ORS route geometry coordinates were invalid");

  const distanceMeters = Number(summary?.distance);
  const durationSeconds = Number(summary?.duration);
  if (!Number.isFinite(distanceMeters) || distanceMeters <= 0 || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    throw new Error("ORS route geometry returned no verified summary");
  }
  trace.push(`openrouteservice_route_geometry:${coordinates.length}:${Math.round(distanceMeters)}m`);
  return {
    coordinates,
    distanceMeters,
    durationSeconds,
    originLabel: origin.label,
    destinationLabel: destination.label,
  };
}

async function geocode(apiKey: string, text: string): Promise<{ lat: number; lon: number; label: string } | null> {
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
  return { lat, lon, label };
}

async function searchFoursquareAtPoint(
  apiKey: string,
  point: RouteCoordinate,
  query: string,
  radiusMeters: number,
): Promise<RawPlaceCandidate[]> {
  const search = new URL(FOURSQUARE_SEARCH_URL);
  search.searchParams.set("query", query || "restaurant");
  search.searchParams.set("ll", `${point.lat},${point.lon}`);
  search.searchParams.set("radius", String(radiusMeters));
  search.searchParams.set("limit", "10");
  search.searchParams.set("sort", "RATING");

  const response = await fetch(search.toString(), {
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: "application/json",
      "X-Places-Api-Version": "2025-06-17",
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Foursquare route search ${response.status}: ${text.slice(0, 180)}`);
  const body = JSON.parse(text);
  const rows = Array.isArray(body?.results) ? body.results : Array.isArray(body) ? body : [];
  return rows.map(parseFoursquareCandidate).filter((value: RawPlaceCandidate | null): value is RawPlaceCandidate => Boolean(value));
}

function parseFoursquareCandidate(item: any): RawPlaceCandidate | null {
  const geo = item?.geocodes?.main || item?.geocodes?.roof || item?.geocodes?.drop_off || {};
  const lat = firstFinite(item?.latitude, geo?.latitude, item?.location?.latitude);
  const lon = firstFinite(item?.longitude, geo?.longitude, item?.location?.longitude);
  if (lat == null || lon == null || !validCoordinate(lat, lon)) return null;

  const id = String(item?.fsq_place_id || item?.fsq_id || `${item?.name || "place"}:${lat}:${lon}`).trim();
  const name = String(item?.name || item?.title || "").trim();
  if (!name) return null;
  const categories = Array.isArray(item?.categories) ? item.categories.map((entry: any) => String(entry?.name || "").trim()).filter(Boolean) : [];
  const tastes = Array.isArray(item?.tastes) ? item.tastes.map((entry: unknown) => String(entry || "").trim()).filter(Boolean) : [];
  const location = item?.location || {};
  const address = [location?.formatted_address, location?.address, location?.locality, location?.region]
    .map((value) => String(value || "").trim())
    .filter(Boolean)
    .filter((value, index, all) => all.indexOf(value) === index)
    .join(" | ") || null;
  const ratingValue = Number(item?.rating);
  const rating = Number.isFinite(ratingValue) ? ratingValue : null;
  const website = String(item?.website || "").trim() || null;
  const sourceUrl = id ? `https://foursquare.com/v/${encodeURIComponent(id)}` : website || "https://foursquare.com/";
  return {
    id,
    name,
    address,
    lat,
    lon,
    categories,
    tastes,
    rating,
    website,
    sourceUrl,
    mapUrl: buildMapUrl(lat, lon),
  };
}

async function loadCredential(db: DbClient, provider: SecretProvider): Promise<ProviderCredential | null> {
  const id = provider === "openrouteservice" ? ORS_CREDENTIAL_ID : FOURSQUARE_CREDENTIAL_ID;
  const { data: row } = await db.from("h_runtime_ai_credentials")
    .select("secret_ciphertext,secret_iv,secret_version,oauth_metadata")
    .eq("id", id)
    .eq("provider", provider)
    .maybeSingle();

  if (row) {
    if (Number(row.secret_version || 1) !== 1) return null;
    const key = (await decryptProviderSecret(provider, String(row.secret_ciphertext), String(row.secret_iv))).trim();
    const metadata = isRecord(row.oauth_metadata) ? row.oauth_metadata : {};
    return key ? { key, metadata, source: "encrypted_db" } : null;
  }

  if (provider === "openrouteservice") {
    const env = String(Deno.env.get("OPENROUTESERVICE_API_KEY") || "").trim();
    return env ? { key: env, metadata: { free_only: true, allow_paid: false }, source: "environment" } : null;
  }
  return null;
}

function providerFreeOnly(metadata: Record<string, unknown>): boolean {
  return metadata.free_only === true && metadata.allow_paid !== true;
}

async function decryptProviderSecret(provider: SecretProvider, ciphertext: string, iv: string): Promise<string> {
  const root = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!root) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not configured");
  const label = `h-provider-aes-v1:${provider}`;
  const digest = await crypto.subtle.digest("SHA-256", toArrayBuffer(new TextEncoder().encode(`${label}:${root}`)));
  const key = await crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["decrypt"]);
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: toArrayBuffer(decodeBase64Url(iv)) },
    key,
    toArrayBuffer(decodeBase64Url(ciphertext)),
  );
  return new TextDecoder().decode(decrypted);
}

function makeResult(input: {
  query: string;
  status: RoutePlacesResearchResult["status"];
  priority: HTaskPriority;
  parsed: ParsedRoutePlaceRequest;
  candidates: RoutePlaceCandidate[];
  providerTrace: string[];
  hardConstraints: string[];
  placeSearchQuery: string;
  context: string;
  route?: RouteGeometryResult;
}): RoutePlacesResearchResult {
  return {
    handled: true,
    query: input.query,
    status: input.status,
    priority: input.priority,
    parsed: input.parsed,
    candidates: input.candidates,
    routeDistanceKm: input.route ? round(input.route.distanceMeters / 1000, 1) : null,
    routeDurationMinutes: input.route ? Math.max(1, Math.round(input.route.durationSeconds / 60)) : null,
    routeOriginLabel: input.route?.originLabel ?? null,
    routeDestinationLabel: input.route?.destinationLabel ?? null,
    providerTrace: input.providerTrace,
    hardConstraints: input.hardConstraints,
    context: input.context,
    placeSearchQuery: input.placeSearchQuery,
  };
}

function buildVerifiedContext(
  query: string,
  parsed: ParsedRoutePlaceRequest,
  priority: HTaskPriority,
  route: RouteGeometryResult,
  candidates: RoutePlaceCandidate[],
  trace: string[],
): string {
  return [
    "H_VERIFIED_ROUTE_PLACES_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Route: ${route.originLabel} -> ${route.destinationLabel}`,
    `Route distance km: ${round(route.distanceMeters / 1000, 1)}`,
    `Route provider duration minutes: ${Math.max(1, Math.round(route.durationSeconds / 60))}`,
    `Requested place query: ${parsed.placeQuery}`,
    `Mandatory need: ${parsed.requestedNeed || "none"}`,
    `Provider trace: ${trace.join(" | ")}`,
    "",
    "VERIFIED ROUTE-CORRIDOR CANDIDATES:",
    ...candidates.map((candidate, index) => [
      `[R${index + 1}] ${candidate.name}`,
      `address=${candidate.address || "unknown"}`,
      `coordinates=${candidate.lat},${candidate.lon}`,
      `corridor_distance_m=${candidate.corridorDistanceMeters}`,
      `corridor_distance_km=${candidate.corridorDistanceKm}`,
      `rating=${candidate.rating ?? "unknown"}`,
      `categories=${candidate.categories.join(", ") || "unknown"}`,
      `tastes=${candidate.tastes.join(", ") || "unknown"}`,
      `explicit_need_match=${candidate.explicitNeedMatch}`,
      `source=${candidate.sourceUrl}`,
      `map=${candidate.mapUrl}`,
    ].join("; ")),
    "",
    "RULES:",
    "- corridor_distance is the geometric distance from the Foursquare place coordinate to the verified ORS route line. It is NOT driving detour distance or detour time.",
    "- A place may be described as close to the verified route when its corridor distance is supplied above.",
    "- Never claim exact detour distance/time, fastest stop, traffic impact or road access without a dedicated route-to-candidate calculation.",
    "- If a mandatory dish/service was requested, explicit_need_match=true is useful provider evidence; otherwise require separate exact-place web/menu evidence before claiming the place offers it.",
    "- Do not invent ratings, opening hours, menu items, prices or availability.",
  ].join("\n");
}

function buildMissingEndpointContext(query: string, parsed: ParsedRoutePlaceRequest, priority: HTaskPriority): string {
  return [
    "H_VERIFIED_ROUTE_PLACES_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `origin=${parsed.origin || "MISSING"}`,
    `destination=${parsed.destination || "MISSING"}`,
    "Route-aware place search needs both origin and destination. Ask only for the missing route endpoint/location and preserve the requested place/service constraint.",
    "Do not pretend a place is on the route before route geometry is available.",
  ].join("\n");
}

function buildUnavailableContext(query: string, priority: HTaskPriority, reason: string): string {
  return [
    "H_VERIFIED_ROUTE_PLACES_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Provider status: ${reason}`,
    "Do not estimate whether a place is on the route or how far the detour is.",
    "You may still provide independently verified place information, while explicitly marking the route constraint as unverified.",
  ].join("\n");
}

function buildNoCandidatesContext(
  query: string,
  parsed: ParsedRoutePlaceRequest,
  priority: HTaskPriority,
  route: RouteGeometryResult,
): string {
  return [
    "H_VERIFIED_ROUTE_PLACES_CONTEXT",
    `Request: ${query}`,
    `Effort: ${priority}`,
    `Route: ${route.originLabel} -> ${route.destinationLabel}`,
    `Requested place query: ${parsed.placeQuery}`,
    `Mandatory need: ${parsed.requestedNeed || "none"}`,
    `Search corridor radius meters: ${ROUTE_RADIUS_METERS}`,
    "No candidate passed the verified route-corridor filter. Do not invent a route match.",
  ].join("\n");
}

function extractPlaceQuery(text: string): string {
  const q = String(text || "").replace(/\s+/g, " ").trim();
  const match = q.match(/(مطعم|مطاعم|فندق|فنادق|مقهى|كوفي|كافيه|صيدلية|مستشفى|محطة|سوبرماركت|بقالة|restaurant|hotel|cafe|pharmacy|hospital)\s*([^،,.؟?!]{0,80})/iu);
  if (!match) return "restaurant";
  const category = match[1];
  const tail = String(match[2] || "")
    .replace(/(?:على|في)\s+(?:طريقي|الطريق|طريق).*$/iu, "")
    .replace(/\s+من\s+.+$/iu, "")
    .replace(/\s+(?:قريب|بالقرب).*$/iu, "")
    .trim();
  return `${category}${tail ? ` ${tail}` : ""}`.trim().slice(0, 120);
}

function extractRequestedNeed(text: string): string | null {
  const q = String(text || "").replace(/\s+/g, " ").trim();
  const patterns = [
    /(?:مطعم|مطاعم)\s+(?:يقدم|يبيع|عنده|فيه)\s+([^،,.؟?!]{2,60}?)(?:\s+(?:على|في)\s+(?:طريقي|الطريق|طريق)|$)/iu,
    /(?:مطعم|مطاعم)\s+([^،,.؟?!]{2,50}?)(?:\s+(?:على|في)\s+(?:طريقي|الطريق|طريق)|$)/iu,
    /(?:فندق|فنادق)\s+(?:فيه|عنده|يقدم)\s+([^،,.؟?!]{2,60}?)(?:\s+(?:على|في)\s+(?:طريقي|الطريق|طريق)|$)/iu,
  ];
  for (const pattern of patterns) {
    const raw = pattern.exec(q)?.[1];
    const value = raw?.replace(/^(?:يقدم|يبيع|عنده|فيه)\s+/iu, "").trim();
    if (value && value.length >= 2 && !/^(قريب|طريق|مسار)$/iu.test(value)) return value.slice(0, 60);
  }
  return null;
}

function cleanRouteDestination(value: string): string | null {
  const cleaned = value
    .replace(/\s+(?:يقدم|يبيع|عنده|فيه|بتقييم|تقييم|ويقدم|ويبيع).*$/iu, "")
    .replace(/\s+(?:مطعم|فندق|مقهى|كوفي|كافيه).*$/iu, "")
    .trim()
    .slice(0, 120);
  return cleaned || null;
}

function buildPlaceSearchQuery(parsed: ParsedRoutePlaceRequest): string {
  return [parsed.placeQuery, parsed.origin, parsed.destination].filter(Boolean).join(" ").replace(/\s+/g, " ").trim().slice(0, 220);
}

function candidateExplicitlyMatchesNeed(candidate: RawPlaceCandidate, requestedNeed: string): boolean {
  const haystack = normalizeText([candidate.name, ...candidate.categories, ...candidate.tastes].join(" "));
  const tokens = normalizeText(requestedNeed).split(/\s+/).filter((token) => token.length >= 2 && !STOP_WORDS.has(token));
  if (!tokens.length) return false;
  return tokens.every((token) => haystack.includes(token));
}

const STOP_WORDS = new Set(["رز", "ال", "في", "من", "على", "مع", "يقدم", "يبيع", "عنده", "فيه", "مطعم", "فندق"]);

function dedupeCandidates(candidates: RawPlaceCandidate[]): RawPlaceCandidate[] {
  const seen = new Set<string>();
  const result: RawPlaceCandidate[] = [];
  for (const candidate of candidates) {
    const key = candidate.id || `${normalizeText(candidate.name)}:${round(candidate.lat, 4)}:${round(candidate.lon, 4)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(candidate);
  }
  return result;
}

function distancePointToSegmentMeters(point: RouteCoordinate, a: RouteCoordinate, b: RouteCoordinate): number {
  const originLat = point.lat * Math.PI / 180;
  const metersPerLat = 111_132;
  const metersPerLon = 111_320 * Math.cos(originLat);
  const ax = (a.lon - point.lon) * metersPerLon;
  const ay = (a.lat - point.lat) * metersPerLat;
  const bx = (b.lon - point.lon) * metersPerLon;
  const by = (b.lat - point.lat) * metersPerLat;
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSquared = dx * dx + dy * dy;
  if (lengthSquared <= 0) return Math.sqrt(ax * ax + ay * ay);
  const t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
  const x = ax + t * dx;
  const y = ay + t * dy;
  return Math.sqrt(x * x + y * y);
}

function haversineMeters(a: RouteCoordinate, b: RouteCoordinate): number {
  const radius = 6_371_000;
  const dLat = (b.lat - a.lat) * Math.PI / 180;
  const dLon = (b.lon - a.lon) * Math.PI / 180;
  const lat1 = a.lat * Math.PI / 180;
  const lat2 = b.lat * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * radius * Math.asin(Math.min(1, Math.sqrt(h)));
}

async function recordRateLimitHeaders(db: DbClient, response: Response, endpoint: string): Promise<void> {
  const limit = headerNumber(response.headers.get("x-ratelimit-limit"));
  const remaining = headerNumber(response.headers.get("x-ratelimit-remaining"));
  const resetEpoch = headerNumber(response.headers.get("x-ratelimit-reset"));
  if (limit == null && remaining == null && resetEpoch == null) return;
  await db.from("h_runtime_state").upsert({
    key: "route_provider_openrouteservice",
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

function buildMapUrl(lat: number, lon: number): string {
  const url = new URL("https://www.google.com/maps/search/");
  url.searchParams.set("api", "1");
  url.searchParams.set("query", `${lat},${lon}`);
  return url.toString();
}

function looksSaudi(text: string): boolean {
  return /(السعود|الرياض|جدة|مكة|المدينة|أبها|ابها|محايل|عسير|جازان|الخبر|الدمام|الطائف|خميس\s*مشيط)/iu.test(text);
}

function validCoordinate(lat: number, lon: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

function normalizeText(value: string): string {
  return String(value || "")
    .toLowerCase()
    .replace(/[إأآ]/g, "ا")
    .replace(/ى/g, "ي")
    .replace(/ؤ/g, "و")
    .replace(/ئ/g, "ي")
    .replace(/[ًٌٍَُِّْـ]/g, "")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function firstFinite(...values: unknown[]): number | null {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
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
