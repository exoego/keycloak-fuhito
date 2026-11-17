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

    private final ExternalIdStore store;
    private final ExternalId.Kind kind;

    ExternalIdWrites(ExternalIdStore store, ExternalId.Kind kind) {
        this.store = store;
        this.kind = kind;
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
        Optional<ExternalId> taken = store.find(kind, system, value);
        if (taken.isPresent() && !taken.get().resourceId().equals(resourceId)) {
            throw FuhitoException.conflict("external_id.taken", Map.of("source", system, "externalId", value));
        }
        return new ExternalId.Pair(system, value);
    }

    /**
     * Stores a checked ID for a resource, in place of the one that it has of the same system.
     *
     * @param resourceId the ID of the resource
     * @param pair the checked ID, or null for none
     * @return the stored row, or null when there is none to store
     */
    ExternalId store(String resourceId, ExternalId.Pair pair) {
        if (pair == null) {
            return null;
        }
        Optional<ExternalId> current = store.ofResource(kind, resourceId).stream()
                .filter(row -> row.source().equals(pair.source()))
                .findFirst();
        ExternalId row = new ExternalId(current.map(ExternalId::id).orElseGet(() -> UUID.randomUUID().toString()),
                kind, resourceId, pair.source(), pair.externalId());
        if (current.isEmpty()) {
            store.insert(row);
        } else if (!current.get().equals(row)) {
            store.update(row);
        }
        return row;
    }
}
