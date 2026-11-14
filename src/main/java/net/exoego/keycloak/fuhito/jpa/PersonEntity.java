package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A person that the realm knows.
 */
@Entity
@Table(name = "FUHITO_PERSON")
public class PersonEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "USER_ID", length = 255)
    String userId;

    @Column(name = "NAME", length = 255)
    String name;

    @Column(name = "NAMES_I18N", nullable = false)
    @Convert(converter = JsonConverters.TextMap.class)
    Map<String, String> names;

    @Column(name = "PHONETIC_NAME", length = 255)
    String phoneticName;

    @Column(name = "ATTRIBUTES", nullable = false)
    @Convert(converter = JsonConverters.ValueMap.class)
    Map<String, Object> attributes;

    @Column(name = "EXTERNAL_ID", length = 255)
    String externalId;

    @Column(name = "SOURCE", length = 64)
    String source;

    /**
     * Creates an empty entity for JPA.
     */
    public PersonEntity() {
    }
}
