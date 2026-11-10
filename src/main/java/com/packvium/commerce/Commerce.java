package com.packvium.commerce;

import java.util.Map;

/**
 * The public function spelling of Packvium's exported commercial and control-plane API:
 * PHP's namespaced {@code Packvium\Commerce\quote()} and siblings, as static methods.
 *
 * <p>Each method is the same code as the matching {@link Api} method; see that class and
 * docs/COMMERCE-API.md for the contract. Every method throws {@link CommerceInputException}
 * when the document or request is malformed.
 */
public final class Commerce {
    private Commerce() {
    }

    /** Price one shipment against one pinned or effective-dated tariff version. */
    public static Map<String, Object> quote(Map<String, ?> document, Map<String, ?> request) {
        return Api.quote(document, request);
    }

    /** Decide one eligibility question against a pinned or effective-dated rule set. */
    public static Map<String, Object> evaluatePolicy(Map<String, ?> document, Map<String, ?> request) {
        return Api.evaluatePolicy(document, request);
    }

    /** Report which catalog version a reference resolves to, and what it contains. */
    public static Map<String, Object> catalogVersionInfo(Map<String, ?> document, Map<String, ?> request) {
        return Api.catalogVersionInfo(document, request);
    }

    /** The one byte-comparable spelling of a result document: sorted keys, no padding. */
    public static String canonicalJson(Map<String, ?> result) {
        return Api.canonicalJson(result);
    }
}
