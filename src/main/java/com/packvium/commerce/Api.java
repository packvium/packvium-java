package com.packvium.commerce;

import com.packvium.commerce.catalog.AmbiguousReferenceException;
import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.NoEffectiveVersionException;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.commerce.catalog.Version;
import com.packvium.commerce.policy.Decision;
import com.packvium.commerce.policy.PolicyRegistry;
import com.packvium.commerce.policy.PolicyScope;
import com.packvium.commerce.policy.Rule;
import com.packvium.commerce.policy.RuleNotFoundException;
import com.packvium.commerce.rating.CarrierRegistry;
import com.packvium.commerce.rating.RateBreakdown;
import com.packvium.commerce.rating.RatingRequest;
import com.packvium.commerce.rating.Tariff;
import com.packvium.commerce.rating.TariffNotFoundException;
import com.packvium.commerce.rating.UnavailableServiceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Packvium's exported commercial and control-plane API.
 *
 * <p>Each entry point loads the caller's commerce document into the rating, policy and
 * catalog registries, asks them the question and serializes the answer. No price,
 * decision or version resolution is computed here. The contract -- request shapes,
 * result shapes, rejection codes, complexity and limitations -- is docs/COMMERCE-API.md,
 * and this port is held to byte-identical canonical output against the PHP port.
 *
 * <p>{@link Commerce} is the public spelling; these static methods are the same code
 * behind it.
 */
public final class Api {
    public static final long API_VERSION = 1;

    /** The closed set of rejection codes, in the order docs/COMMERCE-API.md tabulates them. */
    public static final List<String> REJECTION_CODES = List.of(
            "tariff_not_found",
            "no_effective_tariff",
            "unavailable_zone",
            "unavailable_accessorial",
            "policy_rule_not_found",
            "policy_version_not_found",
            "catalog_not_found",
            "catalog_version_not_found",
            "no_effective_catalog_version",
            "ambiguous_catalog_reference");

    private Api() {
    }

    /** Price one shipment against one pinned or effective-dated tariff version. */
    public static Map<String, Object> quote(Map<String, ?> document, Map<String, ?> request) {
        Document loaded = Document.load(document);
        Shape.keys(request, "request",
                List.of("carrier_id", "service_id", "zone", "actual_weight_g", "volume_mm3"),
                List.of("tariff_version", "as_of", "requested_accessorials"));
        String pin = exactlyOne(request, "request", List.of("tariff_version", "as_of"));
        String carrierId = Shape.text(request.get("carrier_id"), "request.carrier_id");
        String serviceId = Shape.text(request.get("service_id"), "request.service_id");
        List<String> accessorials = new ArrayList<>();
        List<Object> entries = Shape.listOf(
                Shape.orDefault(request, "requested_accessorials", List.of()), "request.requested_accessorials");
        for (int index = 0; index < entries.size(); index++) {
            accessorials.add(Shape.text(entries.get(index), "request.requested_accessorials[" + index + "]"));
        }
        RatingRequest rating = Shape.model("request", () -> new RatingRequest(
                Shape.text(request.get("zone"), "request.zone"),
                Shape.integer(request.get("actual_weight_g"), "request.actual_weight_g"),
                Shape.integer(request.get("volume_mm3"), "request.volume_mm3"),
                accessorials));

        try {
            Tariff tariff = resolveTariff(loaded.carriers(), carrierId, serviceId, request, pin);
            return ok("quote", rate(tariff, rating).toArray());
        } catch (Rejection rejection) {
            return rejected(rejection);
        }
    }

    /** Decide one eligibility question against a pinned or effective-dated rule set. */
    public static Map<String, Object> evaluatePolicy(Map<String, ?> document, Map<String, ?> request) {
        Document loaded = Document.load(document);
        Shape.keys(request, "request", List.of("scope", "context"), List.of("as_of", "rule_versions"));
        String pin = exactlyOne(request, "request", List.of("as_of", "rule_versions"));
        PolicyScope scope = Shape.enumCase(PolicyScope::tryFrom, request.get("scope"), "request.scope");
        Map<String, Object> context = Shape.map(request.get("context"), "request.context");

        try {
            return ok("decision", decide(loaded.policies(), scope, context, request, pin).toArray());
        } catch (Rejection rejection) {
            return rejected(rejection);
        }
    }

