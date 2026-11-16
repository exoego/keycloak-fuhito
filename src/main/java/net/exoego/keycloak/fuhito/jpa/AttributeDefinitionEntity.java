package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    /**
     * Creates an empty entity for JPA.
     */
    public AttributeDefinitionEntity() {
    }
}
