package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks for values that come from a request.
 */
public final class Checks {

    /**
     * The longest code.
     */
    public static final int MAX_CODE_LENGTH = 64;

    /**
     * The longest name, in characters.
     */
    public static final int MAX_NAME_LENGTH = 255;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private Checks() {
    }

    /**
     * Requires a value.
     *
     * @param <T> the type of the value
     * @param field the name of the field, for the error
     * @param value the value
     * @return the value
     * @throws FuhitoException when the value is missing
     */
    public static <T> T required(String field, T value) {
        if (value == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", field));
        }
        return value;
    }

    /**
     * Requires a code of ASCII letters, digits, underscores, dots, and hyphens that starts with a letter or digit.
     *
     * @param field the name of the field, for the error
     * @param value the code
     * @return the code
     * @throws FuhitoException when the code is missing or has a bad form
     */
    public static String code(String field, String value) {
        required(field, value);
        if (value.length() > MAX_CODE_LENGTH || !CODE.matcher(value).matches()) {
            throw FuhitoException.invalid("invalid.code", Map.of("field", field));
        }
        return value;
    }

    /**
     * Requires a name that is not blank and not too long. Length counts characters, not bytes.
     *
     * @param field the name of the field, for the error
     * @param value the name
     * @return the name without spaces around it
     * @throws FuhitoException when the name is missing, blank, or too long
     */
    public static String name(String field, String value) {
        required(field, value);
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
            throw FuhitoException.invalid("invalid.name", Map.of("field", field));
        }
        return trimmed;
    }

    /**
     * Checks a code that may be missing.
     *
     * @param field the name of the field, for the error
     * @param value the code, or null
     * @return the code, or null
     * @throws FuhitoException when the code has a bad form
     */
    public static String optionalCode(String field, String value) {
        return value == null ? null : code(field, value);
    }

    /**
     * Checks a name that may be missing.
     *
     * @param field the name of the field, for the error
     * @param value the name, or null
     * @return the name without spaces around it, or null
     * @throws FuhitoException when the name is blank or too long
     */
    public static String optionalName(String field, String value) {
        return value == null ? null : name(field, value);
    }

    /**
     * Reads a date in the form {@code YYYY-MM-DD}.
     *
     * @param field the name of the field, for the error
     * @param value the text, or null
     * @return the date, or null when the text is null
     * @throws FuhitoException when the text is not a date
     */
    public static LocalDate optionalDate(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw FuhitoException.invalid("invalid.date", Map.of("field", field, "value", value)).causedBy(e);
        }
    }

    /**
     * Reads a required date in the form {@code YYYY-MM-DD}.
     *
     * @param field the name of the field, for the error
     * @param value the text
     * @return the date
     * @throws FuhitoException when the text is missing or not a date
     */
    public static LocalDate date(String field, String value) {
        return optionalDate(field, required(field, value));
    }

    /**
     * Builds a period from two dates.
     *
     * @param from the first date
     * @param to the first date after the period, or null for no end
     * @return the period
     * @throws FuhitoException when the end is not after the start
     */
    public static ValidPeriod period(LocalDate from, LocalDate to) {
        if (to != null && !to.isAfter(from)) {
            throw FuhitoException.invalid("invalid.period", Map.of("validFrom", from.toString(), "validTo",
                    to.toString()));
        }
        return new ValidPeriod(from, to);
    }

    /**
     * Checks a share of full-time work in percent.
     *
     * @param field the name of the field, for the error
     * @param value the percentage, or null
     * @return the percentage, or null
     * @throws FuhitoException when the value is not above 0, is above 100, or has more than 2 decimals
     */
    public static BigDecimal optionalPercent(String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() <= 0 || value.compareTo(HUNDRED) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw FuhitoException.invalid("invalid.percent", Map.of("field", field, "value", value.toPlainString()));
        }
        return value;
    }

    /**
     * Reads the name of an enum constant.
     *
     * @param <E> the enum type
     * @param field the name of the field, for the error
     * @param type the enum type
     * @param value the name, or null
     * @param fallback the result when the name is null
     * @return the constant
     * @throws FuhitoException when the enum has no such constant
     */
    public static <E extends Enum<E>> E optionalEnum(String field, Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw FuhitoException.invalid("invalid.choice", Map.of("field", field, "value", value)).causedBy(e);
        }
    }

    /**
     * Checks names in several languages. The keys are language tags, for example {@code ja} and {@code en}.
     *
     * @param field the name of the field, for the error
     * @param names the names by language tag, or null for none
     * @return the names with normalized language tags, in the order given
     * @throws FuhitoException when a language tag or a name is bad
     */
    public static Map<String, String> localizedNames(String field, Map<String, String> names) {
        Map<String, String> result = new LinkedHashMap<>();
        if (names == null) {
            return result;
        }
        names.forEach((tag, text) -> result.put(languageTag(field, tag), name(field + "." + tag, text)));
        return result;
    }

    private static String languageTag(String field, String tag) {
        FuhitoException bad =
                FuhitoException.invalid("invalid.language_tag", Map.of("field", field, "value", String.valueOf(tag)));
        if (tag == null) {
            throw bad;
        }
        try {
            // An empty tag gives "und", with no exception.
            String normalized = new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
            if (normalized.equals("und")) {
                throw bad;
            }
            return normalized;
        } catch (IllformedLocaleException e) {
            throw bad.causedBy(e);
        }
    }
}
