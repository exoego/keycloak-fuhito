package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The fact that the data of a user was masked, and when.
 */
@Entity
@Table(name = "FUHITO_USER_MASK")
public class UserMaskEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "PERSON_ID", nullable = false, length = 36)
    String personId;

    @Column(name = "MASKED_AT", nullable = false)
    Instant maskedAt;

    /**
     * Creates an empty entity for JPA.
     */
    public UserMaskEntity() {
    }
}
