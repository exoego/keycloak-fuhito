package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A kind of reference data that a realm added.
 */
@Entity
@Table(name = "FUHITO_REF_KIND")
public class RealmKindEntity implements VersionedRow {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "CODE", nullable = false, length = 32)
    String code;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public RealmKindEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(RealmKindEntity.class, id);
    }

    // A write may change only the names, which are rows of their own. It counts up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
