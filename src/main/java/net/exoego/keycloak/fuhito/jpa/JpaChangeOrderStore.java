package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;

/**
 * Stores the change orders of one realm in the database of Keycloak.
 */
public class JpaChangeOrderStore implements ChangeOrderStore {

    private static final LocalDate FIRST_DATE = LocalDate.of(1, 1, 1);
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaChangeOrderStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<ChangeOrder> find(String id) {
        return findEntity(id).map(JpaChangeOrderStore::toDomain);
    }

    @Override
    public Optional<ChangeOrder> findByNumber(String number) {
        return em.createQuery("select o from ChangeOrderEntity o where o.realmId = :realmId and o.number = :number",
                        ChangeOrderEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("number", number)
                .getResultStream()
                .findFirst()
                .map(JpaChangeOrderStore::toDomain);
    }

    @Override
    public List<ChangeOrder> list(LocalDate from, LocalDate to) {
        return em.createQuery("select o from ChangeOrderEntity o where o.realmId = :realmId"
                        + " and o.effectiveDate >= :from and o.effectiveDate < :to"
                        + " order by o.effectiveDate desc, o.createdAt desc, o.id", ChangeOrderEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("from", from == null ? FIRST_DATE : from)
                .setParameter("to", to == null ? LAST_DATE : to)
                .getResultStream()
                .map(JpaChangeOrderStore::toDomain)
                .toList();
    }

    @Override
    public void insert(ChangeOrder order) {
        ChangeOrderEntity entity = new ChangeOrderEntity();
        entity.id = order.id();
        entity.realmId = realmId;
        entity.createdAt = order.createdAt();
        copy(order, entity);
        em.persist(entity);
    }

    @Override
    public void update(ChangeOrder order) {
        copy(order, findEntity(order.id()).orElseThrow());
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(em::remove);
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<ChangeOrderEntity> findEntity(String id) {
        ChangeOrderEntity entity = em.find(ChangeOrderEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copy(ChangeOrder order, ChangeOrderEntity entity) {
        entity.number = order.number();
        entity.kind = order.kind();
        entity.title = order.title();
        entity.note = order.note();
        entity.orderDate = order.orderDate();
        entity.effectiveDate = order.effectiveDate();
        entity.approverUserId = order.approverUserId();
        entity.requesterUserId = order.requesterUserId();
        entity.externalReference = order.externalReference();
    }

    private static ChangeOrder toDomain(ChangeOrderEntity e) {
        return new ChangeOrder(e.id, e.number, e.kind, e.title, e.note, e.orderDate, e.effectiveDate,
                e.approverUserId, e.requesterUserId, e.externalReference, e.createdAt);
    }
}
