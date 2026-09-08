import {
  isDirectRouteRequest,
  parseRouteEndpoints,
} from "./route-engine.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("detects direct Arabic route requests", () => {
  assert(isDirectRouteRequest("أسرع طريق من أبها إلى محايل"), "expected direct route request");
  assert(isDirectRouteRequest("كم تبعد محايل عن أبها؟"), "expected distance route request");
  assert(isDirectRouteRequest("المسافة بين الرياض والدمام"), "expected between route request");
});

Deno.test("does not hijack local place discovery", () => {
  assert(!isDirectRouteRequest("شوف لي مطعم على طريق محايل يقدم رز بخاري"), "restaurant discovery must remain local_places");
  assert(!isDirectRouteRequest("أبي فندق قريب الحرم بأرخص سعر"), "hotel discovery must remain local_places");
});

Deno.test("parses Arabic from-to endpoints", () => {
  const parsed = parseRouteEndpoints("أسرع طريق من أبها إلى محايل");
  assert(parsed.origin === "أبها", `unexpected origin: ${parsed.origin}`);
  assert(parsed.destination === "محايل", `unexpected destination: ${parsed.destination}`);
});

Deno.test("parses Arabic distance phrasing with عن", () => {
  const parsed = parseRouteEndpoints("كم تبعد محايل عن أبها؟");
  assert(parsed.origin === "أبها", `unexpected origin: ${parsed.origin}`);
  assert(parsed.destination === "محايل", `unexpected destination: ${parsed.destination}`);
});

Deno.test("parses Arabic between phrasing", () => {
  const parsed = parseRouteEndpoints("المسافة بين الرياض والدمام");
  assert(parsed.origin === "الرياض", `unexpected origin: ${parsed.origin}`);
  assert(parsed.destination === "الدمام", `unexpected destination: ${parsed.destination}`);
});

Deno.test("parses English from-to endpoints", () => {
  const parsed = parseRouteEndpoints("route from Riyadh to Dammam");
  assert(parsed.origin === "Riyadh", `unexpected origin: ${parsed.origin}`);
  assert(parsed.destination === "Dammam", `unexpected destination: ${parsed.destination}`);
});
