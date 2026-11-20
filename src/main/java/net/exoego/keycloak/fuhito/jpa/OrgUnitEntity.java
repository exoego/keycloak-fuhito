package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The identity of an org unit.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT")
public class OrgUnitEntity implements VersionedRow {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "SOURCE", length = 64)
    String source;

    @Column(name = "KC_ORG_ID", length = 255)
    String keycloakOrganizationId;

    @Column(name = "TIME_ZONE", length = 64)
    String timeZone;

    @Column(name = "DAY_START", length = 5)
    String dayStart;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(OrgUnitEntity.class, id);
    }

    // A write may change several rows of the resource. It counts up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
