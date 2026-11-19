package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;

/**
 * The names of the versions of org units, with one row for each language.
 */
final class OrgUnitNames {

    /**
     * The join of the primary row of the version {@code v} as {@code p}, for the order of siblings.
     */
    static final String JOIN_PRIMARY = " join OrgUnitVersionNameEntity p on p.versionId = v.id and p.primary = true";

    private OrgUnitNames() {
    }

    /**
     * Reads the names of many versions in one query.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param versionIds the IDs of the versions
     * @return the names by the ID of the version
     */
    static Map<String, Localized<OrgUnitName>> of(EntityManager em, String realmId, Collection<String> versionIds) {
        return versionIds.isEmpty() ? Map.of() : names(rows(em, realmId, versionIds));
    }

    /**
     * Stores the names of a new version.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param versionId the ID of the version
     * @param names the names
     */
    static void insert(EntityManager em, String realmId, String versionId, Localized<OrgUnitName> names) {
        names.byLanguage().forEach((language, name) -> {
            OrgUnitVersionNameEntity row = new OrgUnitVersionNameEntity();
            row.versionId = versionId;
            row.language = language;
            row.realmId = realmId;
            row.primary = language.equals(names.primaryLanguage());
            row.name = name.name();
            row.shortName = name.shortName();
            row.phoneticName = name.phoneticName();
            em.persist(row);
        });
    }

    /**
     * Stores the names of a stored version in place of the ones it has.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param versionId the ID of the version
     * @param names the names
     */
    static void replace(EntityManager em, String realmId, String versionId, Localized<OrgUnitName> names) {
        List<OrgUnitVersionNameEntity> stored = rows(em, realmId, List.of(versionId));
        if (names.equals(names(stored).get(versionId))) {
            return;
        }
        stored.forEach(em::remove);
        // A new row can have the key of an old one, and only one row of a version is primary.
        em.flush();
        insert(em, realmId, versionId, names);
    }

    private static Map<String, Localized<OrgUnitName>> names(List<OrgUnitVersionNameEntity> rows) {
        Map<String, Map<String, OrgUnitName>> byVersion = new HashMap<>();
        Map<String, String> primary = new HashMap<>();
        for (OrgUnitVersionNameEntity row : rows) {
            byVersion.computeIfAbsent(row.versionId, id -> new HashMap<>())
                    .put(row.language, new OrgUnitName(row.name, row.shortName, row.phoneticName));
            if (row.primary) {
                primary.put(row.versionId, row.language);
            }
        }
        Map<String, Localized<OrgUnitName>> result = new HashMap<>();
        byVersion.forEach((versionId, names) -> result.put(versionId,
                new Localized<>(primary.get(versionId), names)));
        return result;
    }

    private static List<OrgUnitVersionNameEntity> rows(EntityManager em, String realmId,
            Collection<String> versionIds) {
        return em.createQuery("""
                        select n from OrgUnitVersionNameEntity n where n.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("n.versionId", "ids")), OrgUnitVersionNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(versionIds))
                .getResultList();
    }
}
