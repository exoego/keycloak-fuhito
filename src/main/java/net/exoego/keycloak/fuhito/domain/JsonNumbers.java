package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * The numbers of JSON that a 64-bit floating point number gives back the same. JavaScript reads every number of
 * JSON as such a number, and so do most other clients and spreadsheets.
 */
public final class JsonNumbers {

    private JsonNumbers() {
    }

    /**
     * Gives the double of a number when the double gives back the same number. A read writes a double in its
     * shortest form, as JavaScript does.
     *
     * @param value the number
     * @return the double, or empty when the number is too large or too small for a double, or has more digits than
     *         a double keeps
     */
    public static Optional<Double> sameDouble(BigDecimal value) {
        double nearest = value.doubleValue();
        if (!Double.isFinite(nearest) || new BigDecimal(Double.toString(nearest)).compareTo(value) != 0) {
            return Optional.empty();
        }
        return Optional.of(nearest);
    }

    /**
     * Tells whether a double gives back the same number, as {@link #sameDouble} says.
     *
     * @param value the number, which JSON reads as an Integer, a Long, a BigInteger, a Double, or a BigDecimal
     * @return true when a double gives back the same number
     */
    public static boolean fitsDouble(Number value) {
        if (value instanceof Integer) {
            return true;
        }
        if (value instanceof Double nearest) {
            return Double.isFinite(nearest);
        }
        BigDecimal decimal = switch (value) {
            case Long whole -> BigDecimal.valueOf(whole);
            case BigInteger whole -> new BigDecimal(whole);
            case BigDecimal exact -> exact;
            default -> null;
        };
        return decimal != null && sameDouble(decimal).isPresent();
    }
}
