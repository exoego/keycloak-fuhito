package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaProjectedMemberStore;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import net.exoego.keycloak.fuhito.jpa.RowLocks;

/**
 * Takes the link to an organization that Keycloak removes away from the org unit that has it. The unit gets a new
 * version and a propagated change. The records of the members that the projection added go too.
 */
final class OrganizationRemovalHandler {

    private OrganizationRemovalHandler() {
    }

    static void unlink(EntityManager em, String realmId, String organizationId) {
        RowLocks.lockRealm(em, realmId, false);
        List<String> unitIds = RealmData.unlinkOrganization(em, realmId, organizationId);
        if (!unitIds.isEmpty()) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            new JpaChangeLogStore(em, realmId).appendAll(unitIds.stream()
                    .map(unitId -> ChangeLogEntry.propagated(UUID.randomUUID().toString(), now,
                            DerivedValue.KEYCLOAK_ORGANIZATION_ID, unitId,
                            ChangeLogEntry.Cause.KEYCLOAK_ORGANIZATION_REMOVED, null, null))
                    .toList());
        }
        new JpaProjectedMemberStore(em, realmId).forgetOrganization(organizationId);
    }
}
