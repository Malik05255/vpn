import {
  parseConfigurableProvider,
  providerBoundSetupMaterial,
  providerCredentialId,
  providerEncryptionLabel,
  providerFreeOnlyMetadata,
  providerStateKey,
} from "./provider-policy.ts";

Deno.test("parses only supported configurable providers", () => {
  if (parseConfigurableProvider("openrouteservice") !== "openrouteservice") throw new Error("ORS should parse");
  if (parseConfigurableProvider(" FOURSQUARE ") !== "foursquare") throw new Error("Foursquare should parse");
  if (parseConfigurableProvider("tavily") !== null) throw new Error("Tavily must stay outside this config function");
  if (parseConfigurableProvider("openrouter") !== null) throw new Error("OpenRouter must stay outside this config function");
});

Deno.test("maps provider IDs, state keys and encryption labels to runtime loaders", () => {
  if (providerCredentialId("openrouteservice") !== "openrouteservice_default") throw new Error("bad ORS id");
  if (providerCredentialId("foursquare") !== "foursquare_default") throw new Error("bad Foursquare id");
  if (providerStateKey("openrouteservice") !== "provider_openrouteservice") throw new Error("bad ORS state key");
  if (providerStateKey("foursquare") !== "provider_foursquare") throw new Error("bad Foursquare state key");
  if (providerEncryptionLabel("openrouteservice") !== "h-provider-aes-v1:openrouteservice") throw new Error("bad ORS encryption label");
  if (providerEncryptionLabel("foursquare") !== "h-provider-aes-v1:foursquare") throw new Error("bad Foursquare encryption label");
});

Deno.test("stored provider metadata is strictly free-only", () => {
  const metadata = providerFreeOnlyMetadata("foursquare", "2026-09-08T00:00:00.000Z");
  if (metadata.free_only !== true) throw new Error("free_only must be true");
  if (metadata.allow_paid !== false) throw new Error("allow_paid must be false");
  if (metadata.api_version !== "2025-06-17") throw new Error("Foursquare API version missing");
});

Deno.test("setup material is provider-bound", () => {
  const token = "same-raw-token";
  const ors = providerBoundSetupMaterial("openrouteservice", token);
  const fsq = providerBoundSetupMaterial("foursquare", token);
  if (ors === fsq) throw new Error("provider setup material must differ");
  if (!ors.includes("openrouteservice")) throw new Error("ORS binding missing");
  if (!fsq.includes("foursquare")) throw new Error("Foursquare binding missing");
});
