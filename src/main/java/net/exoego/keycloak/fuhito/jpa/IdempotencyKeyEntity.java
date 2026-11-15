package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The response to the first request with an idempotency key.
 */
@Entity
@Table(name = "FUHITO_IDEMPOTENCY_KEY")
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "KEY_VALUE", nullable = false, length = 255)
    String key;

    @Column(name = "REQUEST_PATH", nullable = false, length = 512)
    String path;

    @Column(name = "REQUEST_HASH", nullable = false, length = 64)
    String requestHash;

    @Column(name = "STATUS", nullable = false)
    int status;

    @Column(name = "BODY")
    String body;

    @Column(name = "LOCATION", length = 1024)
    String location;

    @Column(name = "CREATED_AT", nullable = false)
    Instant createdAt;

    /**
     * Creates an empty entity for JPA.
     */
    public IdempotencyKeyEntity() {
    }
}
