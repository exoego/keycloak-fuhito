package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;

/**
 * Reads some attributes of all users of a realm in one query.
 * The API of Keycloak loads one user at a time, with several queries for each.
 * This read uses the tables of Keycloak for the users in its own database. A user that only a user storage
 * provider holds is not in them.
 */
public final class UserAttributeValues {

    private UserAttributeValues() {
    }

    /**
     * The first value of each attribute of each user. Service accounts are left out.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param names the names of the attributes to read
     * @return the values by the name of the attribute, by the ID of the user. A user with none is left out.
     */
    public static Map<String, Map<String, String>> ofRealm(EntityManager em, String realmId,
            Collection<String> names) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        em.createQuery("""
                        select a.user.id, a.name, a.value from UserAttributeEntity a
                        where a.user.realmId = :realmId and a.user.serviceAccountClientLink is null
                        and a.name in :names order by a.user.id, a.id""", Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("names", names)
                .getResultStream()
                .forEach(row -> result.computeIfAbsent((String) row[0], id -> new LinkedHashMap<>())
                        .putIfAbsent((String) row[1], (String) row[2]));
        return result;
    }
}
