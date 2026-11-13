package net.exoego.keycloak.fuhito.domain;

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
        try {
            String normalized = new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
            if (!normalized.equals("und")) {
                return normalized;
            }
        } catch (IllformedLocaleException | NullPointerException e) {
            // Falls through to the error below.
        }
        throw FuhitoException.invalid("invalid.language_tag", Map.of("field", field, "value", String.valueOf(tag)));
    }
}
