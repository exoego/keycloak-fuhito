package net.exoego.keycloak.fuhito.jpa;

/**
 * A row that holds the version of its resource. A write counts the version up once in a transaction.
 */
interface VersionedRow {

    /**
     * Tells whether a write of this transaction counted up the version already.
     *
     * @return true once a write did
     */
    boolean counted();
}
