package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * The records that an end of an employment ended on its date, and the ones that it removed because they would start
 * on that date or later. Each of them gets an entry of its own in the change log, as a write.
 *
 * @param assignmentIds the IDs of the assignments that end on the date now
 * @param others the writes of the other records. These are the roles over org units and the reporting lines that end
 *        on the date now, and each removed record. A removed record comes before the records that it refers to.
 */
public record CutRecords(List<String> assignmentIds, List<Write> others) {

    /** No record. */
    static final CutRecords NONE = new CutRecords(List.of(), List.of());

    /**
     * Returns the writes of the records, for the event log and the change log.
     *
     * @param assignments reads the assignments that end on the date
     * @return the writes. An assignment that ends on the date has the path of its end.
     */
    public List<Write> writes(AssignmentService assignments) {
        return writes(List.of(this), assignments).getFirst();
    }

    /**
     * Returns the writes of the records of several ends, as {@link #writes(AssignmentService)} does for each. The
     * assignments of all of them are read together.
     *
     * @param cuts the records of each end
     * @param assignments reads the assignments that end on the date
     * @return the writes of each end, in the order of the ends
     */
    public static List<List<Write>> writes(List<CutRecords> cuts, AssignmentService assignments) {
        List<String> ids = cuts.stream().flatMap(cut -> cut.assignmentIds().stream()).toList();
        Iterator<AssignmentView> views = ids.isEmpty() ? List.<AssignmentView>of().iterator()
                : assignments.getAll(ids).iterator();
        List<List<Write>> writes = new ArrayList<>();
        for (CutRecords cut : cuts) {
            List<Write> of = new ArrayList<>(cut.others());
            cut.assignmentIds().forEach(id -> of.add(new Write.OfAssignment(false, "/end", views.next())));
            writes.add(of);
        }
        return writes;
    }

    /**
     * Ends the reporting lines of assignments on a date, as an earlier end of the assignments needs. A line cannot
     * outlast its assignment. A line that would start on the date or later is removed.
     *
     * @param lines the storage of reporting lines of the realm
     * @param assignmentIds the IDs of the assignments
     * @param to the first date on which no line of the assignments holds
     * @return the write of each line that changed, by the ID of its assignment
     */
    static Map<String, List<Write>> cutLines(ReportingLineStore lines, Collection<String> assignmentIds,
            LocalDate to) {
        Map<String, List<Write>> writes = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        Map<String, ReportingLine> ended = new LinkedHashMap<>();
        for (ReportingLine line : lines.findByAssignments(assignmentIds)) {
            if (!line.period().from().isBefore(to)) {
                removed.add(line.id());
                writes.computeIfAbsent(line.assignmentId(), any -> new ArrayList<>()).add(Write.Removed.line(line.id()));
            } else if (line.period().endsAfter(to)) {
                ended.put(line.id(), line.withPeriod(line.period().endingAt(to)));
            }
        }
        lines.deleteAll(removed);
        lines.updateAll(List.copyOf(ended.values()));
        if (!ended.isEmpty()) {
            // The read gives the versions that the write counted up.
            lines.findByAssignments(assignmentIds).stream()
                    .filter(line -> ended.containsKey(line.id()))
                    .forEach(line -> writes.computeIfAbsent(line.assignmentId(), any -> new ArrayList<>())
                            .add(new Write.OfReportingLine(false, "/end", line)));
        }
        return writes;
    }
}
