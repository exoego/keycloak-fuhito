package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

/**
 * Operations on the IDs that systems have for the resources of one realm.
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
     * @param own true when the system made the resource
     * @param rowId the ID of the stored row
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
     * @throws FuhitoException when a value is bad, the resource does not exist, or the ID names another resource
     */
    public View put(String kind, String resourceId, String source, String externalId) {
        ExternalId.Kind of = kind(kind);
        ExternalId.Origin origin = originOf(of, Checks.required("resourceId", resourceId));
        ExternalIdWrites writes = new ExternalIdWrites(store, of);
        ExternalId.Pair pair = writes.check(resourceId, Checks.code("source", source),
                Checks.required("externalId", externalId));
        return view(writes.store(resourceId, pair), origin);
    }

    /**
     * Checks an ID of a system, as the column can hold it.
     *
     * @param externalId the ID, or null for none
     * @return the same ID
     * @throws FuhitoException when the ID is blank or too long
     */
    static String value(String externalId) {
        if (externalId != null && (externalId.isBlank() || externalId.length() > MAX_EXTERNAL_ID_LENGTH)) {
            throw FuhitoException.invalid("external_id.bad_value", Map.of("externalId", externalId));
        }
        return externalId;
    }

    /**
     * Removes the ID of one system from a resource. The resource keeps the name of the system that made it.
     *
     * @param kind the kind of resource as text
     * @param resourceId the ID of the resource
     * @param source the name of the system
     * @return the removed ID
     * @throws FuhitoException when the resource has no ID of that system
     */
    public View remove(String kind, String resourceId, String source) {
        ExternalId.Kind of = kind(kind);
        ExternalId row = store.ofResource(of, Checks.required("resourceId", resourceId)).stream()
                .filter(each -> each.source().equals(source))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("external_id.not_found",
                        Map.of("source", String.valueOf(source), "resourceId", resourceId)));
        store.delete(row.id());
        return new View(of, resourceId, row.source(), row.externalId(), false, row.id());
    }

    /**
     * Lists every ID of a resource. The ID of the system that made it comes first, then the others by system.
     *
     * @param kind the kind of resource as text
     * @param resourceId the ID of the resource
     * @return the IDs
     * @throws FuhitoException when the resource does not exist
     */
    public List<View> list(String kind, String resourceId) {
        ExternalId.Kind of = kind(kind);
        ExternalId.Origin origin = originOf(of, Checks.required("resourceId", resourceId));
        return ExternalId.ordered(store.ofResource(of, resourceId), origin.source()).stream()
                .map(row -> view(row, origin))
                .toList();
    }

    /**
     * Finds the resource that an ID of a system names.
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
        ExternalId row = store.find(of, system, value).orElseThrow(() -> FuhitoException.notFound(
                "external_id.unknown", Map.of("source", system, "externalId", value)));
        return view(row, store.originOf(of, row.resourceId()).orElseThrow());
    }

    /**
     * Finds the resource that an ID of a system names.
     *
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the ID of the resource, or empty when none has the ID
     */
    public Optional<String> resourceOf(ExternalId.Kind kind, String source, String externalId) {
        return store.find(kind, source, externalId).map(ExternalId::resourceId);
    }

    /**
     * Returns the ID that one system has for a resource.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @param source the name of the system
     * @return the ID, or empty when the system has none for it
     */
    public Optional<String> externalIdOf(ExternalId.Kind kind, String resourceId, String source) {
        return store.ofResource(kind, resourceId).stream()
                .filter(row -> row.source().equals(source))
                .map(ExternalId::externalId)
                .findFirst();
    }

    /**
     * Reads the IDs of many resources of one kind at once.
     *
     * @param kind the kind of resource
     * @param resourceIds the IDs of the resources
     * @return the IDs of each resource
     */
    public ExternalIdLists lists(ExternalId.Kind kind, Collection<String> resourceIds) {
        return new ExternalIdLists(store.ofResources(kind, resourceIds));
    }

    /**
     * Lists a page of the IDs of every resource, ordered by the ID of the row.
     *
     * @param afterId the ID of the row after which the page starts, or null for the start
     * @param max the largest number of rows
     * @return the IDs
     */
    public List<View> list(String afterId, int max) {
        return store.list(afterId, max).stream()
                .map(row -> new View(row.kind(), row.resourceId(), row.source(), row.externalId(), false, row.id()))
                .toList();
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

    private ExternalId.Origin originOf(ExternalId.Kind kind, String resourceId) {
        return store.originOf(kind, resourceId).orElseThrow(() -> FuhitoException.notFound(
                "external_id.resource_unknown", Map.of("resourceType", kind.name(), "resourceId", resourceId)));
    }

    private static ExternalId.Kind kind(String kind) {
        return Checks.optionalEnum("resourceType", ExternalId.Kind.class, Checks.required("resourceType", kind),
                null);
    }

    private static View view(ExternalId row, ExternalId.Origin origin) {
        return new View(row.kind(), row.resourceId(), row.source(), row.externalId(),
                Objects.equals(row.source(), origin.source()), row.id());
    }
}
