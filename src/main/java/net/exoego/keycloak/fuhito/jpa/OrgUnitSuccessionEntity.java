package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One org unit follows another that ended.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT_SUCCESSION")
public class OrgUnitSuccessionEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "PREDECESSOR_ID", nullable = false, length = 36)
    String predecessorId;

    @Column(name = "SUCCESSOR_ID", nullable = false, length = 36)
    String successorId;

    @Column(name = "EFFECTIVE_DATE", nullable = false)
    LocalDate effectiveDate;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitSuccessionEntity() {
    }
}
