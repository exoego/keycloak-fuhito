package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The details of an org unit for a period.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT_VERSION")
public class OrgUnitVersionEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "ORG_UNIT_ID", nullable = false, length = 36)
    String orgUnitId;

    @Column(name = "VALID_FROM", nullable = false)
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> names;

    @Column(name = "SHORT_NAME", length = 255)
    String shortName;

    @Column(name = "SHORT_NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> shortNames;

    @Column(name = "PHONETIC_NAME", length = 255)
    String phoneticName;

    @Column(name = "UNIT_TYPE_ID", length = 36)
    String unitTypeId;

    @Column(name = "DISPLAY_ORDER", nullable = false)
    int displayOrder;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitVersionEntity() {
    }
}
