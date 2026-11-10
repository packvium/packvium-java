package com.packvium.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One prohibition, carrying the identity, dating and priority the tag fields cannot express. */
public record PolicyRule(
        String id,
        long version,
        long effectiveAt,
        long priority,
        ShipmentContext appliesTo,
        RuleForm form) {

    /**
     * Wire name to its keys in constructor order. Exactly one may appear on a rule: a rule
     * naming two forms would have no single meaning for a citation.
     */
    public static final Map<String, List<String>> FORMS = forms();

    public String citation() {
        return id + "@" + version;
    }

    /** O(k) in the rule's key count. */
    public static PolicyRule fromArray(Object raw, int index) {
        String where = "policy.rules[" + index + "]";
        if (!PhpValues.isArray(raw)) {
            throw new PolicyException(where + " must be an object");
        }
        List<String> named = new ArrayList<>();
        for (String form : FORMS.keySet()) {
            if (PhpValues.has(raw, form)) {
                named.add(form);
            }
        }
        if (named.size() != 1) {
            List<String> forms = PhpValues.sorted(new ArrayList<>(FORMS.keySet()));
            throw new PolicyException(
                    where + " must name exactly one rule form (" + String.join(", ", forms) + "), not " + named.size());
        }
        Object id = PhpValues.get(raw, "id");
        if (!(id instanceof String text) || text.isEmpty()) {
            throw new PolicyException(where + ".id must be a non-empty string");
        }
        String formName = named.get(0);
        return new PolicyRule(
                text,
                integer(PhpValues.get(raw, "version"), where + ".version", 1),
                integer(PhpValues.get(raw, "effective_at"), where + ".effective_at", 0),
                integer(PhpValues.get(raw, "priority"), where + ".priority", 0),
                ShipmentContext.fromArray(PhpValues.get(raw, "applies_to"), where + ".applies_to"),
                form(formName, PhpValues.get(raw, formName), where + "." + formName));
    }

    public static long integer(Object value, String where, long minimum) {
        // A JSON `true` decodes to a Boolean, never to an integer, so it is refused here rather
        // than juggled to 1.
        if (!PhpValues.isInt(value) || ((Number) value).longValue() < minimum) {
            throw new PolicyException(where + " must be an integer >= " + minimum);
        }
        return ((Number) value).longValue();
    }

    private static RuleForm form(String name, Object raw, String where) {
        List<String> keys = FORMS.get(name);
        if (!PhpValues.isArray(raw)) {
            throw new PolicyException(where + " must be an object");
        }
        List<String> present = PhpValues.keys(raw);
        List<String> unknown = PhpValues.sorted(present.stream().filter(key -> !keys.contains(key)).toList());
        if (!unknown.isEmpty()) {
            throw new PolicyException(where + " has unknown keys: " + String.join(", ", unknown));
        }
        List<String> missing = keys.stream().filter(key -> !present.contains(key)).toList();
        if (!missing.isEmpty()) {
            throw new PolicyException(where + " is missing " + String.join(", ", missing));
        }
        List<Object> values = new ArrayList<>();
        for (String key : keys) {
            Object value = PhpValues.get(raw, key);
            if (key.equals("max")) {
                values.add(integer(value, where + ".max", 0));
            } else if (!(value instanceof String text) || text.isEmpty()) {
                throw new PolicyException(where + "." + key + " must be a non-empty string");
            } else {
                values.add(text);
            }
        }
        return switch (name) {
            case "separate_tags" -> new SeparateTags((String) values.get(0), (String) values.get(1));
            case "require_container_tag" -> new RequireContainerTag((String) values.get(0), (String) values.get(1));
            default -> new LimitTagPerContainer((String) values.get(0), (Long) values.get(1));
        };
    }

    private static Map<String, List<String>> forms() {
        Map<String, List<String>> forms = new LinkedHashMap<>();
        forms.put("separate_tags", List.of("tag", "from_tag"));
        forms.put("require_container_tag", List.of("item_tag", "container_tag"));
        forms.put("limit_tag_per_container", List.of("tag", "max"));
        return Collections.unmodifiableMap(forms);
    }
}
