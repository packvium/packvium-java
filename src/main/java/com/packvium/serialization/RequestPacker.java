package com.packvium.serialization;

import com.packvium.Packer;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.policy.PolicyRuleSet;
import com.packvium.result.PackingResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire request in, wire result out: what every engine's command-line entry point does. */
public final class RequestPacker {
    private RequestPacker() {
    }

    /** Pack a decoded JSON request with no caller extensions. */
    public static Map<String, Object> pack(Object rawRequest) {
        return pack(rawRequest, ExtensionRegistry.none());
    }

    /**
     * Pack a decoded JSON request, with the caller's extensions running beside the request's own
     * policy.
     *
     * <p>The caller's constraints are <em>added to</em> the constraints the request's
     * {@code policy} compiles into, never substituted for them: replacing would let an operator
     * lock silently drop a policy rule the request asked for, which is the one thing a lock must
     * not be able to do. The caller's orderings, solvers, candidate scorer and container selector
     * are passed through unchanged.
     *
     * @param rawRequest the request as {@link JsonReader} decodes it, or the same tree of maps and lists
     * @param extensions the caller's registry, such as one carrying operator locks
     * @return the result as the wire format spells it
     */
    public static Map<String, Object> pack(Object rawRequest, ExtensionRegistry extensions) {
        DecodedRequest request = new RequestDecoder(UnsupportedFields.CURRENT).decode(rawRequest);
        // Rules compile into the constraint pipeline rather than post-filtering a chosen answer: an
        // illegal candidate is rejected during search, so the winner was never illegal.
        List<PlacementConstraint> constraints = new ArrayList<>(
                PolicyRuleSet.fromArray(request.policy().orElse(null)).constraints());
        constraints.addAll(extensions.placementConstraints());
        ExtensionRegistry combined = new ExtensionRegistry(constraints, extensions.itemOrderStrategies(),
                extensions.solvers(), extensions.candidateScorer(), extensions.containerSelector());
        PackingResult result = new Packer(request.config(), combined).pack(request.items(), request.containers());
        Map<String, Object> encoded = new LinkedHashMap<>(
                new ResultEncoder(request.outputLengthUnit(), request.outputWeightUnit()).encode(result));
        encoded.put("catalog_versions_used", request.catalogVersionsUsed());
        return encoded;
    }
}
