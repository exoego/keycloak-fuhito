package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The relationship between a user and the legal entity of the realm, for a period.
 */
@Entity
@Table(name = "FUHITO_EMPLOYMENT")
public class EmploymentEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "USER_ID", nullable = false, length = 255)
    String userId;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "STAFF_NUMBER", length = 64)
    String staffNumber;

    @Column(name = "END_REASON_ID", length = 36)
    String endReasonId;

    @Column(name = "EXTERNAL_ID", length = 255)
    String externalId;

    @Column(name = "SOURCE", length = 64)
    String source;

    /**
     * Creates an empty entity for JPA.
     */
    public EmploymentEntity() {
    }
}
