package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Reads a number that a caller writes as text, such as a query parameter or a CSV cell. The text has the form of a
 * number of JSON, as a number in a body has. So it has only the digits 0 to 9, no plus sign in front, no spaces, and
 * no 0 in front of another digit. Java itself would take a digit of any script, such as {@code ５}, and a plus sign.
 */
public final class NumberText {

    private static final Pattern WHOLE = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private NumberText() {
    }

    /**
     * Tells whether a text is a whole number in the form of JSON, such as {@code 0}, {@code 42}, or {@code -7}.
     *
     * @param text the text
     * @return true for a whole number of any size
     */
    public static boolean isWhole(String text) {
        return WHOLE.matcher(text).matches();
    }

    /**
     * Reads a whole number in the form of JSON between two limits.
     *
     * @param text the text
     * @param min the smallest number
     * @param max the largest number
     * @return the number, or empty when the text is no whole number in the form of JSON or is outside the limits
     */
    public static OptionalLong whole(String text, long min, long max) {
        if (!isWhole(text)) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(text);
            return value < min || value > max ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Reads a number in the form of JSON, such as {@code 50}, {@code -0.5}, or {@code 5E1}.
     *
     * @param text the text
     * @return the number as written, or empty when the text is no number in the form of JSON or its exponent is
     *         too large or too small to read
     */
    public static Optional<BigDecimal> decimal(String text) {
        if (!NUMBER.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
