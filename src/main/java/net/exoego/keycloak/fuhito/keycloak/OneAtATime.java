package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.jpa.JpaJobRunStore;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Lets only one run of a job for a realm in at a time, on all nodes of a cluster.
 * A job that works in pieces would mix its pieces with those of a second run.
 */
final class OneAtATime {

    // The lock goes away by itself after this time, for the case that a node dies with it.
    private static final int LOCK_SECONDS = 6 * 60 * 60;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        return run(factory, job, realmId, true, run);
    }

    /**
     * Runs a job, unless another run of it for the realm is going on.
     *
     * @param <T> the type of the result
     * @param factory the factory of sessions
     * @param job the name of the job, which is also the start of the error code
     * @param realmId the ID of the realm
     * @param record true to keep the state of the run as the last run of the job. A dry run is not kept.
     * @param run the job
     * @return the result of the job
     * @throws FuhitoException with the code of the job and ".running" when another run is going on
     */
    static <T> T run(KeycloakSessionFactory factory, String job, String realmId, boolean record, Supplier<T> run) {
        // The session has no transaction, so no timeout of a transaction ends a long run.
        try (KeycloakSession session = factory.create()) {
            ExecutionResult<T> result = session.getProvider(ClusterProvider.class)
                    .executeIfNotExecuted("fuhito-" + job + "-" + realmId, LOCK_SECONDS,
                            () -> record ? recorded(factory, job, realmId, run) : run.get());
            if (!result.isExecuted()) {
                throw FuhitoException.conflict(job + ".running", Map.of());
            }
            return result.getResult();
        }
    }

    // A caller whose call a proxy ended reads the result later. So the state of the run is written in
    // transactions of its own, at its start and at its end.
    private static <T> T recorded(KeycloakSessionFactory factory, String job, String realmId, Supplier<T> run) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> runs(session, realmId).started(job, Instant.now()));
        try {
            T result = run.get();
            String report = Unchecked.io(() -> JSON.writeValueAsString(summary(result)));
            KeycloakModelUtils.runJobInTransaction(factory, session -> runs(session, realmId)
                    .ended(job, Instant.now(), "DONE", report, null));
            return result;
        } catch (FuhitoException e) {
            KeycloakModelUtils.runJobInTransaction(factory, session -> runs(session, realmId)
                    .ended(job, Instant.now(), "FAILED", null, e.code()));
            throw e;
        } catch (RuntimeException e) {
            KeycloakModelUtils.runJobInTransaction(factory, session -> runs(session, realmId)
                    .ended(job, Instant.now(), "FAILED", null, "internal"));
            throw e;
        }
    }

    private static JpaJobRunStore runs(KeycloakSession session, String realmId) {
        return new JpaJobRunStore(session.getProvider(JpaConnectionProvider.class).getEntityManager(), realmId);
    }

    // The counts of a report. A list counts as its size, because the writes of a sync can be many.
    static Map<String, Object> summary(Object result) {
        Map<String, Object> counts = new LinkedHashMap<>();
        if (result instanceof Record report) {
            for (RecordComponent component : report.getClass().getRecordComponents()) {
                Object value;
                try {
                    value = component.getAccessor().invoke(report);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("A report has a public accessor for each value.", e);
                }
                if (value instanceof Collection<?> list) {
                    counts.put(component.getName(), list.size());
                } else if (value instanceof Number || value instanceof Boolean) {
                    counts.put(component.getName(), value);
                }
            }
        }
        return counts;
    }
}
