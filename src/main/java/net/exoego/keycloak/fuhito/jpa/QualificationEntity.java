package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A license or certificate that a user holds.
 */
@Entity
@Table(name = "FUHITO_QUALIFICATION")
public class QualificationEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "PERSON_ID", nullable = false, length = 36)
    String personId;

    // Only for reads. The column is written through personId.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PERSON_ID", insertable = false, updatable = false)
    PersonEntity person;

    @Column(name = "KIND_ID", nullable = false, length = 36)
    String kindId;

    @Column(name = "QUALIFICATION_NUMBER", length = 255)
    String number;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

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
    public QualificationEntity() {
    }
}