    /** Report which catalog version a reference resolves to, and what it contains. */
    public static Map<String, Object> catalogVersionInfo(Map<String, ?> document, Map<String, ?> request) {
        Document loaded = Document.load(document);
        Shape.keys(request, "request", List.of("catalog_id", "resolved_at"), List.of("version", "as_of"));
        String catalogId = Shape.text(request.get("catalog_id"), "request.catalog_id");
        long resolvedAt = Shape.integer(request.get("resolved_at"), "request.resolved_at");
        Optional<Long> version = optionalInteger(request, "version");
        Optional<Long> asOf = optionalInteger(request, "as_of");
        if (version.isPresent() && asOf.isPresent()) {
            throw Shape.fail("request", "expected at most one of ['version', 'as_of']");
        }

        try {
            Version resolved = resolveCatalog(loaded.catalogs(), catalogId, version, asOf);
            return ok("catalog", catalogPayload(catalogId, resolved, resolvedAt));
        } catch (Rejection rejection) {
            return rejected(rejection);
        }
    }

    /** The one byte-comparable spelling of a result document: sorted keys, no padding. */
    public static String canonicalJson(Map<String, ?> result) {
        return CanonicalJson.encode(result);
    }

    // ------------------------------------------------------------------------ internals

    private static String exactlyOne(Map<String, ?> request, String path, List<String> names) {
        List<String> present = new ArrayList<>();
        for (String name : names) {
            if (request.get(name) != null) {
                present.add(name);
            }
        }
        if (present.size() != 1) {
            throw Shape.fail(path, "expected exactly one of ['" + String.join("', '", names) + "']");
        }
        return present.get(0);
    }

    private static Optional<Long> optionalInteger(Map<String, ?> request, String key) {
        Object value = request.get(key);
        return value == null ? Optional.empty() : Optional.of(Shape.integer(value, "request." + key));
    }

