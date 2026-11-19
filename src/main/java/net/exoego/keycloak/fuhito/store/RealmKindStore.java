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
     * Finds the code of a kind by its ID. It reads only the code, so a lock of the kind can come after it.
     *
     * @param id the ID
     * @return the code, or empty when the realm has no kind with the ID
     */
    Optional<String> codeOf(String id);

    /**
     * Stores a new kind.
     *
     * @param kind the kind
     */
    void insert(RealmKind kind);

    /**
     * Stores new names of a kind.
     *
     * @param kind the kind with its ID
     */
    void update(RealmKind kind);

    /**
     * Deletes a kind.
     *
     * @param id the ID
     */
    void delete(String id);
}
