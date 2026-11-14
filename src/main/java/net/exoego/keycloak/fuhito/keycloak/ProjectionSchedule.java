package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.util.List;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
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
 * Rebuilds the projection of every realm that turned it on, again and again after a fixed time.
 */
final class ProjectionSchedule {

    static final String TASK = "fuhito-projection";

    // The cluster runner of Keycloak uses the simple name of this class as the name of the lock.
    // So the name must not be one that another extension is likely to use.
    static final class FuhitoProjectionTask implements ScheduledTask {

        @Override
        public void run(KeycloakSession session) {
            rebuildAll(session.getKeycloakSessionFactory(), session);
        }

        @Override
        public String getTaskName() {
            return TASK;
        }
    }

    private static final Logger LOG = Logger.getLogger(ProjectionSchedule.class);

    private ProjectionSchedule() {
    }

    // The timer of every node of a cluster fires. The runner of Keycloak lets only one of them in at a time.
    // Keycloak runs its own scheduled tasks the same way.
    static void start(KeycloakSessionFactory factory, long intervalSeconds) {
        long intervalMillis = intervalSeconds * 1000;
        KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(TimerProvider.class)
                .schedule(new ClusterAwareScheduledTaskRunner(factory, new FuhitoProjectionTask(), intervalMillis),
                        intervalMillis));
        LOG.infof("The projection runs every %d seconds for the realms that turned it on.", intervalSeconds);
    }

    private static void rebuildAll(KeycloakSessionFactory factory, KeycloakSession session) {
        List<String> realmIds = session.realms().getRealmsStream()
                .filter(realm -> realm.getAttribute(KeycloakOrganizations.PROJECTION_ATTRIBUTE, false))
                .map(RealmModel::getId)
                .toList();
        for (String realmId : realmIds) {
            // One transaction for each realm, so a failure in one realm leaves the others alone.
            try {
                KeycloakModelUtils.runJobInTransaction(factory, each -> rebuild(each, realmId));
            } catch (RuntimeException e) {
                LOG.errorf(e, "The scheduled projection failed for the realm with the ID %s.", realmId);
            }
        }
    }

    private static void rebuild(KeycloakSession session, String realmId) {
        RealmModel realm = session.realms().getRealm(realmId);
        // The realm is missing only when it is removed after the timer listed it. No test can time that.
        if (realm == null) {
            return;
        }
        BusinessDay.Rule rule;
        try {
            rule = RealmTime.rule(realm);
        } catch (FuhitoException e) {
            // UTC would mirror the wrong date for hours. The API refuses such a realm too.
            LOG.warnf("The scheduled projection skips realm %s, which has no usable time zone (%s).",
                    realm.getName(), e.code());
            return;
        }
        session.getContext().setRealm(realm);
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        var orgUnits = new JpaOrgUnitStore(em, realmId);
        try {
            ProjectionReport report = new ProjectionService(orgUnits, new JpaAssignmentStore(em, realmId),
                    new JpaEmploymentStore(em, realmId), new JpaRefItemStore(em, realmId),
                    new KeycloakOrganizations(session, realm), new UnitDays(orgUnits, rule, Clock.systemUTC()))
                    .rebuild();
            LOG.debugf("The scheduled projection of realm %s is done: %s", realm.getName(), report);
        } catch (FuhitoException e) {
            // The data of the realm must change first, so a stack trace on every run would only be noise.
            LOG.warnf("The scheduled projection of realm %s failed with %s %s.", realm.getName(), e.code(),
                    e.params());
            session.getTransactionManager().setRollbackOnly();
        }
    }
}
