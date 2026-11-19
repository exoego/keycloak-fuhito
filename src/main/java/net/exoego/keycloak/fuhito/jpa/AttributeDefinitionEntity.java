package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The definition of one free attribute of one kind of resource.
 */
@Entity
@Table(name = "FUHITO_ATTRIBUTE_DEFINITION")
public class AttributeDefinitionEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "TARGET", nullable = false, length = 32)
    String target;

    @Column(name = "NAME", nullable = false, length = 64)
    String name;

    @Column(name = "VALUE_TYPE", nullable = false, length = 16)
    String type;

    @Column(name = "REF_KIND", length = 32)
    String refKind;

    @Column(name = "MULTIPLE", nullable = false)
    boolean multiple;

    @Column(name = "REQUIRED", nullable = false)
    boolean required;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public AttributeDefinitionEntity() {
    }

    // A write and a change of its IDs of other systems count up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
