package com.packvium.sequence;

import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Non-fatal, localization-ready instruction warning. */
public record SequenceWarning(String code, long index, String messageKey, Map<String, String> arguments) {
    public SequenceWarning {
        arguments = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Arguments in key order, so the rendering never depends on how they were assembled. */
    public Map<String, Object> toMap() {
        List<String> keys = new ArrayList<>(arguments.keySet());
        keys.sort(SequenceWarning::compareKeys);
        Map<String, Object> sorted = new LinkedHashMap<>();
        for (String key : keys) {
            sorted.put(key, arguments.get(key));
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", code);
        rendered.put("index", index);
        rendered.put("message_key", messageKey);
        rendered.put("arguments", sorted);
        return rendered;
    }

    /**
     * The reference engine's {@code ksort}: canonical decimal keys become integers there and
     * compare numerically; every other pair compares as bytes.
     */
    private static int compareKeys(String left, String right) {
        if (isCanonicalInteger(left) && isCanonicalInteger(right)) {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        }
        return SortKey.compareCodePoints(left, right);
    }

    private static boolean isCanonicalInteger(String key) {
        return key.matches("-?(0|[1-9][0-9]{0,17})") && !key.equals("-0");
    }
}
