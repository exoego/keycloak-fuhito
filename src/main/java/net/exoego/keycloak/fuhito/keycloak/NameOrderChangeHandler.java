package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.jpa.PeopleNamedByUsers;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Gives a new version and a propagated change to each person whose read takes the name of its user, when the realm
 * attribute that orders the first name and the last name changes.
 */
final class NameOrderChangeHandler {

    private NameOrderChangeHandler() {
    }

    static void record(KeycloakSession session, String realmId, String order) {
        boolean familyFirst = UserNames.familyFirst(order);
        KeycloakSessionFactory factory = session.getKeycloakSessionFactory();
        if (!reorders(factory, realmId, familyFirst)) {
            return;
        }
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        // The change can touch every person of the realm, so it waits for the other writes as a bulk write does.
        RowLocks.lockRealm(em, realmId, true);
        // Another change of the order may have committed while this one waited.
        if (reorders(factory, realmId, familyFirst)) {
            PeopleNamedByUsers.reorder(em, realmId, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    ChangeLogEntry.Cause.KEYCLOAK_REALM_NAME_ORDER_CHANGED);
        }
    }

    // The event has only the new value. Another transaction still reads the old one, until this one commits.
    // A realm that Keycloak creates in this transaction has no people yet.
    private static boolean reorders(KeycloakSessionFactory factory, String realmId, boolean familyFirst) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, own -> {
            RealmModel realm = own.realms().getRealm(realmId);
            return realm != null && UserNames.familyFirst(realm.getAttribute(UserNames.ORDER_ATTRIBUTE)) != familyFirst;
        });
    }
}
