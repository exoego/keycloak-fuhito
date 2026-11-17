package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The ID that one system has for a resource.
 */
@Entity
@Table(name = "FUHITO_EXTERNAL_ID")
public class ExternalIdEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "RESOURCE_KIND", nullable = false, length = 32)
    String kind;

    @Column(name = "RESOURCE_ID", nullable = false, length = 36)
    String resourceId;

    @Column(name = "SOURCE", nullable = false, length = 64)
    String source;

    @Column(name = "EXTERNAL_ID", nullable = false, length = 255)
    String externalId;

    /**
     * Creates an empty entity for JPA.
     */
    public ExternalIdEntity() {
    }
}
