package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;

/**
 * Removes resources that were made by mistake from the database of Keycloak.
 */
public class JpaResourceRemoval implements ResourceRemoval {

    // For each kind, the realms of the rows that refer to a resource, by the name that an error shows. The rows come
    // by the ID alone, for the reason in Rows.inRealm.
    private static final Map<Kind, Map<String, String>> DEPENDENTS = Map.of(
            Kind.ORG_UNIT, Map.of(
                    "children", "select distinct x.realmId from OrgUnitLinkEntity x where x.parentId = :id",
                    "assignments", "select distinct x.realmId from AssignmentEntity x where x.orgUnitId = :id",
                    "employments", "select distinct x.realmId from EmploymentEntity x where x.employerId = :id",
                    "unitRoles", "select distinct x.realmId from UnitRoleEntity x where x.orgUnitId = :id",
                    "successions", """
                            select distinct x.realmId from OrgUnitSuccessionEntity x
                            where x.predecessorId = :id or x.successorId = :id""",
                    "organization", """
                            select distinct x.realmId from OrgUnitEntity x where x.id = :id
                            and x.keycloakOrganizationId is not null"""),
            Kind.EMPLOYMENT, Map.of(
                    "assignments", "select distinct x.realmId from AssignmentEntity x where x.employmentId = :id",
                    "unitRoles", "select distinct x.realmId from UnitRoleEntity x where x.employmentId = :id",
                    "continuation", """
                            select distinct x.realmId from EmploymentEntity x where x.continuesEmploymentId = :id"""),
            Kind.ASSIGNMENT, Map.of(
                    "reportingLines", """
                            select distinct x.realmId from ReportingLineEntity x where x.assignmentId = :id""",
                    // No index starts with this column, so the realm keeps the read in the rows of the realm.
                    "continuation", """
                            select distinct x.realmId from AssignmentEntity x where x.realmId = :realmId
                            and x.continuesAssignmentId = :id"""));

    private static final String OF_EMPLOYMENT = Rows.ofRecord("x.realmId", "EmploymentEntity", "id", "id");

    // For each kind, the rows that go with the resource, in an order that the foreign keys allow.
    private static final Map<Kind, List<String>> REMOVALS = Map.of(
            Kind.ORG_UNIT, List.of(
                    "delete from OrgUnitLinkEntity x where x.realmId = :realmId and x.childId = :id",
                    """
                    delete from OrgUnitVersionNameEntity x where x.realmId = :realmId and x.versionId in
                    (select v.id from OrgUnitVersionEntity v where v.orgUnitId = :id and %s)"""
                            .formatted(Rows.ofUnit("v.realmId", "id")),
                    "delete from OrgUnitVersionEntity x where x.orgUnitId = :id and " + Rows.ofUnit("x.realmId", "id"),
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ORG_UNIT'
                    and x.resourceId = :id""",
                    "delete from OrgUnitEntity x where x.id = :id and " + Rows.ofUnit("x.realmId", "id")),
            Kind.EMPLOYMENT, List.of(
                    "delete from EmploymentTermsEntity x where x.employmentId = :id and " + OF_EMPLOYMENT,
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'EMPLOYMENT'
                    and x.resourceId = :id""",
                    "delete from EmploymentEntity x where x.id = :id and " + OF_EMPLOYMENT),
            Kind.ASSIGNMENT, List.of(
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ASSIGNMENT'
                    and x.resourceId = :id""",
                    "delete from AssignmentEntity x where x.assignmentId = :id and "
                            + Rows.ofRecord("x.realmId", "AssignmentEntity", "assignmentId", "id")));

    // The realms of the resource, found by the ID alone.
    private static final Map<Kind, String> EXISTS = Map.of(
            Kind.ORG_UNIT, "select x.realmId from OrgUnitEntity x where x.id = :id",
            Kind.EMPLOYMENT, "select x.realmId from EmploymentEntity x where x.id = :id",
            Kind.ASSIGNMENT, "select distinct x.realmId from AssignmentEntity x where x.assignmentId = :id");

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the removal.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaResourceRemoval(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public boolean exists(Kind kind, String id) {
        return inRealm(EXISTS.get(kind), id);
    }

    @Override
    public List<String> dependents(Kind kind, String id) {
        List<String> found = new ArrayList<>();
        DEPENDENTS.get(kind).forEach((name, query) -> {
            if (inRealm(query, id)) {
                found.add(name);
            }
        });
        return found.stream().sorted().toList();
    }

    @Override
    public void remove(Kind kind, String id) {
        // The session may hold the rows from the checks before. The bulk deletes do not see them.
        em.flush();
        em.clear();
        REMOVALS.get(kind).forEach(query -> em.createQuery(query)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .executeUpdate());
    }

    private boolean inRealm(String query, String id) {
        TypedQuery<String> realms = em.createQuery(query, String.class).setParameter("id", id);
        if (realms.getParameters().stream().anyMatch(parameter -> "realmId".equals(parameter.getName()))) {
            realms.setParameter("realmId", realmId);
        }
        return realms.getResultList().contains(realmId);
    }
}
