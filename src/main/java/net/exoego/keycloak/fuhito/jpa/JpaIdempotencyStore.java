package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.IdempotencyStore;

/**
 * Stores the responses to requests with an idempotency key in the database of Keycloak.
 */
public class JpaIdempotencyStore implements IdempotencyStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaIdempotencyStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<Stored> find(String key, String path, Instant since) {
        return em.createQuery("""
                        select k from IdempotencyKeyEntity k where k.realmId = :realmId and k.key = :key
                        and k.path = :path and k.createdAt >= :since""", IdempotencyKeyEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("key", key)
                .setParameter("path", path)
                .setParameter("since", since)
                .getResultStream()
                .findFirst()
                .map(k -> new Stored(k.id, k.requestHash, k.status, k.body, k.location));
    }

    @Override
    public String claim(String key, String path, String requestHash, Instant now, Instant since) {
        em.createQuery("delete from IdempotencyKeyEntity k where k.realmId = :realmId and k.createdAt < :since")
                .setParameter("realmId", realmId)
                .setParameter("since", since)
                .executeUpdate();
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.id = UUID.randomUUID().toString();
        entity.realmId = realmId;
        entity.key = key;
        entity.path = path;
        entity.requestHash = requestHash;
        entity.createdAt = now;
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            // The unique key of the table says that another request took the key first.
            throw FuhitoException.conflict("idempotency.in_progress", Map.of()).causedBy(e);
        }
        return entity.id;
    }

    @Override
    public void complete(String id, int status, String body, String location) {
        IdempotencyKeyEntity entity = Rows.require(em, IdempotencyKeyEntity.class, id, realmId, found -> found.realmId);
        entity.status = status;
        entity.body = body;
        entity.location = location;
        em.flush();
    }
}
