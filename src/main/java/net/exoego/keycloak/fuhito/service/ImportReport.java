package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What an import did, or would do.
 */
public final class ImportReport {

    @Schema(
            name = "ImportCounts",
            description = "The number of rows of one list by outcome.",
            requiredProperties = {"created", "changed", "unchanged"})
    public record Counts(
            @Schema(description = "Rows that made a new thing.")
            int created,
            @Schema(description = "Rows that changed an existing thing.")
            int changed,
            @Schema(description = "Rows that matched what was stored.")
            int unchanged) {
    }

    /**
     * A row that could not be applied.
     *
     * @param list the name of the list in the document
     * @param row the position of the row in its list, counted from 0
     * @param error the cause
     */
    public record RowError(String list, int row, FuhitoException error) {
    }

    /**
     * The outcome of one row.
     */
    public enum Outcome {
        /** The row made a new thing. */
        CREATED,
        /** The row changed an existing thing. */
        CHANGED,
        /** The row matched what was stored. */
        UNCHANGED
    }

    /**
     * One write that the import did, for the event log and the change log.
     */
    public sealed interface Write {

        /**
         * Tells whether the write made a new resource.
         *
         * @return true for a new resource, false for a change
         */
        boolean created();

        /**
         * Returns the type of the written resource.
         *
         * @return the type, as the event log names it
         */
        String resourceType();

        /**
         * Returns the ID of the written resource.
         *
         * @return the ID
         */
        String resourceId();

        /**
         * Returns the path that the same write has in its own endpoint.
         *
         * @return the path below the realm
         */
        String path();

        /**
         * A write of a reference item.
         *
         * @param created true for a new item, false for a change
         * @param state the item after the write
         */
        record OfRefItem(boolean created, RefItem state) implements Write {

            @Override
            public String resourceType() {
                return "FUHITO_REF_ITEM";
            }

            @Override
            public String resourceId() {
                return state.id();
            }

            @Override
            public String path() {
                return "fuhito/ref-items/" + resourceId();
            }
        }

        /**
         * A write of an org unit.
         *
         * @param created true for a new unit, false for a change
         * @param suffix the end of the path after the ID, for example {@code /parent}, or an empty text
         * @param state the unit after the write
         */
        record OfOrgUnit(boolean created, String suffix, OrgUnitState state) implements Write {

            @Override
            public String resourceType() {
                return "FUHITO_ORG_UNIT";
            }

            @Override
            public String resourceId() {
                return state.unit().id();
            }

            @Override
            public String path() {
                return "fuhito/org-units/" + resourceId() + suffix;
            }
        }

        /**
         * A write of an employment.
         *
         * @param created true for a new employment, false for a change
         * @param suffix the end of the path after the ID, for example {@code /terms}, or an empty text
         * @param state the employment after the write
         */
        record OfEmployment(boolean created, String suffix, EmploymentView state) implements Write {

            @Override
            public String resourceType() {
                return "FUHITO_EMPLOYMENT";
            }

            @Override
            public String resourceId() {
                return state.employment().id();
            }

            @Override
            public String path() {
                return "fuhito/employments/" + resourceId() + suffix;
            }
        }

        /**
         * A write of an assignment.
         *
         * @param created true for a new assignment, false for a change
         * @param suffix the end of the path after the ID, for example {@code /end}, or an empty text
         * @param state the assignment after the write
         */
        record OfAssignment(boolean created, String suffix, AssignmentView state) implements Write {

            @Override
            public String resourceType() {
                return "FUHITO_ASSIGNMENT";
            }

            @Override
            public String resourceId() {
                return state.assignmentId();
            }

            @Override
            public String path() {
                return "fuhito/assignments/" + resourceId() + suffix;
            }
        }
    }

    private final Map<String, Map<Outcome, Integer>> counts = new LinkedHashMap<>();
    private final List<RowError> errors = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();

    ImportReport() {
    }

    void count(String list, Outcome outcome) {
        startList(list).merge(outcome, 1, Integer::sum);
    }

    Map<Outcome, Integer> startList(String list) {
        return counts.computeIfAbsent(list, name -> new EnumMap<>(Outcome.class));
    }

    void error(String list, int row, FuhitoException error) {
        errors.add(new RowError(list, row, error));
    }

    void write(Write write) {
        writes.add(write);
    }

    /**
     * Returns the number of rows by outcome for each list of the document.
     *
     * @return the counts by list name
     */
    public Map<String, Counts> counts() {
        Map<String, Counts> result = new LinkedHashMap<>();
        counts.forEach((list, c) -> result.put(list, new Counts(c.getOrDefault(Outcome.CREATED, 0),
                c.getOrDefault(Outcome.CHANGED, 0), c.getOrDefault(Outcome.UNCHANGED, 0))));
        return result;
    }

    /**
     * Returns the rows that could not be applied, in the order they were found.
     *
     * @return the errors
     */
    public List<RowError> errors() {
        return List.copyOf(errors);
    }

    /**
     * Returns the writes that the import did, in order.
     *
     * @return the writes
     */
    public List<Write> writes() {
        return List.copyOf(writes);
    }
}
