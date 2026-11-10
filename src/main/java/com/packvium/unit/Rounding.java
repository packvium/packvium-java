package com.packvium.unit;

public enum Rounding {
    FLOOR("floor"),
    CEIL("ceil"),
    NEAREST("nearest");

    private final String wireName;

    Rounding(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
