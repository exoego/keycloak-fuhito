package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The identity of an org unit.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT")
public class OrgUnitEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "CODE", length = 64)
    String code;

    @Column(name = "EXTERNAL_ID", length = 255)
    String externalId;

    @Column(name = "SOURCE", length = 64)
    String source;

    @Column(name = "KC_ORG_ID", length = 36)
    String keycloakOrganizationId;

    @Column(name = "TIME_ZONE", length = 64)
    String timeZone;

    @Column(name = "DAY_START", length = 5)
    String dayStart;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitEntity() {
    }
}
