package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

/**
 * Operations on the IDs that other systems have for the resources of one realm.
 */
public class ExternalIdService {

    private static final int MAX_EXTERNAL_ID_LENGTH = 255;

    /**
     * One ID of one resource in one system.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @param own true for the pair that the resource itself has
     * @param rowId the ID of the stored row, or null for the own pair
     */
    public record View(ExternalId.Kind kind, String resourceId, String source, String externalId, boolean own,
            String rowId) {
    }

    private final ExternalIdStore store;

    /**
     * Creates the service.
     *
     * @param store the storage of the realm
     */
    public ExternalIdService(ExternalIdStore store) {
        this.store = store;
    }

    /**
     * Stores the ID of one system for a resource, or replaces the one it has.
     *
     * @param kind the kind of resource as text
     * @param resourceId the ID of the resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the stored ID
     * @throws FuhitoException when a value is bad, the resource does not exist, the system is the one of the own
     *         pair, or the ID names another resource
     */
    public View put(String kind, String resourceId, String source, String externalId) {
        ExternalId.Kind of = kind(kind);
        ExternalId.Pair own = pairOf(of, Checks.required("resourceId", resourceId));
        String system = Checks.code("source", source);
        String value = Checks.required("externalId", externalId);
        if (value.isBlank() || value.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw FuhitoException.invalid("external_id.bad_value", Map.of("externalId", value));
        }
        if (system.equals(own.source())) {
            throw FuhitoException.conflict("external_id.own_source", Map.of("source", system));
        }
        if (store.withPair(of, system, value).stream().anyMatch(other -> !other.equals(resourceId))) {
            throw FuhitoException.conflict("external_id.taken", Map.of("source", system, "externalId", value));
        }
        Optional<ExternalId> taken = store.find(of, system, value);
        if (taken.isPresent() && !taken.get().resourceId().equals(resourceId)) {
            // A row of a resource that is gone names nothing.
            if (store.pairOf(of, taken.get().resourceId()).isPresent()) {
                throw FuhitoException.conflict("external_id.taken", Map.of("source", system, "externalId", value));
            }
            store.delete(taken.get().id());
        }
        Optional<ExternalId> current = store.ofResource(of, resourceId).stream()
                .filter(row -> row.source().equals(system))
                .findFirst();
        ExternalId row = new ExternalId(current.map(ExternalId::id).orElse(UUID.randomUUID().toString()), of,
                resourceId, system, value);
        if (current.isPresent()) {
            store.update(row);
        } else {
            store.insert(row);
        }
        return view(row);
    }

    /**
     * Removes the ID of one system from a resource. The own pair stays.
     *
     * @param kind the kind of resource as text
     * @param resourceId the ID of the resource
     * @param source the name of the system
     * @return the removed ID
     * @throws FuhitoException when the resource has no ID of that system in the table
     */
    public View remove(String kind, String resourceId, String source) {
        ExternalId.Kind of = kind(kind);
        ExternalId row = store.ofResource(of, Checks.required("resourceId", resourceId)).stream()
                .filter(each -> each.source().equals(source))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("external_id.not_found",
                        Map.of("source", String.valueOf(source), "resourceId", resourceId)));
        store.delete(row.id());
        return view(row);
    }

    /**
     * Lists every ID of a resource, the own pair first and then the others by system.
     *
     * @param kind the kind of resource as text
     * @param resourceId the ID of the resource
     * @return the IDs
     * @throws FuhitoException when the resource does not exist
     */
    public List<View> list(String kind, String resourceId) {
        ExternalId.Kind of = kind(kind);
        ExternalId.Pair own = pairOf(of, Checks.required("resourceId", resourceId));
        List<View> result = new ArrayList<>();
        if (own.source() != null && own.externalId() != null) {
            result.add(new View(of, resourceId, own.source(), own.externalId(), true, null));
        }
        store.ofResource(of, resourceId).forEach(row -> result.add(view(row)));
        return result;
    }

    /**
     * Finds the resource that an ID of a system names. The own pairs come before the table.
     *
     * @param kind the kind of resource as text
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the ID with its resource
     * @throws FuhitoException when no resource has the ID
     */
    public View find(String kind, String source, String externalId) {
        ExternalId.Kind of = kind(kind);
        String system = Checks.required("source", source);
        String value = Checks.required("externalId", externalId);
        Optional<View> byPair = store.withPair(of, system, value).stream()
                .findFirst()
                .map(resourceId -> new View(of, resourceId, system, value, true, null));
        return byPair.or(() -> store.find(of, system, value)
                        .filter(row -> store.pairOf(of, row.resourceId()).isPresent())
                        .map(ExternalIdService::view))
                .orElseThrow(() -> FuhitoException.notFound("external_id.unknown",
                        Map.of("source", system, "externalId", value)));
    }

    /**
     * Tells whether the IDs of a kind name only org units, which a reader of the org structure may read.
     *
     * @param kind the kind of resource as text
     * @return true for org units
     * @throws FuhitoException when the kind is unknown
     */
    public static boolean isStructure(String kind) {
        return kind(kind) == ExternalId.Kind.ORG_UNIT;
    }

    private ExternalId.Pair pairOf(ExternalId.Kind kind, String resourceId) {
        return store.pairOf(kind, resourceId).orElseThrow(() -> FuhitoException.notFound(
                "external_id.resource_unknown", Map.of("resourceType", kind.name(), "resourceId", resourceId)));
    }

    private static ExternalId.Kind kind(String kind) {
        return Checks.optionalEnum("resourceType", ExternalId.Kind.class, Checks.required("resourceType", kind),
                null);
    }

    private static View view(ExternalId row) {
        return new View(row.kind(), row.resourceId(), row.source(), row.externalId(), false, row.id());
    }
}
