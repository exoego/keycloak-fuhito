package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Gives a new version and a propagated change to the person of a user whose name Keycloak changed, when the read of
 * the person gives that name.
 */
final class UserNameChangeHandler {

    private UserNameChangeHandler() {
    }

    static void record(KeycloakSession session, String realmId, String userId) {
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        Optional<String> person = RealmData.personNamedByUser(em, realmId, userId);
        if (person.isEmpty()) {
            return;
        }
        RowLocks.lockRealm(em, realmId, false);
        RowLocks.lock(em, realmId, Map.of("people", List.of(person.get())));
        RealmModel realm = session.realms().getRealm(realmId);
        // A write of the API may have given the person names before the lock.
        if (person.equals(RealmData.personNamedByUser(em, realmId, userId))
                && !UserNames.of(realm, session.users().getUserById(realm, userId))
                        .equals(committedName(session.getKeycloakSessionFactory(), realmId, userId))) {
            RealmData.countUpPerson(em, realmId, person.get());
            new JpaChangeLogStore(em, realmId).append(ChangeLogEntry.propagated(UUID.randomUUID().toString(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), DerivedValue.PERSON_NAME, person.get(),
                    ChangeLogEntry.Cause.KEYCLOAK_USER_NAME_CHANGED, null, null));
        }
    }

    // No event has the name from before the change. Another transaction still reads it, until this one commits.
    // The lock of the person keeps a second change of the user waiting until then.
    private static String committedName(KeycloakSessionFactory factory, String realmId, String userId) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, own -> {
            RealmModel realm = own.realms().getRealm(realmId);
            own.getContext().setRealm(realm);
            return UserNames.of(realm, own.users().getUserById(realm, userId));
        });
    }
}
