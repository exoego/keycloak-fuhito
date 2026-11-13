package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;

/**
 * Storage of the org units of one realm.
 */
public interface OrgUnitStore {

    /**
     * Returns the ID of the default hierarchy of the realm. Creates the hierarchy when it does not exist yet.
     *
     * @return the ID
     */
    String defaultHierarchyId();

    /**
     * Finds an org unit by ID.
     *
     * @param id the ID
     * @return the unit, or empty when the realm has no such unit
     */
    Optional<OrgUnit> findUnit(String id);

    /**
     * Finds an org unit by code.
     *
     * @param code the code
     * @return the unit, or empty when the realm has no such unit
     */
    Optional<OrgUnit> findUnitByCode(String code);

    /**
     * Stores a new org unit.
     *
     * @param unit the unit
     */
    void insertUnit(OrgUnit unit);

    /**
     * Lists all versions of an org unit, oldest first.
     *
     * @param orgUnitId the ID of the unit
     * @return the versions
     */
    List<OrgUnitVersion> versions(String orgUnitId);

    /**
     * Stores a new version.
     *
     * @param version the version
     */
    void insertVersion(OrgUnitVersion version);

    /**
     * Stores the new state of an existing version.
     *
     * @param version the version
     */
    void updateVersion(OrgUnitVersion version);

    /**
     * Lists all parent links of an org unit in a hierarchy, oldest first.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param childId the ID of the unit
     * @return the links
     */
    List<OrgUnitLink> parentLinks(String hierarchyId, String childId);

    /**
     * Stores a new parent link.
     *
     * @param link the link
     */
    void insertLink(OrgUnitLink link);

    /**
     * Stores the new state of an existing parent link.
     *
     * @param link the link
     */
    void updateLink(OrgUnitLink link);

    /**
     * Removes a parent link.
     *
     * @param id the ID of the link
     */
    void deleteLink(String id);
}
