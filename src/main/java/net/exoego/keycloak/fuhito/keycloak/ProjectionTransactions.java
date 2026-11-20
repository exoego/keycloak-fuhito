package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaProjectedMemberStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import net.exoego.keycloak.fuhito.service.InParallel;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.service.ProjectionService.Parts;
import net.exoego.keycloak.fuhito.service.UnitDays;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
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
    private final KeycloakSession caller;

    // A write waits mostly for the database, so a few threads keep it busy. More would only take connections.
    private static final int THREADS = 4;

    /**
     * Creates the runner for one realm.
     *
     * @param factory makes the sessions
     * @param realmId the ID of the realm
     * @param rule the time zone and the day start of the realm
     * @param caller the session of the request or of the timer that starts the rebuild
     */
    public ProjectionTransactions(KeycloakSessionFactory factory, String realmId, BusinessDay.Rule rule,
            KeycloakSession caller) {
        this.caller = caller;
        this.factory = factory;
        this.realmId = realmId;
        this.rule = rule;
    }

    @Override
    public <T> T alone(Supplier<T> run) {
        return OneAtATime.outsideTransaction(caller,
                () -> OneAtATime.run(factory, "projection", realmId, run));
    }

    @Override
    public <T> Optional<T> aloneIf(BooleanSupplier check, Supplier<T> run) {
        return OneAtATime.outsideTransaction(caller,
                () -> OneAtATime.runIf(factory, "projection", realmId, check, run));
    }

    // Each piece takes a session of its own, so the threads share nothing of Keycloak.
    @Override
    public void inParallel(List<Consumer<Parts>> pieces) {
        InParallel.run(pieces.stream().<Runnable>map(piece -> () -> inNewTransaction(parts -> {
            piece.accept(parts);
            return null;
        })).toList(), THREADS);
    }

    @Override
    public <T> T inNewTransaction(Function<Parts, T> piece) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            RowLocks.lockRealm(em, realmId, false);
            var orgUnits = new JpaOrgUnitStore(em, realmId);
            return piece.apply(new Parts(orgUnits, new JpaAssignmentStore(em, realmId),
                    new JpaEmploymentStore(em, realmId), new JpaRefItemStore(em, realmId),
                    new KeycloakOrganizations(session, realm), new JpaProjectedMemberStore(em, realmId),
                    new UnitDays(orgUnits, rule, Clock.systemUTC())));
        });
    }
}
