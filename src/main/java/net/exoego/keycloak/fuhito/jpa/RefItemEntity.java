package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One entry of a list that a realm defines.
 */
@Entity
@Table(name = "FUHITO_REF_ITEM")
public class RefItemEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "KIND", nullable = false, length = 32)
    String kind;

    @Column(name = "CODE", nullable = false, length = 64)
    String code;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> names;

    @Column(name = "DISPLAY_ORDER", nullable = false)
    int displayOrder;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    @Column(name = "ACTIVE", nullable = false)
    boolean active;

    /**
     * Creates an empty entity for JPA.
     */
    public RefItemEntity() {
    }
}
