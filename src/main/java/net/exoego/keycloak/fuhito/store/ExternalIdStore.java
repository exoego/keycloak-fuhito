package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * Storage of the IDs that other systems have for the resources of one realm.
 */
public interface ExternalIdStore {

    /**
     * Returns the pair of source and external ID that a resource itself has.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @return the pair, which can be empty inside, or empty when the realm has no such resource
     */
    Optional<ExternalId.Pair> pairOf(ExternalId.Kind kind, String resourceId);

    /**
     * Lists the resources of a kind whose own pair is the given one.
     *
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the IDs of the resources
     */
    List<String> withPair(ExternalId.Kind kind, String source, String externalId);

    /**
     * Lists the IDs of other systems of one resource, by system.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @return the IDs
     */
    List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId);

    /**
     * Finds the ID of one system that names a resource of a kind.
     *
     * @param kind the kind of resource
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the row, or empty when none names one
     */
    Optional<ExternalId> find(ExternalId.Kind kind, String source, String externalId);

    /**
     * Stores a new ID.
     *
     * @param externalId the row
     */
    void insert(ExternalId externalId);

    /**
     * Stores the changed ID of a row.
     *
     * @param externalId the row
     */
    void update(ExternalId externalId);

    /**
     * Deletes a row.
     *
     * @param id the ID of the row
     */
    void delete(String id);
}
