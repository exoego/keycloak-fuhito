package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.LongWrite;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.keycloak.models.jpa.entities.CredentialEntity;
import org.keycloak.models.jpa.entities.FederatedIdentityEntity;
import org.keycloak.models.jpa.entities.UserAttributeEntity;
import org.keycloak.models.jpa.entities.UserEntity;
import org.keycloak.models.jpa.entities.UserGroupMembershipEntity;
import org.keycloak.models.jpa.entities.UserRequiredActionEntity;
import org.keycloak.models.jpa.entities.UserRoleMappingEntity;

/**
 * Keeps the session of a long write small. Before each query, Hibernate looks through every entity that the session
 * holds for changes. A write that keeps every entity that it read or wrote then grows with the square of its rows.
 * So the entities of the extension leave the session after each row of the write, once their changes are in the
 * database. So do the users of Keycloak, which a row of a long write only reads. The other entities of Keycloak
 * stay.
 */
public final class JpaLongWrite implements LongWrite {

    // A version counts up once in a transaction. An entity that left the session forgets that it counted up, so it
    // gets the mark back when it is loaded again. A loaded entity knows no entity manager, so the marks belong to
    // the thread of the request.
    private static final ThreadLocal<Set<String>> COUNTED = new ThreadLocal<>();

    // A user that leaves the session is read again when Keycloak writes it, as the cache of users does anyway.
    private static final Set<Class<?>> USERS = Set.of(UserEntity.class, UserAttributeEntity.class,
            UserRequiredActionEntity.class, CredentialEntity.class, FederatedIdentityEntity.class,
            UserGroupMembershipEntity.class, UserRoleMappingEntity.class);

    private final EntityManager em;

    /**
     * Creates the long write.
     *
     * @param em the entity manager of the request
     */
    public JpaLongWrite(EntityManager em) {
        this.em = em;
    }

    @Override
    public <T> T run(Supplier<T> work) {
        COUNTED.set(new HashSet<>());
        try {
            return work.get();
        } finally {
            COUNTED.remove();
        }
    }

    @Override
    public void rowDone() {
        detach(em, JpaLongWrite::leavesAfterEachRow);
    }

    /**
     * Tells whether an entity leaves the session after each row of a long write.
     *
     * @param entity an entity of the session
     * @return true for an entity of the extension or a user of Keycloak
     */
    static boolean leavesAfterEachRow(Object entity) {
        return entity.getClass().getPackageName().equals(JpaLongWrite.class.getPackageName())
                || USERS.contains(entity.getClass());
    }

    /**
     * Sends the changes of the session to the database, and lets go of some of its entities. An entity that counted
     * up its version keeps the mark, as after {@link #rowDone}.
     *
     * @param em the entity manager of the transaction
     * @param which picks the entities to let go of
     */
    static void detach(EntityManager em, Predicate<Object> which) {
        Set<String> counted = COUNTED.get();
        em.flush();
        for (Map.Entry<Object, EntityEntry> entry : em.unwrap(SessionImplementor.class)
                .getPersistenceContextInternal().reentrantSafeEntityEntries()) {
            Object entity = entry.getKey();
            if (which.test(entity)) {
                if (counted != null && entity instanceof VersionedRow versioned && versioned.counted()) {
                    counted.add(key(entity.getClass(), entry.getValue().getId()));
                }
                em.detach(entity);
            }
        }
    }

    /**
     * Lets go of the rows of some resources before a statement counts up their versions in the database, so that no
     * row of the session keeps the old version. A resource that a write of this transaction counted up already keeps
     * its rows, as the statement leaves it out.
     *
     * @param <T> the type of the rows
     * @param em the entity manager of the transaction
     * @param type the class of the rows
     * @param resourceOf the ID of the resource of a row
     * @param resourceIds the IDs of the resources
     * @return the IDs of the resources whose rows in the session counted up already
     */
    static <T extends VersionedRow> Set<String> release(EntityManager em, Class<T> type,
            Function<T, String> resourceOf, Set<String> resourceIds) {
        Set<String> counted = new HashSet<>();
        for (Map.Entry<Object, EntityEntry> entry : em.unwrap(SessionImplementor.class)
                .getPersistenceContextInternal().reentrantSafeEntityEntries()) {
            if (type.isInstance(entry.getKey())) {
                T row = type.cast(entry.getKey());
                if (row.counted() && resourceIds.contains(resourceOf.apply(row))) {
                    counted.add(resourceOf.apply(row));
                }
            }
        }
        detach(em, entity -> type.isInstance(entity) && resourceIds.contains(resourceOf.apply(type.cast(entity)))
                && !counted.contains(resourceOf.apply(type.cast(entity))));
        return counted;
    }

    /**
     * Tells whether a long write runs, so that the marks of {@link #markCounted} are kept.
     *
     * @return true inside {@link #run}
     */
    static boolean marking() {
        return COUNTED.get() != null;
    }

    /**
     * Tells whether a write of this transaction counted up the version of an entity that left the session.
     *
     * @param type the class of the entity
     * @param id the ID of the entity
     * @return true when a write counted it up
     */
    static boolean counted(Class<?> type, String id) {
        Set<String> counted = COUNTED.get();
        return counted != null && counted.contains(key(type, id));
    }

    /**
     * Marks entities whose version a statement counted up in the database, so that a later write of this
     * transaction does not count it up again. Outside a long write, a request writes each resource once, and nothing
     * is marked.
     *
     * @param type the class of the entities
     * @param ids the IDs of the entities
     */
    static void markCounted(Class<?> type, Collection<String> ids) {
        Set<String> counted = COUNTED.get();
        if (counted != null) {
            ids.forEach(id -> counted.add(key(type, id)));
        }
    }

    private static String key(Class<?> type, Object id) {
        return type.getName() + "\u0000" + id;
    }
}
