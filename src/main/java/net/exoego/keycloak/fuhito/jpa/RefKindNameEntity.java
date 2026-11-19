package net.exoego.keycloak.fuhito.jpa;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The name of a kind of reference data in one language.
 */
@Entity
@Table(name = "FUHITO_REF_KIND_NAME")
@IdClass(RefKindNameEntity.Key.class)
public class RefKindNameEntity {

    @Id
    @Column(name = "KIND_ID", length = 36)
    String kindId;

    @Id
    @Column(name = "LANGUAGE", length = 64)
    String language;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "IS_PRIMARY", nullable = false)
    boolean primary;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    /**
     * Creates an empty entity for JPA.
     */
    public RefKindNameEntity() {
    }

    /**
     * The key of a row.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        String kindId;
        String language;

        /**
         * Creates an empty key for JPA.
         */
        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(kindId, key.kindId)
                    && Objects.equals(language, key.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kindId, language);
        }
    }
}
