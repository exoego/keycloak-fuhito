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
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

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
     * Lists the org units with the given IDs.
     *
     * @param ids the IDs
     * @return the units that exist in the realm, in no order
     */
    List<OrgUnit> findUnits(Collection<String> ids);

    /**
     * Finds the org unit that has a code on a date.
     *
     * @param code the code
     * @param date the date
     * @return the unit, or empty when no unit has the code on the date
     */
    Optional<OrgUnit> findUnitByCode(String code, LocalDate date);

    /**
     * Lists the details of every org unit that have one of some codes, over all time.
     *
     * @param codes the codes
     * @return the details, oldest first
     */
    List<OrgUnitVersion> versionsWithCodes(Collection<String> codes);

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
     * Siblings follow the display order, then the primary phonetic name, then the primary name, then the ID.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param rootId the ID of the unit to start from
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date, int first, int max);

    /**
     * Counts the units that {@link #treeOn} lists on all pages together.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param rootId the ID of the unit to start from
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param date the date
     * @return the number of units
     */
    long countTreeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date);

    /**
     * Lists a page of the direct children of a unit on a date, in the order of siblings.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param parentId the ID of the parent
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param date the date
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date, int first,
            int max);

    /**
     * Counts the units that {@link #childrenOn} lists on all pages together.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param parentId the ID of the parent
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param date the date
     * @return the number of children
     */
    long countChildrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date);

    /**
     * The roots that a search of org units keeps.
     */
    enum Roots {
        /**
         * The roots of the default hierarchy. These are the units with no parent in it, but not the rollup units,
         * which are not in it.
         */
        DEFAULT,
        /**
         * The units with no parent and with a child in the hierarchy. These are the roots of a hierarchy other than
         * the default one.
         */
        LINKED
    }

    /**
     * What a search of org units keeps. A value that is null keeps everything.
     *
     * @param text a part of a name, a short name, or a phonetic name in any language, with no regard to case
     * @param code the code
     * @param unitTypeId the ID of the unit type
     * @param lateral true for lateral units only, false for line units only
     * @param rollup true for rollup units only, false for the other units
     * @param legalEntity true for legal entities only, false for the other units
     * @param roots the roots to keep in the hierarchy of the search, or null for every unit
     */
    record Search(String text, String code, String unitTypeId, Boolean lateral, Boolean rollup, Boolean legalEntity,
            Roots roots) {
    }

    /**
     * Lists a page of the org units that match a search on a date, in the order of siblings.
     * Siblings follow the display order, then the primary phonetic name, then the primary name, then the ID.
     *
     * @param hierarchyId the ID of the hierarchy whose parents the units come with, and whose roots the search keeps
     * @param search what to keep
     * @param date the date
     * @param after the details on the date of the unit after which the page starts, or null
     * @param first the number of units to skip
     * @param max the largest number of units to return
     * @return the units of the page
     */
    List<PlacedOrgUnit> searchOn(String hierarchyId, Search search, LocalDate date, OrgUnitVersion after, int first,
            int max);

    /**
     * Counts the org units that match a search on a date.
     *
     * @param hierarchyId the ID of the hierarchy whose roots the search keeps
     * @param search what to keep
     * @param date the date
     * @return the number of units
     */
    long countSearchOn(String hierarchyId, Search search, LocalDate date);

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
     * Counts the roles over an org unit that hold on a date or after it.
     *
     * @param orgUnitId the ID of the org unit
     * @param date the date
     * @return the number of roles
     */
    long unitRolesHoldingFrom(String orgUnitId, LocalDate date);

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
     * Lists the units from the root down to each of some units on a date. The database walks upward from the
     * units, so the whole tree is not loaded.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param ids the IDs of the units
     * @param date the date
     * @return the path of each unit, with the root first and the unit last. A unit that does not exist on the date
     *         has none.
     */
    Map<String, List<PlacedOrgUnit>> pathsOn(String hierarchyId, Collection<String> ids, LocalDate date);

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
     * Finds the work location and the cost center that apply to units on a date. Each comes from the unit, or
     * else from the nearest unit above it in the default hierarchy. The database walks upward, so the whole tree
     * is not loaded.
     *
     * @param ids the IDs of the units
     * @param date the date
     * @return the values, by the ID of the unit. A unit with neither has no entry.
     */
    Map<String, UnitDefaults> defaultsOn(Collection<String> ids, LocalDate date);

    /**
     * Returns the lives of some units, from the first date of the first version to the end of the last one.
     * The versions of a unit have no gaps.
     *
     * @param ids the IDs of the units
     * @return the life of each unit that has versions, by its ID
     */
    Map<String, ValidPeriod> livesOf(Collection<String> ids);

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
     * Stores new org units.
     *
     * @param units the units
     */
    void insertUnits(List<OrgUnit> units);

    /**
     * Lists all versions of an org unit, oldest first.
     *
     * @param orgUnitId the ID of the unit
     * @return the versions
     */
    List<OrgUnitVersion> versions(String orgUnitId);

    /**
     * Lists all versions of some org units, oldest first for each unit.
     *
     * @param orgUnitIds the IDs of the units
     * @return the versions of each unit that has some, by the ID of the unit
     */
    Map<String, List<OrgUnitVersion>> versionsOf(Collection<String> orgUnitIds);

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
     * Lists all parent links of some org units in a hierarchy, oldest first for each unit.
     *
     * @param hierarchyId the ID of the hierarchy
     * @param childIds the IDs of the units
     * @return the links of each unit that has some, by the ID of the unit
     */
    Map<String, List<OrgUnitLink>> parentLinksOf(String hierarchyId, Collection<String> childIds);

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
