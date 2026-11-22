package net.exoego.keycloak.fuhito.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * The values of a resource in several languages, such as its names. One of them is the primary one.
 *
 * @param <T> the type of the value of one language
 * @param primaryLanguage the tag of the language of the primary value
 * @param byLanguage the values by language tag, as the write sent each tag
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
     * alone, for example {@code en} for {@code en-US}, else the primary value. A tag matches with case ignored, and
     * with no other mapping, so {@code iw} does not match {@code he}.
     *
     * @param language the language tag of the reader, or null for the primary value
     * @return the value
     */
    public T in(String language) {
        if (language == null) {
            return primary();
        }
        String alone = language.split("-", 2)[0];
        return find(language).or(() -> find(alone)).orElseGet(this::primary);
    }

    private Optional<T> find(String language) {
        return byLanguage.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(language))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * Checks the values of a request. The fields of the request are {@code primaryLanguage} and {@code names}.
     *
     * @param <V> the type of a value of the request
     * @param <T> the type of a checked value
     * @param primaryLanguage the tag of the primary language
     * @param names the values by language tag
     * @param check the check of one value. It takes the name of the field, for the error, and the value.
     * @return the checked values, with the tags as sent
     * @throws FuhitoException when a value or a tag is missing or bad, two tags differ only in case, or no value has
     *         the primary language. The primary language must be the same text as a tag.
     */
    public static <V, T> Localized<T> check(String primaryLanguage, Map<String, V> names,
            BiFunction<String, V, T> check) {
        String primary = Checks.languageTag("primaryLanguage", Checks.required("primaryLanguage", primaryLanguage));
        checkLanguages(Checks.required("names", names));
        Map<String, T> checked = new TreeMap<>();
        names.forEach((language, value) -> {
            String field = "names." + language;
            checked.put(language, check.apply(field, Checks.required(field, value)));
        });
        if (!checked.containsKey(primary)) {
            throw FuhitoException.invalid("invalid.primary_language", Map.of("value", primary));
        }
        return new Localized<>(primary, checked);
    }

    /**
     * Checks the language tags of the keys of {@code names} in a request.
     *
     * @param names the values by language tag. A value may be null.
     * @throws FuhitoException when a tag is bad, or two tags differ only in case, such as {@code ja} and {@code JA}
     */
    public static void checkLanguages(Map<String, ?> names) {
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String language : names.keySet()) {
            Checks.languageTag("names", language);
            if (!seen.add(language)) {
                throw FuhitoException.invalid("invalid.duplicate_language",
                        Map.of("field", "names." + language, "value", language));
            }
        }
    }
}
