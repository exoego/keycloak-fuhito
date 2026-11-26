package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.LongRead;

/**
 * Keeps the session of a long read small, as {@link JpaLongWrite} does for a write. The same entities leave the
 * session after each page.
 */
public final class JpaLongRead implements LongRead {

    private final EntityManager em;

    /**
     * Creates the long read.
     *
     * @param em the entity manager of the request
     */
    public JpaLongRead(EntityManager em) {
        this.em = em;
    }

    @Override
    public void pageDone() {
        JpaLongWrite.detach(em, JpaLongWrite::leavesAfterEachRow);
    }
}
