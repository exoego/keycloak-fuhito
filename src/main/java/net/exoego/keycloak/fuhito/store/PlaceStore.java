package net.exoego.keycloak.fuhito.store;

import java.util.Collection;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * The reads of one realm that find what a write of an org unit changed in the work location and the cost center that
 * apply below it, and the count-up of the versions of the reads that changed.
 */
public interface PlaceStore {

    /** A realm with nothing below any unit, for a service that records no propagated change. */
    PlaceStore NONE = new PlaceStore() {
        @Override
        public List<PlaceSpan> placesBelow(String orgUnitId, ValidPeriod period) {
            return List.of();
        }

        @Override
        public List<PlaceRow> rowsTakingPlace(Collection<String> orgUnitIds, boolean workLocation,
                ValidPeriod period) {
            return List.of();
        }

        @Override
        public void countUpUnits(Collection<String> ids) {
        }

        @Override
        public void countUpAssignments(Collection<String> assignmentIds) {
        }
    };

    /**
     * A row of an assignment that takes a value from its org unit.
     *
     * @param assignmentId the ID of the assignment
     * @param orgUnitId the ID of the org unit
     * @param period the dates of the row
     */
    record PlaceRow(String assignmentId, String orgUnitId, ValidPeriod period) {
    }

    /**
     * Reads what an org unit and each unit below it in the default hierarchy take from the units down from the org
     * unit, on the dates of a period. The database walks down, so a small unit reads only its own branch.
     *
     * @param orgUnitId the ID of the org unit
     * @param period the dates
     * @return the spans. The spans of one unit do not overlap. A unit that is not below on a date of the period has
     *         none.
     */
    List<PlaceSpan> placesBelow(String orgUnitId, ValidPeriod period);

    /**
     * Lists the rows of the assignments to some org units that have no work location, or no cost center, of their
     * own, and that share a date with a period.
     *
     * @param orgUnitIds the IDs of the org units
     * @param workLocation true for the rows with no work location, false for the rows with no cost center
     * @param period the dates
     * @return the rows
     */
    List<PlaceRow> rowsTakingPlace(Collection<String> orgUnitIds, boolean workLocation, ValidPeriod period);

    /**
     * Counts up the versions of org units whose reads a write changed without writing them, with one statement. A
     * unit that a write of this transaction counted up already keeps its version.
     *
     * @param ids the IDs of the units
     */
    void countUpUnits(Collection<String> ids);

    /**
     * Counts up the versions of assignments whose reads a write changed without writing them, as
     * {@link #countUpUnits} does for units.
     *
     * @param assignmentIds the IDs of the assignments
     */
    void countUpAssignments(Collection<String> assignmentIds);
}
