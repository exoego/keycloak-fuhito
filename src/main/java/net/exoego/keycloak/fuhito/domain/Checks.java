package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.IllformedLocaleException;
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

    /**
     * The longest language tag.
     */
    public static final int MAX_LANGUAGE_TAG_LENGTH = 64;

    /**
     * The first date that the extension takes.
     */
    public static final LocalDate FIRST_DATE = LocalDate.of(1, 1, 1);

    /**
     * The last date that the extension takes.
     */
    public static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);

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
     * Requires at most one of two fields that name one thing in two ways, so that neither is dropped.
     *
     * @param field the name of the first field, for the error
     * @param value the value of the first field, or null
     * @param otherField the name of the other field, for the error
     * @param other the value of the other field, or null
     * @throws FuhitoException when both fields have a value
     */
    public static void notBoth(String field, Object value, String otherField, Object other) {
        if (value != null && other != null) {
            throw FuhitoException.invalid("invalid.twice", Map.of("field", field, "otherField", otherField));
        }
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
        if (!fits(value, MAX_CODE_LENGTH) || !CODE.matcher(value).matches()) {
            throw FuhitoException.invalid("invalid.code", Map.of("field", field));
        }
        return value;
    }

    /**
     * Requires that a write of a stored resource gives no other value of a field that never changes. A write that
     * leaves the field out keeps the stored value, and so does one that gives the stored value, as a read has it.
     *
     * @param field the name of the field, for the error
     * @param stored the stored value, or null
     * @param given the value of the write, or null
     * @throws FuhitoException when the write gives another value
     */
    public static void fixed(String field, Object stored, Object given) {
        if (differs(stored, given)) {
            throw FuhitoException.invalid("invalid.value_fixed", Map.of("field", field));
        }
    }

    /**
     * Requires that a write of a stored resource gives no value of a field but one of the stored ones. It is for a
     * field that the write does not change but that has other values on other dates, such as a parent.
     *
     * @param field the name of the field, for the error
     * @param stored the stored values
     * @param given the value of the write, or null
     * @throws FuhitoException when the write gives another value
     */
    public static void fixedAmong(String field, Collection<?> stored, Object given) {
        if (given != null && !stored.contains(given)) {
            throw FuhitoException.invalid("invalid.value_fixed", Map.of("field", field));
        }
    }

    /**
     * Requires that two fields that are two names of one thing name the same one, when a request gives both, as a
     * read gives them. A request that leaves out the other field agrees. The values compare as in
     * {@link #fixed(String, Object, Object)}.
     *
     * @param field the name of the first field, for the error
     * @param named the value of the other field that names what the first field names, or null when there is none
     * @param otherField the name of the other field, for the error
     * @param other the value of the other field, or null
     * @throws FuhitoException when the other field names another thing
     */
    public static void agree(String field, Object named, String otherField, Object other) {
        if (differs(named, other)) {
            throw FuhitoException.invalid("invalid.mismatch", Map.of("field", field, "otherField", otherField));
        }
    }

    private static boolean differs(Object expected, Object given) {
        return given != null && !given.equals(expected);
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
        if (trimmed.isEmpty() || !fits(trimmed, MAX_NAME_LENGTH)) {
            throw FuhitoException.invalid("invalid.name", Map.of("field", field));
        }
        return trimmed;
    }

    /**
     * Tells whether a text fits a column of a number of characters. The column counts one character for each code
     * point, so an emoji is one character, though Java holds it in two chars.
     *
     * @param value the text
     * @param length the number of characters of the column
     * @return true when the text has at most that many characters
     */
    public static boolean fits(String value, int length) {
        return value.codePointCount(0, value.length()) <= length;
    }

    /**
     * Checks a text that may be missing, as {@link #optionalText(String, String)} does, and requires that it fits
     * its column.
     *
     * @param field the name of the field, for the error
     * @param value the text, or null
     * @param length the number of characters of the column
     * @return the text, or null
     * @throws FuhitoException when the text is empty, only spaces, or has more characters than the column
     */
    public static String optionalText(String field, String value, int length) {
        if (optionalText(field, value) != null && !fits(value, length)) {
            throw FuhitoException.invalid("invalid.too_long", Map.of("field", field, "max", Integer.toString(length)));
        }
        return value;
    }

    /**
     * Checks a text that may be missing. Null is the way to give no value, so an empty text fails.
     *
     * @param field the name of the field, for the error
     * @param value the text, or null
     * @return the text, or null
     * @throws FuhitoException when the text is empty or only spaces
     */
    public static String optionalText(String field, String value) {
        if (value != null && value.isBlank()) {
            throw FuhitoException.invalid("invalid.blank", Map.of("field", field));
        }
        return value;
    }

    /**
     * Requires a text that the database stores as it is sent. A text column cannot hold NUL, and UTF-8 has no form
     * for half of a surrogate pair.
     *
     * @param field the name of the field, for the error
     * @param value the text, or null
     * @return the text, or null
     * @throws FuhitoException when the text has NUL or half of a surrogate pair. The error names the code point.
     */
    public static String storable(String field, String value) {
        if (value != null) {
            value.codePoints()
                    .filter(c -> c == 0 || Character.getType(c) == Character.SURROGATE)
                    .findFirst()
                    .ifPresent(c -> {
                        throw FuhitoException.invalid("invalid.character",
                                Map.of("field", field, "codePoint", String.format(Locale.ROOT, "U+%04X", c)));
                    });
        }
        return value;
    }

    /**
     * Checks a list that may be missing. Null is the way to give no items, so an empty list fails.
     *
     * @param <C> the type of the list
     * @param field the name of the field, for the error
     * @param value the list, or null
     * @return the list, or null
     * @throws FuhitoException when the list is empty
     */
    public static <C extends Collection<?>> C optionalList(String field, C value) {
        if (value != null && value.isEmpty()) {
            throw FuhitoException.invalid("invalid.blank", Map.of("field", field));
        }
        return value;
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
     * Reads a date in the form {@code YYYY-MM-DD}, from {@link #FIRST_DATE} to {@link #LAST_DATE}.
     *
     * @param field the name of the field, for the error
     * @param value the text, or null
     * @return the date, or null when the text is null
     * @throws FuhitoException when the text is not such a date
     */
    public static LocalDate optionalDate(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return inRange(LocalDate.parse(value));
        } catch (DateTimeException e) {
            throw FuhitoException.invalid("invalid.date", Map.of("field", field, "value", value)).causedBy(e);
        }
    }

    /**
     * Reads a required date in the form {@code YYYY-MM-DD}, from {@link #FIRST_DATE} to {@link #LAST_DATE}.
     *
     * @param field the name of the field, for the error
     * @param value the text
     * @return the date
     * @throws FuhitoException when the text is missing or not such a date
     */
    public static LocalDate date(String field, String value) {
        return optionalDate(field, required(field, value));
    }

    /**
     * Requires a date from {@link #FIRST_DATE} to {@link #LAST_DATE}. Java reads other dates too, but PostgreSQL
     * cannot hold some of them and gives year 0 back as year 1. These are the years that {@code YYYY-MM-DD} writes
     * with no sign, without year 0.
     *
     * @param date the date
     * @return the date
     * @throws DateTimeException when the date is outside that range
     */
    public static LocalDate inRange(LocalDate date) {
        if (date.isBefore(FIRST_DATE) || date.isAfter(LAST_DATE)) {
            throw new DateTimeException(date + " is not from " + FIRST_DATE + " to " + LAST_DATE);
        }
        return date;
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
     * Requires that a write of a stored resource names the version that the resource has.
     *
     * @param version the version that the caller read, or null when the request has none
     * @param current the version that the resource has
     * @throws FuhitoException when the request has no version, or another one. The error of another one has the
     *         current version.
     */
    public static void version(Long version, long current) {
        if (version == null) {
            throw FuhitoException.invalid("version.required", Map.of());
        }
        if (version != current) {
            throw FuhitoException.conflict("version.conflict", Map.of("version", Long.toString(current)));
        }
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
            // 1e999999999 has a billion digits without an exponent.
            throw FuhitoException.invalid("invalid.percent", Map.of("field", field, "value", value.toString()));
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
     * Reads a language tag, for example {@code ja}, {@code en-US}, or {@code zh-Hant}.
     *
     * @param field the name of the field, for the error
     * @param tag the tag
     * @return the tag in the form of {@link Locale#toLanguageTag()}
     * @throws FuhitoException when the tag is missing, bad, or too long
     */
    public static String languageTag(String field, String tag) {
        FuhitoException bad =
                FuhitoException.invalid("invalid.language_tag", Map.of("field", field, "value", String.valueOf(tag)));
        if (tag == null) {
            throw bad;
        }
        try {
            // An empty tag gives "und", with no exception.
            String normalized = new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
            if (normalized.equals("und") || !fits(normalized, MAX_LANGUAGE_TAG_LENGTH)) {
                throw bad;
            }
            return normalized;
        } catch (IllformedLocaleException e) {
            throw bad.causedBy(e);
        }
    }
}
