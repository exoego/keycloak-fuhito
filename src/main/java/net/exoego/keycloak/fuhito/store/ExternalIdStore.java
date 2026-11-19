package net.exoego.keycloak.fuhito.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * Storage of the IDs that systems have for the resources of one realm.
 */
public interface ExternalIdStore {

    /**
     * Returns the system that made a resource. A kind of resource that keeps no system gives one with no name.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @return the system, or empty when the realm has no such resource
     */
    Optional<ExternalId.Origin> originOf(ExternalId.Kind kind, String resourceId);

    /**
     * Lists the IDs of one resource, by system.
     *
     * @param kind the kind of resource
     * @param resourceId the ID of the resource
     * @return the IDs
     */
    List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId);

    /**
     * Lists the IDs of many resources of one kind, with one read.
     *
     * @param kind the kind of resource
     * @param resourceIds the IDs of the resources
     * @return the IDs, in no order
     */
    List<ExternalId> ofResources(ExternalId.Kind kind, Collection<String> resourceIds);

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
     * Lists a page of the IDs of every resource, ordered by the ID of the row.
     *
     * @param afterId the ID of the row after which the page starts, or null for the start
     * @param max the largest number of rows
     * @return the rows
     */
    List<ExternalId> list(String afterId, int max);

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
     * Gives the IDs of resources to other resources of the same kind, with one read and one write.
     * The version of each moved row counts up. The versions of the resources do not, so it is for resources that a
     * write removes and for their copies that the same write makes.
     *
     * @param newResourceIds the ID of the new resource of each old one, by kind
     */
    void moveAll(Map<ExternalId.Kind, Map<String, String>> newResourceIds);

    /**
     * Deletes a row.
     *
     * @param id the ID of the row
     */
    void delete(String id);
}
