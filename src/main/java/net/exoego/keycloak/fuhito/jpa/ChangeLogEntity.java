package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One successful write.
 */
@Entity
@Table(name = "FUHITO_CHANGE_LOG")
public class ChangeLogEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    // The database gives the number, so that entries written at the same moment still have an order.
    @Column(name = "SEQUENCE_NUMBER", insertable = false, updatable = false)
    long sequence;

    @Column(name = "RECORDED_AT", nullable = false)
    Instant recordedAt;

    @Column(name = "ACTOR_USER_ID", length = 255)
    String actorUserId;

    @Column(name = "RESOURCE_TYPE", nullable = false, length = 64)
    String resourceType;

    @Column(name = "RESOURCE_ID", nullable = false, length = 36)
    String resourceId;

    @Column(name = "OPERATION", nullable = false, length = 16)
    String operation;

    @Column(name = "RESOURCE_PATH", nullable = false, length = 255)
    String resourcePath;

    @Column(name = "CHANGE_ORDER_ID", length = 36)
    String changeOrderId;

    @Column(name = "REPRESENTATION")
    String representation;

    /**
     * Creates an empty entity for JPA.
     */
    public ChangeLogEntity() {
    }
}
