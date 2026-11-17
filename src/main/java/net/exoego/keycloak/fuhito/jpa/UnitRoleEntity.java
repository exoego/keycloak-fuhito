package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A role of an employment over an org unit, for a period.
 */
@Entity
@Table(name = "FUHITO_UNIT_ROLE")
public class UnitRoleEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "EMPLOYMENT_ID", nullable = false, length = 36)
    String employmentId;

    @Column(name = "ORG_UNIT_ID", nullable = false, length = 36)
    String orgUnitId;

    @Column(name = "ROLE_ID", nullable = false, length = 36)
    String roleId;

    @Column(name = "INCLUDES_DESCENDANTS", nullable = false)
    boolean includesDescendants;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Version
    @Column(name = "VERSION", nullable = false)
    long version;

    /**
     * Creates an empty entity for JPA.
     */
    public UnitRoleEntity() {
    }
}
