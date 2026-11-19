package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;

/**
 * Removes resources that were made by mistake from the database of Keycloak.
 */
public class JpaResourceRemoval implements ResourceRemoval {

    // For each kind, a count of the rows that refer to a resource, by the name that an error shows.
    private static final Map<Kind, Map<String, String>> DEPENDENTS = Map.of(
            Kind.ORG_UNIT, Map.of(
                    "children", "select count(x) from OrgUnitLinkEntity x where x.realmId = :realmId and x.parentId = :id",
                    "assignments", "select count(x) from AssignmentEntity x where x.realmId = :realmId and x.orgUnitId = :id",
                    "employments", "select count(x) from EmploymentEntity x where x.realmId = :realmId and x.employerId = :id",
                    "unitRoles", "select count(x) from UnitRoleEntity x where x.realmId = :realmId and x.orgUnitId = :id",
                    "successions", """
                            select count(x) from OrgUnitSuccessionEntity x where x.realmId = :realmId
                            and (x.predecessorId = :id or x.successorId = :id)""",
                    "organization", """
                            select count(x) from OrgUnitEntity x where x.realmId = :realmId and x.id = :id
                            and x.keycloakOrganizationId is not null"""),
            Kind.EMPLOYMENT, Map.of(
                    "assignments", "select count(x) from AssignmentEntity x where x.realmId = :realmId and x.employmentId = :id",
                    "unitRoles", "select count(x) from UnitRoleEntity x where x.realmId = :realmId and x.employmentId = :id",
                    "continuation", """
                            select count(x) from EmploymentEntity x where x.realmId = :realmId
                            and x.continuesEmploymentId = :id"""),
            Kind.ASSIGNMENT, Map.of(
                    "reportingLines", """
                            select count(x) from ReportingLineEntity x where x.realmId = :realmId
                            and x.assignmentId = :id""",
                    "continuation", """
                            select count(x) from AssignmentEntity x where x.realmId = :realmId
                            and x.continuesAssignmentId = :id"""));

    // For each kind, the rows that go with the resource, in an order that the foreign keys allow.
    private static final Map<Kind, List<String>> REMOVALS = Map.of(
            Kind.ORG_UNIT, List.of(
                    "delete from OrgUnitLinkEntity x where x.realmId = :realmId and x.childId = :id",
                    """
                    delete from OrgUnitVersionNameEntity x where x.realmId = :realmId and x.versionId in
                    (select v.id from OrgUnitVersionEntity v where v.realmId = :realmId and v.orgUnitId = :id)""",
                    "delete from OrgUnitVersionEntity x where x.realmId = :realmId and x.orgUnitId = :id",
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ORG_UNIT'
                    and x.resourceId = :id""",
                    "delete from OrgUnitEntity x where x.realmId = :realmId and x.id = :id"),
            Kind.EMPLOYMENT, List.of(
                    "delete from EmploymentTermsEntity x where x.realmId = :realmId and x.employmentId = :id",
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'EMPLOYMENT'
                    and x.resourceId = :id""",
                    "delete from EmploymentEntity x where x.realmId = :realmId and x.id = :id"),
            Kind.ASSIGNMENT, List.of(
                    """
                    delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ASSIGNMENT'
                    and x.resourceId = :id""",
                    "delete from AssignmentEntity x where x.realmId = :realmId and x.assignmentId = :id"));

    private static final Map<Kind, String> EXISTS = Map.of(
            Kind.ORG_UNIT, "select count(x) from OrgUnitEntity x where x.realmId = :realmId and x.id = :id",
            Kind.EMPLOYMENT, "select count(x) from EmploymentEntity x where x.realmId = :realmId and x.id = :id",
            Kind.ASSIGNMENT, """
                    select count(x) from AssignmentEntity x where x.realmId = :realmId
                    and x.assignmentId = :id""");

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
        return count(EXISTS.get(kind), id) > 0;
    }

    @Override
    public List<String> dependents(Kind kind, String id) {
        List<String> found = new ArrayList<>();
        DEPENDENTS.get(kind).forEach((name, query) -> {
            if (count(query, id) > 0) {
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

    private long count(String query, String id) {
        return em.createQuery(query, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getSingleResult();
    }
}
