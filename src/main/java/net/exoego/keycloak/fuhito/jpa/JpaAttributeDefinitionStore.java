package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;

/**
 * Stores the definitions of free attributes of one realm in the database of Keycloak.
 */
public class JpaAttributeDefinitionStore implements AttributeDefinitionStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaAttributeDefinitionStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public List<AttributeDefinition> list(AttributeDefinition.Target target) {
        return em.createQuery("""
                        select d from AttributeDefinitionEntity d where d.realmId = :realmId
                        and (d.target = :target or :all = true) order by d.target, d.name""",
                        AttributeDefinitionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("target", target == null ? "" : target.name())
                .setParameter("all", target == null)
                .getResultStream()
                .map(JpaAttributeDefinitionStore::toDomain)
                .toList();
    }

    @Override
    public Optional<AttributeDefinition> find(String id) {
        AttributeDefinitionEntity entity = em.find(AttributeDefinitionEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(toDomain(entity)) : Optional.empty();
    }

    @Override
    public void insert(AttributeDefinition definition) {
        AttributeDefinitionEntity entity = new AttributeDefinitionEntity();
        entity.id = definition.id();
        entity.realmId = realmId;
        entity.target = definition.target().name();
        entity.name = definition.name();
        copy(definition, entity);
        em.persist(entity);
        em.flush();
    }

    @Override
    public void update(AttributeDefinition definition) {
        copy(definition, Rows.require(em, AttributeDefinitionEntity.class, definition.id(), realmId,
                found -> found.realmId));
        em.flush();
    }

    @Override
    public void delete(String id) {
        AttributeDefinitionEntity entity = em.find(AttributeDefinitionEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
        }
    }

    private static void copy(AttributeDefinition definition, AttributeDefinitionEntity entity) {
        entity.type = definition.type().name();
        entity.refKind = definition.refKind();
        entity.multiple = definition.multiple();
        entity.required = definition.required();
    }

    static AttributeDefinition toDomain(AttributeDefinitionEntity e) {
        return new AttributeDefinition(e.id, AttributeDefinition.Target.valueOf(e.target), e.name,
                AttributeDefinition.Type.valueOf(e.type), e.refKind, e.multiple, e.required);
    }
}
