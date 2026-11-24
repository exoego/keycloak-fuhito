package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.store.AttributeValueStore;

/**
 * Stores the values of one name of free attributes in every row of a kind of resource of one realm, with a few
 * statements for the whole realm. They are native SQL of PostgreSQL, because JPQL cannot change a value inside JSON
 * text, and a loop over the entities would load every row of the realm into the session. The other values of a row
 * keep their text as it is stored.
 */
public class JpaAttributeValues implements AttributeValueStore {

    // The table of the rows with attributes and its column that names the resource of a row. The table that holds
    // the version of the resource and its column that names the resource. The entities of the two tables.
    private record Table(String rows, String resource, String versions, String versionOf, Class<?> rowEntity,
            Class<?> versionEntity) {
    }

    private static final Map<Target, Table> TABLES = new EnumMap<>(Map.of(
            Target.ORG_UNIT, new Table("FUHITO_ORG_UNIT_VERSION", "ORG_UNIT_ID", "FUHITO_ORG_UNIT", "ID",
                    OrgUnitVersionEntity.class, OrgUnitEntity.class),
            Target.PERSON, new Table("FUHITO_PERSON", "ID", "FUHITO_PERSON", "ID", PersonEntity.class,
                    PersonEntity.class),
            Target.EMPLOYMENT_TERMS, new Table("FUHITO_EMPLOYMENT_TERMS", "EMPLOYMENT_ID", "FUHITO_EMPLOYMENT", "ID",
                    EmploymentTermsEntity.class, EmploymentEntity.class),
            Target.ASSIGNMENT, new Table("FUHITO_ASSIGNMENT", "ASSIGNMENT_ID", "FUHITO_ASSIGNMENT_VERSION", "ID",
                    AssignmentEntity.class, AssignmentVersionEntity.class),
            Target.QUALIFICATION, new Table("FUHITO_QUALIFICATION", "ID", "FUHITO_QUALIFICATION", "ID",
                    QualificationEntity.class, QualificationEntity.class),
            Target.REF_ITEM, new Table("FUHITO_REF_ITEM", "ID", "FUHITO_REF_ITEM", "ID", RefItemEntity.class,
                    RefItemEntity.class)));

    // The type json keeps the text of a value. The type jsonb would write a number such as 1E+300 with all its digits.
    private static final String MISSING = """
            coalesce(json_typeof(cast(r.ATTRIBUTES as json) -> cast(:name as text)), 'null') = 'null'""";

    private static final String PRESENT = """
            coalesce(json_typeof(cast(r.ATTRIBUTES as json) -> cast(:name as text)), 'null') <> 'null'""";

    // The other pairs of the row in their order, and the new pair last.
    private static final String FILLED = """
            coalesce((select '{' || string_agg(e.k || ':' || e.v, ',' order by e.n) || '}' from (
              select cast(to_json(p.key) as text) as k, cast(p.value as text) as v, p.n
              from json_each(cast(r.ATTRIBUTES as json)) with ordinality as p(key, value, n)
              where p.key <> cast(:name as text)
              union all
              select cast(to_json(cast(:name as text)) as text), cast(:value as text), 9223372036854775807) e), '{}')""";

    private static final String CLEARED = """
            coalesce((select '{' || string_agg(cast(to_json(p.key) as text) || ':' || cast(p.value as text), ','
                order by p.n) || '}'
              from json_each(cast(r.ATTRIBUTES as json)) with ordinality as p(key, value, n)
              where p.key <> cast(:name as text)), '{}')""";

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaAttributeValues(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public long countMissing(Target target, String name) {
        return count(target, name, MISSING);
    }

    @Override
    public long countPresent(Target target, String name) {
        return count(target, name, PRESENT);
    }

    private long count(Target target, String name, String condition) {
        return ((Number) picked(em.createNativeQuery("""
                select count(*) from %s r where r.REALM_ID = :realmId and %s"""
                .formatted(TABLES.get(target).rows(), condition)), name).getSingleResult()).longValue();
    }

    @Override
    public long largestAfterFill(Target target, String name, Object value) {
        return ((Number) picked(em.createNativeQuery("""
                select coalesce(max(octet_length(%s)), 0) from %s r where r.REALM_ID = :realmId and %s"""
                .formatted(FILLED, TABLES.get(target).rows(), MISSING)), name)
                .setParameter("value", JsonConverters.text(value))
                .getSingleResult()).longValue();
    }

    @Override
    public List<String> fill(Target target, String name, Object value) {
        return write(target, name, MISSING, FILLED, Map.of("value", JsonConverters.text(value)));
    }

    @Override
    public List<String> clear(Target target, String name) {
        return write(target, name, PRESENT, CLEARED, Map.of());
    }

    // Counts up the version of the resource of each row that the condition picks, once in the transaction, and then
    // writes the new attributes of those rows.
    private List<String> write(Target target, String name, String condition, String attributes,
            Map<String, Object> values) {
        Table table = TABLES.get(target);
        // The session must not keep a copy of a row that the statements change.
        JpaLongWrite.detach(em, entity -> entity.getClass() == table.rowEntity()
                || entity.getClass() == table.versionEntity());
        String resources = """
                select r.%s from %s r where r.REALM_ID = :realmId and %s"""
                .formatted(table.resource(), table.rows(), condition);
        Map<String, List<String>> versionRows = new LinkedHashMap<>();
        for (Object row : picked(em.createNativeQuery("""
                select v.ID as ROW_ID, v.%1$s as RESOURCE_ID from %2$s v
                where v.REALM_ID = :realmId and v.%1$s in (%3$s) order by v.%1$s, v.ID"""
                .formatted(table.versionOf(), table.versions(), resources)), name).getResultList()) {
            Object[] ids = (Object[]) row;
            versionRows.computeIfAbsent((String) ids[1], any -> new ArrayList<>()).add((String) ids[0]);
        }
        if (versionRows.isEmpty()) {
            return List.of();
        }
        List<String> counted = versionRows.entrySet().stream()
                .filter(resource -> resource.getValue().stream()
                        .anyMatch(id -> JpaLongWrite.counted(table.versionEntity(), id)))
                .map(Map.Entry::getKey)
                .toList();
        Query versions = picked(em.createNativeQuery("""
                update %1$s v set VERSION = v.VERSION + 1
                where v.REALM_ID = :realmId and v.%2$s in (%3$s)
                and v.%2$s not in (select unnest(cast(:counted as varchar[])))"""
                .formatted(table.versions(), table.versionOf(), resources)), name);
        versions.setParameter("counted", ManyIds.of(counted)).executeUpdate();
        Query rows = picked(em.createNativeQuery("""
                update %s r set ATTRIBUTES = %s where r.REALM_ID = :realmId and %s"""
                .formatted(table.rows(), attributes, condition)), name);
        values.forEach(rows::setParameter);
        rows.executeUpdate();
        JpaLongWrite.markCounted(table.versionEntity(),
                versionRows.values().stream().flatMap(List::stream).toList());
        return List.copyOf(versionRows.keySet());
    }

    private Query picked(Query query, String name) {
        return query.setParameter("realmId", realmId).setParameter("name", name);
    }
}
