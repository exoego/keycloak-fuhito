package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Finds the stored things that the rows of one import list name, by their IDs or by their IDs in the system of the
 * row. A row is never matched by its values.
 */
final class ImportMatch {

    private final ExternalIdService externalIds;
    private final ExternalId.Kind kind;
    private final Set<String> seen = new HashSet<>();

    ImportMatch(ExternalIdService externalIds, ExternalId.Kind kind) {
        this.externalIds = externalIds;
        this.kind = kind;
    }

    /**
     * Finds the stored thing of a row. An external ID that names nothing is a new thing.
     *
     * @param source the system of the row
     * @param id the ID that the row gives, or null
     * @param externalId the ID in the system of the row, or null
     * @param exists tells whether a thing with an ID is stored
     * @return the ID of the stored thing, or empty for a new one
     * @throws FuhitoException when the row gives neither, when the ID names nothing, when the two name different
     *         things, or when another row names the same thing
     */
    Optional<String> stored(String source, String id, String externalId, Predicate<String> exists) {
        return stored(source, id, externalId, "", exists);
    }

    /**
     * Finds the stored thing of a row that gives one part of it, such as the names of one date. Two rows may name
     * the same thing with other parts.
     *
     * @param source the system of the row
     * @param id the ID that the row gives, or null
     * @param externalId the ID in the system of the row, or null
     * @param part the part that the row gives
     * @param exists tells whether a thing with an ID is stored
     * @return the ID of the stored thing, or empty for a new one
     * @throws FuhitoException when the row gives neither, when the ID names nothing, when the two name different
     *         things, or when another row names the same part of the same thing
     */
    Optional<String> stored(String source, String id, String externalId, String part, Predicate<String> exists) {
        if (id == null && externalId == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "externalId"));
        }
        String key = id == null ? source + "\u0000" + externalId : "\u0000id\u0000" + id;
        if (!seen.add(key + "\u0000" + part)) {
            throw FuhitoException.invalid("import.duplicate_row",
                    Map.of("externalId", Objects.requireNonNullElse(externalId, id)));
        }
        if (id == null) {
            return externalIds.resourceOf(kind, source, externalId);
        }
        if (!exists.test(id)) {
            throw FuhitoException.invalid("import.id_unknown", Map.of("id", id));
        }
        if (externalId != null && !externalIds.resourceOf(kind, source, externalId).equals(Optional.of(id))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", id, "externalId", externalId));
        }
        return Optional.of(id);
    }

    /**
     * Creates a new thing with the external ID of its row.
     *
     * @param <T> the type of the thing
     * @param source the system of the row
     * @param externalId the ID in that system
     * @param create writes the thing
     * @param idOf gives the ID of the new thing
     * @return the new thing
     * @throws FuhitoException when a value is bad
     */
    <T> T create(String source, String externalId, Supplier<T> create, Function<T, String> idOf) {
        return externalIds.create(kind, source, externalId, create, idOf);
    }

    /**
     * Checks a value of a stored thing that never changes. A row that leaves it out keeps it.
     *
     * @param <T> the type of the value
     * @param id the ID of the stored thing
     * @param field the name of the value
     * @param stored the stored value
     * @param given the value of the row, or null
     * @return the stored value
     * @throws FuhitoException when the row gives another value
     */
    static <T> T fixed(String id, String field, T stored, T given) {
        if (given != null && !given.equals(stored)) {
            throw FuhitoException.conflict("import.value_fixed", Map.of("id", id, "field", field));
        }
        return stored;
    }

    /**
     * Checks the source that a row of a stored thing gives. The source of a row is the system of the external IDs
     * that the row gives. A row that gives none can only give the source that the thing keeps.
     *
     * @param id the ID of the stored thing
     * @param stored the source that the thing keeps, or null for a thing that keeps none
     * @param given the source of the row, or null for a row that takes the source of the document
     * @param externalIds the external IDs that the row gives, of the thing and of the things that it names
     * @throws FuhitoException when the row gives another source and no external ID
     */
    static void source(String id, String stored, String given, Object... externalIds) {
        if (Arrays.stream(externalIds).allMatch(Objects::isNull)) {
            fixed(id, "source", stored, given);
        }
    }

    /**
     * Checks a value of a stored thing that never changes, as a row gives it.
     *
     * @param <T> the type of the value
     * @param row the row
     * @param id the ID of the stored thing
     * @param field the key of the value in the row
     * @param stored the stored value
     * @param given the value of the row, or null
     * @return the stored value
     * @throws FuhitoException when the row gives null or another value
     */
    static <T> T fixed(ImportRow row, String id, String field, T stored, T given) {
        if (row.gives(field, given)) {
            Checks.required(field, given);
        }
        return fixed(id, field, stored, given);
    }

    /**
     * Checks a row of a stored thing that clears its end. With no end of its own, the thing ends with the one that it
     * belongs to, and it can only end earlier.
     *
     * @param id the ID of the stored thing
     * @param stored the stored end, or null
     * @param end the end of the one that it belongs to, or null
     * @throws FuhitoException when the thing ends before the one that it belongs to
     */
    static void clearedEnd(String id, LocalDate stored, LocalDate end) {
        if (stored != null && !stored.equals(end)) {
            throw FuhitoException.conflict("import.end_kept", Map.of("id", id, "validTo", stored.toString()));
        }
    }

    /**
     * Checks the {@code validFrom} of a row of a stored thing. A row that leaves it out keeps the stored start.
     *
     * @param row the row
     * @param id the ID of the stored thing
     * @param stored the first date of the whole thing
     * @param given the {@code validFrom} of the row, or null
     * @throws FuhitoException when the row gives null, a bad date, or another date
     */
    static void start(ImportRow row, String id, LocalDate stored, String given) {
        fixed(row, id, "validFrom", stored, Checks.optionalDate("validFrom", given));
    }
}
