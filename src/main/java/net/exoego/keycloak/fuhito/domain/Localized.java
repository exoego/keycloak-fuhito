package net.exoego.keycloak.fuhito.domain;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The values of a resource in several languages, such as its names. One of them is the primary one.
 *
 * @param <T> the type of the value of one language
 * @param primaryLanguage the tag of the language of the primary value
 * @param byLanguage the values by language tag, in the form of {@link Locale#toLanguageTag()}
 */
public record Localized<T>(String primaryLanguage, Map<String, T> byLanguage) {

    /**
     * Creates the values.
     *
     * @throws IllegalArgumentException when no value has the primary language
     */
    public Localized {
        byLanguage = Collections.unmodifiableSortedMap(new TreeMap<>(byLanguage));
        if (!byLanguage.containsKey(primaryLanguage)) {
            throw new IllegalArgumentException("No value has the primary language " + primaryLanguage);
        }
    }

    /**
     * Creates the values of one language.
     *
     * @param <T> the type of the value
     * @param language the language tag
     * @param value the value
     * @return the values
     */
    public static <T> Localized<T> of(String language, T value) {
        return new Localized<>(language, Map.of(language, value));
    }

    /**
     * Returns the primary value.
     *
     * @return the value of the primary language
     */
    public T primary() {
        return byLanguage.get(primaryLanguage);
    }

    /**
     * Returns the value for a reader of a language. It is the value of the tag, else the value of the language
     * alone, for example {@code en} for {@code en-US}, else the primary value.
     *
     * @param locale the language of the reader, or null for the primary value
     * @return the value
     */
    public T in(Locale locale) {
        if (locale == null) {
            return primary();
        }
        T value = byLanguage.get(locale.toLanguageTag());
        if (value == null) {
            value = byLanguage.getOrDefault(locale.getLanguage(), primary());
        }
        return value;
    }

    /**
     * Checks the values of a request. The fields of the request are {@code primaryLanguage} and {@code names}.
     *
     * @param <V> the type of a value of the request
     * @param <T> the type of a checked value
     * @param primaryLanguage the tag of the primary language
     * @param names the values by language tag
     * @param check the check of one value. It takes the name of the field, for the error, and the value.
     * @return the checked values with normalized language tags
     * @throws FuhitoException when a value or a tag is missing or bad, or no value has the primary language
     */
    public static <V, T> Localized<T> check(String primaryLanguage, Map<String, V> names,
            BiFunction<String, V, T> check) {
        String primary = Checks.languageTag("primaryLanguage", Checks.required("primaryLanguage", primaryLanguage));
        Map<String, T> checked = new TreeMap<>();
        Checks.required("names", names).forEach((tag, value) -> {
            String language = Checks.languageTag("names", tag);
            String field = "names." + language;
            checked.put(language, check.apply(field, Checks.required(field, value)));
        });
        if (!checked.containsKey(primary)) {
            throw FuhitoException.invalid("invalid.primary_language", Map.of("value", primary));
        }
        return new Localized<>(primary, checked);
    }
}
