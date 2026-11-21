package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A license or certificate that a user holds.
 */
@Entity
@Table(name = "FUHITO_QUALIFICATION")
public class QualificationEntity implements VersionedRow {

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

    @Column(name = "SOURCE", length = 64)
    String source;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public QualificationEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(QualificationEntity.class, id);
    }

    // A write may change several rows of the resource. It counts up the version once.
    void countUp() {
        if (!counted) {
            version = Versions.next(version);
            counted = true;
        }
    }
}
