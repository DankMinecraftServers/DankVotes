package com.dankmc.dankvotes.core;

/**
 * Small string helpers to keep the codebase Java 8 compatible.
 * (String#isBlank is Java 11+, so we provide our own.)
 */
public final class Strings {
    private Strings() {}

    /** True if the string is null, empty, or only whitespace. */
    public static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }
}
