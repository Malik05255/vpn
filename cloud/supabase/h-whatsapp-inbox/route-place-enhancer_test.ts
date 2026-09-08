import { computeDetourMetrics } from "./route-place-enhancer.ts";

function assertEquals(actual: number, expected: number, label: string) {
  if (actual !== expected) throw new Error(`${label}: expected ${expected}, got ${actual}`);
}

Deno.test("computes positive route detour relative to baseline", () => {
  const result = computeDetourMetrics(
    100_000,
    5_000,
    55_000,
    52_000,
    2_800,
    2_600,
  );
  assertEquals(result.totalDistanceMeters, 107_000, "total distance");
  assertEquals(result.detourDistanceMeters, 7_000, "detour distance");
  assertEquals(result.totalDurationSeconds, 5_400, "total duration");
  assertEquals(result.detourDurationSeconds, 400, "detour duration");
});

Deno.test("never reports negative detour when matrix path is shorter than baseline", () => {
  const result = computeDetourMetrics(
    100_000,
    5_000,
    45_000,
    50_000,
    2_300,
    2_400,
  );
  assertEquals(result.detourDistanceMeters, 0, "clamped detour distance");
  assertEquals(result.detourDurationSeconds, 0, "clamped detour duration");
});
