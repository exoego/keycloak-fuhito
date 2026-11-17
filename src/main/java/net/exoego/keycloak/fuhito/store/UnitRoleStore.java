package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.UnitRole;

/**
 * Storage of the roles over org units of one realm.
 */
public interface UnitRoleStore {

    /**
     * Finds a role by ID.
     *
     * @param id the ID
     * @return the role, or empty when the realm has none with the ID
     */
    Optional<UnitRole> find(String id);

    /**
     * Lists the roles of an employment over all time, oldest first.
     *
     * @param employmentId the ID of the employment
     * @return the roles
     */
    List<UnitRole> findByEmployment(String employmentId);

    /**
     * Lists a page of the roles over an org unit on a date. These are the roles over the unit, and the roles over
     * a unit above that cover the units below. The nearest unit comes first, then the code of the role.
     *
     * @param hierarchyId the ID of the default hierarchy
     * @param orgUnitId the ID of the org unit
     * @param roleId the ID of the role to keep, or null for every role
     * @param nearest true to keep, for each role, only the holders of the nearest unit
     * @param date the date
     * @param first the number of roles to skip
     * @param max the largest number of roles to return
     * @return the roles of the page
     */
    List<UnitRole> holdersOn(String hierarchyId, String orgUnitId, String roleId, boolean nearest, LocalDate date,
            int first, int max);

    /**
     * Lists a page of the roles of a person on a date, in the order of the start, then the ID.
     *
     * @param personId the ID of the person
     * @param date the date
     * @param first the number of roles to skip
     * @param max the largest number of roles to return
     * @return the roles of the page
     */
    List<UnitRole> ofPersonOn(String personId, LocalDate date, int first, int max);

    /**
     * Stores a new role.
     *
     * @param role the role
     */
    void insert(UnitRole role);

    /**
     * What a list of the roles of a date across the realm keeps. A value that is null keeps everything.
     *
     * @param roleId the ID of the role
     * @param orgUnitId the ID of the org unit that the role is over. A unit above does not count.
     * @param date the date on which the roles hold
     */
    record Search(String roleId, String orgUnitId, LocalDate date) {
    }

    /**
     * Lists a page of the roles that match a search, ordered by ID.
     *
     * @param search what to keep
     * @param afterId the ID after which the page starts, or null for the start
     * @param first the number of roles to skip
     * @param max the largest number of roles to return
     * @return the roles of the page
     */
    List<UnitRole> searchOn(Search search, String afterId, int first, int max);

    /**
     * Counts the roles that match a search.
     *
     * @param search what to keep
     * @return the number of roles
     */
    long countSearchOn(Search search);

    /**
     * Stores the new period of a role.
     *
     * @param role the role with its new period
     */
    void update(UnitRole role);

    /**
     * Deletes a role.
     *
     * @param id the ID
     */
    void delete(String id);
}
