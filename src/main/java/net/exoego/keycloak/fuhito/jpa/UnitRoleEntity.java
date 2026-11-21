package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A role of an employment over an org unit, for a period.
 */
@Entity
@Table(name = "FUHITO_UNIT_ROLE")
public class UnitRoleEntity implements VersionedRow {

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

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public UnitRoleEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(UnitRoleEntity.class, id);
    }

    // A write and a change of its IDs of other systems count up the version once.
    void countUp() {
        if (!counted) {
            version = Versions.next(version);
            counted = true;
        }
    }
}
