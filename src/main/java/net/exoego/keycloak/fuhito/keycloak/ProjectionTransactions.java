package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.service.ProjectionService.Parts;
import net.exoego.keycloak.fuhito.service.UnitDays;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Runs each piece of a rebuild of the projection in a session and a transaction of its own.
 * A new session starts with nothing loaded, so the work of a piece does not grow with the pieces before it.
 */
public final class ProjectionTransactions implements ProjectionService.Work {

    private final KeycloakSessionFactory factory;
    private final String realmId;
    private final BusinessDay.Rule rule;

    /**
     * Creates the runner for one realm.
     *
     * @param factory makes the sessions
     * @param realmId the ID of the realm
     * @param rule the time zone and the day start of the realm
     */
    public ProjectionTransactions(KeycloakSessionFactory factory, String realmId, BusinessDay.Rule rule) {
        this.factory = factory;
        this.realmId = realmId;
        this.rule = rule;
    }

    @Override
    public <T> T inNewTransaction(Function<Parts, T> piece) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            var orgUnits = new JpaOrgUnitStore(em, realmId);
            return piece.apply(new Parts(orgUnits, new JpaAssignmentStore(em, realmId),
                    new JpaEmploymentStore(em, realmId), new JpaRefItemStore(em, realmId),
                    new KeycloakOrganizations(session, realm), new UnitDays(orgUnits, rule, Clock.systemUTC())));
        });
    }
}
