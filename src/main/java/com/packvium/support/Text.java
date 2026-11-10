package com.packvium.support;

/** String rules shared with the reference engines, which are ASCII-only where Java is not. */
public final class Text {
    private static final String TRIMMED = " \t\n\r\0\u000B";

    private Text() {
    }

    /** Strips exactly the characters the reference engines strip; a no-break space survives. */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && TRIMMED.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIMMED.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** Lower-cases ASCII letters only, so a unit name never depends on the host locale. */
    public static String asciiLower(String value) {
        StringBuilder lowered = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            lowered.append(character >= 'A' && character <= 'Z' ? (char) (character + 32) : character);
        }
        return lowered.toString();
    }

    public static String unitKey(String unit) {
        return asciiLower(trim(unit));
    }
}
