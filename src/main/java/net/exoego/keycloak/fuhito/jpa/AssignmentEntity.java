package net.exoego.keycloak.fuhito.jpa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The placement of an employment in an org unit, as it is for a period.
 */
@Entity
@Table(name = "FUHITO_ASSIGNMENT")
public class AssignmentEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "ASSIGNMENT_ID", nullable = false, length = 36)
    String assignmentId;

    @Column(name = "EMPLOYMENT_ID", nullable = false, length = 36)
    String employmentId;

    @Column(name = "ORG_UNIT_ID", nullable = false, length = 36)
    String orgUnitId;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "JOB_TITLE_ID", length = 36)
    String jobTitleId;

    @Column(name = "OCCUPATION_ID", length = 36)
    String occupationId;

    @Column(name = "KIND_ID", length = 36)
    String kindId;

    @Column(name = "IS_HEAD", nullable = false)
    boolean head;

    @Column(name = "IS_PRIMARY", nullable = false)
    boolean primary;

    @Column(name = "ALLOCATION_PERCENT", precision = 5, scale = 2)
    BigDecimal allocationPercent;

    @Column(name = "DISPLAY_ORDER", nullable = false)
    int displayOrder;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    @Column(name = "EXTERNAL_ID", length = 255)
    String externalId;

    @Column(name = "SOURCE", length = 64)
    String source;

    /**
     * Creates an empty entity for JPA.
     */
    public AssignmentEntity() {
    }
}
