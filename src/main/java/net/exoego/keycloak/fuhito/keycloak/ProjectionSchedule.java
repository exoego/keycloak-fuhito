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
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.TimerProvider;

/**
 * Rebuilds the projection of every realm that turned it on, again and again after a fixed time.
 */
final class ProjectionSchedule {

    static final String TASK = "fuhito-projection";

    private static final Logger LOG = Logger.getLogger(ProjectionSchedule.class);

    private ProjectionSchedule() {
    }

    static void start(KeycloakSessionFactory factory, long intervalSeconds) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(TimerProvider.class)
                .schedule(() -> run(factory, intervalSeconds), intervalSeconds * 1000, TASK));
        LOG.infof("The projection runs every %d seconds for the realms that turned it on.", intervalSeconds);
    }

    // The timer of every node of a cluster fires. The cluster lets only one of them in at a time.
    // A second run right after the first finds nothing to change.
    private static void run(KeycloakSessionFactory factory, long intervalSeconds) {
        try {
            KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(ClusterProvider.class)
                    .executeIfNotExecuted(TASK, (int) Math.min(intervalSeconds, Integer.MAX_VALUE), () -> {
                        rebuildAll(factory, session);
                        return null;
                    }));
        } catch (RuntimeException e) {
            LOG.error("The scheduled projection did not run.", e);
        }
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
