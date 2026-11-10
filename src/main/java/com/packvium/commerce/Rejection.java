package com.packvium.commerce;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal: a structured rejection travelling to the API boundary, where it becomes the
 * {"status":"rejected", ...} result document. Never escapes this package.
 */
final class Rejection extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String rejectionCode;
    // Never serialized: the rejection is caught inside this package before it can travel.
    private final transient Map<String, Object> fields;

    Rejection(String rejectionCode, Map<String, Object> fields) {
        super(rejectionCode);
        this.rejectionCode = rejectionCode;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    String rejectionCode() {
        return rejectionCode;
    }

    Map<String, Object> fields() {
        return fields;
    }
}
