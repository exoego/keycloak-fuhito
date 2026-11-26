package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.EntityManager;
import org.keycloak.models.jpa.entities.GroupEntity;
import org.keycloak.models.jpa.entities.OrganizationEntity;

/**
 * Sets the organization of a Keycloak group on the owning side of the association only, as Keycloak does for the
 * hidden group of an organization. The organization provider of Keycloak also adds the group to the inverse side,
 * the set of all groups of the organization. That loads every group of the organization into the session, and each
 * later flush walks all of them.
 */
public final class OrganizationOfGroup {

    private OrganizationOfGroup() {
    }

    /**
     * Sets the organization of a group.
     *
     * @param em the entity manager of the transaction
     * @param groupId the ID of the group, which Keycloak made in this transaction
     * @param organizationId the ID of the organization
     */
    public static void setOwningSide(EntityManager em, String groupId, String organizationId) {
        em.find(GroupEntity.class, groupId).setOrganization(em.getReference(OrganizationEntity.class, organizationId));
    }
}
