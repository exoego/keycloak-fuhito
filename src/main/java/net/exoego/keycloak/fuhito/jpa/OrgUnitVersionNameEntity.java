package net.exoego.keycloak.fuhito.jpa;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The names of an org unit for a period in one language.
 */
@Entity
@Table(name = "FUHITO_ORG_UNIT_VERSION_NAME")
@IdClass(OrgUnitVersionNameEntity.Key.class)
public class OrgUnitVersionNameEntity {

    @Id
    @Column(name = "VERSION_ID", length = 36)
    String versionId;

    @Id
    @Column(name = "LANGUAGE", length = 64)
    String language;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "IS_PRIMARY", nullable = false)
    boolean primary;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "SHORT_NAME", length = 255)
    String shortName;

    @Column(name = "PHONETIC_NAME", length = 255)
    String phoneticName;

    /**
     * Creates an empty entity for JPA.
     */
    public OrgUnitVersionNameEntity() {
    }

    /**
     * The key of a row.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        String versionId;
        String language;

        /**
         * Creates an empty key for JPA.
         */
        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(versionId, key.versionId)
                    && Objects.equals(language, key.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionId, language);
        }
    }
}
