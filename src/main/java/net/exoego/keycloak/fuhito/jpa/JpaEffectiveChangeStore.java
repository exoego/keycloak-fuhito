package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.EffectiveChange;
import net.exoego.keycloak.fuhito.domain.EffectiveChange.Kind;
import net.exoego.keycloak.fuhito.store.EffectiveChangeStore;

/**
 * Reads what takes effect on which date from the valid periods in the database of Keycloak.
 */
public class JpaEffectiveChangeStore implements EffectiveChangeStore {

    private record Source(String entity, String idField, Kind kind) {

        // A change on a date ends one row and starts the next. The union keeps one entry for both.
        String select(String dateField) {
            return """
                    select x.%1$s as date, '%2$s' as kind, x.%3$s as resourceId from %4$s x
                    where x.realmId = :realmId and x.%1$s >= :from and x.%1$s < :to
                    """.formatted(dateField, kind.name(), idField, entity);
        }
    }

    private static final String QUERY = Stream.of(
                    new Source("OrgUnitVersionEntity", "orgUnitId", Kind.ORG_UNIT),
                    new Source("OrgUnitLinkEntity", "childId", Kind.ORG_UNIT),
                    new Source("EmploymentEntity", "id", Kind.EMPLOYMENT),
                    new Source("EmploymentTermsEntity", "employmentId", Kind.EMPLOYMENT),
                    new Source("AssignmentEntity", "assignmentId", Kind.ASSIGNMENT),
                    new Source("ReportingLineEntity", "id", Kind.REPORTING_LINE),
                    new Source("QualificationEntity", "id", Kind.QUALIFICATION))
            .flatMap(source -> Stream.of(source.select("validFrom"), source.select("validTo")))
            .collect(Collectors.joining("union\n", "", "order by date, kind, resourceId"));

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaEffectiveChangeStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public List<EffectiveChange> list(LocalDate from, LocalDate to, int first, int max) {
        return em.createQuery(QUERY, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(row -> new EffectiveChange((LocalDate) row[0], Kind.valueOf((String) row[1]), (String) row[2]))
                .toList();
    }
}
