package net.exoego.keycloak.fuhito.service;

import java.util.LinkedHashMap;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKindName;

/**
 * The objects of a row, such as {@code names} and {@code attributes}, merged with the stored ones by key. A key that
 * the row leaves out keeps the stored value, and a key with null removes it. A new thing merges with nothing.
 */
final class Merged {

    private Merged() {
    }

    /**
     * Picks one field of the names of one language.
     */
    @FunctionalInterface
    interface Field {

        /**
         * Picks a field.
         *
         * @param field the name of the field
         * @param given the value of the row, or null
         * @param stored the stored value, or null
         * @return the stored value when the row leaves the field out, or else the value of the row
         */
        String of(String field, String given, String stored);
    }

    /**
     * Merges the fields of the names of one language.
     *
     * @param <N> the type of the names of one language
     */
    @FunctionalInterface
    interface Names<N> {

        /**
         * Merges the names of one language.
         *
         * @param given the names of the row
         * @param stored the stored names, or null
         * @param field picks one field
         * @return the merged names
         */
        N merge(N given, N stored, Field field);
    }

    static final Names<OrgUnitName> UNIT_NAMES = (given, stored, field) -> new OrgUnitName(
            field.of("name", given.name(), stored == null ? null : stored.name()),
            field.of("shortName", given.shortName(), stored == null ? null : stored.shortName()),
            field.of("phoneticName", given.phoneticName(), stored == null ? null : stored.phoneticName()));

    static final Names<RefItemName> ITEM_NAMES = (given, stored, field) -> new RefItemName(
            field.of("name", given.name(), stored == null ? null : stored.name()));

    static final Names<RefKindName> KIND_NAMES = (given, stored, field) -> new RefKindName(
            field.of("name", given.name(), stored == null ? null : stored.name()));

    static final Names<PersonName> PERSON_NAMES = (given, stored, field) -> new PersonName(
            field.of("name", given.name(), stored == null ? null : stored.name()),
            field.of("phoneticName", given.phoneticName(), stored == null ? null : stored.phoneticName()));

    /**
     * Merges the attributes of a row with the stored ones. Each name of the row needs a definition, also a name with
     * null, as in a REST write.
     *
     * @param row the row
     * @param given the attributes of the row, or null
     * @param stored the stored attributes, or null for a new thing
     * @param rules the rules of the attributes of the realm
     * @param target the kind of resource of the row
     * @return the attributes after the row, or null for a new thing with none
     * @throws FuhitoException when a name of the row has no definition
     */
    static Map<String, Object> attributes(ImportRow row, Map<String, Object> given, Map<String, Object> stored,
            AttributeRules rules, AttributeDefinition.Target target) {
        rules.requireDefined(target, given);
        if (given == null) {
            return stored == null || !row.nulls().contains("attributes") ? stored : Map.of();
        }
        Map<String, Object> merged = stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
        given.forEach((name, value) -> {
            if (value == null) {
                merged.remove(name);
            } else {
                merged.put(name, value);
            }
        });
        return merged;
    }

    /**
     * Merges the names of a row with the stored ones. The names of a language merge field by field.
     *
     * @param <N> the type of the names of one language
     * @param row the row
     * @param given the names of the row by language tag, or null
     * @param stored the stored names by language tag, or null for a new thing
     * @param names merges the names of one language
     * @return the names after the row, or null when there are none
     * @throws FuhitoException when a tag of the row is bad, or two tags of the row give one language
     */
    static <N> Map<String, N> names(ImportRow row, Map<String, N> given, Map<String, N> stored, Names<N> names) {
        if (given == null) {
            return row.nulls().contains("names") ? null : stored;
        }
        Map<String, String> languages = Localized.languages(given);
        Map<String, N> merged = stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
        given.forEach((tag, value) -> {
            String language = languages.get(tag);
            if (value == null) {
                merged.remove(language);
            } else {
                String path = "names." + tag + ".";
                merged.put(language, names.merge(value, merged.get(language), (field, mine, kept) ->
                        mine != null || row.nulls().contains(path + field) ? mine : kept));
            }
        });
        return merged;
    }
}
