package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

/**
 * Finds the assignments whose {@code endedOnStart} a write changed, and counts up their versions.
 *
 * <p>{@code endedOnStart} of an assignment lists the other assignments of its person whose last row ends on its start
 * with the primary flag of its first row. So a write changes it only through the end or the primary flag of the last
 * row of another assignment of the person, or through a new or a removed assignment. A write notes the rows of each
 * assignment that it may change before it changes them, and each assignment that it creates. After the write, only
 * the assignments of the same people that start on an old or a new end can have another list.
 */
public final class EndedOnStartChanges {

    // The last row of an assignment, as the lists of the other assignments see it.
    private record Ending(String employmentId, LocalDate to, boolean primary) {

        static Ending of(List<Assignment> rows) {
            Assignment last = rows.stream().max(Comparator.comparing(row -> row.period().from())).orElseThrow();
            return new Ending(last.employmentId(), last.period().to(), last.primary());
        }

        boolean endsOnStartOf(Assignment first) {
            return first.period().from().equals(to) && first.primary() == primary;
        }
    }

    private final AssignmentStore store;
    // The ending of each noted assignment before the write. It is empty for an assignment that the write creates.
    private final Map<String, Optional<Ending>> before = new LinkedHashMap<>();
    private final Set<String> created = new HashSet<>();

    EndedOnStartChanges(AssignmentStore store) {
        this.store = store;
    }

    /**
     * Notes the stored rows of assignments before the write changes them. A second note of an assignment keeps the
     * first one.
     *
     * @param rows the rows, with every row of each assignment
     */
    void note(Collection<Assignment> rows) {
        byAssignment(rows).forEach((id, of) -> before.putIfAbsent(id, Optional.of(Ending.of(of))));
    }

    /**
     * Notes the rows of assignments that the write creates.
     *
     * @param rows the rows, with every row of each assignment
     */
    void noteNew(Collection<Assignment> rows) {
        byAssignment(rows).forEach((id, of) -> {
            created.add(id);
            // An assignment with no end ends on the start of no other.
            if (Ending.of(of).to() != null) {
                before.putIfAbsent(id, Optional.empty());
            }
        });
    }

    /**
     * Finds the other assignments whose list the write changed, counts up their versions, and names each with a
     * propagated change. A new assignment gets none, because the write has an entry of it.
     *
     * @return the propagated changes
     */
    public List<PropagatedChange> changed() {
        return changedBy().values().stream().flatMap(List::stream).toList();
    }

    /**
     * Finds the changes as {@link #changed()} does, by their cause.
     *
     * @return the propagated changes by the ID of the noted assignment whose change caused them. An assignment that
     *         several of them changed is named once, under the first.
     */
    Map<String, List<PropagatedChange>> changedBy() {
        if (before.isEmpty()) {
            return Map.of();
        }
        Map<String, Ending> after = new HashMap<>();
        byAssignment(store.rowsOf(before.keySet())).forEach((id, rows) -> after.put(id, Ending.of(rows)));
        Map<String, Optional<Ending>> changed = new LinkedHashMap<>();
        before.forEach((id, was) -> {
            if (!was.equals(Optional.ofNullable(after.get(id)))) {
                changed.put(id, was);
            }
        });
        if (changed.isEmpty()) {
            return Map.of();
        }
        Map<String, String> employmentOf = new LinkedHashMap<>();
        changed.forEach((id, was) -> employmentOf.put(id,
                was.or(() -> Optional.ofNullable(after.get(id))).orElseThrow().employmentId()));
        Map<String, List<Assignment>> firsts = store.firstRowsOfPeople(new LinkedHashSet<>(employmentOf.values()));
        Map<String, String> causes = new LinkedHashMap<>();
        changed.forEach((id, was) -> {
            Optional<Ending> now = Optional.ofNullable(after.get(id));
            for (Assignment first : firsts.getOrDefault(employmentOf.get(id), List.of())) {
                String other = first.assignmentId();
                if (!other.equals(id) && !created.contains(other)
                        && was.filter(ending -> ending.endsOnStartOf(first)).isPresent()
                                != now.filter(ending -> ending.endsOnStartOf(first)).isPresent()) {
                    causes.putIfAbsent(other, id);
                }
            }
        });
        if (causes.isEmpty()) {
            return Map.of();
        }
        store.countUp(causes.keySet());
        Map<String, List<PropagatedChange>> byCause = new LinkedHashMap<>();
        causes.forEach((other, cause) -> byCause.computeIfAbsent(cause, any -> new ArrayList<>())
                .add(new PropagatedChange(DerivedValue.ASSIGNMENT_ENDED_ON_START, other)));
        return byCause;
    }

    private static Map<String, List<Assignment>> byAssignment(Collection<Assignment> rows) {
        return rows.stream().collect(Collectors.groupingBy(Assignment::assignmentId, LinkedHashMap::new,
                Collectors.toList()));
    }
}
