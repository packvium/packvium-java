package com.packvium.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One open, forward-compatible result axis.
 *
 * <p>The code is deliberately a string rather than an enum so an older client can parse and
 * preserve a value introduced by a newer producer.
 */
public record ResultFact(String code, Map<String, Object> attributes) {
    public ResultFact {
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Result fact code must not be empty");
        }
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public ResultFact(String code) {
        this(code, Map.of());
    }

    public ResultFact withCode(String replacement) {
        return new ResultFact(replacement, attributes);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", code);
        rendered.putAll(attributes);
        return rendered;
    }
}
