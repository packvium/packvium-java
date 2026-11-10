package com.packvium.serialization;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Typed reads over one decoded JSON object; a null member counts as absent, as on the wire. */
final class Fields {
    private final Map<?, ?> members;
    private final String scope;

    Fields(Object value, String scope) {
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException(scope + " must be an object");
        }
        this.members = object;
        this.scope = scope;
    }

    static Fields orEmpty(Object value, String scope) {
        return new Fields(value == null ? Map.of() : value, scope);
    }

    Map<?, ?> members() {
        return members;
    }

    boolean has(String name) {
        return members.get(name) != null;
    }

    Object raw(String name) {
        return members.get(name);
    }

    Object required(String name) {
        Object value = members.get(name);
        if (value == null) {
            throw new IllegalArgumentException(scope + "." + name + " is required");
        }
        return value;
    }

    String text(String name, String fallback) {
        Object value = members.get(name);
        return value == null ? fallback : value.toString();
    }

    Optional<String> optionalText(String name) {
        return Optional.ofNullable(members.get(name)).map(Object::toString);
    }

    long integer(String name, long fallback) {
        Object value = members.get(name);
        return value == null ? fallback : integerValue(value, name);
    }

    Long optionalInteger(String name) {
        Object value = members.get(name);
        return value == null ? null : integerValue(value, name);
    }

    double number(String name, double fallback) {
        Object value = members.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(scope + "." + name + " must be a number");
    }

    boolean bool(String name, boolean fallback) {
        Object value = members.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(scope + "." + name + " must be a boolean");
    }

    List<?> list(String name) {
        Object value = members.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> array) {
            return array;
        }
        throw new IllegalArgumentException(scope + "." + name + " must be an array");
    }

    List<String> strings(String name) {
        return list(name).stream().map(Object::toString).toList();
    }

    Map<String, Object> object(String name) {
        Object value = members.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException(scope + "." + name + " must be an object");
        }
        @SuppressWarnings("unchecked") // JsonReader only ever builds String-keyed objects.
        Map<String, Object> typed = (Map<String, Object>) object;
        return typed;
    }

    Fields child(String name) {
        return orEmpty(members.get(name), scope + "." + name);
    }

    private long integerValue(Object value, String name) {
        if (value instanceof Long whole) {
            return whole;
        }
        if (value instanceof BigInteger) {
            throw new IllegalArgumentException(scope + "." + name + " exceeds integer range");
        }
        throw new IllegalArgumentException(scope + "." + name + " must be an integer");
    }
}