    private static Tariff resolveTariff(
            CarrierRegistry carriers, String carrierId, String serviceId, Map<String, ?> request, String pin) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("carrier_id", carrierId);
        identity.put("service_id", serviceId);
        if (pin.equals("tariff_version")) {
            long version = Shape.integer(request.get("tariff_version"), "request.tariff_version");
            try {
                return carriers.tariff(carrierId, serviceId, version);
            } catch (TariffNotFoundException missing) {
                throw new Rejection("tariff_not_found", with(identity, "tariff_version", version));
            }
        }
        long asOf = Shape.integer(request.get("as_of"), "request.as_of");
        try {
            carriers.versions(carrierId, serviceId);
        } catch (TariffNotFoundException missing) {
            throw new Rejection("tariff_not_found", identity);
        }
        try {
            return carriers.effectiveTariff(carrierId, serviceId, asOf);
        } catch (TariffNotFoundException missing) {
            throw new Rejection("no_effective_tariff", with(identity, "as_of", asOf));
        }
    }

    private static RateBreakdown rate(Tariff tariff, RatingRequest request) {
        try {
            return CarrierRegistry.rateTariff(tariff, request);
        } catch (UnavailableServiceException error) {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("carrier_id", tariff.carrierId());
            identity.put("service_id", tariff.serviceId());
            identity.put("tariff_version", tariff.version());
            if (error.zone().isPresent()) {
                throw new Rejection("unavailable_zone", with(identity, "zone", error.zone().get()));
            }
            throw new Rejection("unavailable_accessorial", with(identity, "accessorial_ids", error.accessorialIds()));
        }
    }

    private static Decision decide(
            PolicyRegistry policies, PolicyScope scope, Map<String, Object> context, Map<String, ?> request,
            String pin) {
        if (pin.equals("as_of")) {
            return policies.evaluate(scope, context, Shape.integer(request.get("as_of"), "request.as_of"));
        }
        return PolicyRegistry.decide(pinnedRules(policies, request.get("rule_versions")), scope, context);
    }

    private static List<Rule> pinnedRules(PolicyRegistry policies, Object value) {
        List<PolicyRegistry.Pin> pins = new ArrayList<>();
        List<Object> entries = Shape.listOf(value, "request.rule_versions");
        for (int index = 0; index < entries.size(); index++) {
            String path = "request.rule_versions[" + index + "]";
            List<Object> pair = Shape.listOf(entries.get(index), path);
            if (pair.size() != 2) {
                throw Shape.fail(path, "expected a [rule_id, version] pair");
            }
            pins.add(new PolicyRegistry.Pin(
                    Shape.text(pair.get(0), path + "[0]"), Shape.integer(pair.get(1), path + "[1]")));
        }
        try {
            return policies.resolveVersions(pins);
        } catch (IllegalArgumentException error) {
            throw new CommerceInputException("request.rule_versions: " + error.getMessage(), error);
        } catch (com.packvium.commerce.policy.VersionNotFoundException error) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("rule_id", error.ruleId());
            fields.put("version", error.version());
            throw new Rejection("policy_version_not_found", fields);
        } catch (RuleNotFoundException error) {
            throw new Rejection("policy_rule_not_found", with(new LinkedHashMap<>(), "rule_id", error.ruleId()));
        }
    }

    private static Version resolveCatalog(
            Map<String, CatalogRegistry> catalogs, String catalogId, Optional<Long> version, Optional<Long> asOf) {
        CatalogRegistry registry = catalogs.get(catalogId);
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("catalog_id", catalogId);
        if (registry == null) {
            throw new Rejection("catalog_not_found", selector);
        }
        version.ifPresent(number -> selector.put("version", number));
        asOf.ifPresent(instant -> selector.put("as_of", instant));
        try {
            return registry.resolve(0, version, asOf);
        } catch (com.packvium.commerce.catalog.VersionNotFoundException missing) {
            throw new Rejection("catalog_version_not_found", selector);
        } catch (NoEffectiveVersionException missing) {
            throw new Rejection("no_effective_catalog_version", selector);
        } catch (AmbiguousReferenceException ambiguous) {
            throw new Rejection("ambiguous_catalog_reference", selector);
        }
    }

    private static Map<String, Object> catalogPayload(String catalogId, Version version, long resolvedAt) {
        Snapshot snapshot = version.snapshot();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("items", (long) snapshot.items().size());
        counts.put("cartons", (long) snapshot.cartons().size());
        counts.put("pallets", (long) snapshot.pallets().size());
        counts.put("exclusions", (long) snapshot.exclusions().size());
        counts.put("overrides", (long) snapshot.overrides().size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalog_id", catalogId);
        payload.put("version", version.number());
        payload.put("effective_at", version.effectiveAt());
        payload.put("published_at", version.publishedAt());
        payload.put("resolved_at", resolvedAt);
        payload.put("rolled_back_from", version.rolledBackFrom().orElse(null));
        payload.put("note", version.note());
        payload.put("entry_counts", counts);
        payload.put("item_ids", snapshot.itemIds());
        payload.put("carton_ids", snapshot.cartonIds());
        payload.put("pallet_ids", snapshot.palletIds());
        return payload;
    }

    private static Map<String, Object> with(Map<String, Object> fields, String key, Object value) {
        Map<String, Object> extended = new LinkedHashMap<>(fields);
        extended.put(key, value);
        return extended;
    }

    private static Map<String, Object> ok(String key, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_version", API_VERSION);
        result.put("status", "ok");
        result.put(key, payload);
        return result;
    }

    private static Map<String, Object> rejected(Rejection rejection) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", rejection.rejectionCode());
        error.put("fields", rejection.fields());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_version", API_VERSION);
        result.put("status", "rejected");
        result.put("error", error);
        return result;
    }
}
