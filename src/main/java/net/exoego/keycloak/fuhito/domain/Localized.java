package net.exoego.keycloak.fuhito.domain;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * @throws FuhitoException when a value or a tag is missing or bad, two tags give one language, or no value has
     *         the primary language. The primary language matches a tag in normalized form.
     */
    public static <V, T> Localized<T> check(String primaryLanguage, Map<String, V> names,
            BiFunction<String, V, T> check) {
        String primary = Checks.languageTag("primaryLanguage", Checks.required("primaryLanguage", primaryLanguage));
        Map<String, String> languages = languages(Checks.required("names", names));
        Map<String, T> checked = new TreeMap<>();
        names.forEach((tag, value) -> {
            String language = languages.get(tag);
            String field = "names." + language;
            checked.put(language, check.apply(field, Checks.required(field, value)));
        });
        if (!checked.containsKey(primary)) {
            throw FuhitoException.invalid("invalid.primary_language", Map.of("value", primary));
        }
        return new Localized<>(primary, checked);
    }

    /**
     * Normalizes the language tags of the keys of {@code names} in a request, such as {@code ja} for {@code JA}.
     *
     * @param names the values by language tag. A value may be null.
     * @return the normalized tag of each key
     * @throws FuhitoException when a tag is bad, or two tags give one language, such as {@code ja} and {@code JA}
     */
    public static Map<String, String> languages(Map<String, ?> names) {
        Map<String, String> languages = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String tag : names.keySet()) {
            String language = Checks.languageTag("names", tag);
            if (!seen.add(language)) {
                throw FuhitoException.invalid("invalid.duplicate_language",
                        Map.of("field", "names." + tag, "value", language));
            }
            languages.put(tag, language);
        }
        return languages;
    }
}
