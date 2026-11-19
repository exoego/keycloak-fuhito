package net.exoego.keycloak.fuhito.jpa;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        Set<String> counted = COUNTED.get();
        em.flush();
        for (Map.Entry<Object, EntityEntry> entry : em.unwrap(SessionImplementor.class)
                .getPersistenceContextInternal().reentrantSafeEntityEntries()) {
            Object entity = entry.getKey();
            if (entity.getClass().getPackageName().equals(JpaLongWrite.class.getPackageName())
                    || USERS.contains(entity.getClass())) {
                if (entity instanceof VersionedRow versioned && versioned.counted()) {
                    counted.add(key(entity.getClass(), entry.getValue().getId()));
                }
                em.detach(entity);
            }
        }
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

    private static String key(Class<?> type, Object id) {
        return type.getName() + "\u0000" + id;
    }
}
