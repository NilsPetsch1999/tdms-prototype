package fhcampus.nilspetsch.tdms.util;

import java.util.regex.Pattern;

public final class IdentifierValidator {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    private IdentifierValidator() {
    }

    public static void requireValid(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " contains unsupported characters.");
        }
    }
}
