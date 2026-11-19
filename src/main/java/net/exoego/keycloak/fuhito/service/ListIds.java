package net.exoego.keycloak.fuhito.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * What the IDs of other systems in one list of an import name. The list reads the IDs that its rows name with one
 * query for each kind and system, before its first row. A row that creates a resource adds its ID, so that a later
 * row finds it. Nothing else in a list writes IDs of the kinds that it reads.
 */
final class ListIds {

    private record Name(ExternalId.Kind kind, String source, String externalId) {
    }

    private final ExternalIdService externalIds;
    private final Map<ExternalId.Kind, Map<String, Set<String>>> wanted = new EnumMap<>(ExternalId.Kind.class);
    private final Map<Name, Optional<String>> known = new HashMap<>();

    ListIds(ExternalIdService externalIds) {
        this.externalIds = externalIds;
    }

    /**
     * Adds an ID that a row names, for the next {@link #read}.
     *
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system, or null for none
     * @return this
     */
    ListIds want(ExternalId.Kind kind, String source, String externalId) {
        if (source != null && externalId != null) {
            wanted.computeIfAbsent(kind, k -> new HashMap<>())
                    .computeIfAbsent(source, s -> new LinkedHashSet<>())
                    .add(externalId);
        }
        return this;
    }

    /**
     * Reads the wanted IDs.
     */
    void read() {
        wanted.forEach((kind, bySource) -> bySource.forEach((source, values) -> {
            Map<String, String> found = externalIds.resourcesOf(kind, source, values);
            values.forEach(value -> known.put(new Name(kind, source, value),
                    Optional.ofNullable(found.get(value))));
        }));
    }

    /**
     * Finds the resource that an ID names. An ID that the list did not read is read alone.
     *
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the ID of the resource, or empty when none has the ID
     */
    Optional<String> resourceOf(ExternalId.Kind kind, String source, String externalId) {
        return known.computeIfAbsent(new Name(kind, source, externalId),
                name -> externalIds.resourceOf(kind, source, externalId));
    }

    /**
     * Creates a resource with an ID of a system, and notes the ID. The create finds the ID free as the list read
     * it. A create that fails may have written the ID, so the ID is read again when a later row names it.
     *
     * @param <T> the type of the new resource
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system, or null for none
     * @param create writes the resource
     * @param idOf gives the ID of the new resource
     * @return the new resource
     */
    <T> T create(ExternalId.Kind kind, String source, String externalId, Supplier<T> create,
            Function<T, String> idOf) {
        Name name = new Name(kind, source, externalId);
        T created;
        try {
            created = create.get();
        } catch (RuntimeException e) {
            known.remove(name);
            throw e;
        }
        if (source != null && externalId != null) {
            known.put(name, Optional.of(idOf.apply(created)));
        }
        return created;
    }
}
