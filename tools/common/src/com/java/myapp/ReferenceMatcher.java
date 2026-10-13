package com.java.myapp;

import java.util.Locale;
import java.util.regex.Pattern;

/** Exact reference identity and complete request-category tokens, independent of letter case. */
final class ReferenceMatcher {
    private ReferenceMatcher() { }

    static boolean sameReference(String expected, String actual) {
        String left = normalize(expected);
        return !left.isEmpty() && left.equals(normalize(actual));
    }

    static boolean sameType(String expected, String actual) {
        String wanted = normalize(expected);
        String label = normalize(actual);
        if (wanted.isEmpty() || label.isEmpty()) return false;
        if (wanted.equals(label)) return true;

        // PROMS adds a site after the category, e.g. NT_IE_TK09(PAC) : SITE.
        // A category code must match a whole token, never part of TK090/TK09X
        // or a site identifier. A full category label is also accepted.
        int siteSeparator = label.indexOf(':');
        if (siteSeparator >= 0) label = label.substring(0, siteSeparator).trim();
        if (wanted.equals(label)) return true;
        if (!wanted.matches("[\\p{L}\\p{N}]+")) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(wanted)
                + "(?![\\p{L}\\p{N}])").matcher(label).find();
    }

    static String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
