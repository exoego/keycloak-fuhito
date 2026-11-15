package net.exoego.keycloak.fuhito.jpa;

import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;

/**
 * Runs work that reads much, and puts off its writes to the end.
 * In the default mode, each query first looks through every loaded object for changes. Work that sends
 * thousands of queries then grows with the square of what it loaded.
 * It is only right for work that does not read back what it wrote before the end.
 */
public final class OneFlush {

    private OneFlush() {
    }

    /**
     * Runs the work with one flush at its end.
     *
     * @param <T> the type of the result
     * @param em the entity manager of the transaction
     * @param work the work
     * @return the result of the work
     */
    public static <T> T run(EntityManager em, Supplier<T> work) {
        FlushModeType before = em.getFlushMode();
        em.setFlushMode(FlushModeType.COMMIT);
        try {
            T result = work.get();
            em.flush();
            return result;
        } finally {
            em.setFlushMode(before);
        }
    }
}
