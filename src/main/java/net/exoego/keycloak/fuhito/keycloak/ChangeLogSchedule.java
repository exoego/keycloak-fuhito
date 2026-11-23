package net.exoego.keycloak.fuhito.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.RealmAttributeValues;
import net.exoego.keycloak.fuhito.service.ChangeLogCleanup;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.executors.ExecutorsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.scheduled.ClusterAwareScheduledTaskRunner;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.timer.TimerProvider;

/**
 * Removes the propagated changes that each realm keeps no longer. See {@link ChangeLogCleanup}.
 */
final class ChangeLogSchedule {

    static final String TASK = "fuhito-change-log";

    /**
     * The realm attribute with the number of days that the realm keeps a propagated change.
     */
    static final String KEEP_ATTRIBUTE = "fuhito.changeLog.keepPropagatedDays";

    private static final Logger LOG = Logger.getLogger(ChangeLogSchedule.class);

    private static final long FIRST_RUN_MILLIS = Duration.ofMinutes(15).toMillis();

    // A realm with a bad setting is logged once on each node, not at every run.
    private static final Map<String, String> SKIPPED = new ConcurrentHashMap<>();

    // The cluster runner of Keycloak uses the simple name of this class as the name of the lock.
    // So the name must not be one that another extension is likely to use.
    static final class FuhitoChangeLogTask implements ScheduledTask {

        @Override
        public void run(KeycloakSession session) {
            removeOld(session.getKeycloakSessionFactory(), session);
        }

        @Override
        public String getTaskName() {
            return TASK;
        }
    }

    private ChangeLogSchedule() {
    }

    // The cleanup of a realm waits for its writes and for its removal, so it runs on a thread of its own and holds up
    // no task of Keycloak.
    static void start(KeycloakSessionFactory factory, long intervalMillis) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            var run = new ClusterAwareScheduledTaskRunner(factory, new FuhitoChangeLogTask(), intervalMillis);
            Executor executor = session.getProvider(ExecutorsProvider.class).getExecutor(TASK);
            session.getProvider(TimerProvider.class).schedule(new InBackground(executor, run),
                    firstDelayMillis(intervalMillis), intervalMillis, TASK);
        });
    }

    // The timer of Keycloak waits one interval before the first run. A node that restarts more often than that would
    // never run the cleanup.
    static long firstDelayMillis(long intervalMillis) {
        return Math.min(intervalMillis, FIRST_RUN_MILLIS);
    }

    private record Target(String id, String name) {
    }

    private static void removeOld(KeycloakSessionFactory factory, KeycloakSession session) {
        List<Target> realms = session.realms().getRealmsStream()
                .map(realm -> new Target(realm.getId(), realm.getName()))
                .toList();
        OneAtATime.outsideTransaction(session, () -> {
            // A transaction for each realm, so a failure in one realm leaves the others alone.
            for (Target realm : realms) {
                try {
                    KeycloakModelUtils.runJobInTransaction(factory, each -> removeOld(each, realm));
                    SKIPPED.remove(realm.id());
                } catch (FuhitoException e) {
                    if (!e.code().equals(SKIPPED.put(realm.id(), e.code()))) {
                        LOG.warnf("The cleanup of the change log skips realm %s. %s", realm.name(),
                                Messages.format(e.code(), e.params(), Locale.ENGLISH));
                    }
                } catch (RuntimeException e) {
                    LOG.errorf(e, "The cleanup of the change log failed for the realm with the ID %s.", realm.id());
                }
            }
            return null;
        });
    }

    // The setting comes from the database and not from the cache of Keycloak, which can lag behind a removal on another
    // node.
    private static void removeOld(KeycloakSession session, Target realm) {
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        long removed = ChangeLogCleanup.removeOld(new JpaChangeLogStore(em, realm.id()),
                () -> RealmAttributeValues.of(em, realm.id(), KEEP_ATTRIBUTE), Instant.now());
        if (removed > 0) {
            LOG.debugf("The cleanup of the change log removed %d propagated changes of realm %s.", removed,
                    realm.name());
        }
    }
}
