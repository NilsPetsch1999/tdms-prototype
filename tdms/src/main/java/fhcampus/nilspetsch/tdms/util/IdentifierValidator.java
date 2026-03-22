package fhcampus.nilspetsch.tdms.util;

import java.util.regex.Pattern;

public final class IdentifierValidator {
    // MySQL identifiers are backtick-quoted in queries, so we can safely support
    // common characters beyond plain alphanumerics while still blocking SQL-breaking input.
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$-]+$");

    private IdentifierValidator() {
    }

    public static void requireValid(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " contains unsupported characters.");
        }
    }
}
