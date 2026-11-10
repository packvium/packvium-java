package com.packvium.serialization;

import com.packvium.Packer;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.policy.PolicyRuleSet;
import com.packvium.result.PackingResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wire request in, wire result out: what every engine's command-line entry point does. */
public final class RequestPacker {
    private RequestPacker() {
    }

    public static Map<String, Object> pack(Object rawRequest) {
        DecodedRequest request = new RequestDecoder(UnsupportedFields.CURRENT).decode(rawRequest);
        // Rules compile into the constraint pipeline rather than post-filtering a chosen answer: an
        // illegal candidate is rejected during search, so the winner was never illegal.
        ExtensionRegistry extensions = ExtensionRegistry.withConstraints(
                PolicyRuleSet.fromArray(request.policy().orElse(null)).constraints());
        PackingResult result = new Packer(request.config(), extensions).pack(request.items(), request.containers());
        Map<String, Object> encoded = new LinkedHashMap<>(
                new ResultEncoder(request.outputLengthUnit(), request.outputWeightUnit()).encode(result));
        encoded.put("catalog_versions_used", request.catalogVersionsUsed());
        return encoded;
    }
}
