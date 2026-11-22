package net.exoego.keycloak.fuhito.jpa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The placement of an employment in an org unit, as it is for a period.
 */
@Entity
@Table(name = "FUHITO_ASSIGNMENT")
public class AssignmentEntity implements VersionedRow {

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

    @Column(name = "WORK_LOCATION_ID", length = 36)
    String workLocationId;

    @Column(name = "COST_CENTER_ID", length = 36)
    String costCenterId;

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

    @Column(name = "SOURCE", length = 64)
    String source;

    // The version of the assignment. Every row of it has the same one.
    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public AssignmentEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(AssignmentEntity.class, id);
    }
}
