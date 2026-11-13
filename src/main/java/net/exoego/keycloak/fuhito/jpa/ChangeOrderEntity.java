package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An approved instruction that causes changes.
 */
@Entity
@Table(name = "FUHITO_CHANGE_ORDER")
public class ChangeOrderEntity {

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

    /**
     * Creates an empty entity for JPA.
     */
    public ChangeOrderEntity() {
    }
}
