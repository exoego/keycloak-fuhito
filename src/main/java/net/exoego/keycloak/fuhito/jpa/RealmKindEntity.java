package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A kind of reference data that a realm added.
 */
@Entity
@Table(name = "FUHITO_REF_KIND")
public class RealmKindEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "CODE", nullable = false, length = 32)
    String code;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> names;

    /**
     * Creates an empty entity for JPA.
     */
    public RealmKindEntity() {
    }
}
