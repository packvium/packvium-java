package com.packvium.commerce;

import com.packvium.commerce.catalog.CartonMaster;
import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.ExclusionRule;
import com.packvium.commerce.catalog.ExclusionScope;
import com.packvium.commerce.catalog.FacilityOverride;
import com.packvium.commerce.catalog.ItemMaster;
import com.packvium.commerce.catalog.MasterEntry;
import com.packvium.commerce.catalog.PalletMaster;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.commerce.policy.PolicyAction;
import com.packvium.commerce.policy.PolicyOperator;
import com.packvium.commerce.policy.PolicyRegistry;
import com.packvium.commerce.policy.PolicyScope;
import com.packvium.commerce.policy.Predicate;
import com.packvium.commerce.rating.AccessorialCharge;
import com.packvium.commerce.rating.CarrierRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Parse one canonical commerce document into the three registries.
 *
 * <p>Does no commercial arithmetic: it validates shape, then hands every field to the
 * rating, policy and catalog models. A version's number is its 1-based position in its
 * versions list, which is how those registries already number a publish().
 *
 * <p>Parsing is strict in both directions -- a missing required key and an unrecognised
 * extra key are both input errors, because a field this contract does not define must
 * never be silently ignored. Complexity: one pass, O(size of the document).
 */
public final class Document {
    private final CarrierRegistry carriers;
    private final PolicyRegistry policies;
    private final Map<String, CatalogRegistry> catalogs;

    private Document(CarrierRegistry carriers, PolicyRegistry policies, Map<String, CatalogRegistry> catalogs) {
        this.carriers = carriers;
        this.policies = policies;
        this.catalogs = Collections.unmodifiableMap(catalogs);
    }

    public CarrierRegistry carriers() {
        return carriers;
    }

    public PolicyRegistry policies() {
        return policies;
    }

    public Map<String, CatalogRegistry> catalogs() {
        return catalogs;
    }

    public static Document load(Map<String, ?> document) {
        Shape.keys(document, "document", List.of(), List.of("tariffs", "policy_rules", "catalogs"));
        CarrierRegistry carriers = loadTariffs(Shape.orDefault(document, "tariffs", List.of()));
        PolicyRegistry policies = loadPolicyRules(Shape.orDefault(document, "policy_rules", List.of()));
        return new Document(carriers, policies, loadCatalogs(Shape.orDefault(document, "catalogs", List.of())));
    }

    private static CarrierRegistry loadTariffs(Object value) {
        CarrierRegistry registry = new CarrierRegistry();
        Set<String> seen = new HashSet<>();
        List<Object> entries = Shape.listOf(value, "document.tariffs");
        for (int index = 0; index < entries.size(); index++) {
            String path = "document.tariffs[" + index + "]";
            Map<String, Object> fields = Shape.map(entries.get(index), path);
            Shape.keys(fields, path, List.of("carrier_id", "service_id", "versions"));
            String carrierId = Shape.text(fields.get("carrier_id"), path + ".carrier_id");
            String serviceId = Shape.text(fields.get("service_id"), path + ".service_id");
            if (!seen.add(carrierId + "\0" + serviceId)) {
                throw Shape.fail(path, "duplicate tariff history for " + carrierId + "/" + serviceId);
            }
            publishTariffVersions(registry, carrierId, serviceId, fields.get("versions"), path);
        }
        return registry;
    }

