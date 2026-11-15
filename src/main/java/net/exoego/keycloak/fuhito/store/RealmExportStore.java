package net.exoego.keycloak.fuhito.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;

/**
 * Reads every stored row of one realm page by page, for an export, and writes what the other stores cannot.
 * Each list is ordered by ID, and a page starts after an ID.
 */
public interface RealmExportStore {

    /**
     * A page of the rows of one list, by ID.
     *
     * @param <T> the type of the rows
     */
    @FunctionalInterface
    interface Page<T> {

        /**
         * Reads a page.
         *
         * @param afterId the ID after which the page starts, or null for the start
         * @param max the largest number of rows
         * @return the rows
         */
        List<T> read(String afterId, int max);
    }

    /**
     * Pages through the kinds of reference data that the realm added.
     *
     * @return the pages
     */
    Page<RealmKind> refKinds();

    /**
     * Finds an ID that a row of the database has already, in any realm.
     *
     * @param list the list of an export, or a part of one such as {@code org-unit-versions}
     * @param ids the IDs of the rows of the list
     * @return one of the IDs that is taken, or empty when none is
     */
    Optional<String> takenId(String list, Collection<String> ids);

    /**
     * Pages through the reference items.
     *
     * @return the pages
     */
    Page<RefItem> refItems();

    /**
     * Pages through the definitions of free attributes.
     *
     * @return the pages
     */
    Page<AttributeDefinition> attributeDefinitions();

    /**
     * Pages through the hierarchies other than the default one.
     *
     * @return the pages
     */
    Page<Hierarchy> hierarchies();

    /**
     * Pages through the org units.
     *
     * @return the pages
     */
    Page<OrgUnit> orgUnits();

    /**
     * Lists every period of the details of some org units.
     *
     * @param orgUnitIds the IDs
     * @return the periods, by unit and then oldest first
     */
    List<OrgUnitVersion> versionsOf(Collection<String> orgUnitIds);

    /**
     * Lists every parent link of some org units, in every hierarchy.
     *
     * @param orgUnitIds the IDs of the children
     * @return the links
     */
    List<OrgUnitLink> parentLinksOf(Collection<String> orgUnitIds);

    /**
     * Lists the successions in which some org units are the predecessor.
     *
     * @param orgUnitIds the IDs of the predecessors
     * @return the successions
     */
    List<OrgUnitSuccession> successionsOf(Collection<String> orgUnitIds);

    /**
     * Pages through the people.
     *
     * @return the pages
     */
    Page<Person> people();

    /**
     * Returns the times of the masks of some people.
     *
     * @param personIds the IDs
     * @return the time for each person who was masked
     */
    Map<String, Instant> maskedAt(Collection<String> personIds);

    /**
     * Pages through the employments.
     *
     * @return the pages
     */
    Page<Employment> employments();

    /**
     * Lists every set of terms of some employments.
     *
     * @param employmentIds the IDs
     * @return the terms, by employment and then oldest first
     */
    List<EmploymentTerms> termsOf(Collection<String> employmentIds);

    /**
     * Pages through the IDs of the assignments.
     *
     * @return the pages
     */
    Page<String> assignmentIds();

    /**
     * Lists every row of some assignments.
     *
     * @param assignmentIds the IDs of the assignments
     * @return the rows, by assignment and then oldest first
     */
    List<Assignment> rowsOf(Collection<String> assignmentIds);

    /**
     * Pages through the reporting lines.
     *
     * @return the pages
     */
    Page<ReportingLine> reportingLines();

    /**
     * Pages through the roles over org units.
     *
     * @return the pages
     */
    Page<UnitRole> unitRoles();

    /**
     * Pages through the qualifications.
     *
     * @return the pages
     */
    Page<Qualification> qualifications();

    /**
     * Pages through the IDs that other systems have for resources.
     *
     * @return the pages
     */
    Page<ExternalId> externalIds();

    /**
     * Pages through the change orders.
     *
     * @return the pages
     */
    Page<ChangeOrder> changeOrders();

    /**
     * Tells whether the realm has no data other than the default hierarchy.
     *
     * @return true when a restore may write into it
     */
    boolean isEmpty();

    /**
     * Returns the Keycloak organizations that the org units of the realm are linked to.
     *
     * @return the IDs of the organizations
     */
    List<String> linkedOrganizations();

    /**
     * Deletes every row of the realm that an export has, before a restore that replaces them.
     * The change log, the keys of repeated requests, and the runs of jobs stay.
     */
    void deleteData();

    /**
     * Stores the time when a person was masked.
     *
     * @param personId the ID of the person
     * @param maskedAt the time
     */
    void restoreMask(String personId, Instant maskedAt);
}
