package net.exoego.keycloak.fuhito.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

/**
 * Stores the ID that a write gives for a resource of one kind, in the system that the write names.
 */
final class ExternalIdWrites {

    /**
     * Finds the resource that an ID of a system names.
     */
    @FunctionalInterface
    interface Lookup {

        /**
         * Finds the resource.
         *
         * @param source the name of the system
         * @param externalId the ID in that system
         * @return the ID of the resource, or empty when none has the ID
         */
        Optional<String> resourceOf(String source, String externalId);
    }

    private final ExternalIdStore store;
    private final ExternalId.Kind kind;
    private final Lookup lookup;

    ExternalIdWrites(ExternalIdStore store, ExternalId.Kind kind) {
        this(store, kind, (source, externalId) -> store.find(kind, source, externalId).map(ExternalId::resourceId));
    }

    /**
     * Creates the writes with a lookup that knows the IDs already, such as the one of a list of an import.
     *
     * @param store the storage of the IDs of the realm
     * @param kind the kind of resource
     * @param lookup finds the resource that an ID names
     */
    ExternalIdWrites(ExternalIdStore store, ExternalId.Kind kind, Lookup lookup) {
        this.store = store;
        this.kind = kind;
        this.lookup = lookup;
    }

    /**
     * Checks the system and the ID that a write gives, before anything is written.
     *
     * @param resourceId the ID of the resource, or null for a new one
     * @param source the name of the system, or null
     * @param externalId the ID in that system, or null when the write gives none
     * @return the ID to store, or null when the write gives none
     * @throws FuhitoException when a value is bad, the ID has no system, or the ID names another resource
     */
    ExternalId.Pair check(String resourceId, String source, String externalId) {
        String system = Checks.optionalCode("source", source);
        String value = ExternalIdService.value(externalId);
        if (value == null) {
            return null;
        }
        Checks.required("source", system);
        Optional<String> taken = lookup.resourceOf(system, value);
        if (taken.isPresent() && !taken.get().equals(resourceId)) {
            throw FuhitoException.conflict("external_id.taken", Map.of("source", system, "externalId", value));
        }
        return new ExternalId.Pair(system, value);
    }

    /**
     * Makes the row of a checked ID for a new resource, which has no IDs yet.
     *
     * @param resourceId the ID of the new resource
     * @param pair the checked ID, or null for none
     * @return the row to store, or empty when there is none
     */
    Optional<ExternalId> forNew(String resourceId, ExternalId.Pair pair) {
        return Optional.ofNullable(pair).map(checked -> new ExternalId(UUID.randomUUID().toString(), kind,
                resourceId, checked.source(), checked.externalId(), 1L));
    }

    /**
     * A row that a write stored.
     *
     * @param row the stored row
     * @param created true when the resource had no ID of the system before
     */
    record Stored(ExternalId row, boolean created) {
    }

    /**
     * Stores a checked ID for a resource, in place of the one that it has of the same system.
     *
     * @param resourceId the ID of the resource
     * @param pair the checked ID, or null for none
     * @return the stored row, or null when there is none to store
     */
    Stored store(String resourceId, ExternalId.Pair pair) {
        if (pair == null) {
            return null;
        }
        Optional<ExternalId> current = store.ofResource(kind, resourceId).stream()
                .filter(row -> row.source().equals(pair.source()))
                .findFirst();
        ExternalId row = new ExternalId(current.map(ExternalId::id).orElseGet(() -> UUID.randomUUID().toString()),
                kind, resourceId, pair.source(), pair.externalId(), current.map(ExternalId::version).orElse(1L));
        if (current.isEmpty()) {
            store.insert(row);
            return new Stored(row, true);
        }
        if (!current.get().equals(row)) {
            store.update(row);
            return new Stored(store.ofResource(kind, resourceId).stream()
                    .filter(stored -> stored.id().equals(row.id()))
                    .findFirst()
                    .orElseThrow(), false);
        }
        return new Stored(row, false);
    }
}
