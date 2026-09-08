export type ConfigurableProvider = "openrouteservice" | "foursquare";

export type ProviderSpec = {
  provider: ConfigurableProvider;
  credentialId: string;
  stateKey: string;
  displayName: string;
  encryptionLabel: string;
  validationEndpoint: string;
  apiVersion: string | null;
};

const SPECS: Record<ConfigurableProvider, ProviderSpec> = {
  openrouteservice: {
    provider: "openrouteservice",
    credentialId: "openrouteservice_default",
    stateKey: "provider_openrouteservice",
    displayName: "OpenRouteService",
    encryptionLabel: "h-provider-aes-v1:openrouteservice",
    validationEndpoint: "https://api.heigit.org/pelias/v1/search",
    apiVersion: null,
  },
  foursquare: {
    provider: "foursquare",
    credentialId: "foursquare_default",
    stateKey: "provider_foursquare",
    displayName: "Foursquare Places",
    encryptionLabel: "h-provider-aes-v1:foursquare",
    validationEndpoint: "https://places-api.foursquare.com/places/search",
    apiVersion: "2025-06-17",
  },
};

export function parseConfigurableProvider(value: unknown): ConfigurableProvider | null {
  const normalized = String(value ?? "").trim().toLowerCase();
  return normalized === "openrouteservice" || normalized === "foursquare" ? normalized : null;
}

export function providerSpec(provider: ConfigurableProvider): ProviderSpec {
  return SPECS[provider];
}

export function providerCredentialId(provider: ConfigurableProvider): string {
  return providerSpec(provider).credentialId;
}

export function providerEncryptionLabel(provider: ConfigurableProvider): string {
  return providerSpec(provider).encryptionLabel;
}

export function providerStateKey(provider: ConfigurableProvider): string {
  return providerSpec(provider).stateKey;
}

export function providerFreeOnlyMetadata(
  provider: ConfigurableProvider,
  validatedAt: string,
): Record<string, unknown> {
  const spec = providerSpec(provider);
  return {
    free_only: true,
    allow_paid: false,
    validated_at: validatedAt,
    validation_endpoint: spec.validationEndpoint,
    api_version: spec.apiVersion,
    encryption_source: "supabase_service_role_derived_v1",
  };
}

/** Bind one setup token to exactly one provider without putting the raw token in storage. */
export function providerBoundSetupMaterial(provider: ConfigurableProvider, rawToken: string): string {
  return `h-provider-config-v1:${provider}:${rawToken}`;
}
