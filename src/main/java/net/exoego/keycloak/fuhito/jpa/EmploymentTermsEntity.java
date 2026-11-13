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
 * The conditions of an employment for a period.
 */
@Entity
@Table(name = "FUHITO_EMPLOYMENT_TERMS")
public class EmploymentTermsEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "EMPLOYMENT_ID", nullable = false, length = 36)
    String employmentId;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "EMPLOYMENT_TYPE_ID", length = 36)
    String employmentTypeId;

    @Column(name = "GRADE_ID", length = 36)
    String gradeId;

    @Column(name = "STATUS", nullable = false, length = 16)
    String status;

    @Column(name = "WORK_PERCENT", precision = 5, scale = 2)
    BigDecimal workPercent;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    /**
     * Creates an empty entity for JPA.
     */
    public EmploymentTermsEntity() {
    }
}
