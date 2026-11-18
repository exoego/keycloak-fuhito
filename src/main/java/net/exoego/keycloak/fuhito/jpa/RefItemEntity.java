package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One entry of a list that a realm defines. Its names are in {@link RefItemNameEntity}.
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

    @Column(name = "DISPLAY_ORDER", nullable = false)
    int displayOrder;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    @Column(name = "ACTIVE", nullable = false)
    boolean active;

    @Column(name = "LEGAL_ENTITY", nullable = false)
    boolean legalEntity;

    @Column(name = "ACTING_HEAD", nullable = false)
    boolean actingHead;

    @Column(name = "LATERAL_UNIT", nullable = false)
    boolean lateralUnit;

    @Column(name = "VERSION", nullable = false)
    long version;

    // True once a write of this session counted up the version. A session of Keycloak has one transaction.
    @Transient
    boolean counted;

    /**
     * Creates an empty entity for JPA.
     */
    public RefItemEntity() {
    }

    // A write may change the item and its names. It counts up the version once.
    void countUp() {
        if (!counted) {
            version++;
            counted = true;
        }
    }
}
