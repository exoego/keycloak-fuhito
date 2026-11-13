package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * What an import did, or would do.
 */
public final class ImportReport {

    /**
     * The number of rows of one list by outcome.
     *
     * @param created rows that made a new thing
     * @param changed rows that changed an existing thing
     * @param unchanged rows that matched what was stored
     */
    public record Counts(int created, int changed, int unchanged) {
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
     *
     * @param resourceType the type of the written resource
     * @param resourceId the ID of the written resource
     * @param created true for a new resource, false for a change
     * @param path the path that the same write has in its own endpoint
     * @param state the state after the write. It is a {@code RefItem} or an {@code OrgUnitState}.
     */
    public record Write(String resourceType, String resourceId, boolean created, String path, Object state) {
    }

    private final Map<String, int[]> counts = new LinkedHashMap<>();
    private final List<RowError> errors = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();

    ImportReport() {
    }

    void count(String list, Outcome outcome) {
        counts.computeIfAbsent(list, name -> new int[3])[outcome.ordinal()]++;
    }

    void startList(String list) {
        counts.computeIfAbsent(list, name -> new int[3]);
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
        counts.forEach((list, c) -> result.put(list, new Counts(c[0], c[1], c[2])));
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
