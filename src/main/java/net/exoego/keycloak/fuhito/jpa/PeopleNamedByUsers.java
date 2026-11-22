package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.DerivedValue;

/**
 * The people whose record has no names. The read of such a person gives the first name and the last name of its
 * Keycloak user, joined in the order of the realm. This reads the users from the tables of Keycloak, which do not
 * hold a user that only a user storage provider has.
 */
public final class PeopleNamedByUsers {

    // The characters that String.strip() takes off both ends of a part of the name.
    private static final String SPACES = IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

    // A read with one part, or with the same part twice, stays the same in either order.
    private static final String REORDERED = """
            select s.ID from (
              select p.ID, btrim(u.FIRST_NAME, :spaces) as GIVEN, btrim(u.LAST_NAME, :spaces) as FAMILY
              from FUHITO_PERSON p join USER_ENTITY u on u.ID = p.USER_ID
              where p.REALM_ID = :realmId and u.REALM_ID = :realmId
              and not exists (select 1 from FUHITO_PERSON_NAME n where n.PERSON_ID = p.ID)) s
            where s.GIVEN <> '' and s.FAMILY <> '' and s.GIVEN <> s.FAMILY""";

    private PeopleNamedByUsers() {
    }

    /**
     * Counts up the version of each person whose read changes when the order of the first name and the last name
     * changes, and adds a propagated change of each. A few statements do it for the whole realm. The caller locked
     * the realm alone.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param recordedAt when the order changed
     * @param cause what changed the order
     * @return the number of people
     */
    public static int reorder(EntityManager em, String realmId, Instant recordedAt, ChangeLogEntry.Cause cause) {
        // The session must not keep a copy of a row that the statements change.
        JpaLongWrite.detach(em, PersonEntity.class::isInstance);
        long last = new JpaChangeLogStore(em, realmId).appendPropagated(recordedAt, DerivedValue.PERSON_NAME, cause,
                REORDERED, Map.of("spaces", SPACES));
        // The count-up takes the people from the new entries. So both have the same people, also when Keycloak
        // changed the name of a user in between.
        return em.createQuery("""
                        update PersonEntity p set p.version = p.version + 1
                        where p.realmId = :realmId and p.id in (select l.resourceId from ChangeLogEntity l
                        where l.realmId = :realmId and l.sequence > :last)""")
                .setParameter("realmId", realmId)
                .setParameter("last", last)
                .executeUpdate();
    }
}
