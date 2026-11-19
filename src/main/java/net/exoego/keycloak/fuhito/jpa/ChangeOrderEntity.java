package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * An approved instruction that causes changes.
 */
@Entity
@Table(name = "FUHITO_CHANGE_ORDER")
public class ChangeOrderEntity implements VersionedRow {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "ORDER_NUMBER", length = 64)
    String number;

    @Column(name = "KIND", length = 64)
    String kind;

    @Column(name = "TITLE", nullable = false, length = 255)
    String title;

    @Column(name = "NOTE")
    String note;

    @Column(name = "ORDER_DATE")
    LocalDate orderDate;

    @Column(name = "EFFECTIVE_DATE", nullable = false)
    LocalDate effectiveDate;

    @Column(name = "APPROVER_USER_ID", length = 255)
    String approverUserId;

    @Column(name = "REQUESTER_USER_ID", length = 255)
    String requesterUserId;

    @Column(name = "EXTERNAL_REFERENCE", length = 255)
    String externalReference;

    @Column(name = "CREATED_AT", nullable = false)
    Instant createdAt;

    @Column(name = "ANNOUNCED_AT")
    Instant announcedAt;

    @Column(name = "ANNOUNCED_BY_USER_ID", length = 255)
    String announcedByUserId;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public ChangeOrderEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(ChangeOrderEntity.class, id);
    }

    // A write and a change of its IDs of other systems count up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
