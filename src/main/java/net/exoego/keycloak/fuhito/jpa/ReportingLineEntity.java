package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A link from an assignment to a manager, for a period.
 */
@Entity
@Table(name = "FUHITO_REPORTING_LINE")
public class ReportingLineEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "ASSIGNMENT_ID", nullable = false, length = 36)
    String assignmentId;

    @Column(name = "MANAGER_USER_ID", nullable = false, length = 255)
    String managerUserId;

    @Column(name = "KIND", nullable = false, length = 16)
    String kind;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    /**
     * Creates an empty entity for JPA.
     */
    public ReportingLineEntity() {
    }
}