    private static void publishTariffVersions(
            CarrierRegistry registry, String carrierId, String serviceId, Object value, String parent) {
        List<Object> versions = Shape.listOf(value, parent + ".versions");
        if (versions.isEmpty()) {
            throw Shape.fail(parent + ".versions", "a tariff history needs at least one version");
        }
        for (int index = 0; index < versions.size(); index++) {
            String path = parent + ".versions[" + index + "]";
            Map<String, Object> fields = Shape.map(versions.get(index), path);
            Shape.keys(fields, path,
                    List.of("effective_at", "dimensional_weight_divisor", "cost_per_dimensional_kg_minor"),
                    List.of("minimum_charge_minor", "fuel_surcharge_permille", "accessorials"));
            Map<String, Long> costs = new LinkedHashMap<>();
            String costsPath = path + ".cost_per_dimensional_kg_minor";
            for (Map.Entry<String, Object> cost : Shape.map(fields.get("cost_per_dimensional_kg_minor"), costsPath)
                    .entrySet()) {
                costs.put(cost.getKey(), Shape.integer(cost.getValue(), costsPath + "[" + cost.getKey() + "]"));
            }
            Shape.model(path, () -> registry.publish(
                    carrierId, serviceId,
                    Shape.integer(fields.get("effective_at"), path + ".effective_at"),
                    Shape.integer(fields.get("dimensional_weight_divisor"), path + ".dimensional_weight_divisor"),
                    costs,
                    Shape.integer(Shape.orDefault(fields, "minimum_charge_minor", 0L), path + ".minimum_charge_minor"),
                    Shape.integer(Shape.orDefault(fields, "fuel_surcharge_permille", 0L),
                            path + ".fuel_surcharge_permille"),
                    loadAccessorials(Shape.orDefault(fields, "accessorials", List.of()), path + ".accessorials")));
        }
    }

