package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user that the projection added to a Keycloak organization. Keycloak does not record who added a member.
 */
@Entity
@Table(name = "FUHITO_PROJECTED_MEMBER")
public class ProjectedMemberEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "KC_ORG_ID", nullable = false, length = 36)
    String organizationId;

    @Column(name = "USER_ID", nullable = false, length = 255)
    String userId;

    /**
     * Creates an empty entity for JPA.
     */
    public ProjectedMemberEntity() {
    }
}
