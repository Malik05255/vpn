import {
  distancePointToPolylineMeters,
  isRouteAwarePlaceDiscovery,
  parseRoutePlaceRequest,
  rankCorridorCandidates,
  sampleRouteCoordinates,
} from "./route-places-engine.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("detects route-aware restaurant request without treating plain routes as places", () => {
  assert(isRouteAwarePlaceDiscovery("أبي مطعم بخاري على طريقي من أبها إلى محايل"), "expected route-aware place request");
  assert(!isRouteAwarePlaceDiscovery("كم المسافة من أبها إلى محايل"), "plain route must not be route+places");
});

Deno.test("parses explicit route endpoints and requested restaurant need", () => {
  const parsed = parseRoutePlaceRequest("أبي مطعم بخاري على طريقي من أبها إلى محايل");
  assert(parsed.origin === "أبها", `unexpected origin: ${parsed.origin}`);
  assert(parsed.destination === "محايل", `unexpected destination: ${parsed.destination}`);
  assert(parsed.placeQuery.includes("مطعم"), `unexpected place query: ${parsed.placeQuery}`);
  assert(parsed.placeQuery.includes("بخاري"), `place query lost requested dish: ${parsed.placeQuery}`);
  assert(parsed.requestedNeed === "بخاري", `unexpected requested need: ${parsed.requestedNeed}`);
});

Deno.test("destination-only road request preserves missing origin for follow-up", () => {
  const parsed = parseRoutePlaceRequest("أبي مطعم بخاري على طريق محايل");
  assert(parsed.origin === null, `origin should be missing: ${parsed.origin}`);
  assert(parsed.destination === "محايل", `unexpected destination: ${parsed.destination}`);
});

Deno.test("route sampling keeps endpoints and spreads points across geometry", () => {
  const route = Array.from({ length: 11 }, (_, index) => ({ lat: 0, lon: index / 100 }));
  const sampled = sampleRouteCoordinates(route, 4);
  assert(sampled.length === 4, `unexpected sample count: ${sampled.length}`);
  assert(sampled[0].lon === 0, "origin sample missing");
  assert(sampled[sampled.length - 1].lon === 0.1, "destination sample missing");
});

Deno.test("point-to-route distance is near zero on the line and positive off route", () => {
  const line = [{ lat: 0, lon: 0 }, { lat: 0, lon: 0.1 }];
  const onRoute = distancePointToPolylineMeters({ lat: 0, lon: 0.05 }, line);
  const offRoute = distancePointToPolylineMeters({ lat: 0.01, lon: 0.05 }, line);
  assert(onRoute < 1, `on-route point should be near zero: ${onRoute}`);
  assert(offRoute > 1000 && offRoute < 1200, `unexpected off-route distance: ${offRoute}`);
});

Deno.test("corridor ranking rejects far candidates and prioritizes explicit need match", () => {
  const geometry = [{ lat: 0, lon: 0 }, { lat: 0, lon: 0.1 }];
  const base = {
    address: null,
    website: null,
    sourceUrl: "https://foursquare.com/",
    mapUrl: "https://www.google.com/maps/",
    tastes: [] as string[],
  };
  const candidates = [
    {
      ...base,
      id: "near-generic",
      name: "مطعم عام",
      lat: 0.001,
      lon: 0.04,
      categories: ["مطعم"],
      rating: 9.2,
    },
    {
      ...base,
      id: "bukhari",
      name: "مطعم البخاري",
      lat: 0.002,
      lon: 0.05,
      categories: ["مطعم بخاري"],
      rating: 8.5,
    },
    {
      ...base,
      id: "far",
      name: "مطعم بعيد",
      lat: 0.08,
      lon: 0.05,
      categories: ["مطعم بخاري"],
      rating: 10,
    },
  ];
  const ranked = rankCorridorCandidates(candidates, geometry, "بخاري", 2_000);
  assert(ranked.length === 2, `far candidate should be filtered: ${ranked.length}`);
  assert(ranked[0].id === "bukhari", `explicit need match should rank first: ${ranked[0]?.id}`);
  assert(ranked[0].explicitNeedMatch, "requested dish should be an explicit match");
});
