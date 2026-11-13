package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The parent of an org unit in one hierarchy for a period.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT_LINK")
public class OrgUnitLinkEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "HIERARCHY_ID", nullable = false, length = 36)
    String hierarchyId;

    @Column(name = "CHILD_ID", nullable = false, length = 36)
    String childId;

    @Column(name = "PARENT_ID", nullable = false, length = 36)
    String parentId;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitLinkEntity() {
    }
}
