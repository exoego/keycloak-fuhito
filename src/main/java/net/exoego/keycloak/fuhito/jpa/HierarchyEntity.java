package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A named tree of org units in a realm.
 */
@Entity
@Table(name = "FUHITO_HIERARCHY")
public class HierarchyEntity {

    @Id
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    private String realmId;

    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    /**
     * Creates an empty entity for JPA.
     */
    public HierarchyEntity() {
    }

    /**
     * Returns the ID.
     *
     * @return the ID
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the ID.
     *
     * @param id the ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Sets the ID of the realm that owns this hierarchy.
     *
     * @param realmId the realm ID
     */
    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    /**
     * Returns the code, which is unique in the realm.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Sets the code, which is unique in the realm.
     *
     * @param code the code
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * Returns the name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }
}
