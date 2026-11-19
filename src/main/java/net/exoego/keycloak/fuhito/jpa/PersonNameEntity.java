package net.exoego.keycloak.fuhito.jpa;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The names of a person in one language.
 */
@Entity
@Table(name = "FUHITO_PERSON_NAME")
@IdClass(PersonNameEntity.Key.class)
public class PersonNameEntity {

    @Id
    @Column(name = "PERSON_ID", length = 36)
    String personId;

    @Id
    @Column(name = "LANGUAGE", length = 64)
    String language;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "IS_PRIMARY", nullable = false)
    boolean primary;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    @Column(name = "PHONETIC_NAME", length = 255)
    String phoneticName;

    /**
     * Creates an empty entity for JPA.
     */
    public PersonNameEntity() {
    }

    /**
     * The key of a row.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        String personId;
        String language;

        /**
         * Creates an empty key for JPA.
         */
        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(personId, key.personId)
                    && Objects.equals(language, key.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(personId, language);
        }
    }
}
