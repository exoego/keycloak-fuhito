package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Stores the org units of one realm in the database of Keycloak.
 */
public class JpaOrgUnitStore implements OrgUnitStore {

    /**
     * The code of the hierarchy that every realm has.
     */
    public static final String DEFAULT_HIERARCHY_CODE = "default";

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaOrgUnitStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public String defaultHierarchyId() {
        return em.createQuery("select h.id from HierarchyEntity h where h.realmId = :realmId and h.code = :code",
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("code", DEFAULT_HIERARCHY_CODE)
                .getResultStream()
                .findFirst()
                .orElseGet(this::createDefaultHierarchy);
    }

    private String createDefaultHierarchy() {
        HierarchyEntity entity = new HierarchyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setRealmId(realmId);
        entity.setCode(DEFAULT_HIERARCHY_CODE);
        entity.setName("Default");
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        OrgUnitEntity entity = em.find(OrgUnitEntity.class, id);
        // The realm filter keeps one realm from reaching the rows of another by ID.
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(toDomain(entity)) : Optional.empty();
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code) {
        return em.createQuery("select u from OrgUnitEntity u where u.realmId = :realmId and u.code = :code",
                        OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .map(JpaOrgUnitStore::toDomain);
    }

    @Override
    public void insertUnit(OrgUnit unit) {
        OrgUnitEntity entity = new OrgUnitEntity();
        entity.id = unit.id();
        entity.realmId = realmId;
        entity.code = unit.code();
        entity.externalId = unit.externalId();
        entity.source = unit.source();
        entity.keycloakOrganizationId = unit.keycloakOrganizationId();
        em.persist(entity);
    }

    @Override
    public List<OrgUnitVersion> versions(String orgUnitId) {
        return em.createQuery("select v from OrgUnitVersionEntity v where v.realmId = :realmId"
                        + " and v.orgUnitId = :orgUnitId order by v.validFrom", OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public void insertVersion(OrgUnitVersion version) {
        OrgUnitVersionEntity entity = new OrgUnitVersionEntity();
        entity.id = version.id();
        entity.realmId = realmId;
        entity.orgUnitId = version.orgUnitId();
        entity.validFrom = version.period().from();
        entity.validTo = version.period().to();
        entity.name = version.name();
        entity.names = version.names();
        entity.shortName = version.shortName();
        entity.shortNames = version.shortNames();
        entity.phoneticName = version.phoneticName();
        entity.unitTypeId = version.unitTypeId();
        entity.displayOrder = version.displayOrder();
        entity.attributes = version.attributes();
        em.persist(entity);
    }

    @Override
    public List<OrgUnitLink> parentLinks(String hierarchyId, String childId) {
        return em.createQuery("select l from OrgUnitLinkEntity l where l.realmId = :realmId"
                        + " and l.hierarchyId = :hierarchyId and l.childId = :childId order by l.validFrom",
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("childId", childId)
                .getResultStream()
                .map(e -> new OrgUnitLink(e.id, e.hierarchyId, e.childId, e.parentId,
                        new ValidPeriod(e.validFrom, e.validTo)))
                .toList();
    }

    @Override
    public void insertLink(OrgUnitLink link) {
        OrgUnitLinkEntity entity = new OrgUnitLinkEntity();
        entity.id = link.id();
        entity.realmId = realmId;
        entity.hierarchyId = link.hierarchyId();
        entity.childId = link.childId();
        entity.parentId = link.parentId();
        entity.validFrom = link.period().from();
        entity.validTo = link.period().to();
        em.persist(entity);
    }

    private static OrgUnit toDomain(OrgUnitEntity e) {
        return new OrgUnit(e.id, e.code, e.externalId, e.source, e.keycloakOrganizationId);
    }

    private static OrgUnitVersion toDomain(OrgUnitVersionEntity e) {
        return new OrgUnitVersion(e.id, e.orgUnitId, new ValidPeriod(e.validFrom, e.validTo), e.name, e.names,
                e.shortName, e.shortNames, e.phoneticName, e.unitTypeId, e.displayOrder, e.attributes);
    }
}
