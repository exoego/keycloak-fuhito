package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
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
     * Returns the code of every hierarchy of the realm by its ID.
     *
     * @return the codes by ID
     */
    Map<String, String> hierarchyCodes();

    /**
     * Finds an org unit by ID.
     *
     * @param id the ID
     * @return the unit, or empty when the realm has no such unit
     */
    Optional<OrgUnit> findUnit(String id);

    /**
     * Finds the org unit that has a code on a date.
     *
     * @param code the code
     * @param date the date
     * @return the unit, or empty when no unit has the code on the date
     */
    Optional<OrgUnit> findUnitByCode(String code, LocalDate date);

    /**
     * Lists the details of every org unit that have a code, over all time.
     *
     * @param code the code
     * @return the details, oldest first
     */
    List<OrgUnitVersion> versionsWithCode(String code);

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
     * Lists a page of a unit and everything below it on a date, each parent before its children.
     * Siblings follow the display order, then the phonetic name, then the name, then the ID.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param rootId the ID of the unit to start from
     * @param group true for groups only, false for line units only, or null for both
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean group, LocalDate date, int first, int max);

    /**
     * Lists a page of the direct children of a unit on a date, in the order of siblings.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param parentId the ID of the parent, or null for the units with no parent
     * @param linkedOnly true to take only roots that have a child in the hierarchy. It has no effect with a
     *        {@code parentId}.
     * @param group true for groups only, false for line units only, or null for both
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, boolean linkedOnly, Boolean group,
            LocalDate date, int first, int max);

    /**
     * Lists a page of the units that are legal entities on a date. A unit is one when its unit type says so.
     * The order is the display order, then the phonetic name, then the name, then the ID.
     *
     * @param hierarchyId the ID of the hierarchy that the parents come from
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> legalEntitiesOn(String hierarchyId, LocalDate date, int first, int max);

    /**
     * Counts the employments that name an org unit as their employer and start in a period.
     *
     * @param employerId the ID of the org unit
     * @param from the first date of the period
     * @param to the first date after the period, or null for no end
     * @return the number of employments
     */
    long employmentsStartingIn(String employerId, LocalDate from, LocalDate to);

    /**
     * Counts the employments that name an org unit as their employer and still hold on a date or after it.
     *
     * @param employerId the ID of the org unit
     * @param date the date
     * @return the number of employments
     */
    long employmentsHoldingFrom(String employerId, LocalDate date);

    /**
     * Returns, for each of some units, the unit itself and every unit above it on a date.
     * The database walks upward, so the whole tree is not loaded.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param ids the IDs of the units
     * @param date the date
     * @return the IDs of the unit and of its ancestors, by the ID of the unit. A unit that does not exist on the
     *         date has no entry.
     */
    Map<String, Set<String>> ancestorsOn(String hierarchyId, Collection<String> ids, LocalDate date);

    /**
     * Lists the units from the root down to a unit on a date. The database walks upward from the unit, so the
     * whole tree is not loaded.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param id the ID of the unit
     * @param date the date
     * @return the path, with the root first and the unit last. It is empty when the unit does not exist on the
     *         date.
     */
    List<PlacedOrgUnit> pathOn(String hierarchyId, String id, LocalDate date);

    /**
     * Lists the units that set a time zone or a day start of their own. Most realms have none.
     *
     * @return the units
     */
    List<OrgUnit> unitsWithOwnTime();

    /**
     * Lists the ID of a unit and the IDs of the units above it on a date, nearest first.
     * It follows the parent links only, so it also works for a unit that has no details on the date.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param id the ID of the unit
     * @param date the date
     * @return the IDs, with the unit itself first. It is empty when the realm has no such unit.
     */
    List<String> lineOn(String hierarchyId, String id, LocalDate date);

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
     * Lists all parent links of an org unit in every hierarchy, oldest first.
     *
     * @param childId the ID of the unit
     * @return the links
     */
    List<OrgUnitLink> parentLinks(String childId);

    /**
     * Lists the links of a hierarchy to the children of a unit, over all time.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param parentId the ID of the parent
     * @return the links, oldest first
     */
    List<OrgUnitLink> childLinks(String hierarchyId, String parentId);

    /**
     * Counts the units that have a unit as their parent on a date or after it, in any hierarchy.
     *
     * @param parentId the ID of the parent
     * @param date the date
     * @return the number of children
     */
    long childrenHoldingFrom(String parentId, LocalDate date);

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

    /**
     * Stores that one org unit follows another.
     *
     * @param succession the link
     */
    void insertSuccession(OrgUnitSuccession succession);

    /**
     * Lists the links of an org unit to the units before it and after it, oldest first.
     *
     * @param orgUnitId the ID of the org unit
     * @return the links where the unit is the predecessor or the successor
     */
    List<OrgUnitSuccession> successions(String orgUnitId);
}
