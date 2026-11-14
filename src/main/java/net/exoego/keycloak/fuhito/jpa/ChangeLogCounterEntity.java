package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The last sequence number of the change log of one realm.
 */
@Entity
@Table(name = "FUHITO_CHANGE_LOG_COUNTER")
public class ChangeLogCounterEntity {

    @Id
    @Column(name = "REALM_ID", length = 36)
    String realmId;

    @Column(name = "LAST_SEQUENCE", nullable = false)
    long lastSequence;

    /**
     * Creates an empty entity for JPA.
     */
    public ChangeLogCounterEntity() {
    }
}
