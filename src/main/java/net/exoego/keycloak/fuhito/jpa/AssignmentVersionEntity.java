package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The version of an assignment, in one row for each assignment. The rows of its periods have no version, so a
 * count-up writes no row that the exclusion constraints of the periods check.
 */
@Entity
@Table(name = "FUHITO_ASSIGNMENT_VERSION")
public class AssignmentVersionEntity implements VersionedRow {

    // The ID of the assignment.
    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public AssignmentVersionEntity() {
    }

    // The write that adds an assignment counts as its write of the transaction.
    AssignmentVersionEntity(String id, String realmId, long version) {
        this.id = id;
        this.realmId = realmId;
        this.version = version;
        this.counted = true;
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(AssignmentVersionEntity.class, id);
    }

    // A write may change several rows of the assignment. It counts up the version once.
    long countUp() {
        if (!counted) {
            version = Versions.next(version);
            counted = true;
        }
        return version;
    }
}
