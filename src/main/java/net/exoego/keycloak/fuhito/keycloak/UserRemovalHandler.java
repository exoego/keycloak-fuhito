package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.JpaReportingLineStore;
import net.exoego.keycloak.fuhito.rest.EmploymentRepresentation;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.EmploymentView;
import net.exoego.keycloak.fuhito.service.UserRetirement;
import org.keycloak.models.RealmModel;

/**
 * Keeps the history of a user who is removed from Keycloak, and ends what would still hold.
 */
final class UserRemovalHandler {

    private UserRemovalHandler() {
    }

    static void retire(EntityManager em, RealmModel realm, String userId) {
        String realmId = realm.getId();
        var employments = new JpaEmploymentStore(em, realmId);
        var assignments = new JpaAssignmentStore(em, realmId);
        var lines = new JpaReportingLineStore(em, realmId);
        // The user is gone already, so no check of the user can pass. The end of an employment needs none.
        var employmentService = new EmploymentService(employments, assignments, new JpaRefItemStore(em, realmId),
                Set.<String>of()::contains, lines);
        var changeLog = new JpaChangeLogStore(em, realmId);
        for (EmploymentView ended : new UserRetirement(employments, employmentService, assignments, lines,
                RealmTime.clockOrUtc(realm)).retire(userId)) {
            String id = ended.employment().id();
            // No administrator did this write. The removal of the user did.
            changeLog.append(new ChangeLogEntry(UUID.randomUUID().toString(), 0,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), null, "FUHITO_EMPLOYMENT", id, "UPDATE",
                    "fuhito/employments/" + id + "/end", null,
                    KeycloakRequestContext.toJson(EmploymentRepresentation.of(ended))));
        }
    }
}
