package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaExternalIdStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaPersonStore;
import net.exoego.keycloak.fuhito.jpa.JpaProjectedMemberStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.JpaUnitRoleStore;
import net.exoego.keycloak.fuhito.jpa.JpaReportingLineStore;
import net.exoego.keycloak.fuhito.jpa.JpaWriteScopes;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.service.LegalEntityEmployers;
import net.exoego.keycloak.fuhito.service.UserRetirement;
import org.keycloak.models.RealmModel;

/**
 * Keeps the history of a user who is removed from Keycloak, and ends what would still hold.
 * The records of the organizations that the projection added the user to go.
 */
final class UserRemovalHandler {

    private UserRemovalHandler() {
    }

    static void retire(EntityManager em, RealmModel realm, String userId) {
        String realmId = realm.getId();
        // The removal ends the employments of the user with what belongs to them, as a write of the API does.
        RowLocks.lock(em, realmId, new JpaWriteScopes(em, realmId).userData(userId));
        var employments = new JpaEmploymentStore(em, realmId);
        var assignments = new JpaAssignmentStore(em, realmId);
        var lines = new JpaReportingLineStore(em, realmId);
        var externalIds = new JpaExternalIdStore(em, realmId);
        var employmentService = new EmploymentService(employments, externalIds, assignments,
                new JpaRefItemStore(em, realmId), new JpaPersonStore(em, realmId), lines,
                new JpaUnitRoleStore(em, realmId),
                new LegalEntityEmployers(new JpaOrgUnitStore(em, realmId), new JpaRefItemStore(em, realmId)));
        new JpaProjectedMemberStore(em, realmId).forgetUser(userId);
        var changeLog = new JpaChangeLogStore(em, realmId);
        for (Write write : new UserRetirement(employments, employmentService, assignments, lines,
                new JpaPersonStore(em, realmId), new JpaUnitRoleStore(em, realmId), externalIds,
                RealmTime.clockOrUtc(realm)).retire(userId)) {
            // No administrator did this write. The removal of the user did.
            changeLog.append(new ChangeLogEntry(UUID.randomUUID().toString(), 0,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), null, write.resourceType(), write.resourceId(),
                    write.deleted() ? "DELETE" : "UPDATE", write.path(), null));
        }
    }
}
