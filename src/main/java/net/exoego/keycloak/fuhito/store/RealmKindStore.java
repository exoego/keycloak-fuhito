package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.RealmKind;

/**
 * Storage of the kinds of reference data that one realm added.
 */
public interface RealmKindStore {

    /**
     * Lists the kinds, by code.
     *
     * @return the kinds
     */
    List<RealmKind> list();

    /**
     * Finds a kind by code.
     *
     * @param code the code
     * @return the kind, or empty when the realm has none with the code
     */
    Optional<RealmKind> findByCode(String code);

    /**
     * Stores a new kind.
     *
     * @param kind the kind
     */
    void insert(RealmKind kind);

    /**
     * Deletes a kind.
     *
     * @param id the ID
     */
    void delete(String id);
}
