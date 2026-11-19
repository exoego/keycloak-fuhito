package net.exoego.keycloak.fuhito.jpa;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The name of a reference item for a period in one language. The rows of one period have the same ID and dates.
 */
@Entity
@Table(name = "FUHITO_REF_ITEM_NAME")
@IdClass(RefItemNameEntity.Key.class)
public class RefItemNameEntity {

    @Id
    @Column(name = "PERIOD_ID", length = 36)
    String periodId;

    @Id
    @Column(name = "LANGUAGE", length = 64)
    String language;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "REF_ITEM_ID", nullable = false, length = 36)
    String refItemId;

    @Column(name = "VALID_FROM")
    LocalDate validFrom;

    @Column(name = "VALID_TO")
    LocalDate validTo;

    @Column(name = "IS_PRIMARY", nullable = false)
    boolean primary;

    @Column(name = "NAME", nullable = false, length = 255)
    String name;

    /**
     * Creates an empty entity for JPA.
     */
    public RefItemNameEntity() {
    }

    /**
     * The key of a row.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        String periodId;
        String language;

        /**
         * Creates an empty key for JPA.
         */
        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(periodId, key.periodId)
                    && Objects.equals(language, key.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(periodId, language);
        }
    }
}
