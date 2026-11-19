package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A person that the realm knows.
 */
@Entity
@Table(name = "FUHITO_PERSON")
public class PersonEntity implements VersionedRow {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "USER_ID", length = 255)
    String userId;

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
    public PersonEntity() {
    }

    @Override
    public boolean counted() {
        return counted;
    }

    @PostLoad
    void loaded() {
        counted |= JpaLongWrite.counted(PersonEntity.class, id);
    }

    // A write may change several rows of the resource. It counts up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
