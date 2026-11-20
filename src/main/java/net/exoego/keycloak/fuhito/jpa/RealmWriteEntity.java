package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The row of one realm that keeps a bulk write apart from the other writes of the realm. It holds no data.
 */
@Entity
@Table(name = "FUHITO_REALM_WRITE")
public class RealmWriteEntity {

    @Id
    @Column(name = "REALM_ID", length = 36)
    String realmId;

    /**
     * Creates an empty entity for JPA.
     */
    public RealmWriteEntity() {
    }
}