    private static Map<String, AccessorialCharge> loadAccessorials(Object value, String path) {
        Map<String, AccessorialCharge> charges = new LinkedHashMap<>();
        List<Object> entries = Shape.listOf(value, path);
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + "[" + index + "]";
            Map<String, Object> fields = Shape.map(entries.get(index), entryPath);
            Shape.keys(fields, entryPath, List.of("accessorial_id"), List.of("flat_charge_minor", "permille_of_base"));
            String id = Shape.text(fields.get("accessorial_id"), entryPath + ".accessorial_id");
            if (charges.containsKey(id)) {
                throw Shape.fail(entryPath, "duplicate accessorial_id '" + id + "'");
            }
            Object flat = fields.get("flat_charge_minor");
            Object permille = fields.get("permille_of_base");
            charges.put(id, Shape.model(entryPath, () -> new AccessorialCharge(
                    id,
                    flat == null ? Optional.empty()
                            : Optional.of(Shape.integer(flat, entryPath + ".flat_charge_minor")),
                    permille == null ? Optional.empty()
                            : Optional.of(Shape.integer(permille, entryPath + ".permille_of_base")))));
        }
        return charges;
    }

    private static PolicyRegistry loadPolicyRules(Object value) {
        PolicyRegistry registry = new PolicyRegistry();
        Set<String> seen = new HashSet<>();
        List<Object> entries = Shape.listOf(value, "document.policy_rules");
        for (int index = 0; index < entries.size(); index++) {
            String path = "document.policy_rules[" + index + "]";
            Map<String, Object> fields = Shape.map(entries.get(index), path);
            Shape.keys(fields, path, List.of("rule_id", "versions"));
            String ruleId = Shape.text(fields.get("rule_id"), path + ".rule_id");
            if (!seen.add(ruleId)) {
                throw Shape.fail(path, "duplicate rule history for '" + ruleId + "'");
            }
            publishRuleVersions(registry, ruleId, fields.get("versions"), path);
        }
        return registry;
    }

    private static void publishRuleVersions(PolicyRegistry registry, String ruleId, Object value, String parent) {
        List<Object> versions = Shape.listOf(value, parent + ".versions");
        if (versions.isEmpty()) {
            throw Shape.fail(parent + ".versions", "a rule history needs at least one version");
        }
        for (int index = 0; index < versions.size(); index++) {
            String path = parent + ".versions[" + index + "]";
            Map<String, Object> fields = Shape.map(versions.get(index), path);
            Shape.keys(fields, path, List.of("scope", "action", "predicates", "priority", "effective_at"),
                    List.of("reason"));
            Shape.model(path, () -> registry.publish(
                    ruleId,
                    Shape.enumCase(PolicyScope::tryFrom, fields.get("scope"), path + ".scope"),
                    Shape.enumCase(PolicyAction::tryFrom, fields.get("action"), path + ".action"),
                    loadPredicates(fields.get("predicates"), path + ".predicates"),
                    Shape.integer(fields.get("priority"), path + ".priority"),
                    Shape.integer(fields.get("effective_at"), path + ".effective_at"),
                    Shape.text(Shape.orDefault(fields, "reason", ""), path + ".reason")));
        }
    }

    private static List<Predicate> loadPredicates(Object value, String path) {
        List<Predicate> predicates = new ArrayList<>();
        List<Object> entries = Shape.listOf(value, path);
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + "[" + index + "]";
            Map<String, Object> fields = Shape.map(entries.get(index), entryPath);
            Shape.keys(fields, entryPath, List.of("scope", "field", "operator"), List.of("value"));
            predicates.add(Shape.model(entryPath, () -> new Predicate(
                    Shape.enumCase(PolicyScope::tryFrom, fields.get("scope"), entryPath + ".scope"),
                    Shape.text(fields.get("field"), entryPath + ".field"),
                    Shape.enumCase(PolicyOperator::tryFrom, fields.get("operator"), entryPath + ".operator"),
                    Optional.ofNullable(fields.get("value")))));
        }
        return predicates;
    }

    private static Map<String, CatalogRegistry> loadCatalogs(Object value) {
        Map<String, CatalogRegistry> catalogs = new LinkedHashMap<>();
        List<Object> entries = Shape.listOf(value, "document.catalogs");
        for (int index = 0; index < entries.size(); index++) {
            String path = "document.catalogs[" + index + "]";
            Map<String, Object> fields = Shape.map(entries.get(index), path);
            Shape.keys(fields, path, List.of("catalog_id", "versions"));
            String catalogId = Shape.text(fields.get("catalog_id"), path + ".catalog_id");
            if (catalogs.containsKey(catalogId)) {
                throw Shape.fail(path, "duplicate catalog history for '" + catalogId + "'");
            }
            CatalogRegistry registry = Shape.model(path, () -> new CatalogRegistry(catalogId));
            publishCatalogVersions(registry, fields.get("versions"), path);
            catalogs.put(catalogId, registry);
        }
        return catalogs;
    }

    private static void publishCatalogVersions(CatalogRegistry registry, Object value, String parent) {
        List<Object> versions = Shape.listOf(value, parent + ".versions");
        if (versions.isEmpty()) {
            throw Shape.fail(parent + ".versions", "a catalog history needs at least one version");
        }
        for (int index = 0; index < versions.size(); index++) {
            String path = parent + ".versions[" + index + "]";
            Map<String, Object> fields = Shape.map(versions.get(index), path);
            if (fields.containsKey("rollback_to")) {
                publishRollback(registry, fields, path);
                continue;
            }
            Shape.keys(fields, path, List.of("effective_at", "published_at", "snapshot"), List.of("note"));
            Shape.model(path, () -> registry.publish(
                    loadSnapshot(fields.get("snapshot"), path + ".snapshot"),
                    Shape.integer(fields.get("effective_at"), path + ".effective_at"),
                    Shape.integer(fields.get("published_at"), path + ".published_at"),
                    Shape.text(Shape.orDefault(fields, "note", ""), path + ".note")));
        }
    }

    private static void publishRollback(CatalogRegistry registry, Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("rollback_to", "published_at"), List.of("effective_at", "note"));
        Object effective = fields.get("effective_at");
        Optional<Long> effectiveAt = effective == null
                ? Optional.empty() : Optional.of(Shape.integer(effective, path + ".effective_at"));
        Shape.model(path, () -> {
            long toVersion = Shape.integer(fields.get("rollback_to"), path + ".rollback_to");
            long publishedAt = Shape.integer(fields.get("published_at"), path + ".published_at");
            String note = Shape.text(Shape.orDefault(fields, "note", ""), path + ".note");
            return effectiveAt.isPresent()
                    ? registry.rollback(toVersion, publishedAt, effectiveAt.get(), note)
                    : registry.rollback(toVersion, publishedAt, note);
        });
    }

    private static Snapshot loadSnapshot(Object value, String path) {
        Map<String, Object> fields = Shape.map(value, path);
        Shape.keys(fields, path, List.of(), List.of("items", "cartons", "pallets", "exclusions", "overrides"));
        List<ItemMaster> items = collect(fields, path, "items", Document::loadItem);
        List<CartonMaster> cartons = collect(fields, path, "cartons", Document::loadCarton);
        List<PalletMaster> pallets = collect(fields, path, "pallets", Document::loadPallet);
        List<ExclusionRule> exclusions = collect(fields, path, "exclusions", Document::loadExclusion);
        List<FacilityOverride> overrides = collect(fields, path, "overrides", Document::loadOverride);
        return Shape.model(path, () -> new Snapshot(items, cartons, pallets, exclusions, overrides));
    }

    private static <T> List<T> collect(Map<String, Object> fields, String path, String key,
            BiFunction<Map<String, Object>, String, T> load) {
        List<T> loaded = new ArrayList<>();
        List<Object> entries = Shape.listOf(Shape.orDefault(fields, key, List.of()), path + "." + key);
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + "." + key + "[" + index + "]";
            loaded.add(load.apply(Shape.map(entries.get(index), entryPath), entryPath));
        }
        return loaded;
    }

    private static ItemMaster loadItem(Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("id", "dimensions_mm", "weight_g"), List.of("description"));
        return Shape.model(path, () -> new ItemMaster(
                Shape.text(fields.get("id"), path + ".id"),
                Shape.axes(fields.get("dimensions_mm"), path + ".dimensions_mm", 3),
                Shape.integer(fields.get("weight_g"), path + ".weight_g"),
                Shape.text(Shape.orDefault(fields, "description", ""), path + ".description")));
    }

    private static CartonMaster loadCarton(Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("id", "inner_dimensions_mm", "max_payload_g"), List.of("cost_minor"));
        return Shape.model(path, () -> new CartonMaster(
                Shape.text(fields.get("id"), path + ".id"),
                Shape.axes(fields.get("inner_dimensions_mm"), path + ".inner_dimensions_mm", 3),
                Shape.integer(fields.get("max_payload_g"), path + ".max_payload_g"),
                Shape.integer(Shape.orDefault(fields, "cost_minor", 0L), path + ".cost_minor")));
    }

    private static PalletMaster loadPallet(Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("id", "deck_dimensions_mm", "max_payload_g"), List.of("max_stack_height_mm"));
        Object height = fields.get("max_stack_height_mm");
        return Shape.model(path, () -> new PalletMaster(
                Shape.text(fields.get("id"), path + ".id"),
                Shape.axes(fields.get("deck_dimensions_mm"), path + ".deck_dimensions_mm", 2),
                Shape.integer(fields.get("max_payload_g"), path + ".max_payload_g"),
                height == null ? Optional.empty()
                        : Optional.of(Shape.integer(height, path + ".max_stack_height_mm"))));
    }

    private static ExclusionRule loadExclusion(Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("id", "scope", "subject_id", "excluded_id"), List.of("reason"));
        return Shape.model(path, () -> new ExclusionRule(
                Shape.text(fields.get("id"), path + ".id"),
                Shape.enumCase(ExclusionScope::tryFrom, fields.get("scope"), path + ".scope"),
                Shape.text(fields.get("subject_id"), path + ".subject_id"),
                Shape.text(fields.get("excluded_id"), path + ".excluded_id"),
                Shape.text(Shape.orDefault(fields, "reason", ""), path + ".reason")));
    }

    private static FacilityOverride loadOverride(Map<String, Object> fields, String path) {
        Shape.keys(fields, path, List.of("id", "facility_id", "entry_id", "kind", "override"));
        String kind = Shape.text(fields.get("kind"), path + ".kind");
        String overridePath = path + ".override";
        Map<String, Object> payload = Shape.map(fields.get("override"), overridePath);
        MasterEntry override = switch (kind) {
            case "item" -> loadItem(payload, overridePath);
            case "carton" -> loadCarton(payload, overridePath);
            case "pallet" -> loadPallet(payload, overridePath);
            default -> throw Shape.fail(path + ".kind", "expected one of ['carton', 'item', 'pallet']");
        };
        return Shape.model(path, () -> new FacilityOverride(
                Shape.text(fields.get("id"), path + ".id"),
                Shape.text(fields.get("facility_id"), path + ".facility_id"),
                Shape.text(fields.get("entry_id"), path + ".entry_id"),
                override));
    }
}
