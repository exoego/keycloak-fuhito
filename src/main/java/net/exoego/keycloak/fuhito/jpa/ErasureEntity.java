package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The fact that the data of a person was erased, and when. A restore erases the person again.
 */
@Entity
@Table(name = "FUHITO_ERASURE")
public class ErasureEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "PERSON_ID", nullable = false, length = 36)
    String personId;

    @Column(name = "ERASED_AT", nullable = false)
    Instant erasedAt;

    /**
     * Creates an empty entity for JPA.
     */
    public ErasureEntity() {
    }
}
