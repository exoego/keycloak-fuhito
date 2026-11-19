package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefKindName;

/**
 * The names of the kinds of reference data of a realm, with one row for each language.
 */
final class RefKindNames {

    private RefKindNames() {
    }

    /**
     * Reads the names of many kinds in one query.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param kindIds the IDs of the kinds
     * @return the names by the ID of the kind
     */
    static Map<String, Localized<RefKindName>> of(EntityManager em, String realmId, Collection<String> kindIds) {
        return kindIds.isEmpty() ? Map.of() : names(rows(em, realmId, kindIds));
    }

    /**
     * Stores the names of a new kind.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param kindId the ID of the kind
     * @param names the names
     */
    static void insert(EntityManager em, String realmId, String kindId, Localized<RefKindName> names) {
        names.byLanguage().forEach((language, name) -> {
            RefKindNameEntity row = new RefKindNameEntity();
            row.kindId = kindId;
            row.language = language;
            row.realmId = realmId;
            row.primary = language.equals(names.primaryLanguage());
            row.name = name.name();
            em.persist(row);
        });
    }

    /**
     * Stores the names of a stored kind in place of the ones it has.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param kindId the ID of the kind
     * @param names the names
     */
    static void replace(EntityManager em, String realmId, String kindId, Localized<RefKindName> names) {
        List<RefKindNameEntity> stored = rows(em, realmId, List.of(kindId));
        if (names.equals(names(stored).get(kindId))) {
            return;
        }
        stored.forEach(em::remove);
        // A new row can have the key of an old one, and only one row of a kind is primary.
        em.flush();
        insert(em, realmId, kindId, names);
    }

    /**
     * Removes the names of a kind.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param kindId the ID of the kind
     */
    static void delete(EntityManager em, String realmId, String kindId) {
        rows(em, realmId, List.of(kindId)).forEach(em::remove);
    }

    private static Map<String, Localized<RefKindName>> names(List<RefKindNameEntity> rows) {
        Map<String, Map<String, RefKindName>> byKind = new HashMap<>();
        Map<String, String> primary = new HashMap<>();
        for (RefKindNameEntity row : rows) {
            byKind.computeIfAbsent(row.kindId, id -> new HashMap<>()).put(row.language, new RefKindName(row.name));
            if (row.primary) {
                primary.put(row.kindId, row.language);
            }
        }
        Map<String, Localized<RefKindName>> result = new HashMap<>();
        byKind.forEach((kindId, names) -> result.put(kindId, new Localized<>(primary.get(kindId), names)));
        return result;
    }

    private static List<RefKindNameEntity> rows(EntityManager em, String realmId, Collection<String> kindIds) {
        return em.createQuery("""
                        select n from RefKindNameEntity n where n.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("n.kindId", "ids")), RefKindNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(kindIds))
                .getResultList();
    }
}
