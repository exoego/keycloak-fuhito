package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The names of a reference item for a period.
 */
@Entity
@Table(name = "FUHITO_REF_ITEM_NAME")
public class RefItemNameEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "REF_ITEM_ID", nullable = false, length = 36)
    String refItemId;

    @Column(name = "VALID_FROM")
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> names;

    /**
     * Creates an empty entity for JPA.
     */
    public RefItemNameEntity() {
    }
}
