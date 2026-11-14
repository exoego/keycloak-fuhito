package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;

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
     * Finds an org unit by the ID it has in the system it came from.
     *
     * @param source the name of that system
     * @param externalId the ID in that system
     * @return the unit, or empty when the realm has no such unit
     */
    Optional<OrgUnit> findUnitByExternalId(String source, String externalId);

    /**
     * Finds the org unit that is linked to a Keycloak organization.
     *
     * @param organizationId the ID of the organization
     * @return the unit, or empty when no unit is linked to it
     */
    Optional<OrgUnit> findUnitByOrganizationId(String organizationId);

    /**
     * Stores the new state of an existing org unit.
     *
     * @param unit the unit
     */
    void updateUnit(OrgUnit unit);

    /**
     * Lists all org units of the realm.
     *
     * @return the units
     */
    List<OrgUnit> units();

    /**
     * Lists the versions of all org units that are valid on a date.
     *
     * @param date the date
     * @return one version for each unit that exists on the date
     */
    List<OrgUnitVersion> versionsOn(LocalDate date);

    /**
     * Lists the parent links of a hierarchy that are valid on a date.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param date the date
     * @return one link for each unit that has a parent on the date
     */
    List<OrgUnitLink> linksOn(String hierarchyId, LocalDate date);

    /**
     * Lists a page of the units of a hierarchy on a date, each parent before its children.
     * Siblings follow the display order, then the phonetic name, then the name, then the ID.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param rootId the ID of the unit to start from, or null for every root
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, LocalDate date, int first, int max);

    /**
     * Lists a page of the direct children of a unit on a date, in the order of siblings.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param parentId the ID of the parent, or null for the units with no parent
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, LocalDate date, int first, int max);

    /**
     * Returns some units as they are on a date. A unit that does not exist on the date is left out.
     *
     * @param hierarchyId the ID of the hierarchy that the parents come from
     * @param ids the IDs of the units
     * @param date the date
     * @return the units, in no order
     */
    List<PlacedOrgUnit> placedOn(String hierarchyId, Collection<String> ids, LocalDate date);

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
     * Lists all links to the children of an org unit, in every hierarchy.
     *
     * @param parentId the ID of the unit
     * @return the links
     */
    List<OrgUnitLink> childLinks(String parentId);

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
