package com.packvium.validation;

import com.packvium.serialization.JsonReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The reference result validator: every way a result fails to be a sound answer to its request,
 * judged from the two JSON documents alone.
 *
 * <p>It shares no code with this engine -- units, geometry, support and load are recomputed from
 * the request and the reported placements -- so it judges a result from any Packvium engine, and it
 * returns the issues every Packvium package returns for the same documents: the same codes and the
 * same details, in the same order. An empty list means the result is valid.
 *
 * <pre>{@code
 * Object request = JsonReader.parse(requestJson);
 * List<ResultIssue> issues = ResultValidator.validate(request, RequestPacker.pack(request));
 * }</pre>
 *
 * <p>A result whose shape breaks the contract is refused with {@link MalformedResult} instead.
 * Each check is a single pass over the placements, except the pairwise ones: collision, nesting and
 * incompatibility are O(p^2) per container, an exact convex-hull collision test is O(v^3 + e^2)
 * per overlapping pair, and stop unloading order is O(p^2) per stop.
 */
public final class ResultValidator {
    private static final List<String> REQUIRED_KEYS = List.of("status", "feasibility", "termination", "optimality",
            "complete", "algorithm", "summary", "containers", "unpacked_items", "score");

    private ResultValidator() {
    }

    /**
     * Validates two JSON documents given as text, as Python's {@code json} module reads them.
     *
     * @throws MalformedResult when either text is not a JSON object, or the result breaks the contract
     */
    public static List<ResultIssue> validate(String requestJson, String resultJson) {
        return validate(parse(requestJson), parse(resultJson));
    }

    /**
     * Validates two decoded JSON documents, each as {@link JsonReader#parse} returns it, or the same
     * tree of maps, lists, strings, numbers, booleans and nulls.
     *
     * @throws MalformedResult when the result breaks the contract
     */
    public static List<ResultIssue> validate(Object request, Object result) {
        for (String key : REQUIRED_KEYS) {
            if (!Json.has(result, key)) {
                throw MalformedResult.contract("result is missing " + PyFormat.string(key));
            }
        }
        String unit = Json.text(Json.get(Json.get(request, "units", Map.of()), "length", "mm"), "units.length");
        Object configuration = Json.get(request, "configuration", Map.of());
        BigInteger clearance = Units.toTicks(Json.get(configuration, "clearance", 0L), unit, Units.LENGTH);
        Ratio globalSupport = Units.ratioOf(Json.get(configuration, "minimum_support_ratio", 0L));
        Ordered<ItemSpec> items = Spec.items(request, unit);
        Ordered<ContainerSpec> containers = Spec.containers(request, unit);

        List<ResultIssue> issues = new ArrayList<>();
        Object validated = Lattice.materialize(request, result, items, containers, clearance, issues);
        Accounting.checkStructure(validated, issues);
        Facts.checkAlgorithmMetrics(validated, issues);
        Facts.checkEffortBudget(request, validated, issues);
        Facts.checkResultFacts(validated, issues);
        Context context = new Context(items, containers, clearance, Accounting.containerTagRules(request));
        Accounting.checkAccounting(validated, context, issues);
        checkInfeasibleProof(validated, issues);
        Ordered<ContainerSpec> fabricated = checkContainers(validated, result, context, globalSupport, issues);
        checkContainerCounts(validated, configuration, containers, issues);
        ObjectiveCheck.checkFixedPlacements(request, validated, unit, issues);
        ObjectiveCheck.checkGroups(request, validated, issues);
        ObjectiveCheck.checkSolverSelection(request, validated, issues);
        ObjectiveCheck.check(request, validated, context, fabricated, issues);
        return List.copyOf(issues);
    }

    private static Object parse(String text) {
        Object document;
        try {
            document = JsonReader.parse(text);
        } catch (RuntimeException failure) {
            throw MalformedResult.shape("not JSON: " + failure.getMessage());
        }
        if (!Json.isDict(document)) {
            throw MalformedResult.shape("a document is not a JSON object");
        }
        return document;
    }

    private static void checkInfeasibleProof(Object result, List<ResultIssue> issues) {
        if (!Json.isText(Json.at(result, "status"), "infeasible")) {
            return;
        }
        for (Object unpacked : Json.items(Json.at(result, "unpacked_items"), "result.unpacked_items")) {
            if (!Json.isText(Json.get(Json.get(unpacked, "proof", Map.of()), "level"), "proven")) {
                issues.add(new ResultIssue("unproven_infeasible",
                        "infeasible status requires a proven rejection for every unpacked item"));
                return;
            }
        }
    }

    /**
     * Each packed container against its type, recomputing a made-to-measure box first; returns
     * those recomputed boxes by container id, for the objective.
     */
    private static Ordered<ContainerSpec> checkContainers(Object validated, Object reported, Context context,
            Ratio globalSupport, List<ResultIssue> issues) {
        Ordered<ContainerSpec> fabricated = new Ordered<>();
        List<?> originals = Json.items(Json.at(reported, "containers"), "result.containers");
        List<?> packedContainers = Json.items(Json.at(validated, "containers"), "result.containers");
        for (int index = 0; index < packedContainers.size() && index < originals.size(); index++) {
            Object packed = packedContainers.get(index);
            Object containerType = Json.at(packed, "container_type");
            ContainerSpec spec = context.containers().get(containerType);
            if (spec == null) {
                issues.add(new ResultIssue("unknown_container_type", PyFormat.str(containerType)));
                continue;
            }
            spec = ContainerCheck.checkMadeToMeasure(packed, Json.has(originals.get(index), "lattice_summary"), spec,
                    context.items(), context.clearance(), issues);
            if (spec.madeToMeasure() != null) {
                fabricated.set(Json.at(packed, "id"), spec);
            }
            ContainerCheck.check(packed, spec, context.items(), context.clearance(), globalSupport, issues);
            ContainerCheck.checkAchievedExtent(packed, spec, context.clearance(), issues);
        }
        return fabricated;
    }

    private static void checkContainerCounts(Object validated, Object configuration, Ordered<ContainerSpec> containers,
            List<ResultIssue> issues) {
        List<?> opened = Json.items(Json.at(validated, "containers"), "result.containers");
        Ordered<Long> inventory = new Ordered<>();
        for (Object packed : opened) {
            Object containerType = Json.at(packed, "container_type");
            if (containers.has(containerType)) {
                inventory.set(containerType, inventory.get(containerType, 0L) + 1);
            }
        }
        for (int index = 0; index < inventory.size(); index++) {
            Object containerType = inventory.keys().get(index);
            long count = inventory.values().get(index);
            BigInteger quantity = containers.get(containerType).quantity();
            if (quantity != null && BigInteger.valueOf(count).compareTo(quantity) > 0) {
                issues.add(new ResultIssue("container_inventory_exceeded", PyFormat.str(containerType) + ": used " + count
                        + ", available " + quantity));
            }
        }
        BigInteger limit = Json.optionalInteger(Json.get(configuration, "max_containers"), "max_containers");
        if (limit != null && BigInteger.valueOf(opened.size()).compareTo(limit) > 0) {
            issues.add(new ResultIssue("max_containers_exceeded", opened.size() + " containers opened, limit " + limit));
        }
    }
}
