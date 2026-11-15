package net.exoego.keycloak.fuhito.keycloak;

import java.util.Map;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Lets only one run of a job for a realm in at a time, on all nodes of a cluster.
 * A job that works in pieces would mix its pieces with those of a second run.
 */
final class OneAtATime {

    // The lock goes away by itself after this time, for the case that a node dies with it.
    private static final int LOCK_SECONDS = 6 * 60 * 60;

    private OneAtATime() {
    }

    /**
     * Runs a long job with no transaction of the session open.
     * Keycloak ends a transaction after some minutes, also one that does nothing. The session of a request
     * or of a timer has one from its start, and its end would fail after a long job.
     * So that transaction ends first. Keycloak cannot start a second one in a session, so the caller must
     * not use the database of the session after the job.
     *
     * @param <T> the type of the result
     * @param session the session of the request or of the timer
     * @param run the job, which does its work in sessions of its own
     * @return the result of the job
     */
    static <T> T outsideTransaction(KeycloakSession session, Supplier<T> run) {
        session.getTransactionManager().commit();
        return run.get();
    }

    /**
     * Runs the job, unless a run of the same job for the same realm is going on.
     *
     * @param <T> the type of the result
     * @param factory makes the session that holds the lock
     * @param job the name of the job, which is also the start of the error code
     * @param realmId the ID of the realm
     * @param run the job
     * @return the result of the job
     * @throws FuhitoException with the code of the job and ".running" when another run is going on
     */
    static <T> T run(KeycloakSessionFactory factory, String job, String realmId, Supplier<T> run) {
        // The session has no transaction, so no timeout of a transaction ends a long run.
        try (KeycloakSession session = factory.create()) {
            ExecutionResult<T> result = session.getProvider(ClusterProvider.class)
                    .executeIfNotExecuted("fuhito-" + job + "-" + realmId, LOCK_SECONDS, run::get);
            if (!result.isExecuted()) {
                throw FuhitoException.conflict(job + ".running", Map.of());
            }
            return result.getResult();
        }
    }
}
