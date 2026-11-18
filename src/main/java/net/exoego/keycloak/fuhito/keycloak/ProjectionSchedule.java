package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.jpa.JpaJobRunStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.service.DailyProjection;
import net.exoego.keycloak.fuhito.service.ProjectionReport;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.service.UnitDays;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.scheduled.ClusterAwareScheduledTaskRunner;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.timer.TimerProvider;

/**
 * Rebuilds the projection of every realm that turned it on, once a day after its day start.
 * See {@link DailyProjection} for when a realm is due.
 */
final class ProjectionSchedule {

    static final String TASK = "fuhito-projection";

    private static final long CHECK_MILLIS = 60_000;

    // The runner of Keycloak holds the lock of the cluster until the task ends, or for this long when its node
    // dies. So a long run keeps the other nodes out.
    private static final long LOCK_MILLIS = 15 * 60_000;

    private static final Logger LOG = Logger.getLogger(ProjectionSchedule.class);

    // The timer checks every minute. A realm with a bad setting is logged once on each node, not every minute.
    private static final Map<String, String> SKIPPED = new ConcurrentHashMap<>();

    // The cluster runner of Keycloak uses the simple name of this class as the name of the lock.
    // So the name must not be one that another extension is likely to use.
    static final class FuhitoProjectionTask implements ScheduledTask {

        @Override
        public void run(KeycloakSession session) {
            runDue(session.getKeycloakSessionFactory(), session);
        }

        @Override
        public String getTaskName() {
            return TASK;
        }
    }

    private record Due(String name, BusinessDay.Rule rule) {
    }

    private ProjectionSchedule() {
    }

    // The timer of every node of a cluster fires. The runner of Keycloak lets only one of them in at a time.
    // Keycloak runs its own scheduled tasks the same way.
    static void start(KeycloakSessionFactory factory) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(TimerProvider.class)
                .schedule(new ClusterAwareScheduledTaskRunner(factory, new FuhitoProjectionTask(), LOCK_MILLIS),
                        CHECK_MILLIS));
        LOG.info("The projection runs once a day for each realm that turned it on, after the day start.");
    }

    private static void runDue(KeycloakSessionFactory factory, KeycloakSession session) {
        List<String> realmIds = session.realms().getRealmsStream()
                .filter(realm -> realm.getAttribute(KeycloakOrganizations.PROJECTION_ATTRIBUTE, false))
                .map(RealmModel::getId)
                .toList();
        // The session of the timer has a transaction too, and the rebuilds can take longer than it may last.
        OneAtATime.outsideTransaction(session, () -> {
            for (String realmId : realmIds) {
                // Sessions of its own for each realm, so a failure in one realm leaves the others alone.
                try {
                    KeycloakModelUtils.runJobInTransactionWithResult(factory,
                            each -> due(each, realmId, Instant.now())).ifPresent(due -> run(factory, realmId, due));
                } catch (RuntimeException e) {
                    LOG.errorf(e, "The scheduled projection failed for the realm with the ID %s.", realmId);
                }
            }
            return null;
        });
    }

    private static Optional<Due> due(KeycloakSession session, String realmId, Instant now) {
        RealmModel realm = session.realms().getRealm(realmId);
        // The realm is missing only when it is removed after the timer listed it. No test can time that.
        if (realm == null) {
            return Optional.empty();
        }
        try {
            BusinessDay.Rule rule = RealmTime.rule(realm);
            EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            Instant next = DailyProjection.next(realmId, new JpaJobRunStore(em, realmId).last(DailyProjection.JOB),
                    new UnitDays(new JpaOrgUnitStore(em, realmId), rule, Clock.systemUTC()));
            SKIPPED.remove(realmId);
            return next.isAfter(now) ? Optional.empty() : Optional.of(new Due(realm.getName(), rule));
        } catch (FuhitoException e) {
            // UTC would mirror the wrong date for hours. The API refuses such a realm too.
            if (!e.code().equals(SKIPPED.put(realmId, e.code()))) {
                LOG.warnf("The scheduled projection skips realm %s, which has no usable time setting (%s).",
                        realm.getName(), e.code());
            }
            return Optional.empty();
        }
    }

    private static void run(KeycloakSessionFactory factory, String realmId, Due due) {
        Instant startedAt = Instant.now();
        String error = "internal";
        try {
            error = KeycloakModelUtils.runJobInTransactionWithResult(factory,
                    session -> rebuild(session, realmId, due));
        } finally {
            record(factory, realmId, startedAt, error);
        }
    }

    // Returns the code of the error, or null when the rebuild is done.
    private static String rebuild(KeycloakSession session, String realmId, Due due) {
        try {
            ProjectionReport report = new ProjectionService(
                    new ProjectionTransactions(session.getKeycloakSessionFactory(), realmId, due.rule(), session))
                    .rebuild();
            LOG.debugf("The scheduled projection of realm %s is done: %s", due.name(), report);
            return null;
        } catch (FuhitoException e) {
            // The data of the realm must change first, so a stack trace on every run would only be noise.
            LOG.warnf("The scheduled projection of realm %s failed with %s %s.", due.name(), e.code(), e.params());
            return e.code();
        }
    }

    // A crash counts as a failure too. Otherwise it would run again every minute.
    private static void record(KeycloakSessionFactory factory, String realmId, Instant startedAt, String error) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            var runs = new JpaJobRunStore(session.getProvider(JpaConnectionProvider.class).getEntityManager(),
                    realmId);
            runs.started(DailyProjection.JOB, startedAt);
            runs.ended(DailyProjection.JOB, Instant.now(), error == null ? "DONE" : "FAILED", null, error);
        });
    }
}
